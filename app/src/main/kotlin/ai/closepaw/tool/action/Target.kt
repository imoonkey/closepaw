package ai.closepaw.tool.action

/**
 * Targeting method for mobile actions. Parsed from LLM JSON params.
 * Exactly one per action call. Resolved to coordinates by TargetResolver.
 */
sealed interface Target {
    data class ElementIndex(val index: Int) : Target
    data class Text(val text: String, val textIndex: Int = 0) : Target
    data class Coordinate(val x: Int, val y: Int) : Target
}
