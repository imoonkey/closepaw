# Review: Virtual Display Implementation (independent)

Scope:
- Commits reviewed: `c974ceb` → `0403bd2` (focus on latest fix commit `0403bd2`)
- Runtime trace reviewed: `debug-output/run_20260210_170907`
- I did **not** read `review_gemini.md`

## Critical

1. Virtual display/session resources are orphaned after task completion (no shutdown path)
- Where:
  - `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:116`
  - `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:118`
  - `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:265`
  - `app/src/main/kotlin/com/moonkey/androidagent/session/SessionServices.kt:275`
- What happens:
  - `TaskCompleted` callback clears `currentSession` reference in `MainActivity`, but does not submit `Op.Shutdown`.
  - `AgentSession` transitions to `Idle` (not `Shutdown`) on normal completion, so `SessionServices.cleanup()` is never called.
  - `platform.stop()` is only called in `cleanup()`.
- Evidence from run:
  - `debug-output/run_20260210_170907/logcat_full.log:18552` shows `Session Idle`.
  - No `VirtualDisplayPlatform: Stopped` / `Released virtual display` log in this run.
- Risk:
  - Leaked virtual displays, lingering focused windows, IME/focus side effects, and long-running hidden sessions.
- Fix direction:
  - Pick one explicit lifecycle model:
    - One-shot tasks: on `TaskCompleted`, submit `Op.Shutdown`.
    - Multi-task session: do **not** clear `currentSession`; reuse it for next input, and shutdown only on explicit end.

2. Shell launch path is currently broken; success depends on fallback path
- Where:
  - `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/ShizukuClient.kt:187`
  - `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayPlatform.kt:652`
- Runtime evidence:
  - `debug-output/run_20260210_170907/logcat_full.log:3027`
  - `debug-output/run_20260210_170907/logcat_full.log:3028`
  - `debug-output/run_20260210_170907/logcat_full.log:3041`
- What happens:
  - Reflection for `Shizuku.newProcess(...)` uses a signature not present on current runtime, throws `NoSuchMethodException`.
  - Code silently falls back to `launchOnDisplay(...)`, so the issue is masked.
- Risk:
  - Device-specific launch failures will be hard to diagnose; current “works” behavior is accidental fallback.
- Fix direction:
  - Replace reflection with direct API call when available, or support a tested signature matrix with explicit capability detection and one-time failure logging.

## High

1. Keyboard popup on main screen is reproducible and likely a cross-display IME/focus policy issue
- Your observation matches logs/screenshots.
- Evidence around type action:
  - `debug-output/run_20260210_170907/agent.log:466` (`mobile_action` type)
  - `debug-output/run_20260210_170907/logcat_full.log:11132` (`InputMethod: restartInput()`)
  - `debug-output/run_20260210_170907/logcat_full.log:11135` (`onStartInputView`)
  - Screenshot evidence: `debug-output/run_20260210_170907/turn_004_n4.png`, `debug-output/run_20260210_170907/turn_005_n5.png`
- Additional signal:
  - `debug-output/run_20260210_170907/logcat_full.log:19648` focused-display warning.
  - `debug-output/run_20260210_170907/logcat_full.log:19637` / `:19768` show touch down/up on `MainActivity` after task completion (possible manual interaction), so post-completion focus switches are not all agent-caused.
- Inference:
  - Typing on display 12 triggers IME lifecycle while `MainActivity` on display 0 is still foreground in debug flow, so IME UI can surface on main display.
- Fix direction:
  - For virtual-display debug runs, move app to background after auto-start (or ensure main UI is non-focusable for text).
  - Add explicit “automation mode” input policy in `MainActivity` (e.g., no initial text focus + `stateHidden`).

2. `VirtualDisplayPlatform` is already in spaghetti territory (single-class mixed responsibilities)
- Where:
  - `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayPlatform.kt` (773 lines)
- Problem:
  - One class owns lifecycle, perception capture, action dispatch, node ops, injected gestures, app launch, window querying, and event construction.
- Risk:
  - Bug fixes become local patches with side effects; regression probability rises quickly.
- Fix direction:
  - Split into cohesive units:
    - `VirtualDisplayController` (create/release display + ImageReader)
    - `VirtualDisplayPerceptionSource` (a11y + screenshot)
    - `VirtualDisplayActionExecutor` (node/injected actions)
    - `VirtualDisplayAppLauncher` (launch strategy + capability fallback)

3. Design drift + dead code in `ShizukuClient`
- Where:
  - `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/ShizukuClient.kt:216`
  - `app/src/main/aidl/android/hardware/display/IVirtualDisplayCallback.aidl`
- Problem:
  - `createNullCallbackProxy()` is dead code.
  - Implementation drifted from original “no custom AIDL” design intent; currently both AIDL stub and reflection/proxy remnants coexist.
- Risk:
  - Higher maintenance burden and unclear compatibility strategy.
- Fix direction:
  - Choose one callback strategy and delete the other path + dead imports/helpers.

## Medium

1. Hot-path logging is too verbose for normal runs
- Where:
  - `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayPlatform.kt:694`
- Problem:
  - Window summaries are logged on frequent capture/action paths.
- Risk:
  - Performance overhead and noisy logs that hide real failures.
- Fix direction:
  - Gate detailed window logs behind debug flag or sampling.

2. `debug-run.sh` does not stop agent/session after success
- Where:
  - `scripts/debug-run.sh:341`
- Problem:
  - Script breaks monitoring loop on completion, but does not send STOP broadcast in success path.
- Risk:
  - Combined with session lifecycle issue, this leaves orphaned resources and can skew later runs.
- Fix direction:
  - Optionally stop session automatically at end in debug mode (configurable flag).

3. Missing regression tests for virtual-display-specific compatibility points
- Areas needing tests:
  - Lifecycle ownership (`TaskCompleted` vs `Shutdown`)
  - Launch strategy fallback behavior
  - IME/focus side effects around `type`

## “以点见面” — likely systemic issue classes

1. Hidden fallback masks real failures
- Example: broken shell launch path is hidden by fallback.
- Action: add capability probing + explicit telemetry for chosen path and failure reason.

2. Lifecycle ownership is ambiguous across `MainActivity` / `AgentService` / `AgentSession`
- Example: session reference cleared in UI, but session/platform still alive.
- Action: define single owner and deterministic teardown contract.

3. Cross-display focus/IME behavior is not treated as first-class
- Example: typing action can disturb default display UX.
- Action: add explicit focus/IME policy for virtual-display automation mode.

## Refactor recommendation

Recommendation: **CHANGES_REQUESTED before foundation freeze**.

Suggested order:
1. Fix lifecycle ownership (Critical #1).
2. Fix shell launch capability path (Critical #2).
3. Add automation-mode focus/IME policy for virtual-display runs (High #1).
4. Then split `VirtualDisplayPlatform` by responsibility (High #2).

This sequence reduces production risk first, then improves maintainability.
