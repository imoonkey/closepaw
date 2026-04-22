# Protocol & Communication — Implementation Summary

**Date:** 2026-04-16
**Status:** DONE
**Design:** `doc/todo/holistic-review/protocol-communication/final/improvement_plan.md`
**Verification:** `./gradlew assembleDebug test lint` pass. 2 codex review rounds (v1 REQUEST CHANGES → v2 APPROVE). Real-device QA 5/6 PASS, 1 SKIPPED.

## What was implemented

6 tasks across 4 phases, landed as 12 commits from `9f7ddf72` to `682286b0`.

### Phase 3 — Approval validation (`pc-approval-validation`, `9f7ddf72`)
Gate allow-list mutation in `AgentSession.handleApproval()` on `toolRouter.resolveApproval()` success. Unmatched/duplicate `Op.Approve` now logs `Discarding approval with no pending match` and returns early without touching package allow-lists. This closes a security invariant: stale `Op.Approve` could previously mutate `SESSION`/`ALWAYS` allow-lists without matching any real approval request.

### Phase 2 — Checkpoint persistence (`pc-checkpoint-persistence`, `c207570d`)
`SessionCheckpointCoordinator` now round-trips six runtime-affecting fields previously dropped on reload: `actionDelayMs`, `approvalMode`, `debugMode`, `traceEnabled`, `traceRunId`, `excludedTools`. Silent loss could change security posture (e.g. `approvalMode: SMART` → default) and tooling behavior after a session reload.

### Phase 1 — Completion semantics split (`pc-completion-semantics`, `58b7e072`)
Split `CompletionReason` into two enums:
- `TaskOutcome` = `{GOAL_ACHIEVED, MAX_TURNS, TASK_IMPOSSIBLE, ERROR, USER_STOPPED}` — carried by `TaskCompleted.outcome`.
- `SessionEndReason` = `{USER_STOPPED, IDLE_TIMEOUT, INTERRUPTED}` — carried by `SessionCompleted.reason`.

Removed impossible branches in `AgentServiceEventHandler` and `CapsuleStateHolder.onSessionEnded()` that matched task-only outcomes on session-level events. Deleted `SessionCompleted.result` (always null, no consumer).

`SessionRecordingService.completedNormally` now derives from the last `TaskCompleted.outcome` rather than the session-level reason, fixing the data-integrity bug where a successful task followed by an idle timeout was recorded as a failed session.

### Phase 4 — Dead event surface cleanup (`pc-dead-event-cleanup`, `dd897629`)
Pruned ~257 lines of dead protocol:
- Removed `TodosUpdated`, `ScratchpadUpdated` events + dispatcher methods (no consumer).
- Removed `ApprovalResolved` (emitted but never consumed).
- Removed `StatusUpdate.emoji`, `TurnStarted.phase`, `ApprovalRequired.actionId` fields.

### Phase 5 — Codex review (`pc-codex-review`, `a8cf01f2`)
- **Round 1 REQUEST CHANGES** (`3de3f185`):
  - High: `lastTaskOutcome` lifecycle was stale — never cleared on new task, never persisted, shutdown path could leave a running task uncorrected.
  - Medium 1: checkpoint round-trip test did not cover the six new runtime-affecting fields.
  - Medium 2: no session-level regression test for stale-approval invariant.
- **Fixes in `236dfbf3`:** `TaskStarted` clears `lastTaskOutcome` via `SessionRecordingService.onTaskStarted()`; `handleShutdown()` emits `TaskCompleted(USER_STOPPED)` for any in-flight task before `SessionCompleted`; `SessionRuntimeSnapshot` persists `lastTaskOutcome` and `AgentSession.reload()` restores it. `SessionCheckpointConfigSnapshotTest` extended to cover the six fields; `AgentSessionTest` now verifies unmatched `Op.Approve` does not touch allow-lists.
- **Round 2 APPROVE** (`1d696ebb`): one remaining Medium (no automated round-trip test for `lastTaskOutcome` through persist+restore) flagged as non-blocking — implementation path verified by source inspection.

### Phase 6 — Real-device QA (`pc-qa-real-device`, `583531ae`)
5/6 PASS on device EP0110MZ0BC:
- Scenario 1 (stale `Op.Approve`) **SKIPPED** — not externally triggerable via ADB (internal session channel, no intent/broadcast surface). Verified by code review + unit tests.
- Scenario 2 (`completedNormally` derives from `lastTaskOutcome`) PASS — `session-*.json` shows `completedNormally: true` for `Open Settings` task.
- Scenario 3 (checkpoint round-trip) PASS — serialized `context_checkpoint.json` contains all 6 runtime fields + `lastTaskOutcome`.
- Scenario 4 (dead events pruned) PASS — 0 emissions of `TodosUpdated` / `ScratchpadUpdated` / `ApprovalResolved` across multiple runs.
- Scenario 5 (multi-turn) PASS — 5-action flow terminates with `GOAL_ACHIEVED`.
- Scenario 6 (error surface) PASS — opportunistic OpenRouter 402 exercised the split: `outcome=ERROR` (task) distinct from `reason=USER_STOPPED` (session).

## Key decisions / non-obvious notes

- **Deliberately explicitly deferred items:** `AgentError.kt` / `SessionError` kept (now has real bootstrap-failure job), marker interfaces not collapsed, `SessionConfig` not split into sub-configs, `Op.Approve` not renamed to `ResolveApproval`, `actionId`/`callId` naming debt left. All documented in `final/improvement_plan.md` — plan explicitly said "no speculative restructuring."
- **`lastTaskOutcome` as session-level cache:** lives on `SessionRecordingService`, cleared on task start, persisted in `SessionRuntimeSnapshot`. This is the canonical answer to "what outcome should `completedNormally` derive from when the session idle-times-out after a successful task?"
- **`handleShutdown()` correction step:** any in-flight task gets `TaskCompleted(USER_STOPPED)` before `SessionCompleted`, preventing `lastTaskOutcome` from leaking a stale success from the previous task.
- **Scenario 1 limitation is architectural:** `Op.Approve` is an in-process channel message; there's no ADB intent or debug bridge to inject one, so end-to-end stale-replay testing requires instrumentation tests. Unit-level coverage in `AgentSessionTest` and `ToolRouterTest` stands in.

## Artifacts
- Design: `doc/todo/holistic-review/protocol-communication/final/improvement_plan.md`
- Reviews: `codex_review.md` (round 1 REQUEST CHANGES), `re_evaluation_codex.md` (round 2 APPROVE)
- QA: `qa_report.md` + `qa_evidence/`
