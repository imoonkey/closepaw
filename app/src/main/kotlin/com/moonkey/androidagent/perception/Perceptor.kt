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
    fun snapshot(root: AccessibilityNodeInfo?): ScreenSnapshot {
        val timestamp = System.currentTimeMillis()
        if (root == null) {
            return ScreenSnapshot(timestamp, emptyList())
        }

        val elements = mutableListOf<PerceptionElement>()
        val seenKeys = mutableSetOf<String>()
        // Root is owned by system, don't recycle it
        traverse(root, elements, seenKeys, shouldRecycle = false, mode = TraversalMode.INTERACTIVE_ONLY)
        if (elements.size < MAX_ELEMENTS) {
            traverse(root, elements, seenKeys, shouldRecycle = false, mode = TraversalMode.ALL)
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
        for (elem in snapshot.elements) {
            val obj =
                    JSONObject().apply {
                        put("index", elem.index)
                        put("text", elem.text)
                        put("id", elem.resourceId)
                        put("class", elem.className)
                        put("desc", elem.description)
                        put("clickable", elem.isClickable)
                        put("editable", elem.isEditable)
                        put("scrollable", elem.isScrollable)
                        // We simplify bounds to center for some prompts, or keep bounds
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
        mode: TraversalMode
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
        
        // Check both isEditable AND ACTION_SET_TEXT support
        // This handles WebView inputs and custom widgets that support text input
        // but don't set isEditable flag
        val editable = node.isEditable || canAcceptTextInput(node)

        val hasContent = text.isNotBlank() || desc.isNotBlank()
        val shouldKeep = when (mode) {
            TraversalMode.INTERACTIVE_ONLY -> clickable || editable || scrollable
            TraversalMode.ALL -> clickable || editable || scrollable || hasContent
        }

        if (shouldKeep) {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            val index = elements.size
            val className = node.className?.toString()?.substringAfterLast('.') ?: ""
            val key = buildElementKey(
                resourceId = resourceId,
                className = className,
                text = text,
                desc = desc,
                rect = rect
            )
            if (seenKeys.add(key)) {
                val element = PerceptionElement(
                    index = index,
                    text = text.normalizeWhitespace(),
                    resourceId = resourceId,
                    className = className,
                    description = desc.normalizeWhitespace(),
                    isClickable = clickable,
                    isEditable = editable,
                    isScrollable = scrollable,
                    bounds = Bounds(
                        left = rect.left,
                        top = rect.top,
                        right = rect.right,
                        bottom = rect.bottom
                    ),
                    center = Point(x = rect.centerX(), y = rect.centerY())
                )
                elements.add(element)
            }
        }

        // Traverse children and recycle after processing
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverse(child, elements, seenKeys, shouldRecycle = true, mode = mode)
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

    private fun buildElementKey(
        resourceId: String,
        className: String,
        text: String,
        desc: String,
        rect: Rect
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
