package com.moonkey.androidagent.tool.action

import com.moonkey.androidagent.model.Point
import com.moonkey.androidagent.model.ScreenSnapshot

/**
 * Resolves a Target to screen coordinates.
 *
 * Pure function: no Android dependencies, no side effects.
 * Returns null when resolution fails; callers use describeFailure for the error message.
 */
object TargetResolver {

    fun resolve(target: Target, snapshot: ScreenSnapshot?): Point? = when (target) {
        is Target.Coordinate -> Point(target.x, target.y)
        is Target.ElementIndex -> resolveElementIndex(target.index, snapshot)
        is Target.Text -> resolveText(target.text, target.textIndex, snapshot)
    }

    fun describeFailure(target: Target, snapshot: ScreenSnapshot?): String = when (target) {
        is Target.Coordinate ->
            "Invalid coordinates (${target.x}, ${target.y})"

        is Target.ElementIndex -> {
            if (snapshot == null) {
                "Cannot resolve element_index ${target.index}: no snapshot available"
            } else if (!snapshot.hasAccessibility) {
                "Cannot use element_index in screenshot-only mode. Use coordinate (x, y) instead."
            } else {
                val available = snapshot.elements.orEmpty().map { it.index }
                val preview = available.take(20).joinToString(", ")
                val more = if (available.size > 20) " ... and ${available.size - 20} more" else ""
                if (available.isNotEmpty()) {
                    "Element not found: index ${target.index}. Available: $preview$more"
                } else {
                    "Element not found: index ${target.index}. No elements on screen."
                }
            }
        }

        is Target.Text -> {
            if (snapshot == null) {
                "Cannot resolve text \"${target.text}\": no snapshot available"
            } else if (!snapshot.hasAccessibility) {
                "Cannot use text targeting in screenshot-only mode. Use coordinate (x, y) instead."
            } else {
                val count = matchCount(snapshot, target.text)
                "Text \"${target.text}\" index ${target.textIndex} not found (matched $count elements)"
            }
        }
    }

    private fun resolveElementIndex(index: Int, snapshot: ScreenSnapshot?): Point? {
        return snapshot?.elements?.firstOrNull { it.index == index }?.center
    }

    private fun resolveText(text: String, textIndex: Int, snapshot: ScreenSnapshot?): Point? {
        if (snapshot == null) return null
        val matches = snapshot.elements.orEmpty().filter {
            it.text.equals(text, ignoreCase = true) ||
                it.description.equals(text, ignoreCase = true)
        }
        return matches.getOrNull(textIndex)?.center
    }

    private fun matchCount(snapshot: ScreenSnapshot, text: String): Int {
        return snapshot.elements.orEmpty().count {
            it.text.equals(text, ignoreCase = true) ||
                it.description.equals(text, ignoreCase = true)
        }
    }
}
