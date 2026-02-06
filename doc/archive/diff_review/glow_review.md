# Code Review: Edge Glow Feature

> **Reviewer**: AI Code Review  
> **Date**: 2026-01-22  
> **Branch**: `feature/glow`  
> **Base**: `chat`

---

## 1. Summary

This feature implements an **ambient edge glow effect** that provides visual feedback when the agent is actively controlling the device. The glow appears as a colored border around the screen edges that pulses during activity and changes color based on agent state (active, executing, success, error, paused).

### Files Changed

| File | Change Type | Lines |
|------|-------------|-------|
| `AgentService.kt` | Modified | +60 |
| `EdgeGlowManager.kt` | New | 309 |
| `EdgeGlowView.kt` | New | 170 |
| `model/GlowState.kt` | New | 40 |

### Architecture

```
AgentService
    └── EdgeGlowManager (lifecycle management)
            └── EdgeGlowView (Canvas rendering)
                    └── GlowState (enum: Active, Executing, Success, Error, Paused)
```

The implementation follows the design doc well, using Option A (Custom View with Canvas) and integrating cleanly with the existing event flow.

---

## 2. High-Risk Issues (Must-Fix)

### 2.1 Race Condition in `hide()` + `show()` Sequence

**Why it matters**: If `show()` is called while `hide()`'s fade-out animation is in progress, the code path will call `updateState()` on a view that's about to be removed, potentially causing a crash or visual glitch.

**Location**: `EdgeGlowManager.kt:126-141` and `EdgeGlowManager.kt:87-96`

```kotlin
// In hide():
fun hide() {
    // ...
    animateFadeOut {
        try {
            windowManager.removeView(view)  // Removes view...
        } catch (e: Exception) { ... }
        glowView = null  // ...then nullifies reference
    }
}

// In show():
if (glowView != null) {
    updateState(state)  // BUG: Called on view being removed!
    return
}
```

**Proposed Fix**:

```kotlin
// Add an isHiding flag to track animation state
private var isHiding = false

fun show(state: GlowState = GlowState.Active) {
    cancelPendingHide()
    
    // If currently hiding, cancel the animation and remove immediately
    if (isHiding) {
        stopFadeAnimation()
        glowView?.let { 
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        glowView = null
        isHiding = false
    }
    
    if (glowView != null) {
        updateState(state)
        return
    }
    // ... rest of show()
}

fun hide() {
    if (isHiding) return  // Prevent double-hide
    isHiding = true
    // ...
    animateFadeOut {
        // ...
        isHiding = false
    }
}
```

---

### 2.2 Thread Safety: Unprotected Mutable State

**Why it matters**: `glowView` and `currentState` are accessed from multiple call sites (main thread, animation callbacks, handler runnables) without synchronization. This can cause NullPointerException or stale state reads.

**Location**: `EdgeGlowManager.kt:66-67`

```kotlin
private var glowView: EdgeGlowView? = null
private var currentState: GlowState = GlowState.Active
```

**Proposed Fix**:

```kotlin
@Volatile
private var glowView: EdgeGlowView? = null

// Or better: ensure all access is on main thread
@MainThread
fun show(state: GlowState = GlowState.Active) {
    check(Looper.myLooper() == Looper.getMainLooper()) { 
        "show() must be called on main thread" 
    }
    // ...
}
```

---

### 2.3 Software Layer Performance Impact

**Why it matters**: `LAYER_TYPE_SOFTWARE` disables hardware acceleration for the entire view, which can cause dropped frames during animations on large screens or lower-end devices. The glow covers the entire screen and animates continuously.

**Location**: `EdgeGlowView.kt:56-57`

```kotlin
init {
    // Software layer needed for BlurMaskFilter to work
    setLayerType(LAYER_TYPE_SOFTWARE, null)
}
```

**Proposed Fix**: Use `RenderEffect` on API 31+ with fallback to software layer:

```kotlin
init {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Use hardware-accelerated blur on Android 12+
        setLayerType(LAYER_TYPE_HARDWARE, null)
    } else {
        // Fallback to software layer for BlurMaskFilter
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }
}

override fun onDraw(canvas: Canvas) {
    // For API 31+, apply RenderEffect for blur instead of BlurMaskFilter
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Draw without BlurMaskFilter, apply RenderEffect in onSizeChanged
    } else {
        // Existing BlurMaskFilter code
    }
}
```

**Alternative (simpler)**: Remove `BlurMaskFilter` entirely. The gradient alone provides a reasonable glow effect, and the blur adds minimal visual value for significant performance cost. Test both versions visually.

---

### 2.4 Animator Listener Memory Leak

