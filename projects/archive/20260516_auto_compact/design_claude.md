# Auto-Compact (replace `max_turn`) — v2

> Revised after codex review (`review_codex.md`). v1 retained in git history.

## Goal

Delete the `maxTurns` hard cap. Replace it with **context-window-triggered auto-compaction** — the agent runs as long as it needs to; whenever the conversation gets close to the model's context window, we summarize the older portion of history and continue.

Why:
- `maxTurns` is an arbitrary integer that punishes long-but-legitimate tasks ("Max turns reached" even mid-progress).
- It also leaks into the **prompt** (`PromptBuilder.kt:139` writes `"Turn $turnNumber/$maxTurns"`, `AgentTurnRunner.kt:223` adds `"🛑 FINAL TURN"`), teaching the model a finite budget. This biases reasoning toward premature completion or sloppy shortcuts. The prompt-side leak is the *real* reason to kill this, not the loop guard.
- The current `HistoryManager.compress()` Phase 2 is lossy eviction — drops items, leaves a useless one-liner. After a few cycles the agent has effective amnesia.

Success:
- No production control flow stops because of a turn count.
- No prompt text tells the model it has a finite turn budget.
- Compaction preserves the **active user goal**, recent **supplements**, **tool-call consistency** (no orphaned `FunctionCallOutput`), and recent screen state.
- A compaction failure cannot corrupt or drop history; concurrent supplements cannot be clobbered.

## Prior art — what we steal

| | trigger | cut rule | replacement | iterative |
|---|---|---|---|---|
| **pi-mono** | `tokens > contextWindow − reserve` | walk back to ≥ `keepRecentTokens`; cut at any user/assistant/custom message; never at a tool result | `[summary] + [...kept]`; structured-format summary | yes — UPDATE prompt with previous summary |
| **claude-code** | reactive: after turn, `tokens >= contextWindow − 13K` | snip-then-summarize | one summary replaces history; preserves `USER_INTENT`; 3-strike circuit breaker | yes |
| **codex** | mid-turn: `total_usage >= auto_compact_limit` | insert before-last-user-message | replaces history; reactive retry on `prompt_too_long` | no — relies on rolling forward |

**Adopted kernel** (the only part worth keeping):
1. Estimate tokens (history + static overhead). Trigger above `contextWindow − reserve`.
2. Walk back, accumulate `keepRecentTokens`, cut at a **safe** boundary — any `Message` item; never between a `FunctionCall` and its `FunctionCallOutput`.
3. LLM-summarize the discarded prefix into one structured message.
4. New history = `[canonical USER_INTENT(current goal), COMPACTION_SUMMARY, ...kept]`.
5. CAS-swap the history; if revision moved (concurrent supplement), discard the summary and retry next turn.
6. If a previous summary exists, feed it to the summarizer with an UPDATE prompt.
7. On provider `prompt_too_long` error, do one reactive compact + retry.

## Approach

### 1. Model context window

Add `context_window: Int` to JSON catalog. Default 128_000 for cloud, 8_000 for local backend (Leap on-device models are typically 4K–32K).

```kotlin
data class ModelEntry(
    /* ... */
    val contextWindow: Int,  // required, validated > 0
)
```

JSON shape:
```json
"gpt-5.2": {
  "display_name": "GPT-5.2",
  ...
  "context_window": 400000
}
```

Validation in `JsonModelEntry.toModelEntry()`: `require(contextWindow > 0)`. The catalog currently has **12** entries (gpt-5.2/.4 × {response, chat, codex}, glm-5, minimax-m2.5, kimi-k2.5, qwen3-vl, qwen3.5, autoglm-phone-9b) — all populated with realistic windows.

### 2. HistoryManager: add revision + CAS

```kotlin
class HistoryManager(...) {
    // monotonically increasing, bumped by addItem/recordItems/replaceAll
    @Volatile private var _revision: Long = 0
    val revision: Long get() = _revision

    @Synchronized
    fun snapshot(): Pair<Long, List<ResponseItem>> = _revision to items.toList()

    /** CAS replace. Returns true on swap, false if revision moved. */
    @Synchronized
    fun replaceAllIfRevision(expected: Long, newItems: List<ResponseItem>): Boolean {
        if (_revision != expected) return false
        items.clear()
        items.addAll(newItems)
        lastTokenEstimate = null
        _revision++
        onMutation?.invoke()
        return true
    }

    // existing addItem/recordItems/replaceAll also bump _revision
}
```

This is the **only** new state on `HistoryManager`. It closes the supplement race (blocker 2): supplement injection bumps the revision; if it happens while compactor is awaiting the LLM, the CAS fails, the half-baked summary is dropped, and the next turn tries again with the supplement included.

### 3. New file: `Compactor.kt`

Per-Agent, not per-session. Built in `Agent` constructor (or by a factory passed to `SessionAgentRunner` / `SubAgentRunner`) using that agent's resolved `ModelEntry` and `LLMClient`.

