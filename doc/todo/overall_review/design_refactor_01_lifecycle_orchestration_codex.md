status: draft

# Refactor 01: Lifecycle & Orchestration Contract Hardening

Date: 2026-02-16
Goal: make task/session lifecycle explicit, single-source-of-truth, and tooling-friendly.

## Problem
Lifecycle behavior is currently distributed across `AgentSession`, `AgentService`, and `MainActivity`, with implicit contracts around when a task is considered done versus when a session is terminal. Debug tooling currently depends on fragile log string matching.

## Scope
- `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt`
- `scripts/debug-run.sh`

## Design
1. Define explicit completion signal contract.
- Task completion is terminal for one job but non-terminal for session.
- Session completion remains shutdown-level terminal.

2. Emit stable machine-oriented log markers for tooling.
- Add one deterministic marker for `TaskCompleted` and one for `SessionCompleted`.
- Keep human-readable status text separate from machine matching.

3. Update debug tooling to prioritize task-level completion.
- Stop debug-run when task completes (with existing session alive semantics).
- Add timeout guard (`DEBUG_MAX_WAIT_SECONDS`) to avoid indefinite wait loops.

4. Keep compatibility.
- Do not alter event model semantics in this phase.
- Do not change UX behavior of keeping session alive for next task.

## Phases
### Phase 1 (now)
- Add task-complete matching in `debug-run.sh`.
- Add timeout guard.
- Add clear service/session logs for task completion.

### Phase 2
- Introduce typed lifecycle coordinator wrapper (single class owning session/task state transitions for app/service).

### Phase 3
- Add integration tests for task completion + session continuation and debug tooling behavior.

## Risks
- False positives in log matching if pattern too broad.
- Mitigation: match event-type strings (`TaskCompleted`), not generic phrases.

## Verification
- Manual run with `debug-run.sh` and confirm auto-stop at task completion.
- Existing session lifecycle tests continue to pass.