**Why it matters**: The anonymous `AnimatorListenerAdapter` instances hold implicit references to `EdgeGlowManager`. If the animator is cancelled mid-flight, the listener may not be removed, and the callback closure holds references to `glowView` and `onComplete`.

**Location**: `EdgeGlowManager.kt:265-270`, `EdgeGlowManager.kt:285-290`

```kotlin
addListener(object : android.animation.AnimatorListenerAdapter() {
    override fun onAnimationEnd(animation: android.animation.Animator) {
        onComplete()  // Holds reference to outer scope
    }
})
```

**Proposed Fix**: Remove listener on cancel and check view validity:

```kotlin
private fun animateFadeOut(onComplete: () -> Unit) {
    stopFadeAnimation()
    
    val viewRef = glowView ?: return onComplete()
    val startAlpha = 0.4f
    
    val listener = object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) {
            animation.removeAllListeners()  // Prevent leak
            if (glowView === viewRef) {  // Verify same view
                onComplete()
            }
        }
        override fun onAnimationCancel(animation: android.animation.Animator) {
            animation.removeAllListeners()
        }
    }
    
    fadeAnimator = ValueAnimator.ofFloat(startAlpha, 0f).apply {
        // ...
        addListener(listener)
        start()
    }
}
```

---

## 3. Medium Issues (Should-Fix)

### 3.1 Double State Transition on Task Completion

**Why it matters**: Both `TaskCompleted` and `SessionCompleted` events can fire in quick succession, causing conflicting glow state updates. For example: TaskCompleted sets Success → SessionCompleted (USER_STOPPED) calls hideImmediately(), causing a jarring visual.

**Location**: `AgentService.kt:330-337` and `AgentService.kt:358-372`

```kotlin
is AgentEvent.TaskCompleted -> {
    edgeGlowManager?.updateState(GlowState.Success)  // Sets Success
    // ...
}

is AgentEvent.SessionCompleted -> {
    when (event.reason) {
        CompletionReason.USER_STOPPED -> {
            edgeGlowManager?.hideImmediately()  // Immediately hides!
        }
        // ...
    }
}
```

**Proposed Fix**: Add debouncing or track if a state transition is already pending:

```kotlin
is AgentEvent.SessionCompleted -> {
    // Skip glow update if task just completed (let TaskCompleted handle it)
    // Or: consolidate all completion handling in SessionCompleted only
}
```

---

### 3.2 Magic Number: Base Alpha Hardcoded Multiple Times

**Why it matters**: The value `0.4f` appears in 4 places. If changed in one place but not others, behavior becomes inconsistent.

**Locations**: 
- `EdgeGlowManager.kt:106` - `setGlowAlpha(0f)`... then fades to 0.4
- `EdgeGlowManager.kt:253` - `setGlowAlpha(0.4f)` 
- `EdgeGlowManager.kt:259` - `ValueAnimator.ofFloat(0f, 0.4f)`
- `EdgeGlowManager.kt:277` - `startAlpha = 0.4f`

**Proposed Fix**: Extract to companion object constant:

```kotlin
companion object {
    // ...
    /** Base alpha when glow is fully visible (not pulsing) */
    private const val BASE_ALPHA = 0.4f
}

// Then use BASE_ALPHA everywhere
```

---

### 3.3 updateState() When Not Showing

