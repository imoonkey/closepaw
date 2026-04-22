# Tool Improvement Plan

Concrete implementation plan based on tool analysis from top AndroidWorld agents.

## Current State

```kotlin
// MobileActionTool actions
click(element_index)
long_press(element_index, duration_ms?)
type(text, element_index?)
swipe(start: [x,y], end: [x,y], duration_ms?)
system_button(button: back|home|enter|recents)
wait(duration_ms?)
```

### Gaps Identified

1. **Single-selector targeting** (index only)
2. **No clear text option** for type
3. **No memory tool** for cross-app data
4. **No overlap handling** for obscured elements
5. **No agent_thought tracking** for audit trail
6. **Basic error messages** (no available indices hint)
7. **No cursor positioning** for text input
8. **No bounds validation** for coordinates

---

## Priority 1: High Impact, Low Effort

### 1.1 Add `clear` Option to Type Action

**Implementation:**

```kotlin
// In MobileActionTool.kt
class TypeActionHandler : ActionHandler {
    override fun validate(params: JSONObject): ValidationResult {
        if (!params.has("text")) {
            return ValidationResult.Invalid("type action requires text")
        }
        return ValidationResult.Valid
    }
    
    override fun createInvocation(params: JSONObject): ToolInvocation {
        val text = params.getString("text")
        val idx = params.optInt("element_index", -1)
        val clear = params.optBoolean("clear", false)  // NEW
        
        return UIActionInvocation(
            description = "Type '$text' ${if (clear) "(clear first)" else ""}",
            uiAction = UIAction.Type(text, if (idx >= 0) idx else null, clear)
        )
    }
}
```

**Schema update:**

```kotlin
"clear" to PropertySpec(
    type = "boolean",
    description = "Clear existing text before typing (default false)"
)
```

**Platform implementation:**

```kotlin
// In UIAction.kt
data class Type(
    val text: String,
    val elementIndex: Int? = null,
    val clear: Boolean = false  // NEW
) : UIAction()

// In AccessibilityActionPerformer.kt
is UIAction.Type -> {
    val element = action.elementIndex?.let { findElement(it) }
    element?.let { 
        performClick(it)
        if (action.clear) {
            // Select all and delete
            performSelectAll(it)
            performDelete()
        }
    }
    performTextInput(action.text)
}
```

**Effort:** Low (parameter + execution logic)

---

### 1.2 Add Helpful Error Messages with Available Indices

**Implementation:**

```kotlin
// In AccessibilityActionPerformer.kt
private fun findElementByIndex(index: Int): AccessibilityNodeInfo? {
    val elements = currentSnapshot?.elements ?: return null
    
    if (index < 0 || index >= elements.size) {
        val availableIndices = elements.indices.take(20).joinToString()
        val suffix = if (elements.size > 20) "... and ${elements.size - 20} more" else ""
        Log.w(TAG, "Index $index out of range. Available: $availableIndices$suffix")
        return null
    }
    
    return elements.getOrNull(index)?.node
}
```

**In tool response:**

```kotlin
is ActionResult.ElementNotFound -> ToolExecutionResult.Failure(
    "Element not found: index ${result.elementIndex}. " +
    "Available indices: ${result.availableIndices.take(20).joinToString()}${if (result.availableIndices.size > 20) "..." else ""}"
)
```

**Effort:** Low (error message formatting)

---

### 1.3 Add `agent_thought` Parameter to All Tools

**Implementation:**

```kotlin
// In MultiActionTool.kt
abstract class MultiActionTool : BaseTool() {
    
    override val parameterSchema: JSONObject by lazy {
        createActionSchema(
            actionDescription = "...",
            additionalProperties = mapOf(
                "agent_thought" to PropertySpec(  // NEW - Add to all tools
                    type = "string",
                    description = "Brief explanation of WHY this action is being performed"
                ),
                // ... other properties
            )
        )
    }
}

// In UIActionInvocation.kt
class UIActionInvocation(
    override val toolName: String,
    override val params: JSONObject,
    private val description: String,
    private val uiAction: UIAction,
    private val agentThought: String? = params.optString("agent_thought", null)  // NEW
) : ToolInvocation {
    
    override fun getDescription(): String {
        return if (agentThought != null) {
            "$description (reason: $agentThought)"
        } else {
            description
        }
    }
}
```

