# Overlay System

> Smart Capsule, Edge Glow, Status Island, Action Visualizer, and mode-aware overlay branching.
> Last updated: 2026-02-17 (commit: c57e349)

## Overview

The overlay system provides visual feedback and interaction when the agent executes tasks outside the main app. All system overlays use Android's `TYPE_ACCESSIBILITY_OVERLAY` for system-wide visibility. The main app embeds a Compose capsule widget instead.

All overlay hosts are Compose-based, using `OverlayComposeHost` to bridge `ComposeView` into `WindowManager`.

### Mode-Aware Branching

`ServiceOverlayController` selects overlays based on `PlatformMode` and `CapsuleContext`:

| Mode | Context | Overlays | Rationale |
|------|---------|----------|-----------|
| `ACCESSIBILITY` | `MAIN_APP` | None (Compose capsule in-app) | User in app, SmartCapsuleCompose handles UI |
| `ACCESSIBILITY` | `SCREEN_VIEWING` | EdgeGlow + SmartCapsule + ActionVisualizer | User sees agent on same screen |
| `VIRTUAL_DISPLAY` | `MAIN_APP` | None (Compose capsule in-app) | User in app |
| `VIRTUAL_DISPLAY` | `SCREEN_VIEWING` | SmartCapsule overlay | User watching VD viewer |
| `VIRTUAL_DISPLAY` | `BACKGROUND` | StatusIsland | Compact pill on real screen |

> See: `app/ServiceOverlayController.kt`, `ui/overlay/model/CapsuleContext.kt`

---

## Smart Capsule

The primary UI for user-agent collaboration. Exists in two forms:
- **System overlay** (`CapsuleOverlayHost`) — Compose-based, shown outside the main app via `WindowManager`
- **Compose widget** (`SmartCapsuleCompose`) — embedded in the main app's `ChatScreen` as `Scaffold.bottomBar`

Both render `SmartCapsuleSurface` and are driven by `CapsuleMode` from a shared `CapsuleStateHolder`.

> See: `ui/overlay/model/CapsuleMode.kt`, `ui/overlay/CapsuleStateHolder.kt`

### Architecture

**State management:**
- **CapsuleStateHolder** — single source of truth. Holds `CapsuleMode`, `CapsuleContext`, `PlatformMode`, `turnPhase`, `isAgentMidTurn`, `isStopPending` as `StateFlow`s. All state transitions happen here.

**Overlay host:**
- **CapsuleOverlayHost** — Compose overlay via `OverlayComposeHost`. Manages focusability (keyboard) for `WaitingForInput`, debounces button callbacks (300ms), supports interaction locking (full-screen touch blocker), and transient supplement confirmation flash.

**Compose rendering:**
- **SmartCapsuleSurface** — 3-row Compose layout consuming `CapsuleMode` and `CapsuleRenderSpec`
- **SmartCapsuleSurfaceParts** — Row 1 (status dot + thought), Row 2 (controls + nav), Row 3 (input + action button)
- **SmartCapsuleHostLayout** — host-level padding for overlay positioning

**In-app widget:**
- **SmartCapsuleCompose** — Compose version for main app. Same `SmartCapsuleSurface`, same callbacks.

### CapsuleMode

```kotlin
sealed interface CapsuleMode {
    data class Running(val thought: String) : CapsuleMode
    data class TakeoverPending(val lastThought: String) : CapsuleMode
    data class Takeover(val lastThought: String) : CapsuleMode
    data class WaitingForInput(val question: String, val callId: String) : CapsuleMode
    data class WaitingForAction(val instruction: String, val callId: String) : CapsuleMode
    data class Done(val message: String) : CapsuleMode
    data class Error(val message: String) : CapsuleMode
    data object Hidden : CapsuleMode
}
```

### CapsuleRenderSpec

> See: `ui/overlay/model/CapsuleRenderSpec.kt`

Pure rendering specification derived from `CapsuleMode`. Maps mode to visual properties. Both overlay and in-app renderers consume this spec.

