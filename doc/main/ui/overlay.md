# Overlay System

> Smart Capsule, Edge Glow, Status Island, Action Visualizer, and mode-aware overlay branching.
> Last updated: 2026-02-12 (Smart Capsule V2)

## Overview

The overlay system provides visual feedback when the agent is executing tasks outside the main app. All overlays use Android's `TYPE_ACCESSIBILITY_OVERLAY` for system-wide visibility.

### Mode-Aware Branching

`ServiceOverlayController` selects overlays based on `PlatformMode`:

| Mode | Overlays on Real Screen | Rationale |
|------|------------------------|-----------|
| `ACCESSIBILITY` | EdgeGlow + SmartCapsule + ActionVisualizer | User sees the agent operating on the same screen |
| `VIRTUAL_DISPLAY` | StatusIsland only | Agent operates on a hidden VD; only a compact pill is needed on the real screen |

→ See: `app/ServiceOverlayController.kt`

---

## Smart Capsule (V2)

The floating overlay for user-agent collaboration. Driven entirely by `CapsuleMode`.

→ See: `ui/overlay/model/CapsuleMode.kt`, `ui/overlay/SmartCapsuleManager.kt`

### Architecture

One sealed interface, `CapsuleMode`, is the single source of truth for the capsule's UI:

```kotlin
sealed interface CapsuleMode {
    data class Running(val thought: String) : CapsuleMode
    data class TakeoverPending(val lastThought: String) : CapsuleMode
    data class Takeover(val lastThought: String) : CapsuleMode
    data class SupplementInput(val previousMode: CapsuleMode) : CapsuleMode
    data class WaitingForInput(val question: String, val callId: String) : CapsuleMode
    data class WaitingForAction(val instruction: String, val callId: String) : CapsuleMode
    data class Done(val message: String) : CapsuleMode
    data class Error(val message: String) : CapsuleMode
    data object Hidden : CapsuleMode
}
```

Call `updateMode(newMode)` to change state. The manager handles show/hide/render automatically.

### Thought Pipeline

Agent thoughts flow through:

1. LLM returns tool call with `agent_thought` parameter
2. `AgentTurnRunner` extracts and sanitizes the thought (≤40 chars)
3. `AgentEvent.ThoughtUpdate` emitted
4. `SmartCapsuleManager` updates to `Running(thought)`

### Modes

| Mode | Visual | User Action |
|------|--------|-------------|
| **Running** | Pulsing blue dot, thought text, [Stop] | Tap pill → TakeoverPending |
| **TakeoverPending** | Amber dot, "即将接管..." | Agent finishes current turn, then → Takeover |
| **Takeover** | Amber dot, [继续] [补充] [Stop] | Resume, supplement, or stop |
| **SupplementInput** | EditText + keyboard, [Send] | Type message, confirm → agent receives it |
| **WaitingForInput** | Question text, EditText, [Send] | Type answer to agent's question |
| **WaitingForAction** | Instruction text, [完成] | Tap "Done" after performing physical action |
| **Done** | Green dot, completion message | Auto-hides after 3s |
| **Error** | Red dot, error message, [关闭] | Dismiss |
| **Hidden** | Not shown | — |

### Status Dot Colors

| Mode | Color | Hex |
|------|-------|-----|
| Running | Blue | `#2563EB` |
| TakeoverPending | Amber | `#F59E0B` |
| Takeover | Amber | `#F59E0B` |
| WaitingForInput/Action | Purple | `#7C3AED` |
| Done | Teal | `#0D9488` |
| Error | Red | `#DC2626` |

### Layout

Two-row layout built programmatically by `SmartCapsuleLayoutBuilder`:

```
┌──────────────────────────────────────────┐
│ [●] Thought text...          [▶ 继续] [⏹]│  ← Row 1: status + thought + buttons
│ [EditText for input...        ] [Send]   │  ← Row 2: supplement/answer input (hidden by default)
└──────────────────────────────────────────┘
```

### Callbacks

`SmartCapsuleManager` exposes callbacks wired by `ServiceOverlayController`:

| Callback | Triggered By | Dispatches |
|----------|--------------|------------|
| `onTakeover` | User requests takeover | `Op.Takeover` |
| `onResume` | User taps "继续" in Takeover | `Op.Resume` |
| `onSupplement` | User sends supplement text | `Op.Supplement(text)` |
| `onUserResponse` | User answers ask_user | `Op.UserResponse(callId, response)` |
| `onStop` | User taps Stop | `Op.Shutdown` |
| `onOpenApp` | User taps app icon | Opens main activity |
| `onDismissError` | User dismisses error | Hides capsule |

### Integration

→ See: `app/ServiceOverlayController.kt`, `app/AgentService.kt`

Events flow: `AgentSession` → `AgentEvent` → `AgentService.handleEvent()` → `ServiceOverlayController` → `SmartCapsuleManager.updateMode()`

---

## Edge Glow

Ambient visual feedback showing the agent is actively controlling the device.

### Features

