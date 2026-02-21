# Overlay Touchability: Aligned Design

Date: 2026-02-21
Status: DRAFT — pending alignment
Sources: `design_claude.md`, `overlay_touchability_dispatchgesture_safe_design_codex.md`

---

## 1. Problem (Consensus)

The hotfix in commit `2493be6` hardcoded `FLAG_NOT_TOUCHABLE` on `CapsuleOverlayHost` to prevent `dispatchGesture` events from being silently consumed by the overlay. This fixed eval regressions but broke capsule button interactivity and the interaction lock shield.

Both designs agree on the root cause: three concerns are coupled into a single flag:
1. Overlay window touchability (`FLAG_NOT_TOUCHABLE`)
2. User touch blocking during agent execution (lock shield)
3. Agent gesture pass-through (`dispatchGesture` must reach target app)

## 2. Requirements (Consensus)

1. **No `dispatchGesture` regression** — agent gestures must reach the target app in all modes.
2. **Capsule buttons touchable** — Takeover/Resume/Stop/Done/Close/input must work on overlay capsule.
3. **Interaction lock functional** — during Running in A11y OTHER_APP, user touches blocked.
4. **Takeover pass-through** — during Takeover, user touches reach underlying app.
5. **VD mode unaffected** — `VirtualDisplayInputInjector` (Shizuku) injects to virtual display directly. No overlay conflict.

## 3. Architecture (Consensus)

Two-layer approach:

```
Layer 1: Mode-Driven Baseline Touchability
  Static layer. Derives FLAG_NOT_TOUCHABLE from CapsuleMode.
  Updated on every mode transition.

Layer 2: Gesture Pass-Through Gate
  Dynamic layer. Temporarily forces FLAG_NOT_TOUCHABLE during
  dispatchGesture. Overrides Layer 1. Restore after gesture.
```

## 4. Touchability Matrix (Resolved)

| CapsuleMode | Touchable | Window Size | Rationale |
|---|---|---|---|
| `Hidden` | NO | WRAP_CONTENT | No UI; pass through |
| `TakeoverPending` | YES | MATCH_PARENT | Agent still owns control; shield must keep blocking accidental touches while handover finishes |
| `Running` | YES | MATCH_PARENT | Shield blocks user; capsule buttons accessible above shield |
| `Takeover` | YES | WRAP_CONTENT | User needs Resume; touches outside capsule reach underlying app |
| `WaitingForInput` | YES | WRAP_CONTENT | User types response |
| `WaitingForAction` | YES | WRAP_CONTENT | User taps Done |
| `Done` | YES | WRAP_CONTENT | Dismissible (also auto-hides) |
| `Error` | YES | WRAP_CONTENT | User taps Close |

Dynamic override: When `gestureDispatchInFlight`, force NOT_TOUCHABLE regardless of baseline.

## 5. Gate Interface (Resolved)

Adopt a minimal token API: keeps callsite simple, but avoids boolean-pair bugs if future concurrency appears.

```kotlin
interface OverlayTouchGate {
    fun beginGesturePassThrough(): AutoCloseable
}
```

Usage:
```kotlin
val token = overlayTouchGate?.beginGesturePassThrough()
try { dispatchGesture(...) }
finally { token?.close() }
```

Implementation note:
1. `CapsuleOverlayHost` keeps an internal `passThroughDepth` counter.
2. `beginGesturePassThrough()` increments depth and applies `FLAG_NOT_TOUCHABLE`.
3. `close()` decrements depth (idempotent) and restores baseline only when depth hits 0.
4. No `StateFlow` exposure needed.

## 6. Policy Location (Resolved)

Keep touchability policy in `OverlayLocationPolicy.kt` as a top-level pure function:

```kotlin
internal fun shouldCapsuleOverlayBeTouchable(mode: CapsuleMode): Boolean =
    mode !is CapsuleMode.Hidden
```

Rationale:
1. Co-locates overlay behavior policies (`deriveOverlayVisibility`, `shouldLockUserInteraction`, touchability).
2. Keeps `CapsuleOverlayHost` focused on window application mechanics.
3. Avoids extra file/data-class overhead.

## 7. Wiring (Consensus)

```
CapsuleOverlayHost.touchGate
  -> ServiceOverlayController (exposes getter)
    -> AccessibilityPlatform (constructor param)
      -> AccessibilityGestureInjector (constructor param)
```

One-directional. Gesture injector calls the gate; never observes mode.

## 8. Files Changed (Consensus)

| File | Change |
|---|---|
| `platform/OverlayTouchGate.kt` (or similar) | **New** — token-based gate interface |
| `ui/overlay/compose/CapsuleOverlayHost.kt` | Remove hardcoded `FLAG_NOT_TOUCHABLE`, add mode observer + gate impl |
| `platform/AccessibilityGestureInjector.kt` | Add gate param, wrap `dispatchGesture` |
| `app/ServiceOverlayController.kt` | Expose gate |
| `platform/AccessibilityPlatform.kt` | Thread gate to gesture injector |
| `app/AgentService.kt` | Pass gate when constructing platform |
| `app/OverlayLocationPolicy.kt` | Add `shouldCapsuleOverlayBeTouchable(mode)` |
| `app/OverlayLocationPolicyTest.kt` | Add touchability policy tests |
| `doc/main/ui/overlay.md` | Update touchability section |

## 9. Risks (Consensus)

1. **User touch leak during gesture window** (~100-300ms) — acceptable; shield prevents accidental interference, not adversarial input.
2. **Race between mode transition and gate** — both run on Main thread, no interleave within a single handler.
3. **Gate called on hidden overlay** — guarded by `isShowing()` check.
4. **Flag flip frequency** — deduplicate updates if value unchanged.

---

## 10. Code Evidence Behind Resolutions

1. `TakeoverPending` must stay touchable:
`shouldLockUserInteraction()` currently treats any non-`Takeover`, non-terminal mode as lockable, including `TakeoverPending` (`app/src/main/kotlin/com/moonkey/androidagent/app/OverlayLocationPolicy.kt`). If window is `NOT_TOUCHABLE` in this mode, the full-screen shield cannot consume touches and lock semantics break.

2. Token gate is enough:
`AccessibilityGestureInjector` dispatch path is single-call and Main-thread bound (`withContext(Dispatchers.Main)`), but tokenized enter/exit makes restoration robust with minimal extra complexity.

3. Policy placement:
Overlay behavior policy already lives in `OverlayLocationPolicy.kt`; touchability rule belongs with visibility and lock rules for unified tests and fewer hidden couplings.
