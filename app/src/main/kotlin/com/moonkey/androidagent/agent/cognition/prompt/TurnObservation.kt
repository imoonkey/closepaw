package com.moonkey.androidagent.agent.cognition.prompt

import com.moonkey.androidagent.model.ScreenImage
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.perception.PerceptionConfig
import com.moonkey.androidagent.perception.Perceptor

/**
 * Canonical observation payload for one turn.
 *
 * Created once from [ScreenSnapshot] + [PerceptionConfig], then consumed by both
 * [PromptBuilder] (for the LLM prompt) and history recording (for future turns).
 * The expensive [Perceptor.toPromptJson] call happens exactly once at construction.
 *
 * This eliminates the temporal coupling where prompt-building had to precede
 * history recording for correctness — both now project from this immutable payload.
 */
data class TurnObservation(
    /** A11y tree JSON string, or null when screenshot-only mode */
    val screenJson: String?,
    /** Number of elements in the accessibility tree */
    val elementCount: Int,
    /** Whether the soft keyboard is currently visible */
    val keyboardVisible: Boolean,
    /** Whether accessibility data is available to the LLM */
    val hasAccessibility: Boolean,
    /** Screenshot image, if captured */
    val image: ScreenImage?,
    /**
     * The canonical screen block shared by prompt rendering and history recording.
     *
     * In a11y mode: element count header + keyboard note + JSON tree.
     * In screenshot-only mode: a short factual note (prompt layer adds richer guidance).
     */
    val screenBlock: String
) {
    companion object {
        /** Build the canonical observation from a snapshot and perception config. */
        fun capture(
            snapshot: ScreenSnapshot,
            perceptionConfig: PerceptionConfig
        ): TurnObservation {
            val hasA11y = perceptionConfig.capturesAccessibility
            val screenJson = if (hasA11y) Perceptor.toPromptJson(snapshot) else null
            val screenBlock = if (hasA11y) {
                buildString {
                    appendLine("Screen state (${snapshot.elements.size} elements):")
                    if (snapshot.keyboardVisible) {
                        appendLine("keyboard_visible: true (BACK will dismiss keyboard first, not navigate back)")
                    }
                    appendLine("```json")
                    appendLine(screenJson)
                    append("```")
                }
            } else {
                "(Screenshot-only mode — no accessibility tree)"
            }
            return TurnObservation(
                screenJson = screenJson,
                elementCount = snapshot.elements.size,
                keyboardVisible = snapshot.keyboardVisible,
                hasAccessibility = hasA11y,
                image = snapshot.image,
                screenBlock = screenBlock
            )
        }
    }
}
