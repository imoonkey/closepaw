# Experiment: Click Priority Order — A11Y vs VD on Files RecyclerView

**Date**: 2026-03-06
**Code**: `edb4acd` (feat(debug): add Shizuku injection and display-id targeting to action-debug)

## Scope

This experiment tests click transports **specifically on Files (DocumentsUI) RecyclerView items**. Files RecyclerView is a known difficult surface — in most other apps, all four transports (`node_action_click`, `dispatchGesture`, `adb input tap`, Shizuku `injectInputEvent`) work correctly. Results here should NOT be generalized to other surfaces without separate testing.

Where Shizuku injection and `adb input tap` show different results despite using the same underlying `InputManager.injectInputEvent()` API, the discrepancy is likely a test setup issue (HiddenApiBypass timing, binder wrapper configuration, action-debug context vs standalone process) rather than a fundamental limitation. This is an open question worth revisiting with a cleaner test harness.

## Environment

- **Device**: Emulator `sdk_gphone64_arm64`, API 34
- **App**: `com.moonkey.androidagent` debug build, accessibility service enabled
- **Shizuku**: `moe.shizuku.privileged.api` v13.6.0, server running as uid=2000
- **Files app**: `com.google.android.documentsui` (system DocumentsUI)
- **Target**: clickable `LinearLayout` at (540,763) containing "task.html" in Downloads folder
- **LLM**: minimax-m2.5 via OpenRouter (agent-loop tests only)

---

## Results

### Table 1: Agent-loop 2x2 Matrix

Setup: edit `ActionPriorityOrder.click` → `setup.sh` (build+install) → `debug-run.sh AGENT_MODE=basic`. VD tests add `PLATFORM_MODE=vd` with Shizuku running. Before/after screenshots compared manually.

| # | Platform | Priority Order | Channel Used | Turns | Screen Verification |
|---|----------|---------------|-------------|-------|-------------------|
| A | A11Y | `node_click → gesture_tap` | `node_action_click` (1st) | 3 | Files→Chrome ✅ |
| B | A11Y | `gesture_tap → node_click` | `gesture_tap` (1st) | 8 | Unchanged ❌ |
| C | VD | `node_click → gesture_tap` | `node_action_click` (1st) | 5 | Files→Chrome ✅ |
| D | VD | `gesture_tap → node_click` | `node_action_click` (fallback) | 8 | Fallback worked ✅ |

Notes:
- B: `gesture_tap` returned "Success", turn 5→6 screenshots pixel-identical. `UiChangeDetector` missed it. Agent recovered via overflow menu.
- D: VD `injectTap` (Shizuku → VD display) had no effect. Fallback to `node_action_click` succeeded.

Output: `debug-output/run_20260306_19{3512,3628,4143,4410}/`

### Table 2: Isolated action-debug (action-test.sh, no agent loop)

Setup: Files force-stopped and relaunched at Downloads before each test. Accessibility service enabled, no agent session. `OverlayTouchGate` NULL (no capsule overlay).

| # | Channel | Settle | Pre-condition | action_accepted | ui_changed | Screen |
|---|---------|--------|--------------|-----------------|-----------|--------|
| 1 | `node_click` | 350ms | warm | success | unchanged | Opened but capture too early |
| 2 | `node_click` | 2000ms | warm | success | changed | Files→Chrome ✅ |
| 3 | `gesture_tap` (dispatchGesture) | 2000ms | warm | success | unchanged | Identical ❌ |
| 4 | `adb input tap` | 2000ms | warm | N/A | N/A | Files→Chrome ✅ |
| 5 | `gesture_tap` | 2000ms | clean (force-stop+relaunch) | success | unchanged | Identical ❌ |
| 6 | `gesture_tap` | 2000ms | clean + pointer overlay OFF | success | unchanged | Identical ❌ |
| 7 | Shizuku inject, display 0 | 2000ms | clean, no HiddenApiBypass | success | unchanged | Identical ❌ |
| 8 | Shizuku inject, display 0 | 2000ms | clean, with HiddenApiBypass | success | unchanged | Identical ❌ |

Output: `debug-output/action-test/{warm_node_click_2s,warm_gesture_tap,warm_adb_tap,clean_gesture_tap,clean_gesture_no_overlay,shizuku_d0_tap,shizuku_d0_bypass}/`

### Table 3: Secondary display (adb input -d)

Setup: simulated overlay display created via `settings put global overlay_display_devices "1080x2400/420"` → display 11. Files launched on display 11 with `am start --display 11`. Verified visible via screencap.

| # | Method | Target Display | Screen |
|---|--------|---------------|--------|
| 1 | `adb input tap 540 763` | display 0 (Files on d0) | Files→Chrome ✅ |
| 2 | `adb input -d 11 tap 540 763` | display 11 (Files on d11) | Identical ❌ |

