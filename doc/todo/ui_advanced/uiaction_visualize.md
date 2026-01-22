# UI Action Visualization

> Design document for visualizing click, swipe, and other touch actions on screen.

## Overview

When the agent performs touch actions (click, swipe, scroll), the user sees nothing happening - the screen just changes. This is confusing and feels "magical" in a bad way. Users benefit from seeing *where* and *how* the agent is interacting with the screen.

## Motivation

**Problem**: 
- Text input is visible (keyboard appears, text shows)
- Touch actions are invisible (no visual feedback)
- Users feel disconnected: "What just happened?"
- Debugging is hard: "Did it click the right element?"

**Solution**: Visualize touch actions with:
- Ripple effect for taps/clicks
- Trail animation for swipes/scrolls
- Brief, non-intrusive, elegant

## Visual Design

### Click/Tap Visualization

```
Frame 0ms        Frame 100ms      Frame 200ms      Frame 300ms
    *               ○                 ◯                (fade)
   (tap)        (ripple)         (expand)          (disappear)
```

**Specifications**:
| Property | Value |
|----------|-------|
| Initial dot | 8dp radius |
| Final ripple | 48dp radius |
| Duration | 300ms |
| Color | Primary Blue (#2563EB) at 60% opacity |
| Animation | EaseOut (fast start, slow end) |

### Swipe Visualization

```
Start           During           End
  ●              ●─────          ●────────────○
 (dot)          (line)          (line + end dot)
```

**Specifications**:
| Property | Value |
|----------|-------|
| Line width | 4dp |
| Start dot | 8dp radius |
| End dot | 6dp radius |
| Color | Primary Blue (#2563EB) at 50% opacity |
| Duration | Match gesture duration + 200ms fade |
| Animation | Draw line as gesture progresses |

### Scroll Visualization

Similar to swipe, but with directional arrow indicator:

```
Scroll Down          Scroll Up
    ●                    ○
    │                    │
    │                    │
    ▼                    ▲
    ○                    ●
```

### Color by Action Type

| Action | Color | Opacity |
|--------|-------|---------|
| Click | `#2563EB` (Blue) | 60% |
| Swipe | `#3B82F6` (Light Blue) | 50% |
| Scroll | `#6366F1` (Indigo) | 50% |
| Long Press | `#7C3AED` (Purple) | 60% |

## Technical Design

### Architecture

```
ActionVisualizerManager
├── showClick(x: Float, y: Float)
├── showSwipe(startX, startY, endX, endY, durationMs)
├── showScroll(direction: ScrollDirection)
└── dispose()

Internal:
├── ClickRippleView - Expanding circle animation
├── SwipeTrailView - Line drawing animation
└── VisualizerOverlay - Container for all visualizations
```

### Key Insight: Hook into AccessibilityPlatform

The ideal integration point is `AccessibilityPlatform.kt`, right before dispatching gestures:

```kotlin
// AccessibilityPlatform.kt

class AccessibilityPlatform(
    private val service: AccessibilityService,
    private val visualizer: ActionVisualizerManager? = null  // Optional
) : AndroidPlatform {
    
    private suspend fun performTap(x: Float, y: Float): ActionResult {
        // Show visualization BEFORE the action
        visualizer?.showClick(x, y)
        
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, DEFAULT_GESTURE_DURATION_MS))
            .build()
        
        return dispatchGesture(gesture)
    }
    
    private suspend fun performSwipeGesture(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long
    ): ActionResult {
        // Show visualization BEFORE the action
        visualizer?.showSwipe(startX, startY, endX, endY, durationMs)
        
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        // ... rest of implementation
    }
}
```

### Implementation

#### ActionVisualizerManager

```kotlin
class ActionVisualizerManager(
    private val context: AccessibilityService
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    
    // Reusable overlay - stays added, children come and go
    private var overlayContainer: FrameLayout? = null
    
    fun showClick(x: Float, y: Float) {
        ensureOverlay()
        val ripple = ClickRippleView(context).apply {
            setPosition(x, y)
        }
        addAndAnimate(ripple, duration = 300)
    }
    
    fun showSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) {
        ensureOverlay()
        val trail = SwipeTrailView(context).apply {
            setPath(startX, startY, endX, endY)
        }
        addAndAnimate(trail, duration = durationMs + 200)
    }
    
    private fun ensureOverlay() {
        if (overlayContainer != null) return
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or  // Pass-through!
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        
        overlayContainer = FrameLayout(context)
        windowManager.addView(overlayContainer, params)
    }
    
    private fun addAndAnimate(view: View, duration: Long) {
        overlayContainer?.addView(view)
        
        // Animate, then remove
        view.animate()
            .alpha(0f)
            .setDuration(200)
            .setStartDelay(duration - 200)
            .withEndAction {
                overlayContainer?.removeView(view)
            }
            .start()
    }
}
```

#### ClickRippleView

```kotlin
class ClickRippleView(context: Context) : View(context) {
    private var centerX = 0f
    private var centerY = 0f
    
    private var currentRadius = 8f.dp
    private val maxRadius = 48f.dp
    
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2563EB")
        style = Paint.Style.FILL
        alpha = (255 * 0.6).toInt()
    }
    
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 300
        interpolator = DecelerateInterpolator()
        addUpdateListener { 
            val progress = it.animatedValue as Float
            currentRadius = 8f.dp + (maxRadius - 8f.dp) * progress
            paint.alpha = ((1f - progress * 0.7f) * 255 * 0.6f).toInt()
            invalidate()
        }
    }
    
    fun setPosition(x: Float, y: Float) {
        centerX = x
        centerY = y
        animator.start()
    }
    
    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(centerX, centerY, currentRadius, paint)
    }
}
```

#### SwipeTrailView

```kotlin
class SwipeTrailView(context: Context) : View(context) {
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    
    private var progress = 0f
    
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B82F6")
        style = Paint.Style.STROKE
        strokeWidth = 4f.dp
        strokeCap = Paint.Cap.ROUND
        alpha = (255 * 0.5).toInt()
    }
    
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B82F6")
        style = Paint.Style.FILL
        alpha = (255 * 0.6).toInt()
    }
    
    fun setPath(sx: Float, sy: Float, ex: Float, ey: Float, durationMs: Long) {
        startX = sx; startY = sy
        endX = ex; endY = ey
        
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        // Draw start dot
        canvas.drawCircle(startX, startY, 8f.dp, dotPaint)
        
        // Draw line up to current progress
        val currentX = startX + (endX - startX) * progress
        val currentY = startY + (endY - startY) * progress
        canvas.drawLine(startX, startY, currentX, currentY, linePaint)
        
        // Draw end dot at current position
        if (progress > 0.1f) {
            canvas.drawCircle(currentX, currentY, 6f.dp, dotPaint)
        }
    }
}
```

### Integration Flow

```
User sends task
        │
        ▼
Agent determines action (e.g., Click element 5)
        │
        ▼
AccessibilityPlatform.performAction(Click(5))
        │
        ├──► visualizer.showClick(x, y)    ← Visualization appears
        │
        └──► dispatchGesture(tapGesture)   ← Action executes
        │
        ▼
    ActionResult
```

### File Structure

```
ui/overlay/
├── SmartCapsuleManager.kt          # Existing
├── EdgeGlowManager.kt              # From glow design
├── visualizer/
│   ├── ActionVisualizerManager.kt  # New - orchestrator
│   ├── ClickRippleView.kt          # New - tap effect
│   ├── SwipeTrailView.kt           # New - swipe effect
│   └── ScrollIndicatorView.kt      # New - scroll arrows
```

### Platform Integration

Modify `AccessibilityPlatform` constructor to accept optional visualizer:

```kotlin
// AgentService.kt
val visualizer = ActionVisualizerManager(this)
val platform = AccessibilityPlatform(this, visualizer)

// Or via dependency injection / service locator pattern
```

## Performance Considerations

1. **Overlay reuse**: Keep single container overlay, add/remove child views
2. **Hardware acceleration**: Use `setLayerType(LAYER_TYPE_HARDWARE, null)`
3. **Short-lived views**: Remove immediately after animation
4. **Avoid overdraw**: Use `TRANSLUCENT` pixel format, minimal fills

## Edge Cases

1. **Rapid successive clicks**: Queue animations, show all
2. **Very long swipes**: Cap line length, use dashed line for overflow
3. **Gestures near screen edges**: Ensure full ripple visible (clamp if needed)
4. **Multi-touch**: Not supported by agent (single action at a time)

## UX Considerations

### Timing

- Visualization should appear **before or simultaneously** with action
- Should **not delay** action execution
- Fade-out should feel natural, not abrupt

### Visibility vs. Intrusiveness

- Large enough to see at a glance
- Transparent enough to not obscure content
- Short duration (300-500ms max)

### User Control

Settings option: "Show touch visualization"
- Default: ON
- Useful to disable for screen recording or presentations

## Future Enhancements

### P1 - Core
- [x] Click ripple
- [x] Swipe trail
- [ ] Integration with AccessibilityPlatform

### P2 - Polish
- [ ] Scroll direction indicators
- [ ] Long press pulsing effect
- [ ] Element highlight box (show bounding box of target element)

### P3 - Advanced
- [ ] Recording mode (persist visualizations longer)
- [ ] Heatmap (aggregate click locations over session)
- [ ] Path prediction (show where swipe will go)

## Alternatives Considered

1. **Screenshot overlay with highlight**: Too slow, requires image processing
2. **System touch pointer (Developer Options)**: Requires manual enable, not controllable
3. **Accessibility highlight service**: Only shows focus, not arbitrary coordinates
4. **Post-action highlight**: Less useful than pre/during action

## Testing Plan

1. **Visual verification**: Record screen during agent session
2. **Performance**: Profile frame rate during animations
3. **Edge cases**: Test rapid clicks, long swipes, corner positions
4. **Integration**: Verify visualization doesn't interfere with actual gestures

## References

- [GestureDescription](https://developer.android.com/reference/android/accessibilityservice/GestureDescription)
- [ValueAnimator](https://developer.android.com/guide/topics/graphics/prop-animation)
- [Canvas drawing](https://developer.android.com/training/custom-views/custom-drawing)
