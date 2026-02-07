# Platform Abstraction

> AndroidPlatform, Perceptor, and screen perception.
> Last updated: 2026-02-04 (commit: da83b53ba4e849e52b45158a3485261d7399facb)

## AndroidPlatform

→ See: `platform/AndroidPlatform.kt`

Abstraction for Android-specific operations.

### Interface

```kotlin
interface AndroidPlatform {
    suspend fun captureScreen(): ScreenSnapshot
    suspend fun performAction(action: UIAction, snapshot: ScreenSnapshot): ActionResult
    suspend fun launchApp(packageName: String): ActionResult
    suspend fun getInstalledApps(): List<AppInfo>
    fun hasRequiredPermissions(): Boolean
    fun getCurrentPackageName(): String?
    fun getDisplayInfo(): DisplayInfo
}
```

### Operations

| Method | Purpose |
|--------|---------|
| `captureScreen()` | Get current UI state as `ScreenSnapshot` |
| `performAction()` | Execute UI actions |
| `launchApp()` | Launch app by package name |
| `getInstalledApps()` | Query launchable apps |
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
        visualizer?.showClick(x, y)
        // ... dispatch gesture
    }

    private suspend fun performSwipe(...): ActionResult {
        visualizer?.showSwipe(...)
        // ... dispatch gesture
    }
}
```

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

## UIAction Types

→ See: `platform/UIAction.kt`

| Action | Description |
|--------|-------------|
| `Click` | Single tap at coordinates or element |
| `LongClick` | Long press |
| `Type` | Text input |
| `Swipe` | Gesture with start/end coordinates |
| `SystemButton` | Back, home, enter, recents |
| `Wait` | Pause execution |

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
├── AccessibilityPlatform.kt   # Implementation
├── AccessibilityNodeFinder.kt # Node search helpers
├── UIAction.kt                # Action types
└── ActionResult.kt            # Result types

perception/
├── Perceptor.kt               # A11y tree → ScreenSnapshot
└── ScreenSummary.kt           # Text summary for history
```

---

## Related Docs

- [Tools](tools.md) - Tool execution uses platform
- [Loop](../agent/loop.md) - Perception in ReAct loop
- [Planning](../agent/planning.md) - Context hygiene
