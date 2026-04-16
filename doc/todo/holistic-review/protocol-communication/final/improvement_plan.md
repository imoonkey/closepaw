# Protocol & Communication — Improvement Plan

Updated: 2026-04-16 (re-evaluated against current codebase)
Original: 2026-04-08

---

## Goals

- Fix the three concrete bugs: completion semantics, checkpoint persistence, approval validation.
- Prune dead event surface opportunistically when those files are open.
- No speculative restructuring — every change must fix a real problem or remove proven dead weight.

---

## Phase 1: Fix Completion Semantics

**Problem:** `CompletionReason` conflates task outcome with session shutdown reason, causing session recording to misreport successful tasks as failures when the session later shuts down.

### 1A. Split CompletionReason into Two Enums

```kotlin
enum class TaskOutcome {
    GOAL_ACHIEVED, MAX_TURNS, TASK_IMPOSSIBLE, ERROR, USER_STOPPED
}

enum class SessionEndReason {
    USER_STOPPED, IDLE_TIMEOUT, INTERRUPTED
}
```

### 1B. Update Event Types

- `TaskCompleted(..., outcome: TaskOutcome)` — replace `reason: CompletionReason`
- `SessionCompleted(..., reason: SessionEndReason)` — replace `reason: CompletionReason`
- Remove `SessionCompleted.result` (always null, no consumer)

### 1C. Fix Consumers

Remove impossible branches in:
- `AgentServiceEventHandler` — `SessionCompleted` handler branches on `GOAL_ACHIEVED`, `MAX_TURNS`, `TASK_IMPOSSIBLE`, `ERROR` (impossible at session level)
- `CapsuleStateHolder.onSessionEnded()` — same impossible branches

### 1D. Fix Session Recording

Update `SessionRecordingService.completeSession()` to derive `completedNormally` from the last `TaskCompleted.outcome` rather than the session-level reason. A session that completes a task successfully then idles out should record as successful.

**Risk:** Medium — touches event types and all consumers. Build-verify after.

---

## Phase 2: Fix Checkpoint Persistence

**Problem:** `SessionCheckpointCoordinator` silently drops `actionDelayMs`, `approvalMode`, `debugMode`, `traceEnabled`, `traceRunId`, and `excludedTools` on reload. This can change security posture and tooling behavior.

### 2A. Persist All Runtime-Affecting Fields

Add the missing fields to the checkpoint snapshot and restore path in `SessionCheckpointCoordinator`.

### 2B. Document Intentionally Non-Persisted Fields

If any fields are deliberately transient (e.g., debug-only settings), document that decision explicitly with a comment explaining why.

**Risk:** Low — additive change to persistence. Verify with round-trip test.

---

## Phase 3: Fix Approval Validation

**Problem:** `handleApproval()` mutates allow-lists before checking if a pending approval exists. Stale/duplicate `Op.Approve` can change security policy without matching a real approval request.

### 3A. Gate Allow-List Mutation on resolveApproval() Success

Reorder `AgentSession.handleApproval()` so that:
1. Call `toolRouter.resolveApproval()` first
2. Only if it returns `true`, persist allow-list changes for `SESSION`/`ALWAYS` scope
3. If `false`, log a warning and discard the op

**Risk:** Low — small reorder in one method. Critical correctness fix.

---

## Phase 4: Prune Dead Event Surface (opportunistic)

Do these when already editing nearby files from Phases 1-3. Not worth standalone commits.

| Item | Action | When to do it |
|------|--------|---------------|
| `TodosUpdated` / `ScratchpadUpdated` | Stop emitting (delete dispatcher methods + event classes) | When touching `AgentEventDispatcher` |
| `ApprovalResolved` | Stop emitting | When touching `AgentSession` approval path (Phase 3) |
| `ApprovalRequired.actionId` | Remove field | When touching approval events |
| `TurnStarted.phase` | Remove field (keep `TurnPhaseChanged` as the canonical signal) | When touching turn events |
| `StatusUpdate.emoji` | Remove field + consumer branch | When touching status events |

---

## Execution Order

| Step | What | Risk | Dependency |
|------|------|------|------------|
| 1 | Phase 3: approval validation | Low | None — isolated fix |
| 2 | Phase 1: completion semantics | Medium | None — but wider change surface |
| 3 | Phase 2: checkpoint persistence | Low | None |
| 4 | Phase 4: dead surface cleanup | None | Opportunistic with above |

Phase 3 is smallest and highest-urgency (security). Phase 1 is highest-value structural fix. Phase 2 is straightforward. Phase 4 rides along.

---

## Explicitly Deferred

These items from the original plan are not worth dedicated effort now:

| Original item | Why deferred |
|---------------|-------------|
| Delete `AgentError.kt` | Partially alive (`PlatformError` used for bootstrap failures) |
| Delete `SessionError` | Now has a real job (bootstrap failure reporting) |
| Collapse marker interfaces | Broad rename churn, no runtime payoff |
| Split `SessionConfig` into sub-configs | Concrete bug is persistence loss, not flatness |
| Make `SessionLlmConfig` states unrepresentable | Call-sites already construct consistently |
| Rename `Op.Approve` → `ResolveApproval` | Naming-only churn |
| Unify `actionId`/`callId` naming | Naming debt, not runtime bug |
| Move UI projection out of `protocol/` | Not a source of breakage |
| Move `sanitizeThought()` | Cosmetic |
| Replace `ApprovalDetails.args: JSONObject` | Remove field entirely if touched, don't swap type |
| Hot-idle transition event | Runtime already handles this correctly |

---

## Non-Goals

- No richer event inheritance
- No generic message envelope
- No backward-compat hacks
- No speculative restructuring for hypothetical future needs