**Effort:** Low (parameter addition)

---

### 1.4 Add Bounds Validation for Swipe

**Implementation:**

```kotlin
// In SwipeActionHandler
override fun validate(params: JSONObject): ValidationResult {
    // ... existing validation ...
    
    // Get screen dimensions from context (would need to pass this in)
    val screenWidth = ScreenDimensions.width
    val screenHeight = ScreenDimensions.height
    
    val sx = start.getInt(0).coerceIn(0, screenWidth)
    val sy = start.getInt(1).coerceIn(0, screenHeight)
    val ex = end.getInt(0).coerceIn(0, screenWidth)
    val ey = end.getInt(1).coerceIn(0, screenHeight)
    
    // Warn if coordinates were clamped
    if (sx != start.getInt(0) || sy != start.getInt(1) ||
        ex != end.getInt(0) || ey != end.getInt(1)) {
        Log.w(TAG, "Coordinates clamped to screen bounds")
    }
    
    return ValidationResult.Valid
}
```

**Effort:** Low (validation logic)

---

## Priority 2: Medium Effort, High Impact

### 2.1 Add Memory Tool

**New tool: `MemoryTool.kt`**

```kotlin
class MemoryTool : MultiActionTool() {
    
    override val name = "memory"
    
    override val description = """
Persist information across steps for later retrieval.

Actions:
- save: Store content with a key (key, content required)
- read: Retrieve content by key (key required)
- list: List all stored keys

Format: "At step N, I obtained [content] from [source]"
Use memory for cross-app data transfer instead of clipboard.
""".trimIndent()
    
    override val actionHandlers = mapOf(
        "save" to SaveActionHandler(),
        "read" to ReadActionHandler(),
        "list" to ListActionHandler()
    )
    
    // Memory storage (in-memory, persisted to session)
    private val storage = mutableMapOf<String, String>()
    private const val MAX_ITEMS = 10
    
    inner class SaveActionHandler : ActionHandler {
        override val actionName = "save"
        
        override fun validate(params: JSONObject): ValidationResult {
            if (!params.has("key")) return ValidationResult.Invalid("save requires key")
            if (!params.has("content")) return ValidationResult.Invalid("save requires content")
            return ValidationResult.Valid
        }
        
        override fun createInvocation(params: JSONObject): ToolInvocation {
            return DataQueryInvocation(
                toolName = "memory",
                params = params,
                description = "Save to memory: ${params.getString("key")}",
                execute = { ctx ->
                    val key = params.getString("key")
                    val content = params.getString("content")
                    
                    storage[key] = content
                    
                    // Limit size
                    if (storage.size > MAX_ITEMS) {
                        val oldest = storage.keys.first()
                        storage.remove(oldest)
                    }
                    
                    ToolExecutionResult.Success("Saved '$key' to memory")
                }
            )
        }
    }
    
    inner class ReadActionHandler : ActionHandler {
        override val actionName = "read"
        
        override fun createInvocation(params: JSONObject): ToolInvocation {
            return DataQueryInvocation(
                toolName = "memory",
                params = params,
                description = "Read from memory: ${params.getString("key")}",
                execute = { ctx ->
                    val key = params.getString("key")
                    val content = storage[key]
                    
                    if (content != null) {
                        ToolExecutionResult.Success("Content of '$key': $content")
                    } else {
                        ToolExecutionResult.Failure("Key '$key' not found. Available: ${storage.keys.joinToString()}")
                    }
                }
            )
        }
    }
    
    inner class ListActionHandler : ActionHandler {
        override val actionName = "list"
        
        override fun createInvocation(params: JSONObject): ToolInvocation {
            return DataQueryInvocation(
                toolName = "memory",
                params = params,
                description = "List memory keys",
                execute = { ctx ->
                    if (storage.isEmpty()) {
                        ToolExecutionResult.Success("No items in memory")
                    } else {
                        ToolExecutionResult.Success("Memory keys: ${storage.keys.joinToString()}")
                    }
                }
            )
        }
    }
}
```

**Register in ToolRegistry:**

