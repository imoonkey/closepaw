# History Compression Redesign

## 1. Reference Analysis

### 1.1 Coding Agents

#### Codex (OpenAI)

Codex has the most sophisticated compression system among the coding agents analyzed. Key mechanisms:

**Trigger**: Auto-compaction fires when `total_usage_tokens >= auto_compact_token_limit` (model-specific). Can trigger pre-turn, mid-turn, or on model switch (context window shrinkage).

**Local compaction** (non-OpenAI models): Runs a dedicated LLM call with a "context checkpoint" prompt. The LLM generates a handoff summary for its future self. Then Codex keeps:
- Real user messages (selected reverse-chronologically, budget: 20K tokens)
- The LLM-generated summary (as a `Compaction` item)
- Ghost snapshots (for `/undo` support)

Everything else — assistant messages, tool calls, tool outputs, reasoning — is dropped.

**Remote compaction** (OpenAI models): Calls a proprietary `POST /v1/responses/compact` API endpoint. Post-processing filters keep only: real user messages, assistant messages, and compaction items. All tool artifacts are dropped.

**Context reinjection**: After compaction, the initial context (system prompt, instructions) is reinjected. Two modes: `DoNotInject` (pre-turn; next turn will inject automatically) vs `BeforeLastUserMessage` (mid-turn; inject immediately before last real user message, which is the pattern the model was trained on).

**Overflow fallback**: If the context window is still exceeded after compaction, Codex removes items from the oldest end, one at a time, until it fits. Only removes "Codex-generated" items (tool outputs, developer messages). Stops at user/assistant messages.

**Key insight**: Codex treats compression as a "handoff" — old context becomes a summary, and the LLM starts fresh with that summary plus recent user messages.

#### Gemini CLI (Google)

Three-phase approach:

**Phase 1 — Split**: Find a safe split point at ~70% of history (only splits after user messages, never mid-tool-call). Keep last 30% intact.

**Phase 2 — LLM Summarize**: Run a dedicated model call to produce a `<state_snapshot>` of the compressed portion. Then a second verification pass: "Did you miss critical details?" On subsequent compressions, the LLM must integrate the previous snapshot (prevents cascading information loss).

**Phase 3 — Reassemble**: `[summary as user message] + [model acknowledgment] + [preserved 30% tail]`

**Token management for tool outputs**: Reverse token budget (50K tokens, newest-first). Old tool outputs that exceed the budget are truncated and saved to a file with a reference in history.

**Failure recovery**: If LLM summarization fails (empty summary, inflated token count), falls back to truncation-only mode. If that fails too, keeps original history (no-op).

**Key insight**: The two-pass summarization with verification is clever — it catches information loss that a single pass would miss. The 70/30 split ensures recent context is always pristine.

### 1.2 Mobile Agents

#### MAI-UI (Alibaba)

Sliding window on images. `history_n = 3` — only the last `(history_n - 1)` steps include screenshots. All text history (actions, thoughts, assistant responses) is kept regardless. Vision budget is the control lever.

#### DroidRun (startup)

Three-layer memory:
- **Action history**: Fixed window, last 5 actions with structured fields (action, summary, outcome, error)
- **Fast memory**: Max 10 items, sliding window
- **Manager memory**: Append-only string for planning notes, never compressed
- **Chat history**: `limit_history(messages, max_messages, preserve_first=True)` — keep first message (system) + last N-1. Simple and effective.

#### MobileAgent V3 (ByteDance)

`max_turns` config caps conversation length. Separate `limit_images` parameter at inference engine level. Tool responses tracked inline. No explicit compression — relies on turn budget.

#### AutoDevice (Google)

Most aggressive: strips ALL images from history after EVERY LLM call. Uses Anthropic prompt caching (5-min ephemeral TTL) to offset the cost of re-sending full text history. Scratchpad as external memory to store extracted data outside conversation history. `clear_history()` keeps only system prompt.

#### minitap (startup)

Separate message tracks for cortex (planner) and executor. After each cortex decision, ALL executor messages are wiped (`RemoveMessage(id=REMOVE_ALL_MESSAGES)`). Screenshot quality compressed to 50%. State managed via LangGraph reducers.

### 1.3 Common Patterns

Every agent that handles history compression well does the same thing:

