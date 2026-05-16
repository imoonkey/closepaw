# View Virtual Screen Tap Bug

> **Historical**: this diagnosis informed commit `2a22af8d` (open viewer
> from hot idle + live CTA visibility), but on-device QA found the
> post-completion viewer was still unusable (no working takeover/edge
> glow/exit). The CTA was subsequently removed entirely in commit
> `2dfc19bd` rather than continue patching. See `design_codex.md` update.

## Diagnosis

The VD is not intentionally released when a normal task completes. `AgentSession.handleAgentComplete()` emits `TaskCompleted`, flushes the idle checkpoint, transitions the session to `Idle`, clears only `agentRunner`, and schedules the five-minute idle timeout; it does not call `services.cleanup()` or `platform.stop()` in that path (`app/src/main/kotlin/ai/closepaw/session/AgentSession.kt:444`, `:456`, `:463`, `:467`, `:470`). Cleanup that stops the platform only runs from `handleShutdown()` (`AgentSession.kt:596`, `:643`), and `SessionServices.cleanup()` calls `platform.stop()` there (`app/src/main/kotlin/ai/closepaw/session/SessionServices.kt:377`, `:386`).

`VdLifecycleArbiter` also has no idle/paused state. Its non-running states are `Stopped`, `Draining`, and `Broken` (`app/src/main/kotlin/ai/closepaw/platform/virtualdisplay/VdLifecycleArbiter.kt:18`), and the transitions away from `Running` are lifecycle stop/drain (`VirtualDisplayPlatform.stop()` at `app/src/main/kotlin/ai/closepaw/platform/virtualdisplay/VirtualDisplayPlatform.kt:207`, `:213`, `:246`) or binder death (`VirtualDisplayPlatform.kt:175`, `VdLifecycleArbiter.kt:114`). `switchToImageReader()` / viewer surface changes only swap surfaces and return early when not running; they do not release the display (`VirtualDisplayPlatform.kt:258`, `:274`).

The root cause is the tap-time liveness gate using only the `AgentService` bridge's active-session pointer as the source of truth. Before this fix, the completion CTA callback in `MainActivity` called only `AgentService.instance?.isVirtualDisplayViewerAvailable()`; that forwards to `AgentServiceViewerBridge.isViewerAvailable()` (`app/src/main/kotlin/ai/closepaw/app/AgentServiceViewerBridge.kt:44`). The bridge resolves the platform from `AgentService.session` only (`app/src/main/kotlin/ai/closepaw/app/AgentService.kt:91`), while the service session is populated by `observeExternalSession()` (`AgentService.kt:123`, `:132`). If the service-side active session is null/stale while the MainActivity coordinator still holds the live Hot Idle session, the bridge returns false and the CTA silently no-ops even though the row's session can still open the VD.

There is a second post-completion blocker: `VirtualDisplayViewerActivity.onStart()` immediately finishes the viewer when the overlay controller reports an already-idle VD viewer (`app/src/main/kotlin/ai/closepaw/ui/viewer/VirtualDisplayViewerActivity.kt:112`). `ServiceOverlayController.applyVisibility()` explicitly calls this finish path when the user opens the viewer after a task has ended (`app/src/main/kotlin/ai/closepaw/app/ServiceOverlayController.kt:204`). After fixing the gate, that rule would still make the completion CTA look like it did nothing once the completion capsule has auto-hidden.

## Fix Decision

Use a narrow root-cause fix rather than releasing/retaining VD differently:

- Broaden the CTA tap predicate to accept the live VD platform from the MainActivity coordinator session, then rebind `AgentService` to that session before launching the viewer. The strict `VirtualDisplayPlatform.isViewerAvailable()` check remains the resource-level safety guard, so a stopped/broken VD still cannot launch a dead viewer.
- Mark viewer launches from the completion CTA as user-requested idle viewing so `VirtualDisplayViewerActivity` does not immediately finish while the VD is still available. It should still finish when the VD is actually gone.
- Add live CTA visibility from `AgentService` session lifecycle so rows hide `View virtual screen` after the session reaches `Shutdown` (including the five-minute idle timeout), instead of leaving a stale no-op button.

This is closest to fix shape A for the actual tap predicate, with the small reactive visibility piece from C for stale-row cleanup after real shutdown.
