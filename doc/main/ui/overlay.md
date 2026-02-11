# Overlay System

> Smart Capsule, Edge Glow, Status Island, Action Visualizer, and mode-aware overlay branching.
> Last updated: 2026-02-11 (commit: ddc744e)

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

## Smart Capsule

The floating overlay that follows users across all apps during agent execution.

### Features

- **Streaming text**: Shows live agent response
- **Status dot**: Color-coded with pulsing animation
- **Control buttons**: Pause, Stop, Open App
- **Morphing states**: Visual feedback through color and animation

### States

| State | Visual | Behavior |
|-------|--------|----------|
| **Thinking** | Pulsing glow, "Thinking..." | Agent processing |
| **Acting** | Status text | Shows current tool |
| **Streaming** | Live text | Agent response streaming |
| **Success** | Green flash | Task complete |
| **Error** | Red tint, shake | Something went wrong |
| **Paused** | Amber tint | User paused execution |

### Specifications

| Property | Value |
|----------|-------|
| Height (compact) | 48dp |
| Width | Screen width - 32dp margins |
| Corner Radius | 24dp (capsule) |
| Background | White with subtle shadow |
| Status Dot | 8dp, color-coded |
| Typography | 14sp, Medium weight |
| Button Size | 40dp circular |

### Integration

→ See: `ui/overlay/SmartCapsuleManager.kt`

Called from `AgentService`:

```kotlin
session.events.collect { event ->
    when (event) {
        is AgentEvent.TaskStarted -> capsuleManager.onTaskStarted(event.taskId, event.input)
        is AgentEvent.MessageDelta -> capsuleManager.onMessageDelta(event.turnId, event.delta)
        is AgentEvent.ActionExecuted -> capsuleManager.onActionExecuted(event.toolName, event.success)
        is AgentEvent.TaskCompleted -> capsuleManager.onTaskCompleted()
    }
}
```

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
- **Long-press**: Shows inline pause/stop controls
- **Compact**: Small floating pill that doesn't interfere with real-screen usage

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
├── SmartCapsuleManager.kt        # Capsule behavior + updates (A11y mode)
├── SmartCapsuleLayoutBuilder.kt  # Capsule view construction
├── StatusIslandManager.kt         # VD-mode compact pill overlay
├── EdgeGlowManager.kt            # Edge glow lifecycle (A11y mode)
├── EdgeGlowView.kt               # Custom glow rendering
├── model/
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