```
PROTECTED HEAD:  task definition, user goal, system context
EXPENDABLE MIDDLE: old observations, old tool results, old reasoning
PROTECTED TAIL:  recent N turns in full detail
```

**Universal truth #1**: Screen observations / screenshots are the #1 compression target. They're massive (5-20K tokens each for a11y trees) and ephemeral (current screen is always fresh).

**Universal truth #2**: User intent messages are sacred. No agent ever drops the task goal or user corrections.

**Universal truth #3**: Recent context is disproportionately valuable. Every system protects the tail.

**Code agent vs mobile agent difference**: Code agents often use LLM summarization because their tool outputs (file contents, command results) are semantically rich and hard to compress structurally. Mobile agents can get away with structural compression because screen observations are inherently transient — the device has moved on.

---

## 2. Current Implementation: What's Broken

### 2.1 The Bug

From debug session: user sent supplement "改成听陈奕迪" during a running task. After compression, the agent lost track of the correction and reverted to the original task.

### 2.2 Root Cause

The bug is not that `compress()` removes user messages. The D2 fix already protects `role="user"` messages from eviction.

**The real bug**: Screen observations are `Message(role="user", isScreenObservation=true)`. The current `compress()` treats ALL `role="user"` messages as sacred, including screen observations. Since screen observations are 5-20K tokens each, they dominate the token budget and compression can't meaningfully reduce history size.

After compression, the history is still too large. The LLM sees a wall of stale screen-state JSON, and the user's supplement drowns in noise. The correction *exists* but the model can't attend to it effectively.

### 2.3 Structural Problem

Beyond the screen observation bug, the eviction loop has a deeper design flaw:

```kotlin
// Current: removes individual items from front
val removeIndex = items.indexOfFirst { item ->
    item !is ResponseItem.Message || item.role != "user"
}
items.removeAt(removeIndex)
```

This removes items one at a time in a structurally blind way:
- Can remove an assistant message but leave orphaned tool calls from that same response
- Can remove a tool call but leave its FunctionCallOutput orphaned (partially handled by paired removal)
- Destroys conversation coherence — after compression, the history can have user messages with no responses, tool outputs with no context

The result is a history that doesn't read like a conversation. The LLM sees fragments.

### 2.4 No Recent Window Protection

Neither the current compress() nor its eviction loop protects recent items. In practice, `indexOfFirst` removes from the front (oldest first), so recent items usually survive. But this is an implicit guarantee, not an explicit one. There's no hard boundary saying "the last N items are never touched."

### 2.5 PromptBuilder Duplication

`PromptBuilder.compressOldScreenObservations()` does temporary screen compression for prompt building. This is the right idea in the wrong place:
- Old screen data stays in memory (wasted RAM)
- Only affects the prompt, not stored history or checkpoints
- Doesn't help `compress()` meet its token budget
- Duplicates compression logic across two classes

### 2.6 Empirical Evidence: eval/results/20260223_154150

Analyzed 4 failed tasks (30 turns each, MaxTurnsReached) and 2 successful tasks from the round 5 eval. The production config is `maxTokenBudget = 18_000` with `TruncationPolicy.AGGRESSIVE` (from `SessionHistoryBootstrapper.kt:27`).

#### Compression is completely broken in practice

**FilesMoveFile** (30 turns, a11y tree avg: 4150 tokens/screen, 6232 tokens max):

```
Turn  7: 23 items, 16790 tokens → compress fires → NO-OP (16790 < 18000 target)
Turn  8: 26 items, 20709 tokens → compress → evicts 17 items → 9 items, 20097 tokens
                                   STILL OVER 18K. Can't get below because remaining
                                   items are all protected screen observations.
Turn  9 onward: EVERY addItem() triggers compress().
                 Tool calls/outputs are added then IMMEDIATELY evicted.
                 Screen observations grow unchecked.

Turn 12: 12 items, 22658 tokens (growing)
Turn 15: 15 items, 26250 tokens (growing)
Turn 20: 20 items, 42574 tokens (growing)
Turn 25: 25 items, 62199 tokens (growing)
Turn 30: 31 items, 85749 tokens → History cleared (last resort)
```

