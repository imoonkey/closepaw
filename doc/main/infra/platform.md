# Platform Abstraction

> AndroidPlatform, Perceptor, and screen perception.
> Last updated: 2026-02-04

## AndroidPlatform

→ See: `platform/AndroidPlatform.kt`

Abstraction for Android-specific operations.

### Interface

```kotlin
interface AndroidPlatform {
    suspend fun captureScreen(): ScreenSnapshot
    suspend fun performAction(action: UIAction, snapshot: ScreenSnapshot): ActionResult
    fun hasRequiredPermissions(): Boolean
    fun getCurrentPackageName(): String
    fun getDisplayInfo(): DisplayInfo
}
```

### Operations

| Method | Purpose |
|--------|---------|
| `captureScreen()` | Get current UI state as ScreenSnapshot |
| `performAction()` | Execute UI actions |
| `hasRequiredPermissions()` | Check accessibility permission |
| `getCurrentPackageName()` | Get foreground app |
| `getDisplayInfo()` | Screen dimensions |

---

## AccessibilityPlatform

→ See: `platform/AccessibilityPlatform.kt`

Implementation of `AndroidPlatform` using Android Accessibility APIs.

### Action Visualization Integration

Can integrate with `ActionVisualizerManager` to show visual feedback:

```kotlin
class AccessibilityPlatform(
    private val service: AccessibilityService,
    private val visualizer: ActionVisualizerManager? = null
) {
    private suspend fun performTap(x: Float, y: Float): ActionResult {
        visualizer?.showClick(x, y)  // Show ripple before action
        // ... dispatch gesture
    }
    
    private suspend fun performSwipe(...): ActionResult {
        visualizer?.showSwipe(...)   // Show trail during swipe
        // ... dispatch gesture
    }
}
```

---

## Perceptor

→ See: `perception/Perceptor.kt`

Converts raw AccessibilityNodeInfo tree into semantic ScreenSnapshot.

### Responsibilities

- Traverse accessibility tree with proper node recycling
- Extract element data (bounds, text, class) without storing raw nodes
- Filter off-screen elements and elements below minimum size (5px)
- Filter keyboard/IME nodes (Gboard, Samsung, SwiftKey)
- Clip element bounds to screen dimensions
- Limit to MAX_ELEMENTS (80) for token budget
- Generate JSON for LLM prompts via `toPromptJson()`

### Output Element Example

```json
{
  "index": 0,
  "text": "Settings",
  "resource_id": "com.android.settings:id/title",
  "resource_id_index": 0,
  "text_index": 0,
  "class": "TextView",
  "desc": "",
  "clickable": true,
  "editable": false,
  "scrollable": false,
  "enabled": true,
  "focused": false,
  "long_clickable": false,
  "bounds": [0, 100, 1080, 150],
  "center": [540, 125]
}
```

### Occurrence Indices

- `resource_id_index`, `text_index`, `desc_index` - 0-based occurrence count for disambiguation when multiple elements share the same identifier

---

## UIAction Types

→ See: `platform/UIAction.kt`

| Action | Description |
|--------|-------------|
| `Click` | Single tap at coordinates or element |
| `LongClick` | Long press |
| `Type` | Text input |
| `Swipe` | Gesture with start/end coordinates |
| `SystemButton` | Back, home, enter, recents |

---

## ActionResult

→ See: `platform/ActionResult.kt`

```kotlin
sealed class ActionResult {
    data class Success(val message: String?) : ActionResult()
    data class Failure(val reason: String) : ActionResult()
}
```

---

## AccessibilityNodeFinder

→ See: `platform/AccessibilityNodeFinder.kt`

Helper for finding nodes in accessibility tree:
- Search by resource ID
- Search by text
- Search by element index
- Handle occurrence indices for disambiguation

---

## Screenshot Support

When enabled, `AccessibilityPlatform` can attach a compressed screenshot to `ScreenSnapshot.image`:

```kotlin
data class ScreenSnapshot(
    val packageName: String,
    val activityName: String?,
    val elements: List<PerceptionElement>,
    val image: ByteArray? = null  // Compressed JPEG
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
├── AndroidPlatform.kt        # Interface
├── AccessibilityPlatform.kt  # Implementation
├── AccessibilityNodeFinder.kt # Node search helpers
├── UIAction.kt               # Action types
└── ActionResult.kt           # Result types

perception/
├── Perceptor.kt              # A11y tree → ScreenSnapshot
└── ScreenSummary.kt          # Text summary for history
```

---

## Related Docs

- [Tools](tools.md) - Tool execution uses platform
- [Loop](../agent/loop.md) - Perception in ReAct loop
- [Planning](../agent/planning.md) - Context hygiene
