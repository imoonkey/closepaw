# Review: Phase 1 Runtime Fixes

Date: 2026-02-11
Reviewer: Independent subagent (`code-reviewer`)
Scope:
- `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayPlatform.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/ShizukuClient.kt`
- `scripts/debug-run.sh`

## Findings

### Critical
- None

### High
1. Shutdown submission in `onTaskCompleted` used `lifecycleScope`, which can be cancelled during Activity teardown.
2. IME mitigation comment and behavior mismatched (`BACK` in comment vs `ENTER` in code).
3. `ENTER`-based mitigation can trigger unintended submit/navigation.

### Medium
1. `captureA11yTree()` dispatcher consistency risk.
2. `executeShellCommand()` had no timeout.
3. Minor script hygiene and logging concerns.

## Actions Taken

1. **Fixed**: `MainActivity` now submits shutdown via `sessionScope`.
2. **Fixed**: Replaced `ENTER` mitigation with safer focus-clear mitigation after successful text set.
3. **Fixed**: `captureA11yTree()` moved to main dispatcher path and recycles root.
4. **Fixed**: `ShizukuClient.executeShellCommand()` now has a 30s timeout.
5. **Fixed**: Removed trailing-space `ls` command issue in `debug-run.sh`.
6. **Fixed**: Added debug log gating in VD window enumeration hot path.

## Recommendation

APPROVE (Phase 1 after fixes)
