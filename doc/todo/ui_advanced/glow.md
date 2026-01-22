# Agent Mode Edge Glow Effect

> Design document for screen edge glow to indicate agent execution mode.

## Overview

When the agent is actively controlling the device (executing actions), the screen edges should emit a subtle, animated glow effect. This provides clear visual feedback that the agent is in control, distinguishing it from normal device usage.

## Motivation

**Problem**: Users don't have a clear visual indicator when the agent is actively operating vs. idle. The SmartCapsule provides status text, but it's small and can be missed. Users need an ambient, always-visible indicator.

**Solution**: A full-screen edge glow that's:
- Immediately recognizable as "agent mode"
- Non-intrusive (doesn't block content)
- Visually distinct from system UI elements

## Visual Design

### Glow Appearance

```
┌──────────────────────────────────────┐
│░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░│ ← Top edge glow
│░                                    ░│
│░                                    ░│
│░    [Normal screen content]         ░│
│░                                    ░│ ← Side edge glow
│░                                    ░│
│░                                    ░│
│░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░│ ← Bottom edge glow
└──────────────────────────────────────┘
```

### Specifications

| Property | Value | Notes |
|----------|-------|-------|
| Glow width | 16dp | Visible but not intrusive |
| Blur radius | 24dp | Soft fade into content |
| Base opacity | 40% | Visible but not distracting |
| Animation | Pulse 0.8s | Breathing effect while active |

### Color States

| State | Color | Description |
|-------|-------|-------------|
| **Active** | `#2563EB` (Primary Blue) | Agent is thinking/acting |
| **Executing** | `#3B82F6` (Lighter Blue) | Currently performing action |
| **Success** | `#0D9488` (Teal) | Task completed successfully |
| **Error** | `#DC2626` (Red) | Something went wrong |
| **Paused** | `#F59E0B` (Amber) | Agent paused by user |

### Animation

1. **Pulse animation**: Opacity oscillates 30%–50% with easing
2. **Color transitions**: Smooth 200ms fade between states
3. **Entry**: Fade in 300ms when agent starts
4. **Exit**: Fade out 500ms after task completes (with 2s delay)

## Technical Design

### Architecture

```
EdgeGlowManager
├── show(state: GlowState)
├── hide()
├── updateState(state: GlowState)
└── dispose()

GlowState
├── Active
├── Executing
├── Success
├── Error
└── Paused
```

### Implementation Approach

#### Option A: Custom View with Canvas (Recommended)

Create a custom `View` that draws a gradient border using `Canvas`:

```kotlin
class EdgeGlowView(context: Context) : View(context) {
    private var glowColor = Color.parseColor("#2563EB")
    private var glowAlpha = 0.4f
    private val glowWidth = 16.dp
    private val blurRadius = 24.dp
    
    private val paint = Paint().apply {
        maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
    }
    
    override fun onDraw(canvas: Canvas) {
        // Draw top edge
        paint.shader = LinearGradient(0f, 0f, 0f, glowWidth,
            glowColor.withAlpha(glowAlpha), Color.TRANSPARENT, TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), glowWidth, paint)
        
        // Draw bottom edge
        // Draw left edge
        // Draw right edge
    }
}
```

**Pros**:
- Full control over rendering
- Efficient (single view)
- Works on all Android versions

**Cons**:
- Manual gradient management
- Need to handle blur masking carefully

#### Option B: Layered Drawables

Use 4 separate `GradientDrawable` views positioned at each edge:

```kotlin
// Create 4 views: top, bottom, left, right
val topGlow = View(context).apply {
    background = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(glowColor, Color.TRANSPARENT)
    )
    layoutParams = LayoutParams(MATCH_PARENT, glowWidth.dp)
}
```

**Pros**:
- Simpler implementation
- Easy to animate individual edges

**Cons**:
- 4 separate views (minor overhead)
- Less precise blur control

### WindowManager Integration

```kotlin
class EdgeGlowManager(
    private val context: AccessibilityService
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var glowView: View? = null
    private var pulseAnimator: ValueAnimator? = null
    
    fun show(state: GlowState = GlowState.Active) {
        if (glowView != null) {
            updateState(state)
            return
        }
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Critical: NOT_TOUCHABLE allows touch pass-through
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        
        glowView = EdgeGlowView(context).apply {
            setState(state)
        }
        
        windowManager.addView(glowView, params)
        startPulseAnimation()
    }
    
    private fun startPulseAnimation() {
        pulseAnimator = ValueAnimator.ofFloat(0.3f, 0.5f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                (glowView as? EdgeGlowView)?.setGlowAlpha(animator.animatedValue as Float)
            }
            start()
        }
    }
}
```

### Integration Points

1. **AgentService**: Controls glow lifecycle
   ```kotlin
   // In AgentService
   private val edgeGlowManager = EdgeGlowManager(this)
   
   session.events.collect { event ->
       when (event) {
           is AgentEvent.TaskStarted -> edgeGlowManager.show(GlowState.Active)
           is AgentEvent.ActionExecuted -> edgeGlowManager.updateState(
               if (event.success) GlowState.Active else GlowState.Error
           )
           is AgentEvent.TaskCompleted -> {
               edgeGlowManager.updateState(GlowState.Success)
               delay(2000)
               edgeGlowManager.hide()
           }
           is AgentEvent.SessionError -> edgeGlowManager.updateState(GlowState.Error)
       }
   }
   ```

2. **SmartCapsuleManager**: Coordinate with capsule state
   - Glow and capsule should show/hide together
   - Consider combining into unified `AgentOverlayManager`

### File Structure

```
ui/overlay/
├── SmartCapsuleManager.kt    # Existing
├── EdgeGlowManager.kt        # New
├── EdgeGlowView.kt           # New (custom view)
└── model/
    └── GlowState.kt          # New (state enum)
```

## Edge Cases

1. **Multiple overlays**: Glow should render below SmartCapsule (lower z-order)
2. **Notch/Cutout**: Glow should respect display cutouts
3. **Landscape mode**: Glow should adapt to all edges
4. **Performance**: Use hardware acceleration, minimize overdraw
5. **Battery**: Pause animation when screen off (use lifecycle)

## UX Considerations

### When to Show Glow

| Scenario | Show Glow? |
|----------|------------|
| Task started, thinking | Yes (Active) |
| Executing click/swipe | Yes (Executing) |
| Waiting for API response | Yes (Active, slower pulse) |
| Task completed | Yes → Fade (Success) |
| User paused | Yes (Paused, no pulse) |
| Agent idle, no task | No |

### User Control

- **Settings option**: "Show edge glow during agent actions"
- Default: ON
- Accessible via Settings sheet

## Alternatives Considered

1. **Status bar tint**: Less visible, conflicts with system UI
2. **Full screen overlay**: Too intrusive, blocks interaction
3. **Notification LED**: Not available on all devices
4. **Sound/Haptic**: Complementary, not visual

## Implementation Priority

**P1** - Core glow functionality:
- Basic edge glow view
- Active state only
- Integration with AgentService

**P2** - Polish:
- All state colors
- Pulse animation
- Settings toggle

**P3** - Advanced:
- Per-edge brightness based on action location
- Particle effects for special events

## References

- [WindowManager overlay guide](https://developer.android.com/reference/android/view/WindowManager.LayoutParams)
- [BlurMaskFilter](https://developer.android.com/reference/android/graphics/BlurMaskFilter)
- [ValueAnimator](https://developer.android.com/guide/topics/graphics/prop-animation)
