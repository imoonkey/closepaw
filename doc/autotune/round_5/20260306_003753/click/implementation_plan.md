# Click Fix Implementation Plan

## Goal

Land the aligned click/action execution fixes in a safe order:

1. P0 executor-side post-action verification and scroll validation
2. targeted probing beyond unit tests
3. BrowserDraw / BrowserMultiply eval verification
4. broader never-succeeded eval if targeted verification stays clean

## Phase 1

### Scope

- unify post-action change detection for executor actions
- surface `no observable effect` as success annotation for click/long_press/type/swipe
- make `scroll` outcome-validated instead of dispatch-validated
- strengthen change fingerprint to reduce obvious false-unchanged cases

### Files

- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/PostActionAnalysis.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/PointActionExecutorCore.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/ScrollExecutor.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/TypeExecutor.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/SwipeExecutor.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/UiChangeDetector.kt`
- action-layer tests under `app/src/test/kotlin/com/moonkey/androidagent/tool/action/`

### Verification

- targeted unit tests for click/long_press/scroll
- full `testDebugUnitTest`

## Phase 2

### Scope

- run direct probing with `scripts/action-test.sh` on Files row click paths
- compare adb tap / node click / gesture tap / long press
- if the executor itself is still at fault, implement a bounded fix

### Verification

- action-test artifacts under `debug-output/action-test/`
- BrowserDraw / BrowserMultiply eval rerun

## Phase 3

### Scope

- if targeted reruns are stable, run the remaining `never_succeeded` tasks with parallel eval
- write results into round 6 docs and summary

### Verification

- targeted task pass/non-regression check first
- then broader eval scoreboard / per-task analysis
