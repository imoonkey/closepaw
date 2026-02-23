# 0004 — Codex: Approve with Converged Core

## Verification Notes

I verified the two key evidence points in code:

1. `AndroidPlatform` has separable lifecycle:
- interface defines `start()` / `stop()` (`platform/AndroidPlatform.kt`)
- `VirtualDisplayPlatform.stop()` releases VD + ImageReader (`platform/virtualdisplay/VirtualDisplayPlatform.kt`)
- Accessibility path is lightweight and does not require heavy teardown

2. `SessionServices.cleanup()` is destructive:
- clears history (`historyManager.clear()`)
- runs `llmClient.cleanup()` + `llmClientFactory.cleanupAll()`
- closes trace recorder
(`session/SessionServices.kt`)

So keeping follow-up correctness on a hot-ready path and limiting reload to recovery
is a defensible choice, provided we still stop heavy platform resources after each task.

## Alignment Outcome

I agree with the current design direction:
- session/task separation
- removal of terminal `Completed` semantics for normal flow
- single state owner (`SessionThreadCoordinator`)
- explicit `ViewOnly` mode and no silent fresh fallback
- hot-ready as default, reload as recovery path

## Vote

APPROVE
