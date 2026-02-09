package com.moonkey.androidagent.tool.handlers

import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.ScreenSnapshot
import org.json.JSONObject

/**
 * Shared multi-selector targeting utilities for mobile actions.
 */
internal object MultiSelectorTargeting {

    sealed interface Selector {
        data class Point(val x: Int, val y: Int) : Selector
        data class Text(val text: String, val index: Int) : Selector
        data class ElementIndex(val elementIndex: Int) : Selector
    }

    data class Attempt(
        val selector: Selector,
        val label: String
    )

    enum class SelectorOrder {
        COORDINATES,
        TEXT,
        ELEMENT_INDEX
    }

    val DEFAULT_FALLBACK_ORDER = listOf(
        SelectorOrder.COORDINATES,
        SelectorOrder.TEXT,
        SelectorOrder.ELEMENT_INDEX
    )

    val CLICK_FALLBACK_ORDER = listOf(
        SelectorOrder.ELEMENT_INDEX,
        SelectorOrder.TEXT,
        SelectorOrder.COORDINATES
    )

    fun attemptsFromParams(
        params: JSONObject,
        textKey: String,
        textIndexKey: String,
        textLabel: String = "text",
        selectorOrder: List<SelectorOrder> = DEFAULT_FALLBACK_ORDER
    ): List<Attempt> {
        val pointAttempt =
            if (params.has("x") && params.has("y")) {
                val x = params.optInt("x", -1)
                val y = params.optInt("y", -1)
                Attempt(
                    selector = Selector.Point(x, y),
                    label = "coordinates ($x,$y)"
                )
            } else {
                null
            }

        val textAttempt =
            params.optString(textKey, "").trim().takeIf { it.isNotEmpty() }?.let { text ->
                val index = params.optInt(textIndexKey, 0)
                Attempt(
                    selector = Selector.Text(text, index),
                    label = "$textLabel=\"$text\" index $index"
                )
            }

        val elementIndexAttempt =
            params.optInt("element_index", -1)
                .takeIf { params.has("element_index") && it >= 0 }
                ?.let { elementIndex ->
                    Attempt(
                        selector = Selector.ElementIndex(elementIndex),
                        label = "element_index $elementIndex"
                    )
                }

        val attemptsByOrder = mapOf(
            SelectorOrder.COORDINATES to pointAttempt,
            SelectorOrder.TEXT to textAttempt,
            SelectorOrder.ELEMENT_INDEX to elementIndexAttempt
        )

        return selectorOrder
            .distinct()
            .mapNotNull { attemptsByOrder[it] }
    }

    fun resolveElement(snapshot: ScreenSnapshot, elementIndex: Int): PerceptionElement? {
        return snapshot.elements.firstOrNull { it.index == elementIndex }
    }

    fun resolveElementByTextOrDescription(
        snapshot: ScreenSnapshot,
        text: String,
        index: Int
    ): PerceptionElement? {
        val matches = snapshot.elements.filter {
            it.text.equals(text, ignoreCase = true) ||
                it.description.equals(text, ignoreCase = true)
        }
        return matches.getOrNull(index)
    }

    fun findElementIndexByTextOrDescription(
        snapshot: ScreenSnapshot,
        text: String,
        index: Int
    ): Int? {
        return resolveElementByTextOrDescription(snapshot, text, index)?.index
    }

    fun matchCountByTextOrDescription(snapshot: ScreenSnapshot, text: String): Int {
        return snapshot.elements.count {
            it.text.equals(text, ignoreCase = true) ||
                it.description.equals(text, ignoreCase = true)
        }
    }
}
