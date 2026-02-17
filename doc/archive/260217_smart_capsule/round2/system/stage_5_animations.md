# Stage 5 — State Transition Animations

**Status**: IMPLEMENTED
**Depends on**: Stage 4 (expanded layout must exist for expand/collapse animation)
**UX Reference**: `round2/ux_design_round2.md` §6

---

## Goal

Add minimal, tasteful animations to capsule state transitions. Make it feel native without being flashy.

---

## Phase 1: Height Animation (Expand/Collapse)

### 1.1 When

- **Expand**: Running/Takeover → WaitingForInput or WaitingForAction
- **Collapse**: WaitingFor* → Running (on response/resume)

### 1.2 Implementation

Use `ValueAnimator` on the overlay's `WindowManager.LayoutParams.height`:

```kotlin
// SmartCapsuleRenderer.kt or a new SmartCapsuleAnimator.kt

fun animateHeight(
    container: ViewGroup,
    windowManager: WindowManager,
    fromHeight: Int,
    toHeight: Int,
    duration: Long = 250,
    onEnd: (() -> Unit)? = null
) {
    ValueAnimator.ofInt(fromHeight, toHeight).apply {
        this.duration = duration
        interpolator = DecelerateInterpolator()
        addUpdateListener { anim ->
            val params = container.layoutParams as? WindowManager.LayoutParams ?: return@addUpdateListener
            params.height = anim.animatedValue as Int
            try {
                windowManager.updateViewLayout(container, params)
            } catch (_: Exception) {}
        }
        doOnEnd { onEnd?.invoke() }
        start()
    }
}
```

**Problem**: The overlay uses `WRAP_CONTENT` height. We can't animate from one WRAP_CONTENT to another — we need actual pixel values.

**Solution**: Measure the target height before animating:
1. Set new content (expanded/compact) with `visibility = INVISIBLE`
2. Measure: `container.measure(...)` → get `measuredHeight`
3. Set `params.height` to current height (pixel value)
4. Animate from current to measured target
5. On end: set `params.height = WRAP_CONTENT` and update layout

```kotlin
fun animateExpand(container: ViewGroup, windowManager: WindowManager, prepareContent: () -> Unit) {
    val currentHeight = container.height
    prepareContent()  // Set expanded content but hidden
    container.measure(
        View.MeasureSpec.makeMeasureSpec(container.width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    )
    val targetHeight = container.measuredHeight
    animateHeight(container, windowManager, currentHeight, targetHeight, 250) {
        val params = container.layoutParams as? WindowManager.LayoutParams ?: return@animateHeight
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        try { windowManager.updateViewLayout(container, params) } catch (_: Exception) {}
    }
}
```

### 1.3 Integration with Renderer

In `renderWaitingForInput` and `renderWaitingForAction`:
- If transitioning from a compact mode (Running, Takeover): animate expand
- If already in an expanded mode: skip animation (just update content)

In `renderRunning` (after WaitingFor*):
- Animate collapse

Track previous mode to know when to animate:
```kotlin
// SmartCapsuleManager
private var previousMode: CapsuleMode = CapsuleMode.Hidden
```

---

## Phase 2: Dot Color Crossfade

### 2.1 When

- Running (blue) → TakeoverPending/Takeover (amber)
- Takeover (amber) → Running (blue)

### 2.2 Implementation

Use `ValueAnimator` with `ArgbEvaluator`:

```kotlin
fun crossfadeDotColor(dot: View, fromColor: Int, toColor: Int, duration: Long = 200) {
    ValueAnimator.ofArgb(fromColor, toColor).apply {
        this.duration = duration
        addUpdateListener { anim ->
            (dot.background as? GradientDrawable)?.setColor(anim.animatedValue as Int)
        }
        start()
    }
}
```

Integrate into `setDotColor()`:
```kotlin
fun setDotColor(v: CapsuleViews, color: Int, pulsing: Boolean, animate: Boolean = false) {
    if (animate) {
        val currentColor = // get from drawable
        crossfadeDotColor(v.statusDot, currentColor, color)
    } else {
        (v.statusDot.background as? GradientDrawable)?.setColor(color)
    }
    if (pulsing) startPulse(v.statusDot) else stopPulse()
}
```

Pass `animate = true` when transitioning between Running ↔ Takeover states.

---

## Phase 3: Done → Hidden Fade + Slide

### 3.1 When

Done state auto-hides after 3 seconds. Currently just removes the view.

### 3.2 Implementation

Replace the `delayedHideRunnable` in `renderDone` with a slide-down + fade-out:

```kotlin
delayedHideRunnable = Runnable {
    val container = overlayView ?: return@Runnable
    val slideDown = ObjectAnimator.ofFloat(container, "translationY", 0f, dp(16f))
    val fadeOut = ObjectAnimator.ofFloat(container, "alpha", 1f, 0f)
    AnimatorSet().apply {
        playTogether(slideDown, fadeOut)
        duration = 300
        interpolator = AccelerateInterpolator()
        doOnEnd {
            container.translationY = 0f
            container.alpha = 1f
            hide()
        }
        start()
    }
}.also { handler.postDelayed(it, 3000) }
```

Reset `translationY` and `alpha` in `show()` to avoid stale state.

---

## Phase 4: Content Fade

### 4.1 When

- Entering/exiting SupplementInput: row2 content swaps between buttons and input
- Entering WaitingFor*: expandedBody fades in

### 4.2 Implementation

Simple alpha animation:

```kotlin
fun fadeIn(view: View, duration: Long = 150) {
    view.alpha = 0f
    view.visibility = View.VISIBLE
    view.animate().alpha(1f).setDuration(duration).start()
}

fun fadeOut(view: View, duration: Long = 100, onEnd: (() -> Unit)? = null) {
    view.animate().alpha(0f).setDuration(duration).withEndAction {
        view.visibility = View.GONE
        view.alpha = 1f  // Reset for reuse
        onEnd?.invoke()
    }.start()
}
```

Use in render methods where content appears/disappears. Keep it simple — not every visibility change needs a fade. Only:
- `expandedBody` appearing (WaitingFor* entry)
- `supplementInputArea` appearing (SupplementInput entry)

---

## Architecture Decision: Animator vs SmartCapsuleAnimator

### Option A: Inline in SmartCapsuleRenderer
Pros: Simple, no new file
Cons: Renderer grows, animation logic mixed with layout logic

### Option B: SmartCapsuleAnimator.kt (separate file)
Pros: Clean separation, renderer stays focused on layout
Cons: Another file, coordinator needed

### Decision: Option A (inline)

The animation logic is small (~60 lines). Not worth a separate file. Keep it in the renderer. If it grows beyond 50 lines, extract then.

---

## Files Modified

| File | Change |
|------|--------|
| `SmartCapsuleRenderer.kt` | Add animation methods, integrate into render flow |
| `SmartCapsuleManager.kt` | Track previousMode, pass to renderer, reset translationY/alpha |

---

## Testing

| Test | What |
|------|------|
| Manual: Mode transitions | Verify smooth expand/collapse, no visual glitches |
| Manual: Rapid transitions | Verify animation cancellation on quick mode changes |
| Manual: Done auto-hide | Verify slide-down + fade animation |
| Build: ./gradlew assembleDebug | Must pass |

---

## Cancel Semantics

If a new mode arrives during an animation:
1. Cancel all running animators (`animator.cancel()`)
2. Snap to the new state immediately
3. Start new animation if applicable

This prevents visual artifacts from stale animations.