```kotlin
class Compactor(
    private val llmClient: LLMClient,
    private val model: ModelEntry,
    private val staticOverheadTokens: Long = 12_000,  // system prompt + tools + skills + scratchpad
    private val reserveTokens: Long = 24_000,         // next output (~6K) + summary (~5K) + headroom
    private val keepRecentTokens: Long = 20_000,
) {
    suspend fun maybeCompact(
        currentGoal: String,
        history: HistoryManager,
    ): CompactionOutcome {
        val (rev, items) = history.snapshot()
        val tokens = items.sumOf { it.estimateTokens() } + staticOverheadTokens
        if (tokens <= model.contextWindow - reserveTokens) {
            return CompactionOutcome.Skipped
        }

        val cutIdx = findSafeCutPoint(items, keepRecentTokens)
        if (cutIdx <= 0) return CompactionOutcome.NothingToCompact

        val previousSummary = items.firstNotNullOfOrNull {
            (it as? ResponseItem.Message)?.takeIf { m -> m.kind == COMPACTION_SUMMARY }?.content
        }
        val toSummarize = items.subList(0, cutIdx)
            .filterNot { it is ResponseItem.Message && it.kind == COMPACTION_SUMMARY }

        val summaryText = try {
            summarize(currentGoal, toSummarize, previousSummary)
        } catch (ce: CancellationException) {
            throw ce   // ALWAYS rethrow — cancellation is not a compaction failure
        } catch (t: Throwable) {
            return CompactionOutcome.Failed(t.message ?: t.javaClass.simpleName)
        }

        val newItems = buildList {
            add(ResponseItem.Message(MessageKind.USER_INTENT, "Goal: $currentGoal"))
            add(ResponseItem.Message(MessageKind.COMPACTION_SUMMARY, summaryText))
            addAll(items.subList(cutIdx, items.size))
        }

        return if (history.replaceAllIfRevision(rev, newItems)) {
            CompactionOutcome.Compacted(before = tokens, after = history.estimateTokenCount())
        } else {
            CompactionOutcome.Stale   // supplement arrived during summarization; retry next turn
        }
    }

    /** Reactive: called when LLM rejects with prompt_too_long. */
    suspend fun forceCompactNow(currentGoal: String, history: HistoryManager): CompactionOutcome { /* shrink keepRecentTokens by half, run maybeCompact unconditionally */ }
}
```

**`findSafeCutPoint`** (replaces v1's `SCREEN_OBSERVATION`-only rule with pi's generic rule):

```kotlin
private fun findSafeCutPoint(items: List<ResponseItem>, keepTokens: Long): Int {
    // Walk backward, accumulate tokens.
    var acc = 0L
    var hitIdx = -1
    for (i in items.indices.reversed()) {
        acc += items[i].estimateTokens()
        if (acc >= keepTokens) { hitIdx = i; break }
    }
    if (hitIdx < 0) return 0  // total < keepTokens — nothing to compact

    // Snap forward to the next SAFE cut point.
    // Safe = any Message item, or a FunctionCall (its FunctionCallOutput stays to the right).
    // UNSAFE = a FunctionCallOutput (would orphan its FunctionCall).
    for (j in hitIdx..items.lastIndex) {
        when (items[j]) {
            is ResponseItem.Message,
            is ResponseItem.FunctionCall -> return j
            is ResponseItem.FunctionCallOutput -> continue
        }
    }
    return items.size  // no safe boundary in the tail; keep everything
}
```

**Why this handles the edge cases v1 missed**:
- *Supplement-only boundary*: `USER_INTENT` (supplement) is a `Message` → safe cut point. No more dependence on the next `SCREEN_OBSERVATION`.
- *Oversized newest turn*: snap-forward returns `items.size` (nothing safely compactable), so we return `NothingToCompact` rather than corrupting. Compactor logs a warning; if this persists the agent will eventually hit a real `prompt_too_long` and the reactive path fires.
- *Post-action screens in `FunctionCallOutput`*: they stay paired with their `FunctionCall`. The pair is atomic.

**Result type**:
```kotlin
sealed class CompactionOutcome {
    object Skipped : CompactionOutcome()              // under threshold
    object NothingToCompact : CompactionOutcome()     // can't find a safe cut
    object Stale : CompactionOutcome()                // CAS missed; retry next turn (not a failure)
    data class Compacted(val before: Long, val after: Long) : CompactionOutcome()
    data class Failed(val reason: String) : CompactionOutcome()  // LLM error; counts toward circuit breaker
}
```

### 4. Summarization prompt

Lives in `app/src/main/assets/prompts/compaction_initial.md` and `compaction_update.md`. Goal is **not** restated — it's re-injected canonically by the agent loop.

