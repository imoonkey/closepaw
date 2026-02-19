package com.moonkey.androidagent.tool.action

import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.Point
import com.moonkey.androidagent.model.ScreenSnapshot

/**
 * Resolves a Target to screen coordinates.
 *
 * Pure function: no Android dependencies, no side effects.
 */
object TargetResolver {
    sealed interface ResolveResult {
        data class Resolved(
            val point: Point,
            val warnings: List<String> = emptyList()
        ) : ResolveResult

        data class NotFound(val reason: String) : ResolveResult
    }

    fun resolve(target: Target, snapshot: ScreenSnapshot?): ResolveResult = when (target) {
        is Target.Coordinate -> ResolveResult.Resolved(Point(target.x, target.y))
        is Target.ElementIndex -> resolveElementIndex(target.index, snapshot)
        is Target.Text -> resolveText(target.text, target.textIndex, snapshot)
    }

    private fun resolveElementIndex(index: Int, snapshot: ScreenSnapshot?): ResolveResult {
        val snap = snapshot ?: return ResolveResult.NotFound(
            "Cannot resolve element_index $index: no snapshot available"
        )
        if (!snap.hasElements) {
            return ResolveResult.NotFound(
                "Cannot use element_index: no elements on screen. Use coordinate (x, y) instead."
            )
        }
        val element = snap.elements.firstOrNull { it.index == index }
            ?: return ResolveResult.NotFound(buildElementMissingReason(index, snap))
        return resolveElementPoint(element, snap)
    }

    private fun resolveText(text: String, textIndex: Int, snapshot: ScreenSnapshot?): ResolveResult {
        val snap = snapshot ?: return ResolveResult.NotFound(
            "Cannot resolve text \"$text\": no snapshot available"
        )
        if (!snap.hasElements) {
            return ResolveResult.NotFound(
                "Cannot use text targeting: no elements on screen. Use coordinate (x, y) instead."
            )
        }
        val matches = snap.elements.filter {
            it.text.equals(text, ignoreCase = true) ||
                it.description.equals(text, ignoreCase = true)
        }
        val element = matches.getOrNull(textIndex)
            ?: return ResolveResult.NotFound(
                "Text \"$text\" index $textIndex not found (matched ${matches.size} elements)"
            )
        return resolveElementPoint(element, snap)
    }

    private fun buildElementMissingReason(index: Int, snapshot: ScreenSnapshot): String {
        val available = snapshot.elements.map { it.index }
        val preview = available.take(20).joinToString(", ")
        val more = if (available.size > 20) " ... and ${available.size - 20} more" else ""
        return "Element not found: index $index. Available: $preview$more"
    }

    private fun resolveElementPoint(
        element: PerceptionElement,
        snapshot: ScreenSnapshot
    ): ResolveResult.Resolved {
        val center = element.center
        if (!isPointBlockedBySmallerClickable(center, element, snapshot)) {
            return ResolveResult.Resolved(center)
        }

        val b = element.bounds
        val leftCenter = Point(b.left + 1, b.centerY)
        val rightCenter = Point(b.right - 1, b.centerY)
        val topCenter = Point(b.centerX, b.top + 1)
        val bottomCenter = Point(b.centerX, b.bottom - 1)
        val candidates = listOf(leftCenter, rightCenter, topCenter, bottomCenter)

        val fallbackPoint = candidates.firstOrNull { point ->
            !isPointBlockedBySmallerClickable(point, element, snapshot)
        }

        if (fallbackPoint != null) {
            return ResolveResult.Resolved(
                point = fallbackPoint,
                warnings = listOf("Element center likely occluded; using offset point")
            )
        }

        return ResolveResult.Resolved(
            point = center,
            warnings = listOf("Element may be occluded; clicking center anyway")
        )
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