```kotlin
data class CapsuleRenderSpec(
    val dot: DotSpec?,          // Status dot (color, pulsing)
    val thought: ThoughtSpec,   // Row 1 text + alpha
    val expandedBody: String?,  // Expanded question/instruction text
    val buttons: ButtonsSpec,   // Row 2 primary + stop buttons
    val row3: Row3Spec?,        // Row 3 input + action button (null = hidden)
)
```

### NavSpec

> See: `ui/overlay/model/CapsuleRenderSpec.kt`

Separate from `CapsuleRenderSpec` because navigation visibility depends on `CapsuleContext` + `PlatformMode`, not `CapsuleMode`.

```kotlin
data class NavSpec(
    val showMinimize: Boolean,  // [⊖] — VD mode, not in main app, not WaitingFor*/Error
    val showApp: Boolean,       // [📱] — Not in main app, not A11y mode
    val showWatch: Boolean,     // [👁] — VD mode, not SCREEN_VIEWING
)
```

### Modes

| Mode | Dot | Row 1 | Row 2 Primary | Row 3 |
|------|-----|-------|---------------|-------|
| **Running** | Blue (pulsing) | Thought text | [✋ Takeover] | Input + "Add note" |
| **TakeoverPending** | Amber | "Handing over..." | [✋ Handing over] (disabled) | Input + "Add note" |
| **Takeover** | Amber | Last thought (60% alpha) | [▶ Resume] | Input + "Add note" |
| **WaitingForInput** | Hidden | "💬 Awaiting response" + body | [⏹ Stop] only | Input + "Send →" |
| **WaitingForAction** | Hidden | "✋ Action needed" + body | [✅ Done] | Hidden |
| **Done** | Teal | "✓ {message}" | Hidden | Hidden |
| **Error** | Red | "⚠ {message}" | [✕ Close] | Hidden |
| **Hidden** | Hidden | — | Hidden | Input + "Send →" |

### Status Dot Colors

> See: `ui/overlay/model/CapsuleColors.kt`

| Mode | Color | Hex |
|------|-------|-----|
| Running | Blue | `#2563EB` |
| TakeoverPending / Takeover | Amber | `#F59E0B` |
| Done | Teal | `#0D9488` |
| Error | Red | `#EF4444` |

### Layout

Three-row layout rendered by `SmartCapsuleSurface`:

```
┌──────────────────────────────────────────┐
│ [●] Thought text...                      │  ← Row 1: status dot + thought
│──────────────────────────────────────────│
│ [✋ Takeover] [⏹ Stop]     [⊖] [📱] [👁]│  ← Row 2: controls + nav icons
│──────────────────────────────────────────│
│ [Got ideas? Add a note...    ] [Add note]│  ← Row 3: input + action button
└──────────────────────────────────────────┘
```

**Expanded body** (WaitingForInput, WaitingForAction): appears below Row 1 showing the question/instruction text.

**Navigation icons** (Row 2, right side):
- [⊖] Minimize to island (VD mode only, not in main app, not in WaitingFor*/Error)
- [📱] Open main app (not in main app, VD mode only)
- [👁] Open VD viewer (VD mode, not when already viewing)

### Thought Pipeline

1. LLM returns tool call with `agent_thought` parameter
2. `AgentTurnRunner` extracts and sanitizes the thought (≤40 chars via `sanitizeThought`)
3. `AgentEvent.ThoughtUpdate` emitted
4. `ServiceOverlayController` → `CapsuleStateHolder.onThoughtUpdate()` → `Running(thought)`
5. Overlay: `SmartCapsuleSurface` recomposes via `stateHolder.mode` StateFlow collection

### Supplement Confirmation

`CapsuleOverlayHost.flashSupplementConfirmation()` shows transient feedback:
- Between turns: `"✓ Received"` (displayed 1500ms)
- Mid-turn (`isAgentMidTurn=true`): `"✓ Received, will apply next step"` (displayed 2000ms)

### Stop Pending Feedback

`CapsuleStateHolder.isStopPending` drives immediate "Stopping..." disabled UI on the stop button. Not part of the `CapsuleMode` state machine — it's a transient UI flag cleared by the next terminal or new-task event.

