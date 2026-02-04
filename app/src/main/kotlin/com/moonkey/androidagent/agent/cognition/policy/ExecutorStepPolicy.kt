package com.moonkey.androidagent.agent.cognition.policy

import com.moonkey.androidagent.history.ResponseItem

internal sealed interface ExecutorStepDecision {
    data object Continue : ExecutorStepDecision

    data object WarnApproaching : ExecutorStepDecision

    data class ForceStop(
        val narrativeSummary: String
    ) : ExecutorStepDecision
}

internal class ExecutorStepPolicy(
    private val maxSteps: Int,
    private val narrativeSummaryOnLimit: Boolean
) {
    fun evaluate(stepCount: Int, delegatedQuery: String, history: List<ResponseItem>): ExecutorStepDecision {
        return when {
            stepCount >= maxSteps && narrativeSummaryOnLimit ->
                ExecutorStepDecision.ForceStop(
                    narrativeSummary =
                        buildNarrativeSummary(
                            delegatedQuery = delegatedQuery,
                            history = history
                        )
                )
            stepCount >= maxSteps - 1 -> ExecutorStepDecision.WarnApproaching
            else -> ExecutorStepDecision.Continue
        }
    }

    private fun buildNarrativeSummary(delegatedQuery: String, history: List<ResponseItem>): String {
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
            appendLine("Executor reached step limit ($maxSteps) without completing the delegated task.")
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
