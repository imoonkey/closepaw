package com.moonkey.androidagent.data.perception

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.moonkey.androidagent.domain.models.Bounds
import com.moonkey.androidagent.domain.models.PerceptionElement
import com.moonkey.androidagent.domain.models.Point
import com.moonkey.androidagent.domain.models.ScreenSnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * Perceptor - The Perception Engine. Converts raw AccessibilityNodeInfo tree into a semantic
 * ScreenSnapshot.
 */
object Perceptor {

    // TODO: Consider prioritizing interactive elements (clickable/editable) over non-interactive
    //       text elements. Current DFS approach may cap at MAX_ELEMENTS before reaching important
    //       buttons at the bottom of long lists. If this becomes a real issue, implement two-pass
    //       traversal: first collect interactive elements, then fill remaining slots with text.
    private const val MAX_ELEMENTS = 80
    private const val MAX_STRING_LENGTH = 60

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
        // Root is owned by system, don't recycle it
        traverse(root, elements, shouldRecycle = false)

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
            shouldRecycle: Boolean = false
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

        // Filter valid nodes - keep nodes that are interactive or have meaningful content
        val shouldKeep =
                clickable || editable || scrollable || text.isNotBlank() || desc.isNotBlank()

        if (shouldKeep) {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            val index = elements.size
            val element =
                    PerceptionElement(
                            index = index,
                            text = text.normalizeWhitespace(),
                            resourceId = resourceId,
                            className = node.className?.toString()?.substringAfterLast('.') ?: "",
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

        // Traverse children and recycle after processing
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverse(child, elements, shouldRecycle = true)
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