```
You are summarizing an Android agent's progress so another instance can continue from this checkpoint.
The user's current goal is provided separately in the conversation; do NOT restate it.

Output this exact markdown structure:

## Progress
### Done
- [Concrete sub-goals completed; screens reached; data captured.]

### In Progress
- [What the agent was doing right before this summary.]

### Blocked / Failed Attempts
- [Approaches that failed — so we don't retry them. Or "(none)".]

## User Updates
- [If the conversation contained additional user messages (supplements, clarifications, constraints),
   capture them here as standing constraints. Or "(none)".]

## App State
- Foreground app: [package or "unknown"]
- Last meaningful screen: [name / one-line description]
- Key data captured: [text, IDs, values the agent extracted]

## Next Steps
1. [Ordered plan to continue.]

Rules:
- Be concise. Preserve exact strings: package names, button labels, error messages, IDs, numeric values.
- Do NOT add tasks not implied by the conversation. Do NOT speculate.
- Do NOT restate the goal.
```

UPDATE variant wraps an existing summary in `<previous-summary>` tags and asks the model to merge new events in (move items from In Progress → Done, etc.).

The summarizer LLM call uses **no tools** (clean text-only call), `maxTokens ≈ 5000`.

### 5. PromptBuilder: convert summary to user-role context

`MessageKind.COMPACTION_SUMMARY` is currently assistant-role (it inherited from `COMPRESSION_DIGEST`). Treating it as assistant makes the model read the summary as its own prior output. Change:

- `MessageKind.COMPACTION_SUMMARY.apiRole = "user"`.
- `PromptBuilder` wraps the content with a clear prefix:
  ```
  [Context checkpoint from earlier work in this session]
  
  <summary text>
  ```

This makes provenance unambiguous to the model — it's reading a handoff, not its own train of thought.

### 6. Agent loop wiring

`Agent.kt`:

```diff
 while (shouldContinue()) {
     if (pauseState.value) { /* ... */ }
     if (!shouldContinue()) { /* ... */ break }
-    if (turnCount >= config.maxTurns) {
-        Log.w(TAG, "Max turns (${config.maxTurns}) reached")
-        eventDispatcher.status("⚠️ Max turns reached")
-        stopReason = AgentStopReason.MaxTurnsReached
-        break
-    }

+    // Auto-compact before each turn.
+    when (val r = compactor.maybeCompact(config.goal, services.historyManager)) {
+        is CompactionOutcome.Compacted -> {
+            consecutiveCompactionFailures = 0
+            eventDispatcher.status("📚 Compacted history (${r.before} → ${r.after} tokens)")
+        }
+        CompactionOutcome.Failed -> {
+            consecutiveCompactionFailures++
+            if (consecutiveCompactionFailures >= 3) {
+                stopReason = AgentStopReason.Error("Auto-compaction failed 3× in a row")
+                break
+            }
+        }
+        CompactionOutcome.Stale,
+        CompactionOutcome.Skipped,
+        CompactionOutcome.NothingToCompact -> consecutiveCompactionFailures = 0
+    }
+
+    // Eval-only runaway bound (production: config.evalTurnBudget = null).
+    if (config.evalTurnBudget != null && turnCount >= config.evalTurnBudget) {
+        stopReason = AgentStopReason.Error("Eval turn budget reached (${config.evalTurnBudget})")
+        break
+    }

     turnCount++
     ...
 }
```

Retry gate at line 134 loses `hasRemainingTurns`:

```diff
- val hasRemainingTurns = turnCount < config.maxTurns
- val canRetry = hasRemainingTurns && recoverableRetryCount < retryLimit
+ val canRetry = recoverableRetryCount < retryLimit
```

`turnCount` stays — used only for observability (status events, traces, capsule UI). No prompt text reads it.

**`evalTurnBudget`**: a new nullable field on `SessionConfig` and `AgentExecutionConfig`. Production code never sets it. Eval bridge sets it from `max_turns:` in the yaml. This is the *only* surviving turn-budget concept, scoped explicitly to "give up before burning $100 on an infinite eval loop". 5 lines of code; intent is clear from the name.

### 7. Reactive compaction (one-shot retry on prompt_too_long)

In `Turn.runStreaming()` (or wherever the LLM call lives), catch the provider's context-window-exceeded error:

```kotlin
try {
    llmClient.stream(...)
} catch (e: ContextWindowExceededException) {
    compactor.forceCompactNow(config.goal, historyManager)
    llmClient.stream(...)  // retry once
}
```

`ContextWindowExceededException` is a new exception classified by the existing error pipeline (`CloudStreamRetryPolicy` or its peer). Provider-specific error matching: HTTP 413 / `prompt_too_long` / `request_too_large` / Ollama's `prompt too long; exceeded max context length`. Existing patterns in pi-mono's `ai` package show how to classify these.

This is the safety net for static-overhead estimation drift. ~30 LOC total.

### 8. Removals (expanded — addresses missed integration points)

