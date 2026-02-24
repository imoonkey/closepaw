# History Compression Alignment Design

## 1. Problem Statement
The current `HistoryManager.compress()` is structurally wrong for mobile-agent history:
- It protects all `role == "user"` messages, including screen observations.
- Screen observations are the biggest token consumer, so compression frequently cannot hit budget.
- Compression degenerates into repeated no-op loops.

Evidence from code and logs:
- Screen observations are written as `ResponseItem.Message(role="user", isScreenObservation=true)` in `TurnPlanningPhaseRunner.kt:176-181`.
- Compression preserves `role == "user"` in `HistoryManager.kt:236-243`.
- In failed eval run `FilesMoveFile`, compression keeps firing but token count keeps growing (log lines around `logcat.log:3875`, `7266`, `10397`).
- `dropLastNUserTurns()` currently counts any `role == "user"` as a turn (`HistoryManager.kt:163-165`), which is semantically wrong for screen observations.

## 2. Resolved Disagreements (Codex vs Claude)

### D1. Do we need stronger message semantics?
Decision: Yes, but minimal.
- Replace role-string + `isScreenObservation` coupling with explicit `MessageKind`.
- Keep design simple: no deep inheritance, no complicated polymorphism.

Why:
- Current ambiguity is real and visible in code (`role="user"` used for both intent and screen).
- This is the root class of errors for compression and rollback semantics.

### D2. Do we need turn-level compression units?
Decision: No TurnBlock. Use flat-list structural groups.

Evidence:
- In `eval/results/20260223_154150`, final-turn histories across all 14 runs contain exactly one `USER_INTENT` message each (`all_ui_eq_1 = true`).
- With this data shape, anchor-based TurnBlock collapses into one mega-block and loses compression granularity.

Final approach:
- Keep one flat history list.
- Evict by structural groups from oldest to newest:
  - group A: one old `SCREEN_OBSERVATION` (already downgraded by Phase 1), or
  - group B: one action bundle `[ASSISTANT_TEXT + FunctionCall + FunctionCallOutput]`.
- Preserve coherence by evicting whole groups only (never orphan call/output pairs).

### D3. Should we add digest/summary replacement?
Decision: Yes, deterministic digest only (no extra LLM call).
- When Phase 2 evicts items, insert one `COMPRESSION_DIGEST` message at the eviction point.
- Digest is mechanical, not semantic: counts of items evicted by type.
- Does NOT require TurnBlock — digest summarizes whatever was evicted in a batch.

Concrete template:
```
"[Compressed] Removed N earlier items: M tool actions, K screen observations. History truncated to save context."
```

Why:
- Pure deletion loses anti-repeat context.
- LLM summarization is expensive and unnecessary at this stage.
- Mechanical digest is cheap, deterministic, and doesn't try to replicate semantic understanding.

### D4. Proactive compression or only reactive?
Decision: Both.
- Proactive: downgrade old screens immediately when adding a new screen observation.
- Reactive: full `compress()` still runs when threshold is reached.

Why:
- Evidence shows reactive-only compress currently enters no-op loops.

### D5. Should PromptBuilder also compress history?
Decision: No.
- Compression is single-owner in `HistoryManager`.
- `PromptBuilder` only reads `historyManager.forPrompt()`.

Why:
- Two compression paths drift and create inconsistent behavior.

## 3. Final Design

### 3.1 Invariants
1. `USER_INTENT` is never deleted by compression — hard bound, not heuristic.
   - In real usage users issue follow-up requests after task completion (e.g., "open Settings" → agent finishes → "now turn on dark mode"). Each follow-up is a new `USER_INTENT`. ALL must survive compression regardless of count.
   - `isEvictable()` returns `false` for `USER_INTENT`. No phase touches them: Phase 1 only rewrites `SCREEN_OBSERVATION`, Phase 2 only evicts non-`USER_INTENT` groups, Phase 3 only merges `COMPRESSION_DIGEST`.
   - Only `BudgetUnreachable` may be returned when `USER_INTENT` messages alone exceed the budget.
2. Compression must preserve call/output pairing invariants.
3. Screen observations are always first compression target.
4. Compression is deterministic.
5. If budget cannot be met, return explicit `BudgetUnreachable`.

### 3.2 Data Model
`ResponseItem.Message` becomes:
- `kind: MessageKind`
- `content: String`
- `name: String?`

`MessageKind`:
- `USER_INTENT`
- `SCREEN_OBSERVATION`
- `ASSISTANT_TEXT`
- `COMPRESSION_DIGEST`

Notes:
- Remove `isScreenObservation`.
- Remove logic that infers intent by `role == "user"`.
- Role mapping for API output is derived from `kind`:
  - `USER_INTENT`, `SCREEN_OBSERVATION` → `"user"`
  - `ASSISTANT_TEXT`, `COMPRESSION_DIGEST` → `"assistant"`

### 3.2.1 Config Parameters

