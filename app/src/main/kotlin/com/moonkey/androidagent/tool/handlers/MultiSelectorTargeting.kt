package com.moonkey.androidagent.tool.handlers

import com.moonkey.androidagent.model.ScreenSnapshot
import org.json.JSONObject

/**
 * Shared multi-selector targeting utilities for mobile actions.
 *
 * Fallback order (mirrors Minitap + our click implementation):
 * bounds -> coordinates -> resource_id -> text -> element_index
 */
internal object MultiSelectorTargeting {

    sealed interface Selector {
        data class Bounds(val x1: Int, val y1: Int, val x2: Int, val y2: Int) : Selector {
            fun center(): Pair<Int, Int> = ((x1 + x2) / 2) to ((y1 + y2) / 2)
        }

        data class Point(val x: Int, val y: Int) : Selector
        data class ResourceId(val resourceId: String, val index: Int) : Selector
        data class Text(val text: String, val index: Int) : Selector
        data class ElementIndex(val elementIndex: Int) : Selector
    }

    data class Attempt(
        val selector: Selector,
        val label: String
    )

    data class FilterResult(
        val attempts: List<Attempt>,
        val warnings: List<String>
    )

    /**
     * For type targeting, accept `text_index` as a compatibility alias when `target_text_index`
     * is omitted. This helps recover from occasional LLM parameter drift.
     */
    fun targetTextIndexKey(params: JSONObject): String {
        return if (params.has("target_text_index")) "target_text_index" else "text_index"
    }

    fun attemptsFromParams(
        params: JSONObject,
        textKey: String,
        textIndexKey: String,
        textLabel: String = "text"
    ): List<Attempt> {
        val attempts = mutableListOf<Attempt>()

        val hasBounds = params.has("x1") && params.has("y1") && params.has("x2") && params.has("y2")
        if (hasBounds) {
            val x1 = params.optInt("x1", -1)
            val y1 = params.optInt("y1", -1)
            val x2 = params.optInt("x2", -1)
            val y2 = params.optInt("y2", -1)
            val (cx, cy) = Selector.Bounds(x1, y1, x2, y2).center()
            attempts.add(
                Attempt(
                    selector = Selector.Bounds(x1, y1, x2, y2),
                    label = "bounds center ($cx,$cy)"
                )
            )
        }

        val hasPoint = params.has("x") && params.has("y")
        if (hasPoint) {
            val x = params.optInt("x", -1)
            val y = params.optInt("y", -1)
            attempts.add(
                Attempt(
                    selector = Selector.Point(x, y),
                    label = "coordinates ($x,$y)"
                )
            )
        }

        val resourceId = params.optString("resource_id", "").trim()
        if (resourceId.isNotEmpty()) {
            val index = params.optInt("resource_id_index", 0)
            attempts.add(
                Attempt(
                    selector = Selector.ResourceId(resourceId, index),
                    label = "resource_id='$resourceId' index $index"
                )
            )
        }

        val text = params.optString(textKey, "").trim()
        if (text.isNotEmpty()) {
            val index = params.optInt(textIndexKey, 0)
            attempts.add(
                Attempt(
                    selector = Selector.Text(text, index),
                    label = "$textLabel=\"$text\" index $index"
                )
            )
        }

        if (params.has("element_index")) {
            val elementIndex = params.optInt("element_index", -1)
            if (elementIndex >= 0) {
                attempts.add(
                    Attempt(
                        selector = Selector.ElementIndex(elementIndex),
                        label = "element_index $elementIndex"
                    )
                )
            }
        }

        return attempts
    }

    /**
     * Minitap-inspired defensive check for type targeting: if both resource_id and target_text
     * are present but they appear to point at different elements, ignore the resource_id attempt.
     */
    fun filterTypeAttemptsByResourceIdTargetTextMismatch(
        params: JSONObject,
        snapshot: ScreenSnapshot?,
        attempts: List<Attempt>
    ): FilterResult {
        val resourceId = params.optString("resource_id", "").trim()
        val targetText = params.optString("target_text", "").trim()
        if (resourceId.isEmpty() || targetText.isEmpty() || snapshot == null) {
            return FilterResult(attempts = attempts, warnings = emptyList())
        }

        val resourceIdAttempt = attempts.firstOrNull { it.selector is Selector.ResourceId }
            ?: return FilterResult(attempts = attempts, warnings = emptyList())

        val elementIndex = findElementIndexByResourceId(
            snapshot = snapshot,
            resourceId = resourceId,
            index = params.optInt("resource_id_index", 0)
        ) ?: return FilterResult(attempts = attempts, warnings = emptyList())

        val element = snapshot.elements.firstOrNull { it.index == elementIndex }
            ?: return FilterResult(attempts = attempts, warnings = emptyList())

        val elementLabel = element.text.ifBlank { element.description }.trim()
        if (elementLabel.isNotEmpty() && !elementLabel.equals(targetText, ignoreCase = true)) {
            val warning =
                "resource_id='$resourceId' ignored: target_text=\"$targetText\" does not match resolved element text/description \"$elementLabel\""
            return FilterResult(
                attempts = attempts.filterNot { it === resourceIdAttempt },
                warnings = listOf(warning)
            )
        }

        return FilterResult(attempts = attempts, warnings = emptyList())
    }

    fun findElementIndexByResourceId(
        snapshot: ScreenSnapshot,
        resourceId: String,
        index: Int
    ): Int? {
        val matches = snapshot.elements.filter { it.resourceId == resourceId }
        return matches.getOrNull(index)?.index
    }

    fun findElementIndexByTextOrDescription(
        snapshot: ScreenSnapshot,
        text: String,
        index: Int
    ): Int? {
        val matches = snapshot.elements.filter {
            it.text.equals(text, ignoreCase = true) ||
                it.description.equals(text, ignoreCase = true)
        }
        return matches.getOrNull(index)?.index
    }

    fun matchCountByResourceId(snapshot: ScreenSnapshot, resourceId: String): Int {
        return snapshot.elements.count { it.resourceId == resourceId }
    }

    fun matchCountByTextOrDescription(snapshot: ScreenSnapshot, text: String): Int {
        return snapshot.elements.count {
            it.text.equals(text, ignoreCase = true) ||
                it.description.equals(text, ignoreCase = true)
        }
    }
}