```kotlin
val defaultTools = listOf(
    MobileActionTool(),
    AppControlTool(),
    CompleteTaskTool(),
    MemoryTool()  // NEW
)
```

**Effort:** Medium (new tool file, registry update)

---

### 2.2 Add Multi-Selector Targeting

**New data class:**

```kotlin
// In UIAction.kt
data class Target(
    val elementIndex: Int? = null,
    val bounds: Bounds? = null,
    val resourceId: String? = null,
    val resourceIdIndex: Int? = null,
    val text: String? = null,
    val textIndex: Int? = null
)

data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun center() = Pair(x + width / 2, y + height / 2)
}
```

**Updated ClickActionHandler:**

```kotlin
class ClickActionHandler : ActionHandler {
    override val actionName = "click"
    
    override fun validate(params: JSONObject): ValidationResult {
        val hasIndex = params.has("element_index")
        val hasBounds = params.has("bounds")
        val hasResourceId = params.has("resource_id")
        val hasText = params.has("text")
        
        if (!hasIndex && !hasBounds && !hasResourceId && !hasText) {
            return ValidationResult.Invalid(
                "click requires at least one of: element_index, bounds, resource_id, or text"
            )
        }
        return ValidationResult.Valid
    }
    
    override fun createInvocation(params: JSONObject): ToolInvocation {
        val target = Target(
            elementIndex = params.optInt("element_index", -1).takeIf { it >= 0 },
            bounds = params.optJSONObject("bounds")?.let { 
                Bounds(it.getInt("x"), it.getInt("y"), it.getInt("width"), it.getInt("height"))
            },
            resourceId = params.optString("resource_id", null),
            resourceIdIndex = params.optInt("resource_id_index", 0),
            text = params.optString("text", null),
            textIndex = params.optInt("text_index", 0)
        )
        
        return MultiSelectorTapInvocation(
            toolName = "mobile_action",
            params = params,
            target = target
        )
    }
}
```

**Multi-selector execution:**

```kotlin
class MultiSelectorTapInvocation(
    override val toolName: String,
    override val params: JSONObject,
    private val target: Target
) : ToolInvocation {
    
    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        val attempts = mutableListOf<Pair<String, String>>()
        
        // 1. Try element_index first
        target.elementIndex?.let { idx ->
            val result = tryTapByIndex(context, idx)
            if (result is ToolExecutionResult.Success) return result
            attempts.add("element_index=$idx" to (result as ToolExecutionResult.Failure).reason)
        }
        
        // 2. Fallback to bounds
        target.bounds?.let { bounds ->
            val (cx, cy) = bounds.center()
            val result = tryTapAtCoordinates(context, cx, cy)
            if (result is ToolExecutionResult.Success) return result
            attempts.add("bounds(${cx},${cy})" to (result as ToolExecutionResult.Failure).reason)
        }
        
        // 3. Fallback to resource_id
        target.resourceId?.let { id ->
            val result = tryTapByResourceId(context, id, target.resourceIdIndex ?: 0)
            if (result is ToolExecutionResult.Success) return result
            attempts.add("resource_id='$id'" to (result as ToolExecutionResult.Failure).reason)
        }
        
        // 4. Fallback to text
        target.text?.let { text ->
            val result = tryTapByText(context, text, target.textIndex ?: 0)
            if (result is ToolExecutionResult.Success) return result
            attempts.add("text='$text'" to (result as ToolExecutionResult.Failure).reason)
        }
        
        // All attempts failed
        val failureDetails = attempts.joinToString("; ") { "${it.first}: ${it.second}" }
        return ToolExecutionResult.Failure("All targeting methods failed. Attempts: $failureDetails")
    }
    
    private suspend fun tryTapByResourceId(
        context: ToolExecutionContext, 
        resourceId: String, 
        index: Int
    ): ToolExecutionResult {
        val elements = context.currentSnapshot?.elements ?: return ToolExecutionResult.Failure("No snapshot")
        
        val matches = elements.filter { it.resourceId == resourceId }
        if (matches.isEmpty()) return ToolExecutionResult.Failure("No element with resource_id")
        if (index >= matches.size) return ToolExecutionResult.Failure("Index $index out of range for resource_id")
        
        val element = matches[index]
        return context.platform.performAction(UIAction.ClickByNode(element.node), context.currentSnapshot)
            .toToolResult()
    }
    
    private suspend fun tryTapByText(
        context: ToolExecutionContext,
        text: String,
        index: Int
    ): ToolExecutionResult {
        val elements = context.currentSnapshot?.elements ?: return ToolExecutionResult.Failure("No snapshot")
        
        val matches = elements.filter { it.text.equals(text, ignoreCase = true) }
        if (matches.isEmpty()) return ToolExecutionResult.Failure("No element with text")
        if (index >= matches.size) return ToolExecutionResult.Failure("Index $index out of range for text")
        
        val element = matches[index]
        return context.platform.performAction(UIAction.ClickByNode(element.node), context.currentSnapshot)
            .toToolResult()
    }
}
```

