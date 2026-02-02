package com.moonkey.androidagent.perception

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.moonkey.androidagent.model.Bounds
import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.Point
import com.moonkey.androidagent.model.ScreenSnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * Perceptor - The Perception Engine. Converts raw AccessibilityNodeInfo tree into a semantic
 * ScreenSnapshot.
 */
object Perceptor {

    // Prioritize interactive elements before non-interactive text to reduce truncation risk.
    private const val MAX_ELEMENTS = 80
    private const val MAX_STRING_LENGTH = 60
    private const val MIN_ELEMENT_SIZE_PX = 5

    private enum class TraversalMode {
        INTERACTIVE_ONLY,
        ALL
    }

    /**
     * Create a ScreenSnapshot from the accessibility tree.
     * 
     * Does not store AccessibilityNodeInfo references to prevent memory leaks.
     * All data needed for action execution (bounds, center, properties) is extracted
     * and stored in PerceptionElement.
     */
    fun snapshot(
        root: AccessibilityNodeInfo?,
        screenWidthPx: Int? = null,
        screenHeightPx: Int? = null
    ): ScreenSnapshot {
        val timestamp = System.currentTimeMillis()
        if (root == null) {
            return ScreenSnapshot(timestamp, emptyList())
        }

        val elements = mutableListOf<PerceptionElement>()
        val seenKeys = mutableSetOf<String>()
        // Root is owned by system, don't recycle it
        traverse(
            node = root,
            elements = elements,
            seenKeys = seenKeys,
            shouldRecycle = false,
            mode = TraversalMode.INTERACTIVE_ONLY,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )
        if (elements.size < MAX_ELEMENTS) {
            traverse(
                node = root,
                elements = elements,
                seenKeys = seenKeys,
                shouldRecycle = false,
                mode = TraversalMode.ALL,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx
            )
        }

        // Take max elements to avoid token overflow
        val limitedElements = elements.take(MAX_ELEMENTS)

        return ScreenSnapshot(
                timestamp = timestamp,
                elements = limitedElements
        )
    }

    /** Convert Snapshot to JSON string for LLM Prompting */
    fun toPromptJson(snapshot: ScreenSnapshot): String {
        val jsonArray = JSONArray()
        val resourceIdCounts = mutableMapOf<String, Int>()
        val textCounts = mutableMapOf<String, Int>()
        val descCounts = mutableMapOf<String, Int>()
        for (elem in snapshot.elements) {
            val resourceIdIndex = getOccurrenceIndex(
                value = elem.resourceId,
                counts = resourceIdCounts,
                normalize = { it }
            )
            val textIndex = getOccurrenceIndex(
                value = elem.text,
                counts = textCounts,
                normalize = { it.trim().lowercase() }
            )
            val descIndex = getOccurrenceIndex(
                value = elem.description,
                counts = descCounts,
                normalize = { it.trim().lowercase() }
            )

            val obj =
                    JSONObject().apply {
                        put("index", elem.index)
                        put("text", elem.text)
                        put("resource_id", elem.resourceId)
                        if (resourceIdIndex != null) put("resource_id_index", resourceIdIndex)
                        if (textIndex != null) put("text_index", textIndex)
                        if (descIndex != null) put("desc_index", descIndex)
                        put("class", elem.className)
                        put("desc", elem.description)
                        put("clickable", elem.isClickable)
                        put("editable", elem.isEditable)
                        put("scrollable", elem.isScrollable)
                        put("enabled", elem.isEnabled)
                        put("focused", elem.isFocused)
                        put("long_clickable", elem.isLongClickable)
                        put("bounds", JSONArray(listOf(elem.bounds.left, elem.bounds.top, elem.bounds.right, elem.bounds.bottom)))
                        // Provide both bounds and center for flexible targeting
                        // TODO: Consider using streaming JSON writer for better performance
                        //       if profiling shows JSON generation is a bottleneck.
                        put("center", JSONArray(listOf(elem.center.x, elem.center.y)))
                    }
            jsonArray.put(obj)
        }
        return jsonArray.toString(2)
    }

