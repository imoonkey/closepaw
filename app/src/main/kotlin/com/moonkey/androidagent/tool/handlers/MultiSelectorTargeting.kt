package com.moonkey.androidagent.tool.handlers

import com.moonkey.androidagent.model.ScreenSnapshot
import org.json.JSONObject

/**
 * Shared multi-selector targeting utilities for mobile actions.
 *
 * Fallback order (mirrors Minitap + our click implementation):
 * bounds -> coordinates -> text -> element_index
 */
internal object MultiSelectorTargeting {

    sealed interface Selector {
        data class Bounds(val x1: Int, val y1: Int, val x2: Int, val y2: Int) : Selector {
            fun center(): Pair<Int, Int> = ((x1 + x2) / 2) to ((y1 + y2) / 2)
        }

        data class Point(val x: Int, val y: Int) : Selector
        data class Text(val text: String, val index: Int) : Selector
        data class ElementIndex(val elementIndex: Int) : Selector
    }

    data class Attempt(
        val selector: Selector,
        val label: String
    )

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

    fun matchCountByTextOrDescription(snapshot: ScreenSnapshot, text: String): Int {
        return snapshot.elements.count {
            it.text.equals(text, ignoreCase = true) ||
                it.description.equals(text, ignoreCase = true)
        }
    }

    fun hasActionableElementAt(snapshot: ScreenSnapshot, x: Int, y: Int): Boolean {
        return snapshot.elements.any { element ->
            val inBounds =
                x >= element.bounds.left &&
                    x <= element.bounds.right &&
                    y >= element.bounds.top &&
                    y <= element.bounds.bottom
            val actionable =
                element.isEnabled &&
                    (element.isClickable ||
                        element.isEditable ||
                        element.isLongClickable ||
                        element.isScrollable)
            inBounds && actionable
        }
    }
}