```kotlin
data class HistoryConfig(
    val defaultTruncationPolicy: TruncationPolicy = TruncationPolicy.CONSERVATIVE,
    val maxTokenBudget: Long = 100_000,
    val autoCompress: Boolean = true,
    val autoCompressThreshold: Float = 0.85f,
    val compressTargetRatio: Float = 0.5f,
    val recentFullScreens: Int = 3,
    val recentWindowSize: Int = 10
)
```

- `autoCompressThreshold`: fraction of `maxTokenBudget` at which compression triggers.
- `compressTargetRatio`: fraction of `maxTokenBudget` to compress DOWN to.

### 3.2.2 KV Cache Efficiency: Compress Rarely, Compress Deep

LLM providers (OpenAI, Anthropic) cache the KV states of the conversation prefix server-side. When history items are removed from the front, every subsequent token's cache entry is invalidated because positions shift. Frequent small compressions destroy KV cache hit rate.

**Bad pattern** (current behavior):
```
Turn 8:  15.3K → hits 85% of 18K → compress to ~17.9K → 0.1K headroom
Turn 9:  +4K → hits threshold again → compress again
→ KV cache invalidated every 1-2 turns
```

**Good pattern** (compress deep):
```
Turn 8:  15.3K → hits 85% of 18K → compress to 50% = 9K → 6.3K headroom
Turn 9-30: +~285 tokens/turn (screens net-zero via proactive downgrade)
→ ~22 turns of stable prefix, full KV cache reuse
```

Why +285 tokens/turn instead of +4K: proactive screen downgrade (Phase 1) runs on `addItem()`. When the 4th screen arrives, the oldest is compressed to ~10 tokens. Net per turn: ~275 tokens (assistant + call + output + screen delta).

Implementation:
```kotlin
fun autoCompressIfNeeded() {
    val current = estimateTokenCount()
    val trigger = (config.maxTokenBudget * config.autoCompressThreshold).toLong()
    if (current > trigger) {
        val target = (config.maxTokenBudget * config.compressTargetRatio).toLong()
        compress(target)
    }
}
```

With 18K budget: trigger at 15.3K, compress to 9K, stable for ~22 turns.

### 3.3 Compression Pipeline

Phase 0: Normalize
- Ensure function call/output consistency first.

Phase 1: Screen downgrade (highest ROI)
- Keep last `recentFullScreens` screen observations as full text.
- Rewrite older ones to one-line summaries.
- Run proactively on every new screen observation.

~~Phase 2: Tool output truncation~~ — **REMOVED**.
- Tool outputs in this agent are 13-65 tokens (e.g., `"Success: Clicked (964,2157)"`). Even 30 turns of tool outputs total ~500 tokens. Truncation at the AGGRESSIVE limit of 2000 tokens will never fire. Dead code.
- `TruncationPolicy` remains for initial `processItem()` on ingestion (existing behavior), just not as a compression phase.

Phase 2: Group-aware eviction with digest
- Evict structural groups from the flat list (oldest first, outside recent window).
- Never evict `USER_INTENT`.
- When at least one group is evicted in this pass, insert one mechanical `COMPRESSION_DIGEST` breadcrumb at the eviction point.

Phase 3: Hard guard
- Merge adjacent `COMPRESSION_DIGEST` messages into one.
- If still above budget and only `USER_INTENT` + digests remain: return `BudgetUnreachable`.

### 3.4 Protected Recent Window
- `recentWindowSize` items are protected from Phase 2 eviction.
- Phase 1 may still rewrite old screen payloads if they are outside `recentFullScreens`.

### 3.5 API/Behavior Changes
`HistoryManager.compress(targetTokens)` returns `CompressionResult`:
- `Noop(before, after)`
- `Compressed(before, after, stepsApplied)`
- `BudgetUnreachable(after, minimumPossible)`

**`dropLastNUserTurns(n)` → DELETE.**
- Zero callers in production. Dead code.
- Concept borrowed from Codex (coding agents) where file edits are reversible. In a mobile agent, actions are irreversible — tapping a button changes the physical device state. Dropping history doesn't undo the tap. "Rollback" is physically impossible.
- Remove method and its test.

## 4. Implementation Scope

Primary files:
- `app/src/main/kotlin/com/moonkey/androidagent/history/ResponseItem.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/history/HistoryManager.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnPlanningPhaseRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/Agent.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/prompt/PromptBuilder.kt`
- persistence/trace converters under `history/model` and `trace/`

Deprecate/remove:
- `isScreenObservation` field
- `PromptBuilder.compressOldScreenObservations()` and related helpers
- `HistoryManager.dropLastNUserTurns()` and its test

## 5. P0 Tests
1. Compression never deletes `USER_INTENT`.
2. Screen downgrade keeps last N full screens, rewrites older ones.
3. Call/output pairing survives every compression phase.
4. Recent window is protected from eviction/replacement.
5. Repeated `compress()` is idempotent once stabilized.
6. `BudgetUnreachable` is reported when mathematically impossible.

## 6. Non-Goals
- No LLM-based summarization in this iteration.
- No backward compatibility with old snapshot schema beyond what the project explicitly decides to keep.
