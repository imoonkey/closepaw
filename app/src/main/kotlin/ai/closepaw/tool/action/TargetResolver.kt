package ai.closepaw.tool.action

import ai.closepaw.model.Bounds
import ai.closepaw.model.PerceptionElement
import ai.closepaw.model.Point
import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.platform.SemanticTargetHint
import ai.closepaw.perception.mergedText
import ai.closepaw.perception.normalizeForMatching

/**
 * Resolves a Target to screen coordinates.
 *
 * Pure function: no Android dependencies, no side effects.
 *
 * Semantic targets are primary. An optional coordinateHint disambiguates or
 * provides fallback when semantic resolution fails. See design_codex.md.
 */
object TargetResolver {
    sealed interface ResolveResult {
        data class Resolved(
            val point: Point,
            val bounds: Bounds? = null,
            val semanticHint: SemanticTargetHint? = null,
            val warnings: List<String> = emptyList(),
            val coordinateFallback: Boolean = false
        ) : ResolveResult

        data class NotFound(val reason: String) : ResolveResult
        data class Ambiguous(val reason: String) : ResolveResult
    }

    fun resolve(target: Target, snapshot: ScreenSnapshot?): ResolveResult = when (target) {
        is Target.Coordinate -> ResolveResult.Resolved(Point(target.x, target.y))
        is Target.ElementIndex -> resolveSemantic(
            semantic = resolveElementIndex(target.index, snapshot),
            hint = target.coordinateHint,
            semanticLabel = "element_index ${target.index}"
        )
        is Target.Text -> resolveSemantic(
            semantic = resolveText(target.text, target.textIndex, snapshot),
            hint = target.coordinateHint,
            semanticLabel = "text \"${target.text}\" index ${target.textIndex}"
        )
    }

    private fun resolveSemantic(
        semantic: ResolveResult,
        hint: Target.Coordinate?,
        semanticLabel: String
    ): ResolveResult {
        return when (semantic) {
            is ResolveResult.Resolved -> {
                if (hint == null) {
                    semantic
                } else if (semantic.bounds != null && !containsHalfOpen(semantic.bounds, hint)) {
                    ResolveResult.Ambiguous(
                        "Ambiguous target: $semanticLabel resolves to bounds " +
                            "${semantic.bounds} but coordinate hint (${hint.x}, ${hint.y}) " +
                            "lies outside. Refusing to guess."
                    )
                } else {
                    semantic
                }
            }
            is ResolveResult.NotFound -> {
                if (hint != null) {
                    ResolveResult.Resolved(
                        point = Point(hint.x, hint.y),
                        bounds = null,
                        semanticHint = null,
                        warnings = listOf(
                            "Used coordinate fallback after semantic target failed: ${semantic.reason}"
                        ),
                        coordinateFallback = true
                    )
                } else {
                    semantic
                }
            }
            is ResolveResult.Ambiguous -> semantic
        }
    }

    private fun containsHalfOpen(bounds: Bounds, hint: Target.Coordinate): Boolean {
        return hint.x >= bounds.left && hint.x < bounds.right &&
            hint.y >= bounds.top && hint.y < bounds.bottom
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
        val normalizedTarget = normalizeForMatching(text)
        val promptMatches = snap.elements.filter { element ->
            normalizeForMatching(mergedText(element)) == normalizedTarget
        }
        val matches = if (promptMatches.isNotEmpty()) {
            promptMatches
        } else {
            snap.elements.filter { element ->
                normalizeForMatching(element.description) == normalizedTarget ||
                    normalizeForMatching(element.hintText) == normalizedTarget
            }
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
        val hint = SemanticTargetHint(
            resourceId = element.resourceId,
            text = element.text,
            description = element.description,
            className = element.className,
            bounds = element.bounds
        )
        return ResolveResult.Resolved(element.center, bounds = element.bounds, semanticHint = hint)
    }
}
