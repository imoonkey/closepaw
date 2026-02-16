# Phase 1 Independent Code Review (Codex)

Date: 2026-02-16
Scope: MT1, MT2, TC1, H1, H4, G1, G2, G3, G6, G8, G9, G10
Reviewer: `code-reviewer` subagent

## Findings

1. **Critical**: `AgentService.runAgent` old session shutdown could be skipped because the coroutine captured `session` after it was nulled.
2. **High**: `VirtualDisplaySurfaceController` had a TOCTOU gap (check and surface switch were not inside one critical section).
3. **High**: `MainActivity.ensureSessionAndSend` could race and create duplicate sessions during background session bootstrap.
4. **Medium**: Missing targeted tests for Phase 1 behavioral changes.
5. **Medium**: `SessionRecordingService` still had unsynchronized `agentMessageBuffer` access in a few methods.
6. **Low**: `MainActivity.modelCatalog` lazy load still performs blocking asset I/O on first main-thread access.

## Resolution Status

- **Fixed**: Critical #1 (`AgentService.runAgent` now captures `oldSession` before nulling `session`).
- **Fixed**: High #2 (`VirtualDisplaySurfaceController` transition checks + `setVirtualDisplaySurface` now run in one lock scope).
- **Fixed**: High #3 (`MainActivity` now uses a session-creation gate with `sessionCreationInProgress` and retry path).
- **Fixed**: Medium #5 (`SessionRecordingService` now synchronizes `appendTextDelta`, `recordAction`, and `updateActionState`).
- **Deferred**: Medium #4 (targeted unit tests) to later verification hardening pass.
- **Deferred**: Low #6 (UI model catalog lazy-loading path) to later UI/perf cleanup phase.