### CapsuleStateHolder State Transitions

> See: `ui/overlay/CapsuleStateHolder.kt`

| Method | Guard | Transition |
|--------|-------|------------|
| `onTaskStarted(taskId, input)` | Any | → `Running(sanitized input)` |
| `onThoughtUpdate(thought)` | Must be `Running` | → `Running(thought)` |
| `onTakeoverRequested()` | Must be `Running` | → `TakeoverPending(thought)` |
| `onTakeoverConfirmed()` | `TakeoverPending` or `Running` | → `Takeover(thought)` |
| `onResumed()` | `Takeover` or `TakeoverPending` | → `Running("Thinking...")` |
| `onAskUser(type, message, callId)` | Any active | → `WaitingForInput` or `WaitingForAction` |
| `onUserResponseSent(callId)` | `WaitingForInput` or `WaitingForAction` + callId match | → `Running("Processing response...")` |
| `onTaskCompleted(reason, message?)` | Not `Hidden`/`Done`/`Error` | → `Done` or `Error` per reason |
| `onSessionEnded(reason)` | Any | → `Done`/`Hidden`/`Error` per reason |
| `onError(message)` | Any | → `Error(message)` |
| `onDismissError()` | Must be `Error` | → `Hidden` |

Auto-hide: `Done` state schedules auto-hide to `Hidden` after 3000ms.

### Callbacks

`CapsuleOverlayHost` and `SmartCapsuleCompose` expose callbacks wired by `ServiceOverlayController` / `ChatScreen`:

| Callback | Triggered By | Dispatches |
|----------|--------------|------------|
| `onTakeover` | User requests takeover | `CapsuleStateHolder.onTakeoverRequested()` → `Op.Takeover` |
| `onResume` | User taps Resume in Takeover | `Op.Resume` |
| `onSupplement` | User sends supplement text | `Op.Supplement(text)` |
| `onUserResponse` | User answers ask_user | `CapsuleStateHolder.onUserResponseSent()` → `Op.UserResponse(callId, response)` |
| `onStop` | User taps Stop | `Op.Shutdown` |
| `onSend` | User sends new task (Hidden mode) | `Op.UserInput(text)` |
| `onOpenApp` | User taps app icon | Opens main activity |
| `onDismissError` | User dismisses error | `CapsuleStateHolder.onDismissError()` |
| `onMinimize` | Nav [⊖] tapped | Hides capsule, shows island |
| `onOpenViewer` | Nav [👁] tapped | Launches VD viewer activity |

### Integration

**Overlay flow:** `AgentSession` → `AgentEvent` → `AgentService.handleEvent()` → `ServiceOverlayController` → `CapsuleStateHolder` → `SmartCapsuleSurface` recomposes

**Compose (in-app) flow:** `CapsuleStateHolder.mode` collected via `StateFlow` in `ChatScreen` → `SmartCapsuleCompose` → `SmartCapsuleSurface` renders

---

## Edge Glow

> See: `ui/overlay/compose/GlowOverlayHost.kt`, `ui/overlay/compose/EdgeGlowCompose.kt`

Ambient visual feedback showing the agent is actively controlling the device. Full-screen `Canvas`-based Compose overlay rendering gradient glow on all four screen edges.

### Features

- **Four-edge gradient glow** (40dp width) with state-based colors
- **Pulse animation** when Active or Executing (800ms, alpha 0.5→0.85)
- **Static alpha** (0.7) for Paused/Error/Success
- **Touch pass-through** (`FLAG_NOT_TOUCHABLE`)
- **Display cutout handling** (`LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`)
- **Auto-hide** after Success state (2000ms delay, then 500ms fade-out)
- **Derived state** — glow state is derived from `CapsuleMode` + `TurnPhase` via `deriveGlowState()`, eliminating parallel state management

### Glow States

> See: `ui/overlay/model/GlowState.kt`