| File | What goes |
|---|---|
| `protocol/SessionConfig.kt` | `val maxTurns: Int = 50` |
| `protocol/TaskOutcome.kt` | `MAX_TURNS` |
| `agent/Agent.kt` | turn-count guard (83–88); `hasRemainingTurns` retry term (134) |
| `agent/AgentRuntimeTypes.kt` | `AgentStopReason.MaxTurnsReached` |
| `agent/AgentExecutionConfig.kt` | `maxTurns: Int = 50` (line 29) |
| `agent/AgentTurnRunner.kt` | `isFinalTurn(...)` call (207); `"🛑 FINAL TURN"` warning text (223) |
| `agent/TurnPlanningPhaseRunner.kt` | `maxTurns = config.maxTurns` passthrough (103) |
| `agent/cognition/prompt/PromptBuilder.kt` | `maxTurns` parameter (44, 112, 134); `"Turn $turnNumber/$maxTurns"` text (138-139) → replace with `"Turn $turnNumber"` (or remove entirely) |
| `agent/cognition/policy/TurnBudget.kt` | **whole file** — `isFinalTurn`, `DelegationSummary.format(maxTurns, ...)` |
| `agent/cognition/policy/TurnBudgetAndDelegationSummaryTest.kt` | **whole file** (or rewrite as DelegationSummary test without maxTurns) |
| `agent/definition/AgentRoleDef.kt` | `maxTurns: Int = 50` (26, 71, 84) |
| `agent/subagent/SubAgentRunner.kt` | `maxTurns = resolvedRoleDef.maxTurns` (103, 136); branch on `AgentStopReason.MaxTurnsReached` → replace with timeout/error path |
| `session/AgentSession.kt` | `MaxTurnsReached -> MAX_TURNS` mapping (478) |
| `session/SessionCheckpointCoordinator.kt` | `maxTurns =` snapshot serialization (89, 112). Legacy snapshots with this field are silently ignored on reload |
| `session/SessionAgentRunner.kt` | `maxTurns = config.maxTurns` plumbing (85) |
| `session/SessionHistoryBootstrapper.kt` | `autoCompress = true`, `maxTokenBudget = 18_000` — **critical**: leaving these makes lossy eviction run *before* the new compactor |
| `history/HistoryConfig.kt` | `autoCompress`, `autoCompressThreshold`, `compressTargetRatio`, `maxTokenBudget` (all tied to old lossy eviction). `recentFullScreens` and `recentWindowSize` stay (used by screen-downgrade and the no-touch tail in compaction). |
| `history/HistoryManager.kt` | `compress()` Phase 2 eviction + digest insertion + `mergeAdjacentDigests()`; `autoCompressIfNeeded()`; `CompressionResult.BudgetUnreachable`. `downgradeOldScreens()` stays |
| `history/ResponseItem.kt` | `MessageKind.COMPRESSION_DIGEST` → `COMPACTION_SUMMARY`; `apiRole` for `COMPACTION_SUMMARY` returns `"user"` |
| `history/model/SessionRuntimeSnapshot.kt` | `ConversationConfigSnapshot.maxTurns` (63) |
| `trace/AgentTrace.kt` | `put("max_turns", ...)` (49) |
| `trace/TraceModels.kt` | `@SerialName("max_turns") val maxTurns: Int` (64) |
| `trace/TraceRecorderFactory.kt` | `maxTurns = config.maxTurns` (43) |
| `app/MainActivity.kt` | `const val EXTRA_MAX_TURNS = "max_turns"` (83); `maxTurns = settingsState.maxTurns` (676) |
| `app/MainActivityIntentPayload.kt` | `maxTurns` field + parsing (20, 94–98, 159) |
| `app/MainActivityIntentApplier.kt` | `payload.maxTurns?.let { ... }` (98) |
| `app/MainActivityContent.kt` | `maxTurns = settingsState.maxTurns` (129) |
| `app/AppSettingsStore.kt` | `DEFAULT_MAX_TURNS`, `KEY_MAX_TURNS`, read/write (17, 35, 50, 75, 122, 169) |
| `app/AppSettingsState.kt` | `maxTurns` mutable state + logging + observer (19, 47, 60, 89) |
| `app/AgentService.kt` | `maxTurns = maxSteps` injection (358) |
| `app/AgentServiceEventHandler.kt` | `TaskOutcome.MAX_TURNS` branches (192, 200) |
| `ui/overlay/CapsuleStateHolder.kt` | `TaskOutcome.MAX_TURNS -> CapsuleMode.Done("Max steps reached")` (248) |
| `ui/settings/SettingsModels.kt` | `MAX_TURNS_OPTIONS` (43) |
| `ui/settings/SettingsHomePage.kt` | `maxTurns` param + `agentBehaviorSubtitle` (38, 84, 137, 145) |
| `ui/settings/SettingsDropdowns.kt` | `MaxTurnsDropdown` (or whatever the dropdown function is) (92, 97, 99–100) |
| `ui/settings/AgentBehaviorSettingsPage.kt` | `maxTurns` param + `MaxTurnsDropdown(...)` (16, 34) |
| `onboarding/OnboardingDemoController.kt` | `maxTurns = MAX_TURNS` (92); local `MAX_TURNS` constant; `TaskOutcome.MAX_TURNS` message branches |
| `eval/aw_bridge/native_agent_bridge.py` | `max_turns: int` field (30); intent extra emission (205–206) — **kept under the new name `eval_turn_budget`** plumbed to `SessionConfig.evalTurnBudget` |
| `eval/aw_bridge/runner.py` | `max_turns=int(bridge_cfg.get("max_turns", 30))` (268) — kept under same key in yaml; flows into `evalTurnBudget` |
| `eval/config/*.yaml` | `max_turns:` keys stay (still meaningful as eval safety net); semantics now "give up after N turns" not "stop with MAX_TURNS reason" |
| `eval/tests/test_completion_monitor.py` | `"reason: MAX_TURNS"` → `"reason: ERROR"` (with message containing "eval turn budget") |
| `scripts/debug-run.sh` | `DEBUG_MAX_TURNS` is just a log-monitor cap, **keep it** (rename optional) |
| `doc/main/**` | Update protocol/settings/capsule/loop/eval docs |

