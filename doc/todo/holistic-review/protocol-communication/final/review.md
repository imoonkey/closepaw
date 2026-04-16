# Protocol & Communication — Review

Updated: 2026-04-16 (re-evaluated against current codebase)
Original: 2026-04-08 (Claude + Codex double-design)

---

## Executive Assessment

The core command/state surface (`Op`, `SessionState`) is solid. The event layer has improved since the original review — `SessionError`, `TASK_IMPOSSIBLE`, and `ActionOutcome` now have real producers. But three concrete bugs remain: completion semantics leak into session recording, checkpoint persistence drops runtime-affecting fields, and approval allow-list mutation skips validation. These are worth fixing. The remaining dead-surface items are cleanup opportunities, not urgent.

---

## What Works

- **`Op`** — 8 operations map to real user intents. Exhaustive `when` in `AgentSession.submit()`.
- **`SessionState`** — 6 states (`Created`, `Running`, `Idle`, `TakeoverPending`, `Paused`, `Shutdown`) match the hot-idle lifecycle with cooperative takeover. Transitions enforced by guard checks.
- **`SessionError`** — now emitted for bootstrap failures, consumed by chat/overlay. No longer dead.
- **`ActionExecuted`** — now carries `ActionOutcome` (`SUCCESS`/`FAILED`/`SKIPPED`) with real consumers.
- **Small enums** — `TurnPhase`, `AskUserType`, `AppTier`, `ApprovalDecision`, `ApprovalScope`, `ScreenStatePhase` are well-scoped.

---

## High-Priority Findings

### H1. CompletionReason Conflates Task Outcome with Session Shutdown — Causes Recording Bug

`CompletionReason` serves both `TaskCompleted` and `SessionCompleted`. `AgentSession` emits task-level reasons (`GOAL_ACHIEVED`, `MAX_TURNS`, `TASK_IMPOSSIBLE`, `ERROR`) at task end and session-level reasons (`USER_STOPPED`, `IDLE_TIMEOUT`) at session end. Consumers branch on impossible combinations — e.g., `SessionCompleted` with `GOAL_ACHIEVED`.

**Concrete bug:** `SessionRecordingService.completeSession()` derives `completedNormally` from the session-level `CompletionReason`. A session whose last task succeeded but later shuts down via idle timeout is persisted as not completed normally.

Refs: `TaskLifecycleEvents.kt:16-22`, `SessionLifecycleEvents.kt:11-16`, `AgentSession.kt:418-426,573-583`, `SessionRecordingService.kt:211-244`, `AgentServiceEventHandler.kt:117-130`, `CapsuleStateHolder.kt:258-285`

### H2. Checkpoint Persistence Drops Runtime-Affecting Fields

`SessionCheckpointCoordinator` persists only model/agent/perception/platform data. It silently drops `actionDelayMs`, `approvalMode`, `debugMode`, `traceEnabled`, `traceRunId`, and `excludedTools`. Reload can change security posture (approval mode), disable tracing, and alter tool availability.

Refs: `SessionCheckpointCoordinator.kt:84-128`, `SessionConfig.kt:12-60`, `SessionAgentRunner.kt:72-80`, `SessionServices.kt:107-108`, `TraceRecorderFactory.kt:12-16,35-44`

### H3. Approval Allow-List Mutation Skips Pending-Approval Validation

`AgentSession.handleApproval()` persists allow-list changes for `SESSION`/`ALWAYS` scopes before checking whether `toolRouter.resolveApproval()` matched a pending approval. `resolveApproval()` returns `false` when no pending approval exists, but the result is ignored. A stale or duplicate `Op.Approve` can mutate policy without a matching pending approval.

Refs: `AgentSession.kt:589-607`, `ToolRouter.kt:328-337`

---

## Medium-Priority Findings (cleanup when touching nearby code)

### M1. Unconsumed Events Still Emitted

- **`TodosUpdated` / `ScratchpadUpdated`** — emitted from `TurnExecutionPhaseRunner` via `AgentEventDispatcher`, no consumer handles them.
- **`ApprovalResolved`** — emitted in `AgentSession`, no event consumer. UI resolves approval state locally before the op is submitted.

Refs: `AgentEventDispatcher.kt:98-112`, `AgentSession.kt:599-607`

### M2. Redundant Turn Phase Signal

`TurnStarted.phase` is always `PERCEPTION` and `Agent.kt` immediately emits `TurnPhaseChanged(PERCEPTION)`. One is redundant. `ChatEventReducer` uses `TurnStarted` only to clear the buffer, not the phase field.

Refs: `TurnEvents.kt:4-10`, `AgentEventDispatcher.kt:45-52`, `Agent.kt:93-94`, `ChatEventReducer.kt:59-62`

### M3. Duplicate Approval Identifier

`ApprovalRequired.actionId` duplicates `ApprovalDetails.callId`. All consumers use `details.callId`.

Refs: `ApprovalEvents.kt:4-10`, `ApprovalTypes.kt:31-52`, `AgentServiceEventHandler.kt:165-167`

### M4. SessionCompleted.result Is Dead Payload

`SessionCompleted.result` is always emitted as `null`. No consumer reads it.

Refs: `SessionLifecycleEvents.kt:11-16`, `AgentSession.kt:578-583`

---

## Low-Priority / Deferred (not worth dedicated effort)

| Finding | Why deferred |
|---------|-------------|
| 12 marker interfaces in `AgentEventDomains.kt` unused for dispatch | Broad rename churn, no runtime payoff |
| `AgentError` hierarchy over-designed (only `PlatformError` used) | Partially alive now; shrink if touched, don't delete |
| `StatusUpdate.emoji` field never populated | True but tiny; clean up when editing status events |
| `SessionConfig` flat structure mixes concerns | Readable, manageable; concrete bug is persistence, not flatness |
| `SessionLlmConfig` allows contradictory states in type system | Current call-sites construct consistently; no live bug |
| Approval naming (`actionId` vs `callId`, `Approve` vs `ResolveApproval`) | Naming debt, not runtime problem |
| `protocol/` mixes domain contract with UI projection | Small in-process types, not a source of breakage |
| `sanitizeThought()` in protocol package | Tiny shared function, cosmetic placement issue |
| `ApprovalDetails.args: JSONObject` | Not read by consumers; remove field if touched rather than swap type |
| Single-event files, serialization inconsistency | Acceptable at current scale |
