package com.moonkey.androidagent.agent.subagent

import com.moonkey.androidagent.agent.Agent
import com.moonkey.androidagent.agent.AgentConfig
import com.moonkey.androidagent.agent.AgentExecutionRole
import com.moonkey.androidagent.agent.AgentStopReason
import com.moonkey.androidagent.agent.cognition.policy.ExecutorStepDecision
import com.moonkey.androidagent.agent.cognition.policy.ExecutorStepPolicy
import com.moonkey.androidagent.history.HistoryManager
import com.moonkey.androidagent.history.ResponseItem
import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.SessionId
import com.moonkey.androidagent.session.AgentSessionState
import com.moonkey.androidagent.session.SessionServices
import com.moonkey.androidagent.tool.ToolRouter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

fun interface SubAgentRunner {
    suspend fun run(request: SubAgentRequest): SubAgentResult
}

/**
 * Runs a delegated sub-agent with isolated prompt state and tool access.
 */
class IsolatedSubAgentRunner(
    private val definition: AgentDefinition,
    private val parentServices: SessionServices,
    private val parentSessionId: SessionId,
    private val eventEmitter: suspend (AgentEvent) -> Unit
) : SubAgentRunner {

    override suspend fun run(request: SubAgentRequest): SubAgentResult {
        val childTaskId = "sub-${definition.name}-${System.currentTimeMillis()}"
        val childSessionId = SessionId("${parentSessionId.value}::$childTaskId")
        val childTools = parentServices.toolRegistry.createFilteredCopy(
            allowedNames = definition.toolNames.toSet(),
            excludedNames = setOf("delegate_task")
        )
        // Share scratchpad by reference on purpose so planner/executor exchange state in one turn.
        // ScratchpadState uses synchronized access and is safe for concurrent coroutine access.
        val childServices = parentServices.copy(
            toolRegistry = childTools,
            toolRouter = ToolRouter(childTools, parentServices.policyEngine),
            historyManager = HistoryManager(),
            sessionState = AgentSessionState(
                scratchpad = parentServices.sessionState.scratchpad
            )
        )

        val childAgent = Agent(
            config = AgentConfig(
                goal = request.toGoal(),
                sessionId = childSessionId,
                taskId = childTaskId,
                maxTurns = definition.maxTurns,
                uiSettleDelayMs = parentServices.config.actionDelayMs,
                debugMode = parentServices.config.debugMode,
                systemPrompt = definition.systemPrompt,
                allowedToolNames = definition.toolNames.toSet(),
                cognitionProfileId = parentServices.config.cognitionProfileId,
                agentId = childSessionId.value,
                agentRole = definition.executionRole ?: AgentExecutionRole.EXECUTOR,
                parentSessionId = parentSessionId,
                delegationCallId = request.delegationCallId
            ),
            services = childServices,
            eventEmitter = { event -> bridgeEvent(event) },
            cancellationSignal = CompletableDeferred()
        )

        val stopReason = withTimeoutOrNull(definition.timeoutMs) {
            childAgent.run()
        }
        val completion = extractCompletion(childServices.historyManager)

        return when (stopReason) {
            AgentStopReason.GoalAchieved -> {
                if (completion != null && completion.status == "failure") {
                    SubAgentResult(success = false, message = completion.toFailureMessage(definition.name))
                } else {
                    val message = completion?.toSuccessMessage(definition.name)
                        ?: "Sub-agent '${definition.name}' completed."
                    SubAgentResult(success = true, message = message)
                }
            }
            AgentStopReason.MaxTurnsReached -> {
                val stepLimitNarrative =
                    when (
                        val stepDecision =
                            ExecutorStepPolicy(
                                definition.maxTurns,
                                definition.narrativeSummaryOnLimit
                            ).evaluate(
                                stepCount = definition.maxTurns,
                                delegatedQuery = request.query,
                                history = childServices.historyManager.getAll()
                            )
                    ) {
                        is ExecutorStepDecision.ForceStop -> stepDecision.narrativeSummary
                        else -> null
                    }
                SubAgentResult(
                    success = false,
                    message =
                        completion?.toFailureMessage(definition.name)
                            ?: stepLimitNarrative
                            ?: "Sub-agent reached max turns."
                )
            }
            AgentStopReason.UserRequested -> {
                SubAgentResult(success = false, message = "Sub-agent was stopped.")
            }
            is AgentStopReason.Error -> {
                SubAgentResult(success = false, message = stopReason.message)
            }
            null -> {
                SubAgentResult(success = false, message = "Timeout after ${definition.timeoutMs}ms")
            }
        }
    }

    private suspend fun bridgeEvent(event: AgentEvent) {
        val activity = when (event) {
            is AgentEvent.ActionProposed -> "proposed ${event.toolName}: ${event.description}"
            is AgentEvent.ActionExecuted -> {
                val state = if (event.success) "success" else "failed"
                "executed ${event.toolName}: $state"
            }
            is AgentEvent.SessionError -> "error: ${event.error.message}"
            else -> null
        } ?: return

        eventEmitter(
            AgentEvent.SubAgentActivity(
                sessionId = parentSessionId,
                timestamp = System.currentTimeMillis(),
                agentName = definition.name,
                activity = activity
            )
        )
    }
}

private data class CompletionPayload(
    val status: String,
    val answer: String,
    val reason: String?
)

private fun extractCompletion(historyManager: HistoryManager): CompletionPayload? {
    val completionCall = historyManager.getAll()
        .asReversed()
        .filterIsInstance<ResponseItem.FunctionCall>()
        .firstOrNull { it.name == "complete_task" }
        ?: return null

    val status = completionCall.arguments.optString("status", "success")
    val answer = completionCall.arguments.optString("answer", "").trim()
        .ifEmpty { completionCall.arguments.optString("summary", "").trim() }
    val reason = completionCall.arguments.optString("reason", "").trim().ifEmpty { null }

    return CompletionPayload(
        status = status,
        answer = answer,
        reason = reason
    )
}

private fun CompletionPayload.toSuccessMessage(agentName: String): String {
    if (answer.isBlank()) return "Sub-agent '$agentName' completed."
    return "Sub-agent '$agentName' completed:\n$answer"
}

private fun CompletionPayload.toFailureMessage(agentName: String): String {
    return buildString {
        append("Sub-agent '$agentName' reported failure.")
        if (reason != null) {
            append("\nReason: $reason")
        }
        if (answer.isNotBlank()) {
            append("\n")
            append(answer)
        }
    }
}

private fun SubAgentRequest.toGoal(): String {
    val cleanedNotes = importantNotes.map { it.trim() }.filter { it.isNotEmpty() }

    return buildString {
        appendLine("Delegated query:")
        appendLine(query)

        currentSubgoal?.trim()?.takeIf { it.isNotEmpty() }?.let {
            appendLine()
            appendLine("Current subgoal:")
            appendLine(it)
        }

        if (cleanedNotes.isNotEmpty()) {
            appendLine()
            appendLine("Important notes:")
            cleanedNotes.forEach { note -> appendLine("- $note") }
        }
    }.trim()
}
