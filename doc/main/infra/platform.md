# Platform Abstraction

> AndroidPlatform, Perceptor, and screen perception.
> Last updated: 2026-02-08 (commit: a475ef9aacefa7da5ac84bfb0a09a48ce29776d9)

## AndroidPlatform

→ See: `platform/AndroidPlatform.kt`

Abstraction for Android-specific operations.

### Interface

```kotlin
interface AndroidPlatform {
    suspend fun captureScreen(): ScreenSnapshot
    suspend fun performAction(action: UIAction): ActionResult
    suspend fun launchApp(packageName: String): ActionResult
    suspend fun getInstalledApps(): List<AppInfo>
    fun hasRequiredPermissions(): Boolean
    fun getCurrentPackageName(): String?
    fun getDisplayInfo(): DisplayInfo
}
```

Note: `performAction` takes only a `UIAction` — no snapshot parameter. All atomic actions work with coordinates or focused state; element resolution happens in the executor layer.

### Operations

| Method | Purpose |
|--------|---------|
| `captureScreen()` | Get current UI state as `ScreenSnapshot` |
| `performAction()` | Execute a single atomic UI action |
| `launchApp()` | Launch app by package name |
| `getInstalledApps()` | Query launchable apps |
| `hasRequiredPermissions()` | Check accessibility permission |
| `getCurrentPackageName()` | Get foreground app |
| `getDisplayInfo()` | Screen dimensions |

---

## AccessibilityPlatform

→ See: `platform/AccessibilityPlatform.kt`

Implementation of `AndroidPlatform` using Android Accessibility APIs.

### Atomic Platform Principle

Each `UIAction` variant maps to **exactly one** Android API call. The platform has zero strategy — no fallback, no target resolution, no UI change detection. Those responsibilities live in the executor layer.

→ See: [tools.md](tools.md) for the executor architecture.

### Action Dispatch

```kotlin
override suspend fun performAction(action: UIAction): ActionResult = when (action) {
    is UIAction.ClickNodeAt     -> performNodeClickAt(action.x, action.y)
    is UIAction.TapAt           -> performTap(...)
    is UIAction.LongClickNodeAt -> performNodeLongClickAt(action.x, action.y)
    is UIAction.LongPressAt     -> performLongPressGesture(...)
    is UIAction.SetTextOnNodeAt -> performSetTextOnNodeAt(...)
    is UIAction.SetTextOnFocused -> performSetTextOnFocused(...)
    is UIAction.Swipe           -> performSwipe(action)
    is UIAction.SystemButton    -> performSystemButton(action)
    is UIAction.Wait            -> performWait(action)
}
```

### Action Visualization Integration

Can integrate with `ActionVisualizerManager` to show visual feedback:

```kotlin
class AccessibilityPlatform(
    private val service: AccessibilityService,
    private val visualizer: ActionVisualizerManager? = null
) {
    private suspend fun performTap(x: Float, y: Float): ActionResult {
        visualizer?.showClick(x, y)
        // ... dispatch gesture
    }
}
```

---

## UIAction Types

→ See: `platform/UIAction.kt`

Atomic platform operations. Each variant maps to exactly one Android API call.

**Naming convention:**
- `*NodeAt` — accessibility node operation at coordinates (`ACTION_*`)
- `*At` — gesture operation at coordinates (`dispatchGesture`)
- `*OnFocused` — operation on currently focused node

| Action | Type | Description |
|--------|------|-------------|
| `ClickNodeAt(x, y)` | Node | Find clickable node at coords → `ACTION_CLICK` |
| `TapAt(x, y)` | Gesture | Gesture tap at coordinates |
| `LongClickNodeAt(x, y)` | Node | Find node at coords → `ACTION_LONG_CLICK` |
| `LongPressAt(x, y, durationMs)` | Gesture | Gesture hold at coordinates |
| `SetTextOnNodeAt(x, y, text, clear)` | Node | Find node at coords → `ACTION_SET_TEXT` |
| `SetTextOnFocused(text, clear)` | Node | Find focused editable → `ACTION_SET_TEXT` |
| `Swipe(startX, startY, endX, endY, durationMs)` | Gesture | Gesture swipe |
| `SystemButton(button)` | System | Global action (back, home, recents, enter) |
| `Wait(durationMs)` | System | Pause execution |

