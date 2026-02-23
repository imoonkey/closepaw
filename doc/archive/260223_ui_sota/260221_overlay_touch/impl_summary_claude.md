# Overlay Touch Gate — Implementation Summary

**Status**: DONE
**Commit**: `25e8587` (`feat: overlay touch gate enables gesture-first action priority`)
**Verified**: debug-run `run_20260221_001359` — agent completed "open youtube and play a 周深 song" in 5 turns

---

## Problem

`dispatchGesture()` injects touch events through the Android input system, but our capsule overlay window intercepted them before they reached the target app. The original workaround—hardcoding `FLAG_NOT_TOUCHABLE` on the overlay—broke capsule button interactivity (Takeover, Stop, text input). Removing the flag restored buttons but made all gesture actions silently fail: the accessibility service reported "Success" while the overlay consumed every touch.

## Solution

Two-layer touchability system (aligned design from Claude + Codex):

1. **Mode-driven baseline** — `shouldCapsuleOverlayBeTouchable(mode)` sets the default flag per `CapsuleMode`. Only `Hidden` passes touches through; all other modes are touchable for user interaction.

2. **Gesture pass-through gate** — `OverlayTouchGate.beginGesturePassThrough()` temporarily sets `FLAG_NOT_TOUCHABLE` during `dispatchGesture()`, returning a depth-counted `AutoCloseable` token that restores baseline on close.

A 50ms settle delay after setting the flag ensures WindowManagerService processes the `updateViewLayout` IPC before the separate `dispatchGesture` IPC arrives.

## Files Changed

### New
| File | Purpose |
|------|---------|
| `platform/OverlayTouchGate.kt` | Interface: `beginGesturePassThrough(): AutoCloseable` |

### Modified — Core Gate
| File | Change |
|------|--------|
| `ui/overlay/compose/CapsuleOverlayHost.kt` | Gate implementation with depth counting, mode-driven touchability observer, removed hardcoded `FLAG_NOT_TOUCHABLE` from `createLayoutParams()` |
| `platform/AccessibilityGestureInjector.kt` | Calls gate before `dispatchGesture()`, 50ms `FLAG_SETTLE_MS` delay, diagnostic logging |
| `app/OverlayLocationPolicy.kt` | `shouldCapsuleOverlayBeTouchable()` policy function |

### Modified — Wiring
| File | Change |
|------|--------|
| `session/AgentSession.kt` | Accept `overlayTouchGate` param, pass to `PlatformFactory` |
| `platform/PlatformFactory.kt` | Accept and forward gate to `AccessibilityPlatform` |
| `platform/AccessibilityPlatform.kt` | Accept and forward gate to `AccessibilityGestureInjector` |
| `app/AgentService.kt` | `getOverlayTouchGate()` getter, pass gate in internal `runAgent()` path |
| `app/MainActivity.kt` | Pass gate in external `ensureSessionAndSend()` path |
| `app/ServiceOverlayController.kt` | Expose `overlayTouchGate` property from `CapsuleOverlayHost` |

### Modified — Behavior
| File | Change |
|------|--------|
| `tool/action/ActionPriorityOrder.kt` | Switched to gesture-first: `gesture_tap → node_click`, `gesture_swipe → a11y_scroll` |
| `agent/definition/StandaloneAgentDef.kt` | "Own UI — Do NOT Interact" prompt section |
| `agent/definition/ExecutorAgentDef.kt` | Same self-takeover prevention prompt |

### Tests
| File | Change |
|------|--------|
| `app/OverlayLocationPolicyTest.kt` | `shouldCapsuleOverlayBeTouchable` tests for Hidden and all non-Hidden modes |

## Debug Run History

| Run | Turns | Outcome | Root Cause | Fix |
|-----|-------|---------|------------|-----|
| 1 (`run_20260220_233012`) | 20 (MAX_TURNS) | Agent clicked own Takeover button | Self-takeover (reasoning) | Own-UI avoidance prompt |
| 2 (`run_20260220_234859`) | 20 (MAX_TURNS) | Gestures not reaching YouTube | Gate null in external session path | Wired `getOverlayTouchGate()` in AgentService/MainActivity |
| 3 (`run_20260221_000118`) | 20 (MAX_TURNS) | Gestures still not reaching YouTube | IPC timing race + fix not deployed | 50ms `FLAG_SETTLE_MS` delay + diagnostic logging |
| 4 (`run_20260221_001359`) | **5 (SUCCESS)** | 周深 - 大魚 playing on YouTube | — | — |

## Key Diagnostic Evidence (Run 4)

Logcat shows the complete gate lifecycle for each gesture:
```
CapsuleOverlayHost: beginGesturePassThrough: depth=1, isShowing=true
AccessibilityGestureInjector: Touch gate active — FLAG_NOT_TOUCHABLE set, waiting 50ms
AccessibilityGestureInjector: dispatchGesture dispatched=true
AccessibilityGestureInjector: dispatchGesture completed
CapsuleOverlayHost: endGesturePassThrough: depth=0
```

Screen element count changed every turn (16→40→35→28→18), confirming gestures reached YouTube. Previous failing runs showed constant 36 elements with zero gate log output.