| State | Color | Hex | Derived When |
|-------|-------|-----|-------------|
| **Active** | Blue | `#2563EB` | Running (non-execution phase) |
| **Executing** | Light Blue | `#3B82F6` | Running + `EXECUTION` turn phase |
| **Success** | Teal | `#0D9488` | Done mode |
| **Error** | Red | `#DC2626` | Error mode |
| **Paused** | Amber | `#F59E0B` | TakeoverPending, Takeover, WaitingFor* modes |

```kotlin
fun deriveGlowState(mode: CapsuleMode, turnPhase: TurnPhase?): GlowState
```

### Visibility

Only visible in `ACCESSIBILITY` mode when user is in `SCREEN_VIEWING` context (not in main app).

---

## Status Island (VD Mode)

> See: `ui/overlay/compose/IslandOverlayHost.kt`, `ui/overlay/compose/StatusIslandCompose.kt`

Compact floating pill overlay displayed on the **real screen** during virtual display mode when the user is in `BACKGROUND` context.

### Features

- **Compose-based** — `StatusIslandCompose` renders a `Surface` with dot + text
- **Status dot**: Color derived from `CapsuleMode` + `TurnPhase` via `deriveGlowState()`
- **Text**: Mode-dependent (thought text truncated to 24 chars, or status like "Paused", "Action needed")
- **Tap**: Expands to full Smart Capsule overlay (`onExpandCapsule` callback)
- **Position**: Top-center, below status bar (4dp margin)
- **Shadow**: 4dp elevation

### Integration

Driven by `ServiceOverlayController` in `VIRTUAL_DISPLAY` mode. `IslandOverlayHost.startObserving(stateHolder)` connects the state. When tapped, `ServiceOverlayController.onIslandTapped()` hides the island, sets context to `SCREEN_VIEWING`, and shows the full Smart Capsule overlay.

---

## Action Visualizer

> See: `ui/overlay/visualizer/ActionVisualizerManager.kt`, `ui/overlay/compose/VisualizerOverlayHost.kt`, `ui/overlay/compose/ActionVisualizerCompose.kt`

Visual feedback when the agent performs touch actions. Compose `Canvas`-based rendering with automatic item lifetime management.

### Features

- **Click ripple** — expanding circle animation
- **Swipe trail** — animated line with start/end dots
- **Touch pass-through** (`FLAG_NOT_TOUCHABLE`)
- **Automatic cleanup** — items removed after their duration expires
- **Edge clamping** — coordinates clamped to 10px from screen edges

### Visualization Types

#### Click Ripple

| Property | Value |
|----------|-------|
| Initial radius | 8dp |
| Final radius | 48dp |
| Duration | 500ms |
| Animation | Linear expansion with alpha fade (0.6 → ~0.18) |
| Click color | Blue (`#2563EB`) at 60% opacity |
| Long press color | Purple (`#7C3AED`) at 60% opacity |

#### Swipe Trail

| Property | Value |
|----------|-------|
| Line width | 4dp |
| Start dot radius | 8dp |
| End dot radius | 6dp |
| Duration | gesture duration + 400ms |
| Swipe color | Light Blue (`#3B82F6`) at 50% opacity |
| Scroll color | Indigo (`#6366F1`) at 50% opacity |

### API

```kotlin
class ActionVisualizerManager(
    context: AccessibilityService,
    lifecycleOwner: LifecycleOwner,
    savedStateRegistryOwner: SavedStateRegistryOwner,
) {
    var enabled: Boolean
    fun showClick(x: Float, y: Float, longPress: Boolean = false)
    fun showSwipe(startX, startY, endX, endY, durationMs: Long)
    fun showScrollAsSwipe(startX, startY, endX, endY, durationMs: Long)
    fun dispose()
}
```

Called from `AccessibilityPlatform` before dispatching gestures.

---

## Overlay Compose Infrastructure

### OverlayComposeHost

> See: `ui/overlay/compose/OverlayComposeHost.kt`

Shared utility wrapping a `ComposeView` for system overlay via `WindowManager`:

```kotlin
class OverlayComposeHost(context, lifecycleOwner, savedStateRegistryOwner, windowManager, tag) {
    fun isShowing(): Boolean
    fun show(layoutParams, content: @Composable () -> Unit)
    fun hide()
    fun updateLayoutParams(update: (WindowManager.LayoutParams) -> Unit)
    fun getWindowToken(): IBinder?
    fun dispose()
}
```

- Uses `ViewCompositionStrategy.DisposeOnDetachedFromWindow`
- Sets `ViewTreeLifecycleOwner` and `ViewTreeSavedStateRegistryOwner`
- Wraps content in `ChatTheme`

### ServiceLifecycleOwner

> See: `ui/overlay/compose/ServiceLifecycleOwner.kt`

Implements `LifecycleOwner` + `SavedStateRegistryOwner` for services. Manual lifecycle events: `onCreate()` → CREATE → START → RESUME, `onDestroy()` → PAUSE → STOP → DESTROY. Enables Compose in non-Activity contexts (AccessibilityService).

---

## Z-Order

### Accessibility Mode (SCREEN_VIEWING)

Edge glow added **before** SmartCapsule so it renders below:

```
Screen
  └── EdgeGlow (bottom — FLAG_NOT_TOUCHABLE)
      └── SmartCapsule (middle)
          └── ActionVisualizer (top — FLAG_NOT_TOUCHABLE)
```

### Virtual Display Mode (BACKGROUND)

Only the Status Island is shown on the real screen:

```
Screen
  └── StatusIsland (single overlay, top-center)
```

### Virtual Display Mode (SCREEN_VIEWING)

SmartCapsule overlay shown atop VD viewer:

```
Screen
  └── VirtualDisplayViewerActivity
      └── SmartCapsule (overlay atop viewer)
```

### Main App (MAIN_APP)

No system overlays. SmartCapsuleCompose is embedded in the Compose hierarchy:

```
ChatScreen
  └── SmartCapsuleCompose (Scaffold.bottomBar)
```

---

## File Structure

```
ui/overlay/
├── CapsuleStateHolder.kt                  # Single source of truth: mode/context/phase StateFlows
├── compose/
│   ├── OverlayComposeHost.kt              # ComposeView → WindowManager bridge
│   ├── ServiceLifecycleOwner.kt           # LifecycleOwner for AccessibilityService
│   ├── CapsuleOverlayHost.kt              # Smart Capsule system overlay
│   ├── GlowOverlayHost.kt                 # Edge glow system overlay
│   ├── IslandOverlayHost.kt               # Status island system overlay (VD mode)
│   ├── VisualizerOverlayHost.kt           # Action visualizer system overlay
│   ├── EdgeGlowCompose.kt                 # Canvas-based 4-edge glow rendering
│   ├── StatusIslandCompose.kt             # Compact status pill composable
│   └── ActionVisualizerCompose.kt         # Canvas-based click/swipe rendering
├── model/
│   ├── CapsuleMode.kt                     # Sealed interface (8 modes)
│   ├── CapsuleContext.kt                  # MAIN_APP / SCREEN_VIEWING / BACKGROUND
│   ├── CapsuleColors.kt                   # Status dot color constants
│   ├── CapsuleRenderSpec.kt               # Mode → visual properties + NavSpec
│   └── GlowState.kt                       # Glow state enum + deriveGlowState()
└── visualizer/
    └── ActionVisualizerManager.kt         # Visualization orchestrator

ui/capsule/
├── SmartCapsuleCompose.kt                 # Compose capsule for main app
└── surface/
    ├── SmartCapsuleSurface.kt             # 3-row capsule Compose layout
    ├── SmartCapsuleSurfaceParts.kt        # Row1/Row2/Row3 components
    └── SmartCapsuleHostLayout.kt          # Host-level padding

app/
├── ServiceOverlayController.kt           # Mode-aware overlay branching
└── AgentService.kt                       # Exposes CapsuleStateHolder, viewer lifecycle hooks
```

---

## Related Docs

- [Style](style.md) - Color definitions
- [Tech Design](tech_design.md) - Integration details
- [User Interaction](user_interaction.md) - User flows
- [Platform](../infra/platform.md) - AccessibilityPlatform integration