**Effort:** Medium-High (new execution logic, element finding by resource_id/text)

---

### 2.3 Add Overlap Avoidance (DroidRun Style)

**Geometry helpers:**

```kotlin
// In tool/helpers/Geometry.kt
object Geometry {
    
    fun rectsOverlap(a: Rect, b: Rect): Boolean {
        return !(a.right <= b.left || b.right <= a.left || 
                 a.bottom <= b.top || b.bottom <= a.top)
    }
    
    fun findClearPoint(bounds: Rect, blockers: List<Rect>, depth: Int = 0): Pair<Int, Int>? {
        val cx = (bounds.left + bounds.right) / 2
        val cy = (bounds.top + bounds.bottom) / 2
        
        // Check if center is blocked
        val blocked = blockers.any { b -> 
            cx >= b.left && cx < b.right && cy >= b.top && cy < b.bottom 
        }
        
        if (!blocked) return Pair(cx, cy)
        
        // Recursion limit
        if (depth > 4 || bounds.width() * bounds.height() < 100) return null
        
        // Try quadrants
        val quadrants = listOf(
            Rect(bounds.left, bounds.top, cx, cy),
            Rect(cx, bounds.top, bounds.right, cy),
            Rect(bounds.left, cy, cx, bounds.bottom),
            Rect(cx, cy, bounds.right, bounds.bottom)
        )
        
        for (q in quadrants) {
            val point = findClearPoint(q, blockers, depth + 1)
            if (point != null) return point
        }
        
        return null
    }
}
```

**In tap execution:**

```kotlin
private fun findTapPoint(
    targetIndex: Int,
    elements: List<UIElement>
): Pair<Int, Int>? {
    val target = elements.getOrNull(targetIndex) ?: return null
    val targetBounds = target.bounds
    
    // Find elements that might overlap (higher index = rendered on top)
    val blockers = elements
        .filter { it.index > targetIndex }
        .filter { Geometry.rectsOverlap(targetBounds, it.bounds) }
        .map { it.bounds }
    
    return Geometry.findClearPoint(targetBounds, blockers)
}
```

**Effort:** Medium (geometry helpers + integration)

---

## Priority 3: Higher Effort

### 3.1 Add Transcribe Screen Tool

```kotlin
class TranscribeScreenTool : BaseTool() {
    override val name = "transcribe_screen"
    
    override val description = """
Extract all visible text from current screen.

Use when:
- Reading file content
- Extracting list items
- Finding UI elements by their labels
""".trimIndent()
    
    override suspend fun execute(params: JSONObject, context: ToolExecutionContext): ToolExecutionResult {
        val snapshot = context.platform.captureScreen()
        
        val texts = snapshot.elements
            .mapNotNull { it.text.takeIf { t -> t.isNotBlank() } }
            .distinct()
            .joinToString("\n")
        
        return ToolExecutionResult.Success(
            output = "Visible text on screen:\n$texts",
            observation = ToolObservation.ScreenState(
                accessibilityTree = Perceptor.toPromptJson(snapshot),
                elementCount = snapshot.elements.size,
                snapshot = snapshot
            )
        )
    }
}
```

**Effort:** Medium (new tool, but uses existing perception)

---

### 3.2 Add Focus + Cursor Positioning for Type

