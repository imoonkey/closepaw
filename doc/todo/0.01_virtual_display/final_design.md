# Virtual Display via Shizuku — Final Design

> Merges Design 1's platform correctness with Design 2's lifecycle/UI design.
> Reviewed against qi_note, codex review, and gemini review.
>
> **Status: IMPLEMENTED** (runtime stabilization phases complete; UI Phase 4 in Section 11 remains deferred)
> Implementation commit: 04cecbd (2026-02-10)

---

## 1. Goal

The agent operates on the user's real screen. This means:
- User can't use their phone while the agent works
- User touches disrupt the agent mid-task
- Agent actions are visible and distracting

**Solution**: Create a virtual display via Shizuku. The agent works there.
The user keeps their phone. When Shizuku is unavailable, fall back to the
real screen (existing AccessibilityPlatform).

---

## 2. Constraints (from qi_note)

1. Shizuku approach. No ADB fallback, no root.
2. UI: mini island on real screen → tap to view VD → smart capsule inside VD page → swipe up to exit (task continues).
3. Verify APIs against AOSP/official sources.
4. Auto-detect Shizuku: available → virtual display, unavailable → real screen.
5. VirtualDisplayPlatform uses a11y tree. Share code with AccessibilityPlatform.
6. AndroidPlatform interface may be modified if beneficial.

---

## 3. Architecture

```
Agent → SessionServices → AndroidPlatform ──→ AccessibilityPlatform  (real screen)
                                           └→ VirtualDisplayPlatform (virtual display)
```

Everything above `AndroidPlatform` is unchanged. `PlatformFactory` selects
the implementation at session start based on Shizuku availability and user
preference.

---

## 4. Key Design Decisions

### 4.1 A11y tree via displayId filtering (from Design 1)

`AccessibilityService.getWindows()` returns windows across ALL displays.
`AccessibilityWindowInfo.getDisplayId()` (API 30+) lets us filter to our
virtual display. Our minSdk is 31. No compatibility hacks needed.

This preserves the agent's structured perception — element indices, clickable
flags, bounds, text content. Screenshot-only mode is a fallback, not the
default.

**Rationale**: Dropping a11y (as Design 2 proposed) would cripple agent
intelligence. The a11y tree is what makes node-based actions reliable.

### 4.2 Node-based vs coordinate-based actions (from Design 1)

| Action type | Implementation | Why |
|---|---|---|
| `ClickNodeAt` | a11y `ACTION_CLICK` on found node | Works across displays, precise |
| `LongClickNodeAt` | a11y `ACTION_LONG_CLICK` on found node | Same |
| `SetTextOnNodeAt` | a11y `ACTION_SET_TEXT` on found node | Reliable text input |
| `SetTextOnFocused` | a11y `ACTION_SET_TEXT` on focused node | Same |
| `TapAt` | Shizuku `injectInputEvent` | Coordinate gesture |
| `LongPressAt` | Shizuku `injectInputEvent` | Coordinate gesture |
| `Swipe` | Shizuku `injectInputEvent` sequence | Coordinate gesture |
| `SystemButton` | Shizuku `KeyEvent` injection | Must target displayId |
| `Wait` | `delay()` | No change |

Node-based actions use `AccessibilityNodeInfo.performAction()` which routes
correctly regardless of display. Coordinate-based actions need
`IInputManager.injectInputEvent()` because `dispatchGesture()` only works
on the default display.

### 4.3 Screen capture via ImageReader

We own the virtual display's Surface. `ImageReader` provides it. Reading
pixels requires zero privilege — no Shizuku call needed for screenshots.

### 4.4 Shizuku binder access via reflection

Instead of defining fragile AIDL files (where transaction IDs must match
the exact AOSP version), we use `ShizukuBinderWrapper` + reflection on
the framework's own stub classes:

```kotlin
val rawBinder = SystemServiceHelper.getSystemService("display")
val wrapped = ShizukuBinderWrapper(rawBinder)
val stub = Class.forName("android.hardware.display.IDisplayManager\$Stub")
val proxy = stub.getMethod("asInterface", IBinder::class.java).invoke(null, wrapped)
// Call methods via reflection — transaction IDs always match the device's framework
```

This is the pattern used by Taskbar, scrcpy-server, and other proven
Shizuku-based apps. It works because the framework's own stubs handle
serialization correctly for each Android version.

### 4.5 Fallback to real screen

```kotlin
val platform: AndroidPlatform = if (shizukuAvailable && userPrefersVirtualDisplay) {
    VirtualDisplayPlatform(service, shizukuClient, config).also { it.start() }
} else {
    AccessibilityPlatform(service, sessionConfig, visualizer, traceRecorder)
}
```

Shizuku unavailable → AccessibilityPlatform. No silent degradation within
VirtualDisplayPlatform. The choice is made once at session start.

