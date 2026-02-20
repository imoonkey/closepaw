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
        return resolveElementPoint(element)
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
        return resolveElementPoint(element)
    }

    private fun buildElementMissingReason(index: Int, snapshot: ScreenSnapshot): String {
        val available = snapshot.elements.map { it.index }
        val preview = available.take(20).joinToString(", ")
        val more = if (available.size > 20) " ... and ${available.size - 20} more" else ""
        return "Element not found: index $index. Available: $preview$more"
    }

    private fun resolveElementPoint(element: PerceptionElement): ResolveResult.Resolved {
        return ResolveResult.Resolved(element.center)
    }
}