### 9. Subagent

`SubAgentRunner.kt:79` builds a fresh `HistoryManager` per child. Each subagent also constructs its own `Compactor` after `childServices` and `childModelName` are resolved — bound to the **child's** model (which may differ from parent via `subagentModel`). `Compactor` is per-`Agent` instance, not per-session.

`SubAgentRunner.kt:103,136` references to `resolvedRoleDef.maxTurns` go away. The delegation-failure summary path that currently fires on `MaxTurnsReached` shifts to: fires only on `AgentStopReason.Error` or timeout (`timeoutMs`, which remains as the runaway guard for delegated subagents).

## Components

**New**:
- `history/Compactor.kt` (~180 LOC)
- `assets/prompts/compaction_initial.md`, `compaction_update.md`
- Exception type: `ContextWindowExceededException` (in `llm/`)

**Modified**:
- `history/HistoryManager.kt` — add revision + CAS API; delete Phase 2 eviction + auto-compress trigger + BudgetUnreachable.
- `history/ResponseItem.kt` — `COMPRESSION_DIGEST` → `COMPACTION_SUMMARY` (user role).
- `history/HistoryConfig.kt` — drop the old-eviction fields.
- `llm/ModelCatalog.kt` — add `contextWindow` field + JSON parsing + validation.
- `llm/CloudStreamRetryPolicy.kt` (or `CloudStreamRetryRunner.kt`) — classify context-overflow errors; surface as `ContextWindowExceededException`.
- `agent/Agent.kt` — replace turn guard with compaction; per-Agent compactor construction; `evalTurnBudget` honored if non-null.
- `agent/Turn.kt` — wrap LLM call with reactive-compact retry on `ContextWindowExceededException`.
- `agent/cognition/prompt/PromptBuilder.kt` — drop `maxTurns` param; "Turn N" or no turn text; wrap `COMPACTION_SUMMARY` content with "[Context checkpoint from earlier work]" prefix.
- `session/SessionAgentRunner.kt` — construct `Compactor` per `Agent` and pass through.

**Deleted**:
- `agent/cognition/policy/TurnBudget.kt` (and its test).
- `agent/cognition/policy/TurnBudgetAndDelegationSummaryTest.kt` (or stripped of maxTurns).
- All `maxTurns` / `MAX_TURNS` references per the table above.

## Interactions

```
┌─────────────────────────────────────────────────────────────────┐
│                       Agent Loop (per turn)                     │
│                                                                 │
│  ┌────────────────────────┐                                     │
│  │ compactor              │   est ≤ window − reserve ?          │
│  │  .maybeCompact(goal,h) │──yes─► Skipped                      │
│  │                        │──no──► snapshot(rev,items)          │
│  │                        │       summarize via LLM             │
│  │                        │       CAS replaceAllIfRevision      │
│  │                        │         success → Compacted         │
│  │                        │         miss    → Stale (retry)     │
│  │                        │       LLM error → Failed (counter)  │
│  └───────────┬────────────┘                                     │
│              ▼                                                  │
│  ┌────────────────────────┐                                     │
│  │ Perceive → Think → Act │ ──prompt_too_long──►                │
│  │ → Observe              │   forceCompactNow → retry once      │
│  └────────────────────────┘                                     │
└─────────────────────────────────────────────────────────────────┘
```

**Compaction state machine**:

| State | Event | Next | Side effect |
|---|---|---|---|
| Idle | tokens ≤ threshold | Idle | none → `Skipped` |
| Idle | tokens > threshold | Reading | snapshot `(rev, items)` |
| Reading | no safe cut found | Idle | → `NothingToCompact` |
| Reading | safe cut found | Summarizing | start LLM call |
| Summarizing | LLM success | Swapping | build newItems |
| Summarizing | LLM error | Idle | → `Failed(reason)`; loop counter++ |
| Summarizing | CancellationException | (propagates) | rethrow (NEVER count as failure) |
| Swapping | CAS success | Idle | → `Compacted(before, after)`; loop counter = 0 |
| Swapping | CAS miss | Idle | → `Stale`; loop counter = 0; retry next turn |

