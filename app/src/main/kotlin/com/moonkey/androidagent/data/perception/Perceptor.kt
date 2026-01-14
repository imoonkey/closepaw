package com.moonkey.androidagent.data.perception

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.moonkey.androidagent.domain.models.PerceptionElement
import com.moonkey.androidagent.domain.models.ScreenSnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * Perceptor - The Perception Engine. Converts raw AccessibilityNodeInfo tree into a semantic
 * ScreenSnapshot.
 */
object Perceptor {

    private const val MAX_ELEMENTS = 80
    private const val MAX_STRING_LENGTH = 60

    fun snapshot(root: AccessibilityNodeInfo?): ScreenSnapshot {
        val timestamp = System.currentTimeMillis()
        if (root == null) {
            return ScreenSnapshot(timestamp, null, emptyList(), emptyMap())
        }

        val elements = mutableListOf<PerceptionElement>()
        val nodeMap = mutableMapOf<Int, AccessibilityNodeInfo>() // Map index -> Node for execution

        traverse(root, elements, nodeMap)

        // Take max elements to avoid token overflow
        val limitedElements = elements.take(MAX_ELEMENTS)
        // Adjust map to only include limited elements
        val limitedMap = nodeMap.filterKeys { it < MAX_ELEMENTS }

        return ScreenSnapshot(
                timestamp = timestamp,
                rootOriginal =
                        root, // Warning: Keeping root might cause memory leaks if held too long in
                // a service, usually okay for short lived loop.
                elements = limitedElements,
                rawMap = limitedMap
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
                        put("center", JSONArray(elem.center.toList()))
                    }
            jsonArray.put(obj)
        }
        return jsonArray.toString(2)
    }

    private fun traverse(
            node: AccessibilityNodeInfo,
            elements: MutableList<PerceptionElement>,
            nodeMap: MutableMap<Int, AccessibilityNodeInfo>
    ) {
        if (elements.size >= MAX_ELEMENTS) return

        val text = node.text?.toString()?.take(MAX_STRING_LENGTH) ?: ""
        val desc = node.contentDescription?.toString()?.take(MAX_STRING_LENGTH) ?: ""
        val resourceId = node.viewIdResourceName?.take(MAX_STRING_LENGTH) ?: ""
        val clickable = node.isClickable
        val editable = node.isEditable
        val scrollable = node.isScrollable

        // Filter valid nodes
        val shouldKeep =
                clickable || editable || scrollable || text.isNotBlank() || desc.isNotBlank()

        if (shouldKeep) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

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
                            bounds =
                                    intArrayOf(
                                            bounds.left,
                                            bounds.top,
                                            bounds.right,
                                            bounds.bottom
                                    ),
                            center = intArrayOf(bounds.centerX(), bounds.centerY())
                    )
            elements.add(element)
            nodeMap[index] = node
        }

        // BFS/DFS Children
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child -> traverse(child, elements, nodeMap) }
        }
    }

    private fun String.normalizeWhitespace(): String {
        return this.replace(Regex("\\s+"), " ").trim()
    }
}