    /**
     * Traverse accessibility tree and extract element data.
     * 
     * - Does not store AccessibilityNodeInfo references
     * - Properly recycles child nodes after traversal
     * - Checks ACTION_SET_TEXT for WebView/custom widget text input support
     * 
     * @param node Current node to process
     * @param elements List to collect extracted elements
     * @param shouldRecycle Whether to recycle this node after processing (false for root)
     */
    private fun traverse(
        node: AccessibilityNodeInfo,
        elements: MutableList<PerceptionElement>,
        seenKeys: MutableSet<String>,
        shouldRecycle: Boolean = false,
        mode: TraversalMode,
        screenWidthPx: Int?,
        screenHeightPx: Int?
    ) {
        if (elements.size >= MAX_ELEMENTS) {
            if (shouldRecycle) node.recycle()
            return
        }

        val text = node.text?.toString()?.take(MAX_STRING_LENGTH) ?: ""
        val desc = node.contentDescription?.toString()?.take(MAX_STRING_LENGTH) ?: ""
        val resourceId = node.viewIdResourceName?.take(MAX_STRING_LENGTH) ?: ""
        val clickable = node.isClickable
        val scrollable = node.isScrollable
        val enabled = node.isEnabled
        val focused = node.isFocused
        val longClickable = node.isLongClickable

        if (isKnownKeyboardNode(resourceId)) {
            if (shouldRecycle) node.recycle()
            return
        }
        
        // Check both isEditable AND ACTION_SET_TEXT support
        // This handles WebView inputs and custom widgets that support text input
        // but don't set isEditable flag
        val editable = node.isEditable || canAcceptTextInput(node)

        val hasContent = text.isNotBlank() || desc.isNotBlank() || resourceId.isNotBlank()
        val shouldKeep = when (mode) {
            TraversalMode.INTERACTIVE_ONLY -> clickable || editable || scrollable
            TraversalMode.ALL -> clickable || editable || scrollable || hasContent
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val intersectsScreen = intersectsScreen(rect, screenWidthPx, screenHeightPx)
        val meetsMinSize = rect.width() > MIN_ELEMENT_SIZE_PX && rect.height() > MIN_ELEMENT_SIZE_PX
        val className = node.className?.toString()?.substringAfterLast('.') ?: ""
        val key = buildElementKey(
            resourceId = resourceId,
            className = className,
            text = text,
            desc = desc,
            rect = rect,
            isClickable = clickable,
            isEditable = editable,
            isScrollable = scrollable
        )
        val alreadySeen = seenKeys.contains(key)
        if (shouldKeep && !alreadySeen && intersectsScreen && meetsMinSize) {
            seenKeys.add(key)
            val index = elements.size
            val left = rect.left
            val top = rect.top
            val right = rect.right
            val bottom = rect.bottom
            val clippedBounds = clipBoundsToScreen(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx
            )
            val element = PerceptionElement(
                index = index,
                text = text.normalizeWhitespace(),
                resourceId = resourceId,
                className = className,
                description = desc.normalizeWhitespace(),
                isClickable = clickable,
                isEditable = editable,
                isScrollable = scrollable,
                isEnabled = enabled,
                isFocused = focused,
                isLongClickable = longClickable,
                bounds = Bounds(
                    left = clippedBounds.left,
                    top = clippedBounds.top,
                    right = clippedBounds.right,
                    bottom = clippedBounds.bottom
                ),
                center = Point(
                    x = ((clippedBounds.left + clippedBounds.right) / 2),
                    y = ((clippedBounds.top + clippedBounds.bottom) / 2)
                )
            )
            elements.add(element)
        }

        // Traverse children and recycle after processing
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverse(
                node = child,
                elements = elements,
                seenKeys = seenKeys,
                shouldRecycle = true,
                mode = mode,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx
            )
            // Note: child is recycled inside traverse() when shouldRecycle=true
        }
        
        // Recycle this node if allowed (not root)
        if (shouldRecycle) {
            node.recycle()
        }
    }
    
