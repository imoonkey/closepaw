package ai.closepaw.agent.cognition.policy

import ai.closepaw.history.ResponseItem

/**
 * Returns true when the current turn is the final one allowed by the budget.
 */
internal fun isFinalTurn(turnNumber: Int, maxTurns: Int): Boolean = turnNumber >= maxTurns

/**
 * Builds a narrative summary when a delegated agent reaches its turn limit
 * without completing the task. Used to communicate context back to the parent agent.
 */
internal object DelegationSummaryFormatter {
    fun format(maxTurns: Int, delegatedQuery: String, history: List<ResponseItem>): String {
        val attemptedCalls = history.filterIsInstance<ResponseItem.FunctionCall>().takeLast(6)
        val recentOutputs = history.filterIsInstance<ResponseItem.FunctionCallOutput>().takeLast(3)

        val attemptedSummary =
            if (attemptedCalls.isEmpty()) {
                "- No tool calls were executed."
            } else {
                attemptedCalls.joinToString(separator = "\n") { call ->
                    val action =
                        call.arguments.optString("action", "").trim()
                            .ifBlank { call.name }
                    "- ${call.name} ($action)"
                }
            }

        val observationSummary =
            if (recentOutputs.isEmpty()) {
                "- No post-action observations captured."
            } else {
                recentOutputs.joinToString(separator = "\n") { output ->
                    "- ${output.content.lineSequence().firstOrNull().orEmpty().take(140)}"
                }
            }

        return buildString {
            appendLine("Agent reached turn limit ($maxTurns) without completing the delegated task.")
            appendLine("Delegated query: $delegatedQuery")
            appendLine()
            appendLine("Attempted actions:")
            appendLine(attemptedSummary)
            appendLine()
            appendLine("Recent observations:")
            appendLine(observationSummary)
            appendLine()
            appendLine("Suggested alternatives:")
            appendLine("- Avoid repeating the same interaction path.")
            appendLine("- Try back/menu/search/filter from the current screen.")
            appendLine("- Use accessibility tree state first; screenshot only as optional support.")
        }.trimEnd()
    }
}
