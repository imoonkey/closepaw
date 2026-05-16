package ai.closepaw.tool.action

/**
 * Targeting method for mobile actions. Parsed from LLM JSON params.
 * Resolved to coordinates by TargetResolver.
 *
 * Semantic targets (ElementIndex, Text) may carry an optional coordinateHint
 * supplied by the model. The semantic target is primary; the hint is used
 * only as fallback when semantic resolution fails, or as a consistency check
 * against the resolved bounds.
 */
sealed interface Target {
    data class ElementIndex(
        val index: Int,
        val coordinateHint: Coordinate? = null
    ) : Target

    data class Text(
        val text: String,
        val textIndex: Int = 0,
        val coordinateHint: Coordinate? = null
    ) : Target

    data class Coordinate(val x: Int, val y: Int) : Target
}
