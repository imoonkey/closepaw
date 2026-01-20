# Platform & Perception Review

> **Module**: `platform/`, `data/perception/`, `domain/models/`
> **Reviewer**: Claude
> **Date**: January 19, 2026

## Summary

The Platform layer abstracts Android-specific operations:
- `AndroidPlatform`: Interface for screen capture and action execution
- `AccessibilityPlatform`: Implementation using AccessibilityService APIs
- `UIAction`: Platform-agnostic action types
- `Perceptor`: Converts accessibility tree to semantic ScreenSnapshot

---

## High-Risk Issues (Must Fix)

### H1. AccessibilityNodeInfo Lifecycle Not Managed
**Location**: `Perceptor.kt:35-42`, `AccessibilityPlatform.kt:32-38`

**Problem**: AccessibilityNodeInfo objects have strict lifecycle requirements:
1. They must be recycled via `recycle()` after use
2. They become invalid after the UI changes

The code keeps references in `ScreenSnapshot.rawMap`:
```kotlin
data class ScreenSnapshot(
    val timestamp: Long,
    val rootOriginal: AccessibilityNodeInfo?,  // Kept reference!
    val elements: List<PerceptionElement>,
    val rawMap: Map<Int, AccessibilityNodeInfo>  // Many kept references!
)
```

These are stored but never recycled. Even worse, `Perceptor.snapshot()` comments:
```kotlin
rootOriginal = root,  // Warning: Keeping root might cause memory leaks if held too long
```

**Impact**: 
- Memory leaks (AccessibilityNodeInfo objects accumulate)
- Stale node errors when trying to interact with recycled nodes
- Crashes from accessing invalid nodes after UI changes

**Fix**: 
```kotlin
// Option 1: Don't store nodes at all, use indices to re-fetch
data class ScreenSnapshot(
    val timestamp: Long,
    val elements: List<PerceptionElement>,
    // Remove rawMap and rootOriginal
)

// When executing action, re-traverse to find node by index
suspend fun performClick(elementIndex: Int): ActionResult {
    val root = service.rootInActiveWindow ?: return ActionResult.Failure("No root")
    try {
        val node = findNodeAtIndex(root, elementIndex) 
            ?: return ActionResult.ElementNotFound(elementIndex)
        return clickNode(node)
    } finally {
        root.recycle()  // Always recycle!
    }
}

// Option 2: Clone essential data, don't keep node references
data class ClickableElement(
    val index: Int,
    val bounds: Rect,
    val isClickable: Boolean,
    val text: String
)
// Store ClickableElement instead of AccessibilityNodeInfo
```

---

### H2. performType() May Double-Click Before Setting Text
**Location**: `AccessibilityPlatform.kt:107-133`

**Problem**: If initial ACTION_SET_TEXT fails, the code clicks to focus then retries:
```kotlin
val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

return if (result) {
    ActionResult.Success("Text entered: ${action.text}")
} else {
    // Try clicking first to focus, then set text
    clickNode(node)  // This may navigate away!
    val retryResult = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    // ...
}
```