3-strike circuit breaker lives in agent loop, not in compactor.

**Defense layers**:

```
addItem(SCREEN_OBSERVATION)
        │
        ▼
downgradeOldScreens()   ◄── Layer 0: free, local. Older full screens → one-line summary.
        │                   Runs on every new screen capture.
        ▼
top of next turn:
compactor.maybeCompact()  ◄── Layer 1: proactive. LLM summarize when est > window − reserve.
        │
        ▼
LLM call (in turn)
        │ catch ContextWindowExceededException
        ▼
compactor.forceCompactNow() → retry  ◄── Layer 2: reactive safety net.
```

## Defaults

| Constant | Value | Rationale |
|---|---|---|
| `staticOverheadTokens` | 12_000 | System prompt + tool schemas + scratchpad + app skills + memory — measured ceiling |
| `reserveTokens` | 24_000 | Next-turn output (~6K) + summary message (~5K) + headroom for static drift (~13K) |
| `keepRecentTokens` | 20_000 | ~3–5 most recent turns survive verbatim |
| `forceCompact` shrunken `keepRecentTokens` | 10_000 | Reactive path: more aggressive |
| `MAX_CONSECUTIVE_COMPACTION_FAILURES` | 3 | Same as Claude Code |
| Default `contextWindow` (cloud, JSON-missing) | 128_000 | Conservative; all cloud catalog entries ≥ this in practice |
| Default `contextWindow` (local backend) | 8_000 | Leap on-device models are typically 4–32K; force JSON-specified |
| `evalTurnBudget` (production) | `null` | No turn cap in production |
| `evalTurnBudget` (eval default) | 30 (from yaml) | Eval safety net, unchanged from current eval semantics |

## Tasks

### task-1: `model-catalog-context-window`
- **Scope**: `llm/ModelCatalog.kt`, `assets/llm_models.json`, `ModelCatalogTest.kt`.
- **Accept**: `ModelEntry.contextWindow: Int` (required, validated `> 0`). All 12 JSON entries populated. Local backend default-fallback returns 8_000 if unspecified. Parsing test covers both fallbacks and validation failure.
- **Deps**: none.

### task-2: `history-revision-cas`
- **Scope**: `history/HistoryManager.kt` + `HistoryManagerTest.kt`.
- **Accept**:
  - `_revision: Long` field. `addItem`, `recordItems`, `replaceAll`, `clear` bump it.
  - `snapshot(): Pair<Long, List<ResponseItem>>` exposes atomic read.
  - `replaceAllIfRevision(expected, newItems): Boolean` CAS-swaps; returns false on revision mismatch.
  - Test: concurrent supplement during a fake long-running compactor → CAS fails, supplement preserved.
- **Deps**: none.

### task-3: `compactor-core`
- **Scope**: new `history/Compactor.kt` + `CompactorTest.kt`. `assets/prompts/compaction_initial.md`, `compaction_update.md`.
- **Accept**:
  - `findSafeCutPoint`: walks back, snaps forward; never returns index of a `FunctionCallOutput`. Edge cases unit-tested: oversized newest turn → `items.size`; supplement-only boundary → cuts at `USER_INTENT`; threshold mid `FunctionCallOutput` → snaps to next safe point.
  - `maybeCompact` returns `Skipped` / `NothingToCompact` / `Stale` / `Compacted(before, after)` / `Failed(reason)`.
  - Rethrows `CancellationException` (test: cancel during fake LLM call propagates cleanly).
  - Output history = `[USER_INTENT(current goal), COMPACTION_SUMMARY, ...kept]`. Old summary replaced not duplicated. Iterative test: second compaction uses UPDATE prompt with previous summary.
  - `forceCompactNow` shrinks `keepRecentTokens` and runs unconditionally.
- **Deps**: task-1, task-2.

### task-4: `history-manager-cleanup`
- **Scope**: `history/HistoryManager.kt`, `ResponseItem.kt`, `HistoryConfig.kt`, `SessionHistoryBootstrapper.kt`.
- **Accept**:
  - `MessageKind.COMPRESSION_DIGEST` → `COMPACTION_SUMMARY`; `apiRole` for it returns `"user"`.
  - `HistoryManager.compress()` Phase 2 eviction + `mergeAdjacentDigests()` + `autoCompressIfNeeded()` deleted.
  - `CompressionResult.BudgetUnreachable` deleted.
  - `HistoryConfig`: `autoCompress`, `autoCompressThreshold`, `compressTargetRatio`, `maxTokenBudget` deleted. `recentFullScreens`, `recentWindowSize` kept.
  - `SessionHistoryBootstrapper`: drop `autoCompress = true` and `maxTokenBudget` arg.
  - `downgradeOldScreens()` retained and still invoked from `addItem`/`recordItems`.
- **Deps**: task-3.

