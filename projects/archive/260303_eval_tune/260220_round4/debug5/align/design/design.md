# Executor Refactoring — Aligned Design (v2)

## Premise

`dispatchGesture` is treated as working in eval after overlay flag fix (`FLAG_NOT_TOUCHABLE`, run `20260220_145635`). Overlay implementation is out of scope here.

## Final Decisions (Codex proposal)

1. **Dual-path actions are gesture-first**:
   - `click`: `gesture_tap -> node_click` (semantic target only for fallback)
   - `long_press`: `gesture_long_press -> node_long_click` (semantic target only for fallback)
   - `scroll`: `gesture_swipe(direction) -> a11y_scroll(direction)`
2. **Single-path actions unchanged**:
   - `swipe`: gesture only
   - `type`: node text path (with existing tap-to-focus fallback behavior)
3. **No `UiChangeDetector` in production executors**.
4. **KISS scope**: do not introduce new framework-level abstractions (`ActionPriorityPolicy`, `ResolvedTarget` class family, etc.) in this round.
5. **Target unification for scroll**: extend existing `TargetResolver.ResolveResult.Resolved` with `bounds: Bounds?`, and make `ScrollExecutor` consume `TargetResolver` instead of its private `resolveScrollArea(JSONObject, ...)`.

## Implementation Scope (Stage 1)

Only three files change in this stage:

1. `app/src/main/kotlin/com/moonkey/androidagent/tool/action/TargetResolver.kt`
   - Add `bounds: Bounds? = null` to `ResolveResult.Resolved`.
   - Fill `bounds` from `PerceptionElement.bounds` in `resolveElementPoint`.
   - Keep existing callers compatible (click/long_press/type only using `point`).

2. `app/src/main/kotlin/com/moonkey/androidagent/tool/action/ScrollExecutor.kt`
   - Change interface from `(params: JSONObject, ...)` to `(target: Target?, direction: String, ...)`.
   - Use `TargetResolver` to resolve target point/bounds.
   - Default to full-screen bounds + screen center when target absent/unresolvable.
   - Change cascade to gesture-first then a11y fallback.

3. `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/MobileActionTool.kt`
   - For `scroll`, parse `target` with existing `parseOptionalTarget(params)`.
   - Pass `direction + target` into new `ScrollExecutor` API.

## Explicit Non-Goals (this stage)

1. No broad refactor of `ClickExecutor` / `LongPressExecutor` internals.
2. No extraction of shared helper classes unless required for correctness.
3. No redesign of `AndroidPlatform`, `NodeActionPerformer`, or `AccessibilityGestureInjector`.
4. No overlay flag changes in this document.

## Why this scope

1. Meets master intent: after dispatch fix, dual-path should be gesture-first.
2. Resolves the real outlier (`ScrollExecutor`) while minimizing moving parts.
3. Preserves readability and local reasoning in current executors.
4. Keeps risk bounded for quick eval verification.

## Verification Plan

1. Build/tests:
   - `./gradlew assembleDebug`
   - `./gradlew test`
2. Eval smoke:
   - `eval/.venv/bin/python eval/aw_bridge/runner.py --tasks "SystemBrightnessMax,SystemBrightnessMin"`
3. Action debug checks:
   - `scripts/action-test.sh scroll ...`
   - `scripts/action-test.sh swipe ...`
   - verify attempt trails show gesture-first then a11y fallback when needed.