After the turn 8 compression, the system enters a death spiral:
1. New screen observation added → +4K tokens, protected (it's a "user" message)
2. New assistant/tool items added → +300 tokens, immediately evicted by compress()
3. Net effect: +4K tokens per turn, monotonically increasing, compression does nothing

The compression algorithm ran **60+ times** from turn 8 to turn 30 and saved a total of ~0 tokens beyond the initial eviction. Meanwhile, token count grew from 20K to 86K.

**Across all 4 failed tasks**, the same pattern:

| Task | Compression Turn | Post-Compress Items | Post-Compress Tokens | Final Tokens |
|------|-----------------|--------------------|--------------------|-------------|
| FilesMoveFile | 8 | 9 | 20,097 | 85,749 |
| ClockTimerEntry | 11 | 12 | ~18,000 | ~50,000+ |
| BrowserMultiply | 21 | 37→23 | ~18,000 | ~30,000+ |
| SimpleSmsSend | 26 | 69→30 | ~18,000 | ~18,900 |

FilesMoveFile shows the worst case: File Manager has large a11y trees (avg 4150 tokens, max 6232). After compression, ONLY screen observations remain, and they're untouchable.

#### Context loss causes action repetition

**BrowserMultiply**: Agent clicked a button 5 times (turns 12-20), then compression at turn 21 removed all the tool call history. At turn 23, the agent "forgot" it already completed 5 clicks and started the entire clicking sequence over. Wasted 6 turns (20% of budget) repeating work.

#### Token budget math proves the design

The production budget is 18,000 tokens. Each screen observation averages 1500-4000 tokens depending on the app's UI complexity.

**Current design**: 18K ÷ 4K/screen = 4.5 screens max. After 5 turns, screen observations alone exhaust the budget. Compression can't help because screens are protected.

**New design** (proactive screen downgrade, keep last 3 full):
- 3 full screens: 3 × 4K = 12K tokens
- N compressed screens: N × 10 = negligible
- Remaining budget: 18K - 12K = 6K for assistant/tool history
- Each turn of assistant+tool ≈ 300 tokens → fits 20 turns of action history

The new design makes 18K viable for 20+ turns. The current design makes it fail at turn 5-8.

---

## 3. New Design

### 3.1 First Principles

**What does the LLM need from history?**

| Information | Priority | Why |
|-------------|----------|-----|
| Task goal + user corrections | CRITICAL | Defines what to do. Without this, the agent is lost. |
| Recent turns (last 3-5) | HIGH | Context for the current decision. What just happened? |
| What was tried before | MEDIUM | Prevents repeating failed actions |
| Old screen states | LOW | Screens change every turn; old ones are noise |

The current screen is always attached fresh by PromptBuilder. Old screens serve almost no purpose — the device has moved on.

**Design principle**: Compress what's cheap to lose. Screen observations are the most compressible because (a) they're the largest items and (b) the current screen always replaces them. After that, tool outputs can be aggressively truncated — the tool name and success/failure already tell the story.

### 3.2 The Algorithm

```
compress(targetTokens):

    Phase 1: DOWNGRADE old screen observations
      Keep last K (default: 3) screen observations uncompressed.
      Replace older ones with one-liner: "Screen: N elements (compressed)"
      Alone, this reclaims 60-90% of history token usage.
      → early return if within budget

    Phase 2: TRUNCATE old tool outputs
      Apply AGGRESSIVE policy (2K token max) to all outputs outside
      the recent window (last recentWindowSize items).
      → early return if within budget

    Phase 3: EVICT oldest non-essential items
      Working from oldest toward newest, stop before the recent window.
      Never evict: Message(role="user", isScreenObservation=false)
      Evict in structural groups: remove a FunctionCall together with
      its FunctionCallOutput. Remove compressed screen observations.
      Remove assistant messages.
      → early return if within budget
```

### 3.3 Key Design Decisions

**Proactive screen compression**: When a new screen observation is added to history, immediately downgrade all old ones beyond the retention window. This keeps memory lean at all times. `compress()` Phase 1 becomes a safety net, not the primary mechanism.

**Recent window protection**: The last `recentWindowSize` items (default: 10) are never touched by Phase 2 or Phase 3. They represent the most recent 2-3 turns and are critical for the current decision. Only Phase 1 (screen downgrade) can affect items inside the recent window — and it only rewrites the content, never removes.

**No LLM summarization (for now)**: Mobile agent screen observations are inherently transient. Structural compression (screen downgrade + output truncation + eviction) handles 95%+ of cases. LLM summarization can be added as a future Phase 0 for extreme cases, but the structural approach is cheaper, faster, and deterministic.

**No pluggable strategy pattern**: One agent, one model, one flow. The phased design is easy to extend if needed — each phase can become a strategy later. But a well-tuned single algorithm beats a framework.

### 3.4 Implementation

#### HistoryConfig.kt

Add `recentFullScreens` and `recentWindowSize` parameters:

```kotlin
data class HistoryConfig(
    val defaultTruncationPolicy: TruncationPolicy = TruncationPolicy.CONSERVATIVE,
    val maxTokenBudget: Long = 100_000,
    val autoCompress: Boolean = true,
    val autoCompressThreshold: Float = 0.85f,
    val recentFullScreens: Int = 3,
    val recentWindowSize: Int = 10
)
```

#### HistoryManager.kt — `downgradeOldScreens()`

New private method. Called proactively on every screen observation addition.

```kotlin
private fun downgradeOldScreens() {
    val screenIndices = items.indices.filter {
        val item = items[it]
        item is ResponseItem.Message && item.isScreenObservation
    }
    if (screenIndices.size <= config.recentFullScreens) return

    val toDowngrade = screenIndices.dropLast(config.recentFullScreens)
    for (i in toDowngrade) {
        val msg = items[i] as ResponseItem.Message
        if (!msg.content.endsWith("(compressed)")) {
            items[i] = msg.copy(content = compressScreenContent(msg.content))
        }
    }
    lastTokenEstimate = null
}
```

#### HistoryManager.kt — `compressScreenContent()`

Moved from PromptBuilder into HistoryManager:

```kotlin
private fun compressScreenContent(fullContent: String): String {
    val count = ELEMENT_COUNT_REGEX.find(fullContent)?.groupValues?.get(1)
    return when {
        count != null -> "Screen: $count elements (compressed)"
        fullContent.contains("No accessibility tree") ||
            fullContent.contains("accessibility tree omitted") ->
            "Screen: screenshot only (compressed)"
        else -> "Screen: unknown (compressed)"
    }
}

companion object {
    private const val TAG = "HistoryManager"
    private const val TOKENS_PER_CHAR = 0.25f
    private val ELEMENT_COUNT_REGEX = Regex("""Screen state \((\d+) elements\)""")
}
```

#### HistoryManager.kt — `addItem()` change

Trigger proactive screen downgrade when a new screen observation arrives:

```kotlin
@Synchronized
fun addItem(item: ResponseItem) {
    val processed = processItem(item, config.defaultTruncationPolicy)
    items.add(processed)
    lastTokenEstimate = null

    // Proactive: downgrade old screens when a new one arrives
    if (processed is ResponseItem.Message && processed.isScreenObservation) {
        downgradeOldScreens()
    }

    Log.d(TAG, "Added item: ${item.javaClass.simpleName}, total items: ${items.size}")
    autoCompressIfNeeded()
    onMutation?.invoke()
}
```

#### HistoryManager.kt — `compress()` rewrite

```kotlin
@Synchronized
fun compress(targetTokens: Long) {
    Log.d(TAG, "Compressing from ${estimateTokenCount()} tokens, target: $targetTokens")

    // Phase 1: Downgrade old screen observations
    downgradeOldScreens()
    if (estimateTokenCount() <= targetTokens) {
        onMutation?.invoke()
        return
    }

    // Phase 2: Truncate old tool outputs (outside recent window)
    val recentBoundary = recentBoundary()
    for (i in 0 until recentBoundary) {
        val item = items[i]
        if (item is ResponseItem.FunctionCallOutput) {
            items[i] = truncateOutput(item, TruncationPolicy.AGGRESSIVE)
        }
    }
    lastTokenEstimate = null
    if (estimateTokenCount() <= targetTokens) {
        onMutation?.invoke()
        return
    }

    // Phase 3: Evict oldest non-essential items (outside recent window)
    while (estimateTokenCount() > targetTokens && items.size > config.recentWindowSize) {
        val boundary = recentBoundary()
        val removeIndex = items.subList(0, boundary).indexOfFirst { isEvictable(it) }
        if (removeIndex < 0) break

        val removed = items.removeAt(removeIndex)
        // Paired removal: evicting a FunctionCall also evicts its output
        if (removed is ResponseItem.FunctionCall) {
            val outputIdx = items.indexOfFirst {
                it is ResponseItem.FunctionCallOutput && it.callId == removed.id
            }
            if (outputIdx >= 0) items.removeAt(outputIdx)
        }
        lastTokenEstimate = null
    }

    Log.d(TAG, "Compressed to ${estimateTokenCount()} tokens, ${items.size} items")
    onMutation?.invoke()
}

/**
 * Index marking the start of the "recent window" — items at or after
 * this index are protected from Phase 2 truncation and Phase 3 eviction.
 */
private fun recentBoundary(): Int =
    (items.size - config.recentWindowSize).coerceAtLeast(0)

/**
 * Can this item be evicted during Phase 3?
 *
 * Protected (never evicted): user text messages (goal, supplements, corrections).
 * Evictable: everything else — assistant messages, screen observations (already
 * compressed by Phase 1), tool calls, tool outputs.
 */
private fun isEvictable(item: ResponseItem): Boolean {
    if (item is ResponseItem.Message && item.role == "user" && !item.isScreenObservation) {
        return false
    }
    return true
}
```

#### PromptBuilder.kt — Simplification

Remove all screen compression logic. HistoryManager now handles this permanently.

Remove:
- `compressOldScreenObservations()` method
- `compressScreenContent()` method
- `recentFullScreenTurns` constructor parameter
- `ELEMENT_COUNT_REGEX` companion constant

Simplify `buildHistorySection()`:

```kotlin
private fun buildHistorySection(): List<ResponseInputItem> {
    return historyManager.forPrompt().mapNotNull { it.toResponseInputItem() }
}
```

### 3.5 What Stays The Same

- `ResponseItem` sealed class — `isScreenObservation` already provides the needed flag
- `PersistedHistoryItem` and `HistoryItemConverter` — compressed content is just shorter strings
- `normalizeHistory()` — call/output pairing is orthogonal to compression
- `forPrompt()` — returns normalized items, no change
- `dropLastNUserTurns()` — rollback logic is unchanged
- `SessionCheckpointCoordinator` — snapshots contain whatever's in history
- `estimateTokenCount()` — same estimation method

### 3.6 Example: Before and After

Example history at turn 8 (before compression):

```
Item  Type              Tokens    Status
───────────────────────────────────────────
 0    User: "Goal"         60    protected
 1    Screen obs           15K   ← Phase 1 target
 2    Assistant             200   ← Phase 3 target
 3    FunctionCall           60   ← Phase 3 target
 4    FunctionCallOutput   2K    ← Phase 2 target
 5    User: "改成听陈奕迪"    30   protected
 6    Screen obs           12K   ← Phase 1 target
 7    Assistant             200   ← Phase 3 target
 8    FunctionCall           60   ← Phase 3 target
 9    FunctionCallOutput   1K    ← Phase 2 target
10    Screen obs           10K   ← recent window (kept full)
11    Assistant             200   ← recent window
12    FunctionCall           60   ← recent window
13    FunctionCallOutput    1K   ← recent window
14    Screen obs            8K   ← recent window (kept full)
15    Assistant             200   ← recent window
16    FunctionCall           60   ← recent window
17    FunctionCallOutput    1K   ← recent window

Total: ~51K tokens
Target: 30K tokens
```

After Phase 1 (screen downgrade, keeping last 3 screens [items 10, 14, current]):

```
 0    User: "Goal"          60
 1    Screen: compressed     10   ← was 15K
 5    User: "改成听陈奕迪"    30
 6    Screen: compressed     10   ← was 12K
10    Screen obs           10K   ← kept (recent 3)
14    Screen obs            8K   ← kept (recent 3)
... rest unchanged ...

Total: ~14K tokens ← already under 30K
```

Phase 1 alone reclaimed ~27K tokens (53%). Phases 2 and 3 aren't even needed in this scenario. The user's supplement is intact. The recent turns are intact. The LLM gets a clean, readable history.

### 3.7 Why Not Something Fancier?

**Why not LLM summarization?** (like Codex or Gemini CLI)
- Extra LLM call per compression adds latency and cost on mobile
- Screen observations are 80-90% of token usage, and they compress trivially with structural downgrade
- Code agent tool outputs are semantically rich (file contents, code diffs) and need intelligent summarization. Mobile agent tool outputs are simple ("tapped element 5", "scrolled down") and can be dropped or truncated without semantic loss
- LLM summarization is a solid future extension (Phase 0 before Phase 1) for very long conversations that outlive structural compression. The current design doesn't preclude it.

**Why not a turn-based grouping abstraction?** (parse history into `Turn` objects)
- Adds a data structure layer without corresponding benefit. The flat list with `isScreenObservation` flag is sufficient to identify compression targets.
- "Turns" in a mobile agent are ambiguous — not every turn has a user message, not every turn has tool calls. Forcing a Turn abstraction creates edge case handling that doesn't exist in the flat model.
- If turn grouping becomes needed for future features (e.g., turn-level summarization), it can be added then.

**Why not a pluggable condenser pipeline?** (like OpenHands)
- OpenHands has 10+ condenser strategies because it supports many model backends, tool types, and conversation patterns. We have one agent, one model per session, and a predictable conversation structure.
- The phased design IS a pipeline, just a hardcoded one. Each phase can be factored into a strategy later if the need arises.

---

## 4. Test Plan

### New Tests

```
HistoryManagerTest:
  compress_downgrades_old_screen_observations
    Given: 5 screen observations + other items, recentFullScreens=3
    Then: first 2 screen observations compressed to one-liners, last 3 unchanged

  compress_preserves_user_supplements_among_screens
    Given: [user goal, screen, assistant, tool call, tool output,
            user supplement, screen, assistant, tool call, tool output]
    When: compress(100) // extremely tight budget
    Then: both user messages survive, screen obs downgraded

  compress_protects_recent_window
    Given: 20 items, recentWindowSize=10
    When: compress(tight budget)
    Then: last 10 items untouched by Phase 2 and Phase 3

  proactive_screen_downgrade_on_addItem
    Given: 3 screen observations already in history (recentFullScreens=3)
    When: addItem(4th screen observation)
    Then: oldest screen observation now compressed

  downgrade_is_idempotent
    Given: already-compressed screen observations
    When: downgradeOldScreens() called again
    Then: no change to already-compressed items

  compress_evicts_oldest_first_respects_recent_window
    Given: 20 items including assistant messages, tool calls, tool outputs
    When: compress(tight budget)
    Then: items removed from front, recent window items untouched,
          user text messages never removed

  compress_paired_removal
    Given: FunctionCall + FunctionCallOutput pair in history
    When: FunctionCall is evicted
    Then: its FunctionCallOutput is also removed
```

### Existing Tests to Update

- `compress never removes user messages` — verify behavior unchanged (non-screen user messages still sacred)
- `auto compress keeps token budget bounded` — verify still passes with new algorithm
- `forPrompt adds placeholder output when missing` — unchanged, normalization is orthogonal
- PromptBuilder tests — remove `compressOldScreenObservations` test, simplify `buildHistorySection` test

---

## 5. Implementation Checklist

- [ ] Add `recentFullScreens: Int = 3` and `recentWindowSize: Int = 10` to `HistoryConfig`
- [ ] Add `ELEMENT_COUNT_REGEX` to `HistoryManager` companion object
- [ ] Add `compressScreenContent()` private method to `HistoryManager`
- [ ] Add `downgradeOldScreens()` private method to `HistoryManager`
- [ ] Add `isEvictable()` private method to `HistoryManager`
- [ ] Add `recentBoundary()` private method to `HistoryManager`
- [ ] Modify `addItem()` to call `downgradeOldScreens()` on new screen observations
- [ ] Rewrite `compress()` with three-phase algorithm and recent window protection
- [ ] Remove from `PromptBuilder`: `compressOldScreenObservations()`, `compressScreenContent()`, `recentFullScreenTurns` parameter, `ELEMENT_COUNT_REGEX`
- [ ] Simplify `PromptBuilder.buildHistorySection()` to just call `forPrompt()` directly
- [ ] Write new tests (see Section 4)
- [ ] Update existing tests as needed
- [ ] Run: `./gradlew clean assembleDebug lint test`
