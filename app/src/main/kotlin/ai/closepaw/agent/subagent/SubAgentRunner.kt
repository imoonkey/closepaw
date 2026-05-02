package ai.closepaw.agent.subagent

import ai.closepaw.agent.Agent
import ai.closepaw.agent.AgentEventDispatcher
import ai.closepaw.agent.AgentExecutionConfig
import ai.closepaw.agent.AgentStopReason
import ai.closepaw.agent.definition.AgentRoleDef
import ai.closepaw.agent.definition.ResolvedAgentRole
import ai.closepaw.agent.cognition.policy.DelegationSummaryFormatter
import ai.closepaw.history.HistoryManager
import ai.closepaw.history.ResponseItem
import ai.closepaw.protocol.*
import ai.closepaw.session.AgentSessionState
import ai.closepaw.session.SessionServices
import ai.closepaw.tool.ToolName
import ai.closepaw.tool.ToolRouter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sub-agent primitives used by `delegate_task`.
 *
 * The parent planner delegates one atomic instruction to an isolated child agent,
 * then receives a normalized success/failure message.
 */

/**
 * Delegation payload passed from parent to a sub-agent.
 */
data class SubAgentRequest(
    val query: String,
    val currentSubgoal: String? = null,
    val importantNotes: List<String> = emptyList(),
    val delegationCallId: String? = null
)

/**
 * Result returned after running a sub-agent.
 */
data class SubAgentResult(
    val success: Boolean,
    val message: String
)

fun interface SubAgentRunner {
    /** Runs a delegated task and returns a compact result for the parent planner. */
    suspend fun run(request: SubAgentRequest): SubAgentResult
}

/**
 * Runs a delegated sub-agent with isolated prompt state and tool access.
 */
internal class IsolatedSubAgentRunner(
    private val roleDef: AgentRoleDef,
    private val parentServices: SessionServices,
    private val parentSessionId: SessionId,
    private val eventDispatcher: AgentEventDispatcher
) : SubAgentRunner {

    /**
     * Spins up a temporary child agent with filtered tools and shared scratchpad.
     */
    override suspend fun run(request: SubAgentRequest): SubAgentResult {
        val childTaskId = "sub-${roleDef.name}-${System.currentTimeMillis()}"
        val childSessionId = SessionId("${parentSessionId.value}::$childTaskId")
        val resolvedRoleDef: ResolvedAgentRole = roleDef.resolve(
            snapshot = parentServices.termuxSnapshot,
            excludedTools = parentServices.config.excludedTools.toToolNames()
        )
        val childTools = parentServices.toolRegistry.createFilteredCopy(
            allowedNames = resolvedRoleDef.allowedToolNames,
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

        // Sub-agents use executor model when available, otherwise fall back to main model
        val childModelName = parentServices.config.executorModel
            ?: parentServices.config.mainModel

        val childAgent = Agent(
            config = AgentExecutionConfig(
                goal = request.toGoal(),
                sessionId = childSessionId,
                taskId = childTaskId,
                maxTurns = resolvedRoleDef.maxTurns,
                uiSettleDelayMs = parentServices.config.actionDelayMs,
                debugMode = parentServices.config.debugMode,
                systemPrompt = resolvedRoleDef.systemPrompt,
                allowedToolNames = resolvedRoleDef.allowedToolNames,
                agentId = childSessionId.value,
                agentRole = resolvedRoleDef.executionRole,
                parentSessionId = parentSessionId,
                delegationCallId = request.delegationCallId,
                modelName = childModelName
            ),
            services = childServices,
            eventEmitter = { event -> bridgeEvent(event) },
            cancellationSignal = CompletableDeferred()
        )

        val stopReason = withTimeoutOrNull(resolvedRoleDef.timeoutMs) {
            childAgent.run()
        }
        val completion = extractCompletion(childServices.historyManager)

        return when (stopReason) {
            is AgentStopReason.GoalAchieved -> {
                if (completion != null && completion.status == "failure") {
                    SubAgentResult(success = false, message = completion.toFailureMessage(roleDef.name))
                } else {
                    val message = completion?.toSuccessMessage(roleDef.name)
                        ?: "Sub-agent '${roleDef.name}' completed."
                    SubAgentResult(success = true, message = message)
                }
            }
            AgentStopReason.MaxTurnsReached -> {
                val narrative = DelegationSummaryFormatter.format(
                    maxTurns = resolvedRoleDef.maxTurns,
                    delegatedQuery = request.query,
                    history = childServices.historyManager.getAll()
                )

                SubAgentResult(
                    success = false,
                    message = completion?.toFailureMessage(roleDef.name)
                        ?: narrative
                )
            }
            AgentStopReason.UserRequested -> {
                SubAgentResult(success = false, message = "Sub-agent was stopped.")
            }
            is AgentStopReason.Error -> {
                SubAgentResult(success = false, message = stopReason.message)
            }
            is AgentStopReason.TaskImpossible -> {
                SubAgentResult(success = false, message = stopReason.message)
            }
            null -> {
                SubAgentResult(success = false, message = "Timeout after ${resolvedRoleDef.timeoutMs}ms")
            }
        }
    }

    private suspend fun bridgeEvent(event: AgentEvent) {
        val activity = when (event) {
            is ActionProposed -> "proposed ${event.toolName}: ${event.description}"
            is ActionExecuted -> {
                val state = when (event.outcome) {
                    ActionOutcome.SUCCESS -> "success"
                    ActionOutcome.FAILED -> "failed"
                    ActionOutcome.SKIPPED -> "skipped"
                }
                "executed ${event.toolName}: $state"
            }
            is SessionError -> "error: ${event.message}"
            else -> return
        }

        eventDispatcher.subAgentActivity(
            agentName = roleDef.name,
            activity = activity
        )
    }
}

private fun Set<String>.toToolNames(): Set<ToolName> = map { ToolName.from(it) }.toSet()

private data class CompletionPayload(
    val status: String,
    val answer: String
)

/** Extracts the latest `complete_task` payload from child history, if present. */
private fun extractCompletion(historyManager: HistoryManager): CompletionPayload? {
    val completionCall = historyManager.getAll()
        .asReversed()
        .filterIsInstance<ResponseItem.FunctionCall>()
        .firstOrNull { it.name == "complete_task" }
        ?: return null

    val status = completionCall.arguments.optString("status", "success")
    val answer = completionCall.arguments.optString("answer", "").trim()
        .ifEmpty { completionCall.arguments.optString("summary", "").trim() }

    return CompletionPayload(
        status = status,
        answer = answer
    )
}

private fun CompletionPayload.toSuccessMessage(agentName: String): String {
    if (answer.isBlank()) return "Sub-agent '$agentName' completed."
    return "Sub-agent '$agentName' completed:\n$answer"
}

private fun CompletionPayload.toFailureMessage(agentName: String): String {
    return buildString {
        append("Sub-agent '$agentName' reported failure.")
        if (answer.isNotBlank()) {
            append("\n")
            append(answer)
        }
    }
}

/** Converts structured delegation fields into the child agent goal text. */
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