### 4.6 Lifecycle on AndroidPlatform

Add `start()`/`stop()` with default no-op implementations:

```kotlin
interface AndroidPlatform {
    suspend fun start() {}   // VirtualDisplayPlatform creates display
    suspend fun stop() {}    // VirtualDisplayPlatform releases resources
    // ... existing methods unchanged ...
}
```

- `AccessibilityPlatform`: no-op (already ready when a11y service runs)
- `VirtualDisplayPlatform`: creates/releases display + ImageReader

Called by `AgentSession` at session start/end. `SessionServices.cleanup()`
calls `platform.stop()`.

### 4.7 Code sharing

Already-extracted utilities reused directly:
- `Perceptor.snapshot(root)` — pass root from filtered windows
- `AccessibilityNodeFinder` — all methods work with any root node

Extract from `AccessibilityPlatform` into shared utility:
- `BitmapUtils.scaleBitmapIfNeeded()` and `BitmapUtils.compressJpeg()`

---

## 5. API Verification

| API | Source | Shell permission? | Verified by |
|---|---|---|---|
| `IDisplayManager.createVirtualDisplay` | [AOSP IDisplayManager.aidl](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/hardware/display/IDisplayManager.aidl) | Yes (shell/system) | scrcpy, Taskbar, LSPosed |
| `IInputManager.injectInputEvent` | [AOSP IInputManager.aidl](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/hardware/input/IInputManager.aidl) | Yes (shell) | scrcpy, `adb shell input` |
| `ActivityOptions.setLaunchDisplayId` | [Official docs](https://developer.android.com/reference/android/app/ActivityOptions#setLaunchDisplayId(int)) | Needs shell for non-default display | Taskbar |
| `AccessibilityWindowInfo.getDisplayId` | Public API since API 30 | No | Standard SDK |
| `ImageReader` | Public API | No | Standard SDK |
| `InputEvent.setDisplayId` | Hidden API (`@hide`) | No (just hidden) | HiddenApiBypass |

---

## 6. File Plan

### New files

```
platform/virtualdisplay/
├── ShizukuClient.kt             # Shizuku binder wrapper
├── VirtualDisplayPlatform.kt    # AndroidPlatform for virtual display
└── VirtualDisplayConfig.kt      # Display config data class

platform/
├── PlatformFactory.kt           # Platform selection logic
└── BitmapUtils.kt               # Extracted shared bitmap utilities
```

### Modified files

| File | Change |
|---|---|
| `platform/AndroidPlatform.kt` | Add `start()`/`stop()` with default no-op |
| `app/build.gradle.kts` | Shizuku + HiddenApiBypass dependencies |
| `AndroidManifest.xml` | Shizuku provider + permission |
| `protocol/Op.kt` | `PlatformMode` enum, add to `SessionConfig` |
| `model/Models.kt` | `ScreenImageSource.VIRTUAL_DISPLAY_CAPTURE` |
| `session/AgentSession.kt` | Use `PlatformFactory`, call `platform.start()` |
| `session/SessionServices.kt` | Call `platform.stop()` in `cleanup()` |
| `app/AppSettingsState.kt` | `platformMode` preference |
| `app/AppSettingsStore.kt` | Persist `platformMode` |
| `app/AgentService.kt` | Pass platform mode to session |
| `app/MainActivity.kt` | Pass platform mode to session config |

### Unchanged (the whole point of good abstraction)

`Agent.kt`, `AgentTurnRunner.kt`, `Perceptor.kt`, `UIAction.kt`,
`ActionResult.kt`, `ToolRouter.kt`, all tool implementations, all agent
definitions.

---

## 7. Dependencies

```kotlin
// app/build.gradle.kts
dependencies {
    // Shizuku — binder forwarding with shell UID
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Hidden API bypass — for InputEvent.setDisplayId(), ServiceManager, etc.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
```

**No AIDL files needed.** We use reflection on the framework's own stub
classes through ShizukuBinderWrapper.

---

## 8. Lifecycle

```
Session Start
  └→ PlatformFactory.create(shizukuClient, config, service, ...)
       ├→ Shizuku unavailable: AccessibilityPlatform (same as today)
       └→ Shizuku available:
            └→ VirtualDisplayPlatform.start()
                ├→ HiddenApiBypass.addHiddenApiExemptions("")
                ├→ Create ImageReader (capture surface)
                ├→ Create virtual display via Shizuku (IDisplayManager)
                ├→ Register Shizuku binder death listener
                └→ Return displayId

Agent Loop (unchanged)
  ├→ captureScreen()
  │     ├→ A11y: service.windows.filter { it.displayId == displayId }
  │     │        → find TYPE_APPLICATION → root → Perceptor.snapshot(root)
  │     └→ Screenshot: imageReader.acquireLatestImage() → compress → ScreenImage
  ├→ performAction(action)
  │     ├→ Node-based: find node in filtered windows → performAction()
  │     └→ Coordinate-based: inject via IInputManager with displayId
  └→ repeat

Session End
  └→ SessionServices.cleanup()
       └→ platform.stop()
            ├→ Release virtual display (IDisplayManager)
            ├→ Close ImageReader
            └→ Remove death listener
```

---

## 9. UI Design (Minimal V1)

### 9.1 Real screen: Mini Island

When agent runs on virtual display, the real screen shows a minimal
floating indicator (overlay):
- Small pill shape showing "Agent running..."
- Tap → open VirtualDisplayActivity to watch

**Not in scope for platform Phase 1-3.** This is Phase 4 work and has
its own design considerations (overlay permissions, capsule integration).

### 9.2 Virtual Display Activity

A simple Activity that:
- Shows a live preview of the virtual display content (via ImageReader frames)
- Contains the full SmartCapsule (pause/resume/stop)
- Swipe up from bottom → `finish()` (does NOT stop the agent)

### 9.3 Capsule routing

In virtual display mode:
- `ServiceOverlayController` shows mini island instead of full capsule
- `SmartCapsuleManager` renders inside `VirtualDisplayActivity`, not as overlay

---

## 10. Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Display creation method signature varies by API | Crash on some versions | Version-branched reflection; test on API 31/33/34/35 |
| A11y tree empty on virtual display | Agent blind to UI structure | `FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS`; fall back to screenshot-only perception |
| Input injection targets wrong display | Wrong app gets poked | Always set displayId on every event; assert before injection |
| Shizuku dies mid-session | Agent loses privileged access | `Shizuku.addBinderDeadListener()` → emit session error, stop cleanly |
| ImageReader stale frames | Outdated screenshot | `acquireLatestImage()` (drops stale); add delay after actions |
| Apps refuse to launch on virtual display | Some apps check display type | `FLAG_PUBLIC`; most apps don't check |
| Hidden API changes between Android versions | Reflection fails | Wrap in try/catch; version-specific fallbacks |

---

## 11. Implementation Phases

### Phase 1: Foundation
- Add Shizuku + HiddenApiBypass dependencies to `build.gradle.kts`
- Add Shizuku manifest entries (provider, permission)
- Create `VirtualDisplayConfig` data class
- Create `ShizukuClient` (status checks, binder wrappers, reflection calls)
- Add `start()`/`stop()` to `AndroidPlatform` interface
- Add `ScreenImageSource.VIRTUAL_DISPLAY_CAPTURE`
- Extract `BitmapUtils` from `AccessibilityPlatform`
- Add `PlatformMode` to `SessionConfig`

**Acceptance**: Build compiles. ShizukuClient methods callable. Existing tests pass.

### Phase 2: VirtualDisplayPlatform Core
- Implement `VirtualDisplayPlatform`:
  - `start()`: create ImageReader + virtual display
  - `stop()`: release resources
  - `captureScreen()`: a11y tree (filtered) + ImageReader screenshot
  - `performAction()`: all UIAction variants
- Unit tests for config, action dispatch logic, input event construction

**Acceptance**: All UIAction types implemented. Unit tests pass. Build compiles.

### Phase 3: Wiring
- Create `PlatformFactory`
- Modify `AgentSession.create()` to use PlatformFactory
- Modify `SessionServices.cleanup()` to call `platform.stop()`
- Add `platformMode` to `AppSettingsState` / `AppSettingsStore`
- Wire up `AgentService` and `MainActivity`

**Acceptance**: Platform selection works. Settings toggle works. Build compiles.
End-to-end testable on device with Shizuku.

### Phase 4: UI (Future — separate design doc)
- Mini Island overlay on real screen
- VirtualDisplayActivity with live preview
- Capsule routing (mini vs full)
- Swipe-to-exit gesture

---

## 12. Test Strategy

### Unit tests (no device)
- `VirtualDisplayConfig.fromPhysicalDisplay()` — correct metrics
- `PlatformFactory` — correct platform selection for each mode
- `BitmapUtils` — scaling, compression
- Input event construction (displayId set correctly)
- Action dispatch mapping (each UIAction → correct handler)

### Integration tests (device + Shizuku)
- `ShizukuClient` binder wrapper works
- Display creation returns valid displayId
- Input injection delivers events to correct display
- A11y tree filtering returns windows from virtual display only
- `captureScreen()` returns non-empty snapshot
- Full agent run: "Open Settings" on virtual display

### Regression
- All existing tests pass unchanged
- AccessibilityPlatform behavior identical (start/stop are no-ops)

---

## 13. One-sentence summary

A clean `VirtualDisplayPlatform` implementing the existing `AndroidPlatform`
interface, using Shizuku for display creation and input injection, ImageReader
for screen capture, and filtered a11y windows for structured perception —
with zero changes to the agent/tool/session layers above.
