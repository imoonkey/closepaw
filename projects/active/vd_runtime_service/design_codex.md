# Virtual Display Runtime Boundary

## Goal

Decouple Virtual Display resource lifetime from `AgentSession` without turning it into a new app, process, or IPC project.

This is not needed to fix the immediate `New Session` bug. That bug should be fixed directly by making `New Session` avoid implicit app handoff side effects. This design is for the later architecture step: make VD ownership explicit so sessions, chat history, and the viewer no longer decide when the virtual display itself is created or released.

Success means:

- `AgentSession` can shut down without necessarily releasing the VD.
- `SessionServices.cleanup()` can stay simple and still call `platform.stop()`.
- Viewer surface switching and viewer touch do not depend on `session?.platform as? VirtualDisplayPlatform`.
- Android Service is an optional wrapper after the runtime boundary is stable.

## Approach

Phase 1: introduce an app-internal `VirtualDisplayRuntimeManager` owned by `AgentService` or the app service graph. It runs in the current app process. No independent APK. No separate process.

The manager owns VD runtime resources:

- Shizuku client and binder-death handling.
- VD display id and `ImageReader`.
- surface mode switching between `ImageReader` and viewer `SurfaceView`.
- VD touch/input plumbing.
- runtime state and idle retention policy.

Each session gets a lightweight `VirtualDisplaySessionPlatform` that implements `AndroidPlatform` and delegates to the manager. The proxy keeps session-scoped data such as `SessionConfig`, `TraceRecorder`, and app-blocking policy. Its `stop()` releases the session lease, not the VD resources. Actual VD teardown belongs to explicit runtime stop, idle timeout, service destruction, or unrecoverable runtime failure.

Phase 2: optionally wrap the same manager in an app-internal started+bound `VirtualDisplayRuntimeService`. The service should use a local binder in the same process first. It should not introduce AIDL, a second process, or a separate APK unless a later design proves that need.

## Components

### Current Code Shape

- `AgentSession.create()` calls `PlatformFactory.create(...)`.
- `PlatformFactory` constructs `VirtualDisplayPlatform` directly for VD mode.
- `SessionServices.cleanup()` calls `platform.stop()`, which currently releases the VD.
- `VirtualDisplayPlatform` owns both runtime resources and session-scoped capture policy.
- `AgentServiceViewerBridge` reaches through the active session to find `VirtualDisplayPlatform`.
- `VirtualDisplayViewerActivity` talks to `AgentService.instance`, which forwards viewer events to that session-owned platform.
- `SessionCoordinator.clearSession()` submits `Op.Shutdown`, which flows into `SessionServices.cleanup()`.

### New Phase 1 Components

`VirtualDisplayRuntimeManager`

- App-internal singleton-like owner, scoped to `AgentService` lifecycle.
- Owns runtime resources and the VD runtime state machine.
- Exposes `acquireSession(...)`, `releaseSession(...)`, `attachViewer(...)`, `detachViewer(...)`, `onViewerTouch(...)`, `isRunning`, `currentPackageName()`, and `stopRuntime(...)`.

`VirtualDisplaySessionPlatform`

- Implements `AndroidPlatform`.
- Created per `AgentSession`.
- Delegates capture/action/app launch to the manager under a session lease.
- Owns session-scoped policy inputs: `SessionConfig`, `TraceRecorder`, `isPackageBlocked`.
- `start()` ensures the runtime is running and acquires the session lease.
- `stop()` releases the session lease only.

`VirtualDisplayRuntimeCore`

- Extracted resource-level logic from `VirtualDisplayPlatform`.
- Reuses existing `VdLifecycleArbiter`, `VirtualDisplaySurfaceController`, input injection, window access, and app launch plumbing where possible.
- Does not own a session trace recorder or session perception config.

`PlatformFactory`

- Stops constructing raw `VirtualDisplayPlatform` directly.
- In VD mode, asks the runtime manager for a session platform.
- Keeps existing fallback to `AccessibilityPlatform` when Shizuku is unavailable or permission is missing.

`AgentServiceViewerBridge`

- Talks to `VirtualDisplayRuntimeManager`, not the current session platform.
- Viewer actions remain explicit: open viewer, attach surface, detach surface, forward touch.

`VirtualDisplayRuntimeService` (optional phase 2)

- App-internal Android service that owns the same manager.
- Started to keep the runtime alive; bound by `AgentService` and viewer code for local calls.
- Same process by default. No independent APK and no phase-1 separate process.

## Interactions / State Machine

Runtime state:

