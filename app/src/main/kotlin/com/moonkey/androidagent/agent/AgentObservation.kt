package com.moonkey.androidagent.agent

import com.moonkey.androidagent.tool.ToolObservation

/**
 * Observation - Post-action state captured after tool execution.
 */
sealed class Observation {
    data class ScreenState(
        val accessibilityTree: String,
        val summary: String = ""
    ) : Observation()
    data class TextOutput(val content: String) : Observation()
}

/**
 * Extension to convert ToolObservation to Agent's Observation.
 */
fun ToolObservation.toObservation(): Observation {
    return when (this) {
        is ToolObservation.ScreenState -> Observation.ScreenState(this.accessibilityTree, this.summary)
        is ToolObservation.TextOutput -> Observation.TextOutput(this.content)
    }
}