```kotlin
class TypeActionHandler : ActionHandler {
    
    override fun createInvocation(params: JSONObject): ToolInvocation {
        val text = params.getString("text")
        val target = parseTarget(params)  // Parse multi-selector target
        val clear = params.optBoolean("clear", false)
        val positionCursorAtEnd = params.optBoolean("position_at_end", true)  // NEW
        
        return FocusAndTypeInvocation(
            text = text,
            target = target,
            clear = clear,
            positionCursorAtEnd = positionCursorAtEnd
        )
    }
}

class FocusAndTypeInvocation(
    private val text: String,
    private val target: Target,
    private val clear: Boolean,
    private val positionCursorAtEnd: Boolean
) : ToolInvocation {
    
    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        // 1. Focus element if target provided
        val focusResult = target?.let { focusElement(context, it) }
        if (focusResult is ToolExecutionResult.Failure) return focusResult
        
        // 2. Position cursor at end (tap near bottom-right)
        if (positionCursorAtEnd && target?.bounds != null) {
            val bounds = target.bounds!!
            val endX = bounds.x + (bounds.width * 0.95).toInt()
            val endY = bounds.y + (bounds.height * 0.95).toInt()
            context.platform.performAction(UIAction.TapAt(endX, endY), context.currentSnapshot)
            delay(100)  // Brief settle
        }
        
        // 3. Clear if requested
        if (clear) {
            context.platform.performAction(UIAction.SelectAll, context.currentSnapshot)
            context.platform.performAction(UIAction.Delete, context.currentSnapshot)
        }
        
        // 4. Type text
        return context.platform.performAction(UIAction.InputText(text), context.currentSnapshot)
            .toToolResult()
    }
}
```

**Effort:** Medium-High (new invocation type, focus logic)

---

## Implementation Roadmap

### Phase 1: Quick Wins (1-2 days)

- [ ] Add `clear` option to type action
- [ ] Add helpful error messages with available indices
- [ ] Add `agent_thought` parameter to all tools
- [ ] Add bounds validation/clamping for swipe

### Phase 2: Memory & Targeting (3-5 days)

- [ ] Implement Memory tool (save/read/list)
- [ ] Add multi-selector Target object
- [ ] Implement fallback execution for tap (index → bounds → resource_id → text)
- [ ] Add overlap avoidance with geometry helpers

### Phase 3: Advanced Features (1 week+)

- [ ] Add transcribe_screen tool
- [ ] Implement focus + cursor positioning for type
- [ ] Add retry logic with automatic selector switching
- [ ] Integration testing with AndroidWorld tasks

---

## Testing Strategy

### Unit Tests

```kotlin
class GeometryTest {
    @Test
    fun `findClearPoint avoids blockers`() {
        val bounds = Rect(0, 0, 100, 100)
        val blocker = Rect(40, 40, 60, 60)  // Blocks center
        
        val point = Geometry.findClearPoint(bounds, listOf(blocker))
        
        assertNotNull(point)
        assertFalse(point!!.first in 40..60 && point.second in 40..60)
    }
}

class MultiSelectorTest {
    @Test
    fun `fallback to resource_id when index fails`() {
        // Setup: element with index out of range but resource_id exists
        val target = Target(elementIndex = 999, resourceId = "btn_submit")
        
        val result = MultiSelectorTapInvocation(target).execute(context)
        
        assertTrue(result is ToolExecutionResult.Success)
    }
}
```

### Integration Tests

1. Cross-app data transfer using memory tool
2. Type with clear in search fields
3. Tap on obscured elements
4. Fallback targeting when indices shift

---

## Success Metrics

| Metric | Current | Target | Measurement |
|--------|---------|--------|-------------|
| Tap success rate | ~80% | 95%+ | % of tap actions that hit intended element |
| Cross-app task success | Low | 70%+ | % of multi-app tasks completed |
| Type replace success | Manual multi-step | Single action | Clear + type in one call |
| Error recovery | Rare | Common | Agent pivots strategy after failure |

---

## Summary

### Must-Have (Priority 1)
1. `clear` option for type
2. Helpful error messages
3. `agent_thought` tracking

### Should-Have (Priority 2)
1. Memory tool
2. Multi-selector targeting
3. Overlap avoidance

### Nice-to-Have (Priority 3)
1. Transcribe screen tool
2. Cursor positioning
3. Automatic retry with selector switching

This plan moves our agent from M3A-baseline level toward top-performer capabilities.