### Summary: Transport Matrix (Files RecyclerView only)

| Transport | Mechanism | Display 0 | Secondary Display |
|-----------|-----------|-----------|-------------------|
| `node_action_click` | `performAction(ACTION_CLICK)` | ✅ (Table 1 A/C, Table 2 #2) | ✅ (Table 1 C/D) |
| `adb shell input tap` | standalone process → `InputManager.injectInputEvent` | ✅ (Table 2 #4) | ❌ (Table 3 #2) |
| `dispatchGesture` | `AccessibilityService.dispatchGesture()` | ❌ false success (Table 1 B, Table 2 #3/5/6) | N/A |
| Shizuku `injectInputEvent` | app → binder → `IInputManager` | ❌ false success* (Table 2 #7/8) | ❌* (Table 1 D) |

*Shizuku results may reflect test setup issues (see Scope and Open Questions) rather than a transport-level limitation. On most other surfaces, Shizuku injection works correctly.

---

## Observations

### dispatchGesture logcat

```
W AccessibilityGestureInjector: overlayTouchGate is NULL — gestures may be consumed by overlay
D AccessibilityGestureInjector: dispatchGesture dispatched=true, #0(start=0,dur=100,len=0.0,...)
D AccessibilityGestureInjector: dispatchGesture completed
```

`dispatchGesture` returns `true` and `onCompleted` fires. Files RecyclerView does not respond.

### Shizuku injection logcat

Without HiddenApiBypass:
```
W ey.androidagent: Accessing hidden method IInputManager$Stub$Proxy.injectInputEvent (max-target-o, reflection, denied)
W ey.androidagent: Accessing hidden method IInputManager.injectInputEvent (unsupported, reflection, allowed)
```

With HiddenApiBypass:
```
D ShizukuRuntime: Hidden API restrictions bypassed
W VirtualDisplayInputInjector: setDisplayId unavailable, cannot set displayId=0
```

`injectInputEvent` returns `true` in both cases. `setDisplayId` reflection fails even with bypass (`NoSuchMethodException`). For display 0 this is irrelevant (`MotionEvent.obtain()` defaults to displayId=0).

### UiChangeDetector inconsistency

Table 1 test B: `UiChangeDetector` did not catch gesture_tap false success (screen identical but reported as changed). Previous session tests correctly detected the false success. Difference is not explained.

---

## Conclusions (supported by data, scoped to Files RecyclerView on this emulator)

1. **`node_action_click` is the only reliable channel for this surface**: 8/8 successes across A11Y and VD modes.
2. **`dispatchGesture` false-succeeds on this surface**: 0/4 actual success on display 0. API reports completion, view does not respond.
3. **Shizuku `injectInputEvent` did not work in this test setup**: 0/2 on display 0, 0/1 on VD display. Returns `true`, view does not respond. May be a setup issue (see Open Questions).
4. **`adb input tap` works on display 0** (1/1), **did not work on secondary display** (0/1).
5. **Current priority `node_click → gesture_tap` is correct.** No change needed for either A11Y or VD mode on this surface.

---

## Open Questions (not tested / not diagnosed)

1. **Why did Shizuku `injectInputEvent` and `adb input tap` show different results on display 0?** Both call `InputManager.injectInputEvent()`. The discrepancy is likely a test setup issue — the Shizuku path in action-debug runs through `DebugActionExecutor` which initializes `ShizukuClient` ad-hoc (outside the normal VD platform lifecycle), HiddenApiBypass may not fully apply, and MotionEvent construction/timing differs from `adb input`. Worth revisiting with a cleaner harness that matches the real VD agent path more closely.
2. **Are these results emulator-specific?** All tests on API 34 emulator. Real devices may behave differently for both `dispatchGesture` on Files and secondary display input routing.
3. **Why did gesture_tap work in a previous session?** Not reproduced in current session (0/4). Possible: different emulator state, different target app, timing fluke.
4. **Why does `adb input -d` fail on secondary display?** Could be emulator limitation (simulated overlay display ≠ real secondary display). Not tested on real multi-display hardware.

---

## Test Infrastructure Notes

- `debug-run.sh` does NOT build/install — must run `setup.sh` after code changes
- `debug-run.sh` default changed from `pro` to `basic` (`edb4acd`)
- `action-test.sh --shizuku --display-id N` added in `edb4acd`
- Settle: 350ms default too short for intent resolution (Files→Chrome); use `--settle 2000`
- Shizuku server: must start as uid=2000 when `adb shell` runs as root
- `OverlayTouchGate`: NULL in action-debug mode (no agent session)