```text
Stopped
  -- acquireSession --> Starting

Starting
  -- create VD ok --> RunningHeadless
  -- create VD failed --> Stopped

RunningHeadless
  -- attachViewer(surface) --> RunningLivePreview
  -- releaseSession(last session) --> IdleRetained
  -- explicit stop / fatal error --> Stopping

RunningLivePreview
  -- detachViewer --> RunningHeadless
  -- releaseSession(last session) --> IdleRetained
  -- explicit stop / fatal error --> Stopping

IdleRetained
  -- acquireSession --> RunningHeadless
  -- attachViewer(surface) --> RunningLivePreview
  -- idle timeout / explicit stop --> Stopping

Stopping
  -- resources released --> Stopped

Any running state
  -- Shizuku binder death --> Broken

Broken
  -- cleanup complete --> Stopped
```

Session interaction:

```text
AgentSession start
  -> VirtualDisplaySessionPlatform.start()
  -> runtime.acquireSession(sessionId, policy)

AgentSession cleanup
  -> SessionServices.cleanup()
  -> platform.stop()
  -> runtime.releaseSession(sessionId)
  -> VD may remain IdleRetained

Viewer opened
  -> runtime.attachViewer(surface)

Viewer hidden
  -> runtime.detachViewer()
```

Guards and side effects:

- A session can perform VD operations only while it holds an active lease.
- New session creation does not release or launch any app by itself.
- Runtime teardown is centralized in `stopRuntime(...)`.
- Binder death invalidates all active leases and moves the runtime to `Broken`.
- Idle retention must have one clear timeout or explicit stop path; no hidden release from session cleanup.

## Tasks

1. `vd-runtime-contract`
   - Scope: `app/src/main/kotlin/ai/closepaw/platform/virtualdisplay/*`
   - Acceptance: runtime manager/session platform/core responsibilities are defined in code interfaces or small classes; no behavior moved yet.
   - Dependencies: none.

2. `vd-runtime-core-extract`
   - Scope: `app/src/main/kotlin/ai/closepaw/platform/virtualdisplay/*`
   - Acceptance: VD resource ownership moves out of the session platform into a runtime core while preserving existing `VdLifecycleArbiter` semantics.
   - Dependencies: `vd-runtime-contract`.

3. `vd-session-platform-proxy`
   - Scope: `app/src/main/kotlin/ai/closepaw/platform/virtualdisplay/*`, `app/src/main/kotlin/ai/closepaw/session/*`
   - Acceptance: VD sessions use `VirtualDisplaySessionPlatform`; `platform.stop()` releases only the session lease.
   - Dependencies: `vd-runtime-core-extract`.

4. `vd-platform-factory-wiring`
   - Scope: `app/src/main/kotlin/ai/closepaw/platform/PlatformFactory.kt`, `app/src/main/kotlin/ai/closepaw/app/AgentService.kt`, session creation call sites
   - Acceptance: VD mode gets a session platform from the manager; Shizuku fallback behavior remains unchanged.
   - Dependencies: `vd-session-platform-proxy`.

5. `vd-viewer-runtime-bridge`
   - Scope: `app/src/main/kotlin/ai/closepaw/app/AgentServiceViewerBridge.kt`, `app/src/main/kotlin/ai/closepaw/ui/viewer/*`
   - Acceptance: viewer attach/detach/touch uses the runtime manager instead of casting the active session platform.
   - Dependencies: `vd-platform-factory-wiring`.

6. `vd-runtime-idle-policy`
   - Scope: runtime manager, service lifecycle tests, QA helpers
   - Acceptance: when the last session ends, VD enters `IdleRetained`; it releases only on explicit stop, app/service teardown, runtime failure, or configured idle timeout.
   - Dependencies: `vd-viewer-runtime-bridge`.

7. `vd-runtime-service-wrapper`
   - Scope: manifest, `app/src/main/kotlin/ai/closepaw/platform/virtualdisplay/*`, `app/src/main/kotlin/ai/closepaw/app/*`
   - Acceptance: optional same-process started+bound service owns the manager behind the same runtime API.
   - Dependencies: `vd-runtime-idle-policy`.

## Trade-offs

- Manager before Android Service: validates the boundary without adding service binding, lifecycle races, or IPC failure modes.
- Session proxy instead of special-casing cleanup: preserves the simple `SessionServices.cleanup() -> platform.stop()` contract.
- Runtime core split instead of reusing `VirtualDisplayPlatform` forever: avoids leaking one session's `TraceRecorder` and perception config into later sessions.
- Same process only: keeps Shizuku permission, hidden API access, and crash handling simpler. A separate process would require a real IPC contract and recovery model.
- Idle-retained VD uses resources after a session ends. That cost is deliberate and bounded by explicit stop and idle timeout.

## Self-Review

This design keeps the immediate product bug out of scope and focuses on the later ownership boundary. It avoids an independent APK, avoids phase-1 service/process work, and keeps existing cleanup call sites readable. The main complexity is the runtime core split, but that complexity already exists implicitly inside `VirtualDisplayPlatform`; making it explicit prevents session-scoped trace/config state from becoming stale when VD lives longer than a session.