---

## ActionResult

→ See: `platform/ActionResult.kt`

Simple result of executing an atomic `UIAction`:

```kotlin
sealed interface ActionResult {
    data class Success(val message: String) : ActionResult
    data class Failure(val reason: String) : ActionResult
    data class Cancelled(val reason: String = "Action cancelled") : ActionResult

    fun isSuccess(): Boolean = this is Success
}
```

No `ElementNotFound` — platform doesn't know about elements, returns `Failure("No clickable node at (x,y)")`. No `exception` field — exceptions are logged at source.

---

## AccessibilityNodeFinder

→ See: `platform/AccessibilityNodeFinder.kt`

Internal helper for finding accessibility nodes in the a11y tree:

| Method | Purpose |
|--------|---------|
| `findClickableNodeAtLocation(x, y)` | Smallest clickable node at coordinates |
| `findLongClickableNodeAtLocation(x, y)` | Smallest long-clickable node at coordinates |
| `findFocusedEditableNode()` | Currently focused editable node |
| `findNodeAtLocation(x, y)` | Text-input capable node at coordinates |

All methods check `isVisibleToUser` to avoid clicking invisible nodes. Properly recycles `AccessibilityNodeInfo` objects.

---

## Perceptor

→ See: `perception/Perceptor.kt`

Converts raw AccessibilityNodeInfo tree into semantic `ScreenSnapshot`.

### Responsibilities

- Traverse accessibility tree with proper node recycling
- Extract element data (bounds, text, class) without storing raw nodes
- Filter off-screen elements and tiny elements
- Filter keyboard/IME nodes (Gboard, Samsung, SwiftKey)
- Clip bounds to screen dimensions
- Limit to `MAX_ELEMENTS` for token budget
- Generate JSON for LLM prompts via `toPromptJson()`

### Prompt JSON Example

```json
{
  "index": 0,
  "text": "Settings",
  "text_index": 0,
  "class": "android.widget.TextView",
  "clickable": true,
  "focused": false,
  "long_clickable": false,
  "bounds": [0, 100, 1080, 150],
  "center": [540, 125]
}
```

Notes:
- `text` is merged text (`element.text` fallback to `element.description`).
- `text_index` is emitted only when repeated visible labels need disambiguation.
- Boolean fields like `clickable`/`editable`/`scrollable` are emitted only when true.

---

## Screenshot Support

When enabled, `AccessibilityPlatform` can attach a compressed screenshot to `ScreenSnapshot.image`:

```kotlin
data class ScreenSnapshot(
    val packageName: String,
    val activityName: String?,
    val elements: List<PerceptionElement>,
    val image: ByteArray? = null
)
```

Configuration:
- `enableScreenshotInput` - Enable/disable
- `screenshotMaxDimension` - Long edge max (default: 1024)
- `screenshotJpegQuality` - 0-100 (default: 70)

---

## File Structure

```
platform/
├── AndroidPlatform.kt         # Interface
├── AccessibilityPlatform.kt   # Implementation (atomic operations only)
├── AccessibilityNodeFinder.kt # Node search helpers
├── UIAction.kt                # Atomic action types
└── ActionResult.kt            # Result types

perception/
├── Perceptor.kt               # A11y tree → ScreenSnapshot
└── ScreenSummary.kt           # Text summary for history
```

---

## Related Docs

- [Tools](tools.md) - Tool execution and executor architecture
- [Loop](../agent/loop.md) - Perception in ReAct loop
- [Planning](../agent/planning.md) - Context hygiene
