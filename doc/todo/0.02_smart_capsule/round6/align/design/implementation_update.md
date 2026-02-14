# Round6 Align Implementation Update

Date: 2026-02-14

## Scope Completed

Implemented the round6 align design rules in production code and tests for:

1. State machine guards:
   - `UserResponseSent(callId)` strict callId match guard.
   - universal completion fallback text `"Task completed"` for empty/null completion result.

2. VD/A11y visibility alignment:
   - Explicit user-location model: `MAIN_APP | VD_VIEWER | OTHER_APP`.
   - Pure visibility decision policy with tested invariants:
     - island/capsule mutual exclusion
     - MAIN_APP hides system overlays
     - A11y never shows island
     - VD interactive modes (`WI/WA/Error`) force `CAPSULE` even when preference is `ISLAND`

3. Island/viewer behavior:
   - `onIslandTapped` in `VD_VIEWER` toggles to capsule directly (no re-open viewer dependency).
   - no-active-task + non-terminal island tap opens main app.

4. Stop feedback contract:
   - Added transient `stopPending` state (outside `CapsuleMode`) for immediate `Stopping...` feedback.
   - Stop action disables repeat clicks until terminal/new-task clears it.
   - Applied in both overlay capsule and compose capsule.

5. Chat side effects:
   - completion message always appended to history.
   - supplement always inserted as a user message.

6. Compose single-component constraint:
   - kept single capsule composable behavior.
   - `VD + MAIN_APP + Hidden` keeps `👁` access within same component.

## Tests Added/Updated

- `OverlayLocationPolicyTest`
- `CapsuleStateHolderTest`
- `CapsuleRenderSpecTest`
- `NavSpecTest`
- `ChatCompletionSummaryTest`

These cover key round6 regressions and visibility/state invariants described in:
- `design.md`
- `user_flow.md`
- `bug_prevention.md`

## Verification

- `:app:testDebugUnitTest` (targeted suites) passed.
- `:app:assembleDebug` passed.
- Per user instruction, `debug-run.sh` visual run is currently skipped because script hangs after task completion.