Issues:
1. `clickNode(node)` might navigate away from the text field (if it's a button that looks like a text field)
2. No delay between click and set text - the click might not have completed
3. The `node` reference may become stale after clicking

**Impact**: Text may be entered in wrong field or lost entirely.

**Fix**:
```kotlin
return if (result) {
    ActionResult.Success("Text entered: ${action.text}")
} else {
    // First try to focus the node
    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    
    // Small delay for focus to take effect
    kotlinx.coroutines.delay(100)
    
    // Re-try setting text
    val retryResult = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    
    if (retryResult) {
        ActionResult.Success("Text entered after focus: ${action.text}")
    } else {
        ActionResult.Failure("Failed to set text - element may not be editable")
    }
}
```

---

### H3. Scroll Gesture May Trigger System Actions
**Location**: `AccessibilityPlatform.kt:135-171`

**Problem**: Scroll coordinates are calculated to avoid system gesture zones:
```kotlin
// Avoid top 0.35 (status bar pull-down zone) and bottom 0.15 (nav gestures)
val (startY, endY) = when (action.direction) {
    ScrollDirection.DOWN -> {
        display.heightPixels * 0.75f to display.heightPixels * 0.35f
    }
    // ...
}
```

But 0.35 of screen height as "safe zone" is arbitrary and may not be correct for all devices:
- Tablets have different system gesture areas
- Some phones have notches that affect safe zones
- Gesture navigation vs 3-button navigation have different zones
- Landscape mode has different safe zones

**Impact**: Scroll may trigger notification shade, navigation gestures, or other system actions.

**Fix**: Use WindowInsets API to get actual system gesture exclusion zones:
```kotlin
@RequiresApi(Build.VERSION_CODES.Q)
private fun getSafeScrollBounds(): Rect {
    val windowInsets = service.rootInActiveWindow?.extras?.getParcelable<WindowInsets>("android:windowInsets")
    val gestureInsets = windowInsets?.systemGestureInsets ?: Insets.NONE
    
    val display = getDisplayInfo()
    return Rect(
        gestureInsets.left,
        gestureInsets.top + 50,  // Extra margin for status bar
        display.widthPixels - gestureInsets.right,
        display.heightPixels - gestureInsets.bottom - 50  // Extra margin
    )
}

// Fallback for older APIs
private fun getSafeScrollBoundsFallback(): Rect {
    val display = getDisplayInfo()
    val statusBarHeight = 100  // Typical status bar
    val navBarHeight = 150     // Typical nav bar
    
    return Rect(
        0,
        statusBarHeight,
        display.widthPixels,
        display.heightPixels - navBarHeight
    )
}
```

---

### H4. dispatchGesture() May Not Resume Continuation
**Location**: `AccessibilityPlatform.kt:241-261`

**Problem**: The gesture callback has paths that don't resume the continuation:
```kotlin
private suspend fun dispatchGesture(gesture: GestureDescription): ActionResult {
    return suspendCancellableCoroutine { continuation ->
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (continuation.isActive) {
                    continuation.resume(ActionResult.Success("Gesture completed"))
                }
            }
            
            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (continuation.isActive) {
                    continuation.resume(ActionResult.Cancelled("Gesture cancelled"))
                }
            }
        }
        
        val dispatched = service.dispatchGesture(gesture, callback, null)
        if (!dispatched) {
            continuation.resume(ActionResult.Failure("Failed to dispatch gesture"))
        }
        // What if onCompleted/onCancelled is never called?
    }
}
```

If `dispatchGesture` returns true but the callback is never invoked (edge case in some devices/situations), the coroutine hangs forever.

**Impact**: Agent hangs indefinitely.

**Fix**: Add timeout:
```kotlin
private suspend fun dispatchGesture(gesture: GestureDescription): ActionResult {
    return withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            // ... existing code
        }
    } ?: ActionResult.Failure("Gesture timed out")
}

companion object {
    private const val GESTURE_TIMEOUT_MS = 5000L
}
```

---

### H5. Perceptor MAX_ELEMENTS May Miss Critical Elements
**Location**: `Perceptor.kt:16`, `Perceptor.kt:31`

**Problem**: Elements are collected via DFS traversal and capped at 80:
```kotlin
private const val MAX_ELEMENTS = 80

// ...
traverse(root, elements, nodeMap)
val limitedElements = elements.take(MAX_ELEMENTS)
```

DFS means the cap might exclude critical elements if they appear later in the tree:
- A crucial button at the bottom of a long list
- A submit button that renders after form fields
- Dialog buttons (often rendered as overlay children late in traversal)

**Impact**: Agent may not see actionable elements, causing failure or wrong actions.

**Fix**: 
```kotlin
// Option 1: Prioritize interactive elements
private fun traverse(
    node: AccessibilityNodeInfo,
    interactive: MutableList<PerceptionElement>,
    nonInteractive: MutableList<PerceptionElement>,
    nodeMap: MutableMap<Int, AccessibilityNodeInfo>
) {
    // ... check element
    if (clickable || editable || scrollable) {
        interactive.add(element)
    } else if (text.isNotBlank() || desc.isNotBlank()) {
        nonInteractive.add(element)
    }
    // ... traverse children
}

fun snapshot(root: AccessibilityNodeInfo?): ScreenSnapshot {
    val interactive = mutableListOf<PerceptionElement>()
    val nonInteractive = mutableListOf<PerceptionElement>()
    
    traverse(root, interactive, nonInteractive, nodeMap)
    
    // Prioritize interactive elements
    val elements = (interactive.take(60) + nonInteractive.take(20))
        .mapIndexed { index, elem -> elem.copy(index = index) }
    // ...
}
```

---

## Medium Issues (Should Fix)

### M1. PerceptionElement.bounds and center Use IntArray
**Location**: `Models.kt:46-57`

**Problem**: Using `IntArray` for bounds/center loses type safety:
```kotlin
data class PerceptionElement(
    // ...
    val bounds: IntArray,  // [left, top, right, bottom]?
    val center: IntArray   // [x, y]?
)
```

No compile-time enforcement of array size. Callers must know the convention.

**Impact**: Runtime errors if array access is wrong (e.g., `bounds[4]`).

**Fix**: Use named data classes:
```kotlin
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class Point(val x: Int, val y: Int)

data class PerceptionElement(
    // ...
    val bounds: Bounds,
    val center: Point
)
```

---

### M2. ScreenSnapshot Equality Broken by IntArray
**Location**: `Models.kt:39-44`

**Problem**: `ScreenSnapshot` contains `IntArray` in elements, which uses reference equality. Data class generated `equals()` won't work correctly.

```kotlin
val snap1 = ScreenSnapshot(...)
val snap2 = ScreenSnapshot(...)  // Same content
snap1 == snap2  // May be false due to IntArray reference comparison
```

**Impact**: Caching, deduplication, or comparison of snapshots will fail.

**Fix**: Use List<Int> instead of IntArray, or override equals/hashCode.

---

### M3. UIAction.ClickAt Not Used
**Location**: `UIAction.kt:21-25`

**Problem**: `UIAction.ClickAt` is defined but no tool uses it. All click tools use `UIAction.Click(elementIndex)`.

**Impact**: Dead code.

**Fix**: Either add a coordinate-based click tool or remove `ClickAt`:
```kotlin
// Option 1: Add tool
class ClickAtTool : BaseTool() {
    override val name = "click_at"
    // ...
}

// Option 2: Remove from UIAction if truly unused
```

---

### M4. hasRequiredPermissions() Always Returns True When Service Running
**Location**: `AccessibilityPlatform.kt:53-55`

**Problem**: Permission check just checks if service is connected:
```kotlin
override fun hasRequiredPermissions(): Boolean {
    return service.serviceInfo != null
}
```

This doesn't check for:
- SYSTEM_ALERT_WINDOW (overlay permission)
- Root access (if needed)
- Specific accessibility capabilities

**Impact**: May report permissions OK when overlay permission is missing.

**Fix**:
```kotlin
override fun hasRequiredPermissions(): Boolean {
    return service.serviceInfo != null &&
           Settings.canDrawOverlays(service)  // Check overlay permission
}
```

---

### M5. Perceptor.normalizeWhitespace() Removes Intentional Spacing
**Location**: `Perceptor.kt:119-121`

**Problem**: Whitespace normalization:
```kotlin
private fun String.normalizeWhitespace(): String {
    return this.replace(Regex("\\s+"), " ").trim()
}
```

This converts "Price:  $99" to "Price: $99" (double space to single). Also removes leading/trailing newlines that might be meaningful.

**Impact**: Subtle text differences may affect LLM understanding.

**Fix**: Be more conservative:
```kotlin
private fun String.normalizeWhitespace(): String {
    // Only collapse multiple spaces, preserve single newlines
    return this
        .replace(Regex("[ \\t]+"), " ")  // Collapse horizontal whitespace
        .replace(Regex("\\n{3,}"), "\n\n")  // Limit consecutive newlines
        .trim()
}
```

---

### M6. ValidationOutcome, AgentAction Unused
**Location**: `Models.kt:6-35`, `Models.kt:59`

**Problem**: Several model classes appear unused:
- `AgentAction` sealed class (not used anywhere)
- `ValidationOutcome` sealed class (not used anywhere)
- `ManagerResult` data class (not used anywhere)

These seem to be V1 remnants.

**Impact**: Dead code confuses readers.

**Fix**: Remove unused classes or mark as deprecated:
```kotlin
@Deprecated("V1 artifact, will be removed")
sealed class AgentAction { ... }
```

---

## Low-Risk Suggestions (Nice to Have)

### L1. ActionResult Could Include Timing
**Location**: `ActionResult.kt`

Adding execution duration helps debug slow actions:
```kotlin
data class Success(
    val message: String = "Action completed",
    val durationMs: Long = 0
) : ActionResult
```

---

### L2. Perceptor Could Be Injectable
**Location**: `Perceptor.kt:14`

`Perceptor` is an object (singleton) which makes testing harder. Consider interface + implementation:
```kotlin
interface ScreenPerceptor {
    fun snapshot(root: AccessibilityNodeInfo?): ScreenSnapshot
    fun toPromptJson(snapshot: ScreenSnapshot): String
}

class DefaultPerceptor : ScreenPerceptor { ... }
```

---

### L3. SystemButtonType Missing POWER
**Location**: `UIAction.kt:80-84`

`POWER` button (screen off/on) might be useful:
```kotlin
enum class SystemButtonType {
    BACK,
    HOME,
    RECENTS,
    POWER  // AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
}
```

---

### L4. DisplayInfo Could Include Orientation
**Location**: `AndroidPlatform.kt:58-62`

Adding orientation helps tools adjust for landscape:
```kotlin
data class DisplayInfo(
    val widthPixels: Int,
    val heightPixels: Int,
    val density: Float,
    val orientation: Int  // Configuration.ORIENTATION_*
)
```

---

## Questions

1. **Node caching**: Is there ever a case where keeping AccessibilityNodeInfo references is intentional for performance? The current approach seems designed for single-use.

2. **Multi-window support**: How does the system handle split-screen or floating windows? `service.rootInActiveWindow` only gets one window.

3. **Accessibility service stability**: What happens if the AccessibilityService is unbound mid-operation? Is there error handling for this scenario?