### task-5: `prompt-builder-decontextualize-summary`
- **Scope**: `agent/cognition/prompt/PromptBuilder.kt`.
- **Accept**:
  - `maxTurns` parameter removed from all entry points.
  - `"Turn $turnNumber/$maxTurns"` replaced with `"Turn $turnNumber"` (or removed if not load-bearing).
  - `COMPACTION_SUMMARY` content gets `"[Context checkpoint from earlier work]\n\n"` prefix when serialized.
  - No prompt text mentions any kind of turn budget.
- **Deps**: task-4.

### task-6: `reactive-compaction-retry`
- **Scope**: `llm/CloudStreamRetryPolicy.kt` (or peer), new `ContextWindowExceededException`, `agent/Turn.kt`.
- **Accept**:
  - HTTP 413 / `prompt_too_long` / `request_too_large` / Ollama overflow strings classified as `ContextWindowExceededException`.
  - `Turn.runStreaming()` catches it once, invokes `compactor.forceCompactNow(...)`, retries the LLM call.
  - Test: mock provider throws on first attempt, retry succeeds.
- **Deps**: task-3.

### task-7: `agent-loop-rewire`
- **Scope**: `agent/Agent.kt`, `agent/AgentTurnRunner.kt`, `session/SessionAgentRunner.kt`, `agent/subagent/SubAgentRunner.kt`.
- **Accept**:
  - `Agent` constructor takes `Compactor` (built by `SessionAgentRunner` or `SubAgentRunner` from the agent's resolved model + LLM client).
  - Top of each turn: `compactor.maybeCompact(config.goal, services.historyManager)`. `Compacted` → status event + reset counter. `Failed` → counter++; ≥3 → stop with `Error`. Other outcomes → reset counter.
  - Turn-count guard removed; `hasRemainingTurns` retry term removed.
  - `AgentTurnRunner` no longer calls `isFinalTurn`; no `"FINAL TURN"` warning.
  - `evalTurnBudget` check: `config.evalTurnBudget != null && turnCount >= it` → stop with `Error("Eval turn budget reached (N)")`.
  - `SubAgentRunner`: compactor built per child after `childServices`/`childModelName` known. `MaxTurnsReached` branch removed (subagent runaway covered by `timeoutMs`).
- **Deps**: task-3, task-4, task-5.

### task-8: `protocol-and-session-strip-maxturns`
- **Scope**: `protocol/SessionConfig.kt`, `protocol/TaskOutcome.kt`, `agent/AgentRuntimeTypes.kt`, `agent/AgentExecutionConfig.kt`, `agent/definition/AgentRoleDef.kt`, `agent/cognition/policy/TurnBudget.kt`, `session/AgentSession.kt`, `session/SessionCheckpointCoordinator.kt`, `history/model/SessionRuntimeSnapshot.kt`, `trace/{AgentTrace.kt, TraceModels.kt, TraceRecorderFactory.kt}`.
- **Accept**:
  - `maxTurns` field deleted from `SessionConfig`, `AgentExecutionConfig`, `AgentRoleDef`, `ResolvedAgentRole`, `ConversationConfigSnapshot`.
  - **Added**: `SessionConfig.evalTurnBudget: Int? = null` and same on `AgentExecutionConfig`. Default null. Plumbed through `SessionAgentRunner` to `AgentExecutionConfig`.
  - `AgentStopReason.MaxTurnsReached` deleted.
  - `TaskOutcome.MAX_TURNS` deleted.
  - `TurnBudget.kt` deleted. Its `DelegationSummary.format` either removed or rewritten without `maxTurns` param if still needed by subagent error path.
  - Trace: `max_turns` field/metadata removed from `AgentTrace.kt`, `TraceModels.kt`, `TraceRecorderFactory.kt`.
  - Checkpoint serialization no longer writes `maxTurns`; legacy snapshots ignore it on reload.
  - All compile errors in dependents fixed in this task.
- **Deps**: task-7.

### task-9: `app-and-ui-strip-maxturns`
- **Scope**: `app/{MainActivity, MainActivityIntentPayload, MainActivityIntentApplier, MainActivityContent, AppSettingsStore, AppSettingsState, AgentService, AgentServiceEventHandler}.kt`, `ui/overlay/CapsuleStateHolder.kt`, `ui/settings/{SettingsModels, SettingsHomePage, SettingsDropdowns, AgentBehaviorSettingsPage}.kt`, `onboarding/OnboardingDemoController.kt`.
- **Accept**:
  - All `maxTurns` / `EXTRA_MAX_TURNS` / `DEFAULT_MAX_TURNS` / `KEY_MAX_TURNS` / `MAX_TURNS_OPTIONS` / `MaxTurnsDropdown` references deleted.
  - "Max Turns" UI control removed from `AgentBehaviorSettingsPage`; layout shrinks accordingly. Settings subtitle no longer prints `"$maxTurns turns"`.
  - `TaskOutcome.MAX_TURNS -> ...` branches deleted (when-exhaustiveness restored).
  - `OnboardingDemoController` `maxTurns = MAX_TURNS` deleted; local `MAX_TURNS` constant deleted; `TaskOutcome.MAX_TURNS` message branch deleted.
- **Deps**: task-8.

### task-10: `eval-bridge-rename`
- **Scope**: `eval/aw_bridge/native_agent_bridge.py`, `eval/aw_bridge/runner.py`, `eval/tests/{test_runner.py, test_native_agent_bridge.py, test_completion_monitor.py, test_runner_preflight_policy.py}`.
- **Accept**:
  - `max_turns` (yaml key) **kept** in yaml configs (no operator-facing churn).
  - `native_agent_bridge.py` reads `max_turns` from cfg, sends intent extra as `eval_turn_budget` (new) instead of `max_turns`.
  - `MainActivityIntentPayload` parses `EXTRA_EVAL_TURN_BUDGET` → `SessionConfig.evalTurnBudget`.
  - `test_completion_monitor.py`: `"reason: MAX_TURNS"` test cases replaced with `"reason: ERROR"` + message-contains check for "eval turn budget".
  - Yaml schema unchanged. Eval reproducibility unchanged.
- **Deps**: task-9.

### task-11: `tests-update`
- **Scope**: All test files under `app/src/test/kotlin/ai/closepaw/{session,agent,app,ui,history,trace}` referencing `maxTurns`/`MAX_TURNS`/`MaxTurnsReached`/`COMPRESSION_DIGEST`.
- **Accept**:
  - Tests that purely tested the cap (`AgentRunLoopTest:194,206,194-220`, `CapsuleApprovalTransitionTest:209`, `SessionCheckpointConfigSnapshotTest`) deleted.
  - Tests using `maxTurns = N` as mock-LLM safety net rewritten: mocks terminate explicitly (counted scripted client / explicit `complete_task` after N).
  - New: `CompactorTest` covers cut-point edge cases, CAS race, cancellation propagation, iterative previous-summary, oversized-turn no-op.
  - New: `HistoryManagerCasTest` covers concurrent supplement vs CAS.
  - New: `TurnReactiveCompactionTest` covers prompt_too_long retry path.
  - New: agent loop test — recoverable error retries indefinitely (no `hasRemainingTurns` gate); compaction `Failed`×3 triggers Error stop; `Stale` does not increment failure counter.
  - Prompt-output test: observation text never contains `"Turn N/M"` or `"FINAL TURN"`.
- **Deps**: task-9.

### task-12: `verify`
- **Scope**: `./gradlew clean assembleDebug lint test` + a manual debug-run.
- **Accept**: All green. Manual `scripts/debug-run.sh "Open Settings"` completes without any max-turn UI/status. Force-trigger compaction by temporarily setting `reserveTokens` high; observe `📚 Compacted history` status event and verify next turn proceeds normally.
- **Deps**: task-1 through task-11.

## Trade-offs (vs v1)

**Why CAS instead of a coarse lock?**
A coarse `Mutex` around compaction would block supplement injection during the LLM call (2–10s). The user types a supplement to *steer the agent right now*; making them wait until the agent finishes summarizing defeats the purpose. CAS is 20 LOC and gets us read-during-await semantics.

**Why `evalTurnBudget` survives instead of removing all turn knobs?**
Eval has a legitimate need to cap cost on a runaway loop (~$10/eval-task ceiling). Removing the cap entirely and pushing it to "monitor turn-started events externally + send Stop" works but doubles eval bridge complexity. A 5-line nullable knob with a different name (no longer `maxTurns`) is cheaper, scoped to its actual purpose, and doesn't leak into the prompt.

**Why static-overhead estimate + reactive retry instead of computing full prompt size?**
Computing the full prompt requires running `PromptBuilder` (which has its own side effects: trace recording, observation building) just to count tokens. Doing it twice per turn (once for the estimate, once for the real call) is wasteful. A measured static-overhead ceiling + 24K reserve covers the common case; reactive retry covers drift. Total cost: ~30 LOC of catch-and-retry vs. ~150 LOC of prompt-size-estimator wiring.

**Why preserve `recentWindowSize` and `recentFullScreens` in `HistoryConfig`?**
Both are still used by the surviving subsystems: `recentFullScreens` controls how many screens stay full-fidelity before downgrade (Layer 0 defense); `recentWindowSize` is read by tests and by the no-touch-tail logic that could be reused inside cut-point search. Cheap to keep, removing them is yak-shaving.

**Why per-Agent Compactor instead of session-level?**
`SubAgentRunner` creates fresh `HistoryManager` per child and may use `subagentModel` (different `contextWindow`, different `LLMClient`). A session-level compactor would either compact the wrong history or use the wrong model. Per-Agent is structurally correct, costs one `Compactor` instance per subagent (subagents are short-lived anyway).

**Why drop `## Goal` from the summary prompt?**
The current goal is canonically re-injected by the agent loop on every compaction. Having the summarizer also restate it invites drift (model paraphrases the goal slightly, then we keep the paraphrase forever). Single source of truth: `AgentExecutionConfig.goal`.
