# Platform Robustness — Implementation Summary

> Completed: 2026-04-10
> Review docs: `doc/todo/holistic-review/platform-robustness/final/`

## What was implemented

8 phases of platform boundary hardening based on the holistic review, plus 3 follow-up fixes discovered during QA.

### Phase 1-8 (Core Hardening)

| Phase | Change |
|-------|--------|
| P1: Lifecycle serialization | `VdLifecycleArbiter` (Stopped/Running/Broken state machine), lifecycle mutex, Running lease for ops, start() rollback, binder death → Broken + clearCachedProxies |
| P2: Bounded callbacks | Shared `boundedCallback()` helper, 5s timeout on a11y screenshots, 3s on PixelCopy, `invokeOnCancellation` cleanup, late-callback HardwareBuffer close |
| P3: Gesture safety | Best-effort `ACTION_CANCEL` on interrupted long-press/swipe, MOVE failure fails gesture |
| P4: Window selection | Layer-ordered topmost window for single-root (actions, privacy), topmost windowId for screenshot targeting, aligned policies on both platforms |
| P5: Display metrics | `VirtualDisplayConfig.fromPhysicalDisplay()` uses `WindowManager.maximumWindowMetrics` instead of app content metrics |
| P6: Boundary correctness | Rethrow CancellationException in VD capture, Perceptor off Main, truthful app launch results, surface replacement allowed |
| P7: Resource cleanup | Window recycling in `isKeyboardVisibleOnMainDisplay()`, debug screenshot retention cap, dead code removal |
| P8: Regression tests | `VdLifecycleArbiterTest`, `BoundedCallbackTest` |

### Follow-up Fixes

| Fix | Trigger |
|-----|---------|
| Arbiter admission race, PixelCopy bitmap safety, HardwareBuffer leak | Codex code review |
| Shell input fallback (`input -d`) + syntax fix | QA on real device — HiddenApiBypass failure |
| setDisplayId round-trip verification | False positive from void method invoke |
| VD overlay approval visibility | Capsule hidden in MAIN_APP blocked approval dialogs |
| Debug-only exported viewer | ADB couldn't launch viewer for PixelCopy testing |

## Key decisions

- **Arbiter uses preDrainState**: `stop()` sets state to Stopped *before* draining ops, closing the admission race window
- **VdState.Broken carries resources**: displayId + imageReader preserved for cleanup after binder death
- **Shell fallback is device-adaptive**: `supportsDisplayIdInjection()` does a round-trip test, not just method existence check
- **PixelCopy no onCancel**: PixelCopy has no cancellation API, so timeout doesn't recycle the bitmap (framework may still write to it)
- **Overlay shows for user-attention modes**: VD mode forces capsule visible for WaitingForApproval/Input/Action/Error even in MAIN_APP

## Verification

- `./gradlew assembleDebug test` passes
- QA on real device (P0110, Android 16, API 36):
  - Accessibility mode: all paths pass
  - VD mode: lifecycle, capture, app launch, multi-session, clean stop all pass
  - Hybrid/ImageReader screenshots: captured and sent to LLM
  - PixelCopy/LIVE_PREVIEW: verified via viewer
  - Shell input fallback: available but not needed (setDisplayId works on this device)
  - Overlay approval: capsule shows in VD mode