- **Full-screen edge glow** with gradient fade from edges
- **State-based colors** matching agent execution phases
- **Pulse animation** when active or executing
- **Touch pass-through** (doesn't block interaction)
- **Display cutout handling** for notched devices
- **Auto-hide** after success state (2 seconds)

### Glow States

| State | Color | Hex | Behavior |
|-------|-------|-----|----------|
| **Active** | Primary Blue | `#2563EB` | Pulsing animation |
| **Executing** | Light Blue | `#3B82F6` | Pulsing animation |
| **Success** | Teal | `#0D9488` | Static, auto-hides after 2s |
| **Error** | Red | `#DC2626` | Static |
| **Paused** | Amber | `#F59E0B` | Static |

### Visibility Control

The edge glow is only visible when the main app is **not** in the foreground:

```kotlin
if (!isAppInForeground && shouldShowGlow) {
    edgeGlowManager?.show(currentGlowState)
} else {
    edgeGlowManager?.hide()
}
```

### Integration

→ See: `ui/overlay/EdgeGlowManager.kt`

```kotlin
edgeGlowManager?.show(GlowState.Active)
edgeGlowManager?.updateState(GlowState.Executing)
edgeGlowManager?.updateState(GlowState.Success)  // Auto-hides
edgeGlowManager?.hide()
```

---

## Status Island (VD Mode)

→ See: `ui/overlay/StatusIslandManager.kt`

Compact floating pill overlay displayed on the **real screen** during virtual display mode. Replaces SmartCapsule + EdgeGlow with a single minimal indicator.

### Features

- **Status dot**: Color-coded (thinking, acting, success, error, paused)
- **Tap**: Opens `VirtualDisplayViewerActivity` for live VD preview
- **Long-press**: Shows inline pause/stop controls for 3 seconds
- **Compact**: Small floating pill that doesn't interfere with real-screen usage

### Inline Controls

Long-pressing the pill reveals:
- **Pause/Resume**: Toggle session pause state
- **Stop**: Terminate the session immediately

### States

| State | Color | Hex |
|-------|-------|-----|
| Thinking | Blue | `#2563EB` |
| Acting | Light Blue | `#3B82F6` |
| Success | Teal | `#0D9488` |
| Error | Red | `#DC2626` |
| Paused | Amber | `#F59E0B` |

### Integration

Driven by `ServiceOverlayController` in `VIRTUAL_DISPLAY` mode. All event handlers (`onTaskStarted`, `onMessageDelta`, `onActionExecuted`, `onTaskCompleted`) delegate to `StatusIslandManager` instead of SmartCapsule/EdgeGlow.

---

## Action Visualizer

Visual feedback when the agent performs touch actions.

### Features

- **Ripple effect** for tap/click actions
- **Trail animation** for swipe/scroll actions
- **Non-intrusive** - passes all touch events through
- **Automatic cleanup** after animation completes
- **Color-coded** actions

### Visualization Types

#### Click Ripple

→ See: `ui/overlay/visualizer/ClickRippleView.kt`

| Property | Value |
|----------|-------|
| Initial radius | 8dp |
| Final radius | 48dp |
| Duration | 500ms |
| Animation | EaseOut |
| Click color | Blue (`#2563EB`) at 60% opacity |
| Long press color | Purple (`#7C3AED`) at 60% opacity |

#### Swipe Trail

→ See: `ui/overlay/visualizer/SwipeTrailView.kt`

| Property | Value |
|----------|-------|
| Line width | 4dp |
| Start dot radius | 8dp |
| End dot radius | 6dp |
| Swipe color | Light Blue (`#3B82F6`) at 50% opacity |
| Scroll color | Indigo (`#6366F1`) at 50% opacity |

### Integration

→ See: `ui/overlay/visualizer/ActionVisualizerManager.kt`

Called from `AccessibilityPlatform`:

```kotlin
class AccessibilityPlatform(
    private val service: AccessibilityService,
    private val visualizer: ActionVisualizerManager? = null
) {
    private suspend fun performTap(x: Float, y: Float): ActionResult {
        visualizer?.showClick(x, y)
        // ... dispatch gesture
    }
    
    private suspend fun performSwipe(...): ActionResult {
        visualizer?.showSwipe(startX, startY, endX, endY, durationMs)
        // ... dispatch gesture
    }
}
```

### API

```kotlin
class ActionVisualizerManager(context: AccessibilityService) {
    var enabled: Boolean
    fun showClick(x: Float, y: Float, longPress: Boolean = false)
    fun showSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long)
    fun showScrollAsSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long)
    fun clearAll()
    fun dispose()
}
```

---

## Z-Order

### Accessibility Mode

Edge glow added **before** SmartCapsule so it renders below:

```
Screen
  └── EdgeGlowView (bottom)
      └── SmartCapsule (top)
          └── ActionVisualizer (topmost)
```

### Virtual Display Mode

Only the Status Island is shown on the real screen:

```
Screen
  └── StatusIsland (single overlay)
```

---

## File Structure

```
ui/overlay/
├── SmartCapsuleManager.kt        # CapsuleMode-driven capsule manager (A11y mode)
├── SmartCapsuleLayoutBuilder.kt  # Two-row capsule layout construction
├── StatusIslandManager.kt         # VD-mode compact pill overlay
├── EdgeGlowManager.kt            # Edge glow lifecycle (A11y mode)
├── EdgeGlowView.kt               # Custom glow rendering
├── model/
│   ├── CapsuleMode.kt            # Sealed interface: single source of truth for capsule UI
│   └── GlowState.kt              # State enum with colors
└── visualizer/
    ├── ActionVisualizerManager.kt  # Visualization orchestrator
    ├── ClickRippleView.kt          # Ripple effect view
    └── SwipeTrailView.kt           # Swipe trail view

app/
└── ServiceOverlayController.kt    # Mode-aware overlay branching
```

---

## Related Docs

- [Style](style.md) - Color definitions
- [Tech Design](tech_design.md) - Integration details
- [Platform](../infra/platform.md) - AccessibilityPlatform integration
