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
Decision: Yes, internal-only `TurnBlock`, not persisted.
- `TurnBlock` is a local helper for compression only.
- No extra persisted schema for turn graph.

Why:
- Item-by-item eviction is what breaks coherence.
- Turn grouping is the minimum structure needed to compress safely.

### D3. Should we add digest/summary replacement?
Decision: Yes, deterministic digest only (no extra LLM call).
- Replace evicted turn evidence with one short `COMPRESSION_DIGEST` message.
- Digest template is fixed and deterministic.

Why:
- Pure deletion loses anti-repeat context.
- LLM summarization is expensive and unnecessary at this stage.

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
1. `USER_INTENT` is never deleted by compression.
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
- Role mapping for API output is derived from `kind` (user kinds vs assistant kinds).

### 3.3 Compression Pipeline

Phase 0: Normalize
- Ensure function call/output consistency first.

Phase 1: Screen downgrade (highest ROI)
- Keep last `recentFullScreens` screen observations as full text.
- Rewrite older ones to one-line summaries.
- Run proactively on every new screen observation.

Phase 2: Tool output truncation
- Outside protected recent window, truncate old `FunctionCallOutput` aggressively.

Phase 3: Turn-aware digest replacement
- Build `TurnBlock(anchor=USER_INTENT, evidence=until next USER_INTENT)`.
- For oldest eligible blocks, keep anchor; replace evidence with one digest.
- Do not touch protected recent window.

Phase 4: Hard guard
- If still above budget, merge oldest digests.
- If still above budget and only anchors+digest remain: return `BudgetUnreachable`.

### 3.4 Protected Recent Window
- `recentWindowSize` items are protected from Phase 2 and Phase 3 eviction/replacement.
- Phase 1 may still rewrite old screen payloads if they are outside `recentFullScreens`.

### 3.5 API/Behavior Changes
`HistoryManager.compress(targetTokens)` returns `CompressionResult`:
- `Noop(before, after)`
- `Compressed(before, after, stepsApplied)`
- `BudgetUnreachable(after, minimumPossible)`

`dropLastNUserTurns(n)` counts only `MessageKind.USER_INTENT`.

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

## 5. P0 Tests
1. Compression never deletes `USER_INTENT`.
2. `dropLastNUserTurns()` only counts `USER_INTENT` boundaries.
3. Screen downgrade keeps last N full screens, rewrites older ones.
4. Call/output pairing survives every compression phase.
5. Recent window is protected from eviction/replacement.
6. Repeated `compress()` is idempotent once stabilized.
7. `BudgetUnreachable` is reported when mathematically impossible.

## 6. Non-Goals
- No LLM-based summarization in this iteration.
- No backward compatibility with old snapshot schema beyond what the project explicitly decides to keep.
