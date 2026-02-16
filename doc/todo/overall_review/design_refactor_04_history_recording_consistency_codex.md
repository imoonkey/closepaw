status: draft

# Refactor 04: History Recording Consistency Hardening

Date: 2026-02-16
Goal: prevent history state corruption/data loss and simplify message finalization flow.

## Problem
`SessionRecordingService` manages session state, agent message buffering, debounced persistence, and finalization sequencing. Current logic has stale-state overwrite risk during `completeSession`.

## Scope
- `app/src/main/kotlin/com/moonkey/androidagent/history/SessionRecordingService.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/history/SessionRecordingServiceTest.kt`

## Design
1. Make completion path state-source explicit.
- Finalize buffered agent message first.
- Re-read `currentSession` after finalization before metadata updates.

2. Centralize session mutation helper.
- Use a small helper (`updateCurrentSession`) to avoid stale local snapshots.

3. Keep save semantics unchanged.
- Preserve debounce and final immediate save behavior.

## Phases
### Phase 1 (now)
- Fix stale overwrite bug.
- Add regression test: pending agent buffer + `completeSession()` without prior `completeAgentMessage()` keeps final message.

### Phase 2
- Reduce duplication between `finalizeCurrentAgentMessage` and `updateAgentMessageInSession`.

### Phase 3
- Add concurrency guard docs/tests for expected calling thread.

## Risks
- Ordering regressions around debounced saves.
- Mitigation: preserve `pendingSave?.join()` behavior and add regression tests.

## Verification
- Existing history tests pass.
- New regression test proves no lost final agent message on session completion.