    /**
     * Check if a node can accept text input via ACTION_SET_TEXT.
     * Handles WebView inputs and custom widgets that support text
     * but don't set the isEditable flag.
     */
    private fun canAcceptTextInput(node: AccessibilityNodeInfo): Boolean {
        val actions = node.actionList ?: return false
        return actions.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
    }

    private data class ClippedBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private fun clipBoundsToScreen(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        screenWidthPx: Int?,
        screenHeightPx: Int?
    ): ClippedBounds {
        val w = screenWidthPx
        val h = screenHeightPx
        if (w == null || h == null || w <= 0 || h <= 0) {
            return ClippedBounds(left = left, top = top, right = right, bottom = bottom)
        }
        return ClippedBounds(
            left = left.coerceAtLeast(0),
            top = top.coerceAtLeast(0),
            right = right.coerceAtMost(w),
            bottom = bottom.coerceAtMost(h)
        )
    }

    private fun intersectsScreen(rect: Rect, screenWidthPx: Int?, screenHeightPx: Int?): Boolean {
        val w = screenWidthPx
        val h = screenHeightPx
        if (w == null || h == null || w <= 0 || h <= 0) return true
        return !(rect.right <= 0 || rect.bottom <= 0 || rect.left >= w || rect.top >= h)
    }

    /**
     * Filter out keyboard/IME nodes to avoid cluttering the element list.
     * Covers common keyboards: Google (Gboard), AOSP, Samsung, SwiftKey.
     */
    private fun isKnownKeyboardNode(resourceId: String): Boolean {
        if (resourceId.isBlank()) return false
        return KEYBOARD_RESOURCE_PREFIXES.any { resourceId.startsWith(it) }
    }

    private val KEYBOARD_RESOURCE_PREFIXES = listOf(
        "com.google.android.inputmethod.latin:id/",  // Gboard
        "com.android.inputmethod.latin:id/",         // AOSP keyboard
        "com.samsung.android.honeyboard:id/",        // Samsung keyboard
        "com.swiftkey.swiftkey:id/",                 // SwiftKey (newer)
        "com.touchtype.swiftkey:id/"                 // SwiftKey (older)
    )

    /**
     * Get occurrence index for duplicate disambiguation in prompt JSON.
     * Returns null if value is blank, otherwise returns the 0-based occurrence index.
     */
    private fun getOccurrenceIndex(
        value: String,
        counts: MutableMap<String, Int>,
        normalize: (String) -> String
    ): Int? {
        if (value.isBlank()) return null
        val key = normalize(value)
        val index = counts[key] ?: 0
        counts[key] = index + 1
        return index
    }

    private fun buildElementKey(
        resourceId: String,
        className: String,
        text: String,
        desc: String,
        rect: Rect,
        isClickable: Boolean,
        isEditable: Boolean,
        isScrollable: Boolean
    ): String {
        return buildString {
            append(resourceId)
            append('|')
            append(className)
            append('|')
            append(text)
            append('|')
            append(desc)
            append('|')
            append(if (isClickable) '1' else '0')
            append(if (isEditable) '1' else '0')
            append(if (isScrollable) '1' else '0')
            append('|')
            append(rect.left)
            append(',')
            append(rect.top)
            append(',')
            append(rect.right)
            append(',')
            append(rect.bottom)
        }
    }

    /**
     * Normalize whitespace while preserving meaningful structure.
     * - Collapses multiple horizontal spaces/tabs to single space
     * - Collapses multiple newlines to single newline
     * - Preserves single newlines (meaningful line breaks)
     */
    private fun String.normalizeWhitespace(): String {
        return this
            .replace(Regex("[ \\t]+"), " ")    // Collapse horizontal whitespace only
            .replace(Regex("\\n{2,}"), "\n")   // Collapse multiple newlines to single
            .trim()
    }
}