**Why it matters**: Calling `updateState()` when glow is not visible updates `currentState` but does nothing to `glowView` (it's null). If `show()` is called later with a different state, the internal `currentState` and visual state diverge.

**Location**: `EdgeGlowManager.kt:169-192`

```kotlin
fun updateState(state: GlowState) {
    if (currentState == state) return
    currentState = state  // Updated even if glowView is null!
    
    glowView?.setState(state)  // No-op if null
    // ...
}
```

**Proposed Fix**: Either don't update `currentState` if not showing, or document that `currentState` represents "desired state" not "actual state":

```kotlin
fun updateState(state: GlowState) {
    if (!isShowing()) {
        Log.w(TAG, "updateState called while not showing, ignoring")
        return
    }
    // ...
}
```

---

### 3.4 animateFadeOut Uses Hardcoded startAlpha

**Why it matters**: The fade-out always starts from `0.4f`, but the actual alpha might be different (e.g., mid-pulse at 0.3f or 0.5f). This causes a visible "jump" at the start of fade-out.

**Location**: `EdgeGlowManager.kt:277`

```kotlin
val startAlpha = glowView?.let { 0.4f } ?: return onComplete()
```

**Proposed Fix**: Read actual current alpha from the view:

```kotlin
val startAlpha = glowView?.getCurrentAlpha() ?: return onComplete()

// Add to EdgeGlowView:
fun getCurrentAlpha(): Float = glowAlpha
```

---

### 3.5 Missing Import for `TurnPhase`

**Why it matters**: The code references `TurnPhase.EXECUTION`, `TurnPhase.PLANNING`, etc. but might fail to compile if the enum values don't exist or are named differently.

**Location**: `AgentService.kt:302-307`

```kotlin
when (event.phase) {
    TurnPhase.EXECUTION -> edgeGlowManager?.updateState(GlowState.Executing)
    TurnPhase.PLANNING, TurnPhase.PERCEPTION, TurnPhase.REFLECTION -> 
        edgeGlowManager?.updateState(GlowState.Active)
}
```

**Action**: Verify `TurnPhase` enum exists with these exact values. The import is present (line 18), but cross-check with `protocol/TurnPhase.kt` or wherever it's defined.

---

## 4. Low-Risk Suggestions (Nice-to-Have)

### 4.1 Unused Method: `animateToState()`

**Location**: `EdgeGlowView.kt:88-91`

```kotlin
fun animateToState(state: GlowState) {
    // For now, instant change. Could add ValueAnimator for smooth color transitions.
    setState(state)
}
```

This method is never called. Either implement smooth color transitions or remove the dead code.

---

### 4.2 Add `@UiThread` Annotations

Public methods that must run on UI thread should be annotated for clarity:

```kotlin
@UiThread
fun show(state: GlowState = GlowState.Active) { ... }

@UiThread
fun hide() { ... }
```

---

### 4.3 Consider Sealed Class for `GlowState`

The current enum works, but a sealed class would allow future expansion (e.g., custom colors, animation parameters):

```kotlin
sealed class GlowState(val colorHex: Int) {
    object Active : GlowState(0xFF2563EB.toInt())
    object Executing : GlowState(0xFF3B82F6.toInt())
    object Success : GlowState(0xFF0D9488.toInt())
    object Error : GlowState(0xFFDC2626.toInt())
    object Paused : GlowState(0xFFF59E0B.toInt())
    data class Custom(val color: Int) : GlowState(color)
}
```

---

### 4.4 Z-Order Documentation

The comment in `AgentService.kt:109` says EdgeGlowManager should be initialized first for z-order, but this relies on WindowManager implementation details.

**Suggestion**: Add explicit z-ordering if available, or document the assumption:

```kotlin
// NOTE: WindowManager stacks overlays in add order (first added = bottom).
// EdgeGlowManager must be initialized before SmartCapsuleManager to render below it.
edgeGlowManager = EdgeGlowManager(context = this)
```

---

### 4.5 Settings Toggle (Future Work)

The design doc mentions a Settings option to disable edge glow. Consider adding a stub:

```kotlin
class EdgeGlowManager(
    private val context: AccessibilityService,
    private val enabled: Boolean = true  // Future: read from preferences
) {
    fun show(state: GlowState = GlowState.Active) {
        if (!enabled) return
        // ...
    }
}
```

---

## 5. Questions for Author

1. **Performance Testing**: Has the pulse animation been tested on low-end devices? The software layer + continuous invalidation could cause battery drain.

2. **Display Cutout Handling**: The code sets `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`, but has it been tested on devices with punch-hole cameras or notches?

3. **Landscape Mode**: Does the glow adapt correctly when the device rotates? The view uses `MATCH_PARENT` but the gradient directions are hardcoded.

4. **Accessibility**: Should there be content descriptions or TalkBack announcements when glow state changes? ("Agent is now executing an action")

---

## 6. Recommended Fix Priority

| Priority | Issue | Estimated Effort |
|----------|-------|------------------|
| P0 | 2.1 Race condition in hide()/show() | 30 min |
| P0 | 2.2 Thread safety | 15 min |
| P1 | 2.3 Software layer performance | 1-2 hrs |
| P1 | 2.4 Animator listener leak | 30 min |
| P2 | 3.1 Double state transition | 30 min |
| P2 | 3.2 Magic number extraction | 10 min |
| P2 | 3.4 Fade-out start alpha | 15 min |

---

## 7. Overall Assessment

The implementation is **solid and follows the design doc well**. The code is readable, well-documented, and integrates cleanly with the existing architecture. The main concerns are around race conditions and performance:

- **Strengths**: Clean separation of concerns, good state management, proper lifecycle handling, comprehensive state coverage.
- **Weaknesses**: Thread safety, animation edge cases, software layer performance.

**Recommendation**: Fix P0 issues before merging. P1 issues should be addressed in a follow-up PR if time-constrained.

---

*Review generated following `sop/diff_review.md` guidelines.*
