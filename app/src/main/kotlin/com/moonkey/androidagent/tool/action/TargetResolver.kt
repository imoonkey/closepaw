package com.moonkey.androidagent.tool.action

import com.moonkey.androidagent.model.Point
import com.moonkey.androidagent.model.PerceptionElement
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
            } else if (!snapshot.hasElements) {
                "Cannot use element_index: no elements on screen. Use coordinate (x, y) instead."
            } else if (snapshot.elements.any { it.index == target.index }) {
                "Element ${target.index} is likely occluded by overlapping clickable elements."
            } else {
                val available = snapshot.elements.map { it.index }
                val preview = available.take(20).joinToString(", ")
                val more = if (available.size > 20) " ... and ${available.size - 20} more" else ""
                "Element not found: index ${target.index}. Available: $preview$more"
            }
        }

        is Target.Text -> {
            if (snapshot == null) {
                "Cannot resolve text \"${target.text}\": no snapshot available"
            } else if (!snapshot.hasElements) {
                "Cannot use text targeting: no elements on screen. Use coordinate (x, y) instead."
            } else if (snapshot.elements.any {
                    it.text.equals(target.text, ignoreCase = true) ||
                        it.description.equals(target.text, ignoreCase = true)
                }) {
                "Text target \"${target.text}\" is likely occluded by overlapping clickable elements."
            } else {
                val count = matchCount(snapshot, target.text)
                "Text \"${target.text}\" index ${target.textIndex} not found (matched $count elements)"
            }
        }
    }

    private fun resolveElementIndex(index: Int, snapshot: ScreenSnapshot?): Point? {
        val snap = snapshot ?: return null
        val element = snap.elements.firstOrNull { it.index == index } ?: return null
        return resolveElementPoint(element, snap)
    }

    private fun resolveText(text: String, textIndex: Int, snapshot: ScreenSnapshot?): Point? {
        if (snapshot == null) return null
        val matches = snapshot.elements.filter {
            it.text.equals(text, ignoreCase = true) ||
                it.description.equals(text, ignoreCase = true)
        }
        val element = matches.getOrNull(textIndex) ?: return null
        return resolveElementPoint(element, snapshot)
    }

    private fun matchCount(snapshot: ScreenSnapshot, text: String): Int {
        return snapshot.elements.count {
            it.text.equals(text, ignoreCase = true) ||
                it.description.equals(text, ignoreCase = true)
        }
    }

    private fun resolveElementPoint(element: PerceptionElement, snapshot: ScreenSnapshot): Point? {
        val b = element.bounds
        val width = b.width
        val height = b.height
        if (width <= 2 || height <= 2) return null

        val marginX = (width / 10).coerceIn(2, 32)
        val marginY = (height / 10).coerceIn(2, 32)
        val left = b.left + marginX
        val right = b.right - marginX
        val top = b.top + marginY
        val bottom = b.bottom - marginY
        if (left >= right || top >= bottom) return element.center

        val quarterY = top + ((bottom - top) / 4)
        val thirdY = top + ((bottom - top) / 3)
        val candidatePoints =
                listOf(
                        Point((left + right) / 2, (top + bottom) / 2), // center
                        Point((left + right) / 2, quarterY), // upper-middle
                        Point((left + right) / 2, thirdY), // upper-third
                        Point(left + ((right - left) / 3), quarterY), // upper-left
                        Point(right - ((right - left) / 3), quarterY), // upper-right
                        Point((left + right) / 2, top + 1) // near top edge
                )

        val usablePoint =
                candidatePoints.firstOrNull { point ->
                    !isPointBlockedBySmallerClickable(point, element, snapshot)
                }
        return usablePoint ?: element.center.takeUnless {
            isPointBlockedBySmallerClickable(it, element, snapshot)
        }
    }

    private fun isPointBlockedBySmallerClickable(
            point: Point,
            target: PerceptionElement,
            snapshot: ScreenSnapshot
    ): Boolean {
        val targetArea = target.bounds.width.toLong() * target.bounds.height.toLong()
        return snapshot.elements.any { other ->
            if (other.index == target.index || !other.isClickable) return@any false
            val otherArea = other.bounds.width.toLong() * other.bounds.height.toLong()
            if (otherArea >= targetArea) return@any false
            point.x >= other.bounds.left &&
                point.x <= other.bounds.right &&
                point.y >= other.bounds.top &&
                point.y <= other.bounds.bottom
        }
    }
}
