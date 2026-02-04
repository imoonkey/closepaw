package com.moonkey.androidagent.agent

import android.util.Log
import com.moonkey.androidagent.history.ResponseItem
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.perception.toSummary
import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.ScreenStatePhase
import com.moonkey.androidagent.protocol.TurnPhase
import com.moonkey.androidagent.session.SessionServices
import com.moonkey.androidagent.tool.ToolName
import com.moonkey.androidagent.tool.SimpleToolRouterContext
import com.moonkey.androidagent.tool.ToolCallResult
import com.moonkey.androidagent.tool.ToolObservation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

internal class AgentTurnRunner(
    private val config: AgentConfig,
    private val services: SessionServices,
    private val eventDispatcher: AgentEventDispatcher,
    private val eventEmitter: suspend (AgentEvent) -> Unit,
    private val cancellationSignal: CompletableDeferred<AgentStopReason>,
    private val stopRequested: AtomicBoolean,
    private val promptBuilder: AgentPromptBuilder,
    private val trace: AgentTrace
) {
    companion object {
        private const val TAG = "AgentTurnRunner"
    }

    suspend fun executeTurn(turnId: String, turnNumber: Int): TurnOutcome {
        trace.turnStarted(turnId, turnNumber)

        val outcome =
            try {
                // 1. PERCEPTION
                eventDispatcher.status("👀 Scanning screen...")

                val snapshot = services.platform.captureScreen()
                val currentPackage = services.platform.getCurrentPackageName()
                trace.screenCaptured(turnId, turnNumber, snapshot, currentPackage)

                eventDispatcher.screenCaptured(
                    snapshot = snapshot,
                    packageName = currentPackage,
                    activityName = null,
                    turnId = turnId,
                    turnNumber = turnNumber,
                    phase = ScreenStatePhase.PRE_TURN,
                    traceRunId = services.config.traceRunId
                )

                Log.d(TAG, "Turn $turnNumber: Screen has ${snapshot.elements.size} elements")

                if (config.debugMode) {
                    Log.d(TAG, "Turn $turnNumber: Elements (first 20):")
                    snapshot.elements.take(20).forEach { elem ->
                        val text = elem.text.ifEmpty { elem.description }.take(25)
                        val flags =
                            buildString {
                                if (elem.isClickable) append("C")
                                if (elem.isEditable) append("E")
                                if (elem.isScrollable) append("S")
                            }
                        Log.d(TAG, "  [${elem.index}] \"$text\" $flags @(${elem.center.x},${elem.center.y})")
                    }
                }

                if (cancellationSignal.isCompleted || stopRequested.get()) {
                    TurnOutcome.Cancelled
                } else {
                    // 2. THINK (LLM)
                    eventDispatcher.turnPhaseChanged(turnId, TurnPhase.PLANNING)
                    eventDispatcher.status("🧠 Thinking...")

                    val turn = Turn(services.historyManager, services.toolRegistry, services.llmClient)
                    val systemPrompt = promptBuilder.buildSystemPrompt()
                    val userContext = promptBuilder.buildUserContext(snapshot)

                    trace.llmRequest(
                        turnId = turnId,
                        turnNumber = turnNumber,
                        snapshot = snapshot,
                        systemPrompt = systemPrompt,
                        userContextText = userContext.text,
                        history = services.historyManager.forPrompt()
                    )

                    var turnResult: TurnResult? = null
                    var streamError: Throwable? = null

                    turn.runStreaming(systemPrompt, userContext, services.config.model).collect { event ->
                        when (event) {
                            is TurnStreamEvent.TextDelta -> eventDispatcher.messageDelta(turnId, event.text)
                            is TurnStreamEvent.ToolCallReceived ->
                                Log.d(TAG, "Turn $turnNumber: Received tool call: ${event.toolCall.name}")
                            is TurnStreamEvent.Complete -> {
                                turnResult = event.result
                                Log.d(TAG, "Turn $turnNumber: Stream complete, isComplete=${event.result.isComplete}")
                            }
                            is TurnStreamEvent.Error -> {
                                streamError = event.error
                                Log.e(TAG, "Turn $turnNumber: Stream error", event.error)
                            }
                        }
                    }

                    streamError?.let { throw it }
                    val result = turnResult ?: throw RuntimeException("Stream completed without result")

                    Log.d(TAG, "Turn $turnNumber: LLM response: ${result.content?.take(200)}...")
                    Log.d(TAG, "Turn $turnNumber: Tool calls: ${result.toolCalls.map { it.name }}")

                    trace.llmResponse(turnId, turnNumber, result)

                    if (result.content != null) {
                        services.historyManager.addItem(
                            ResponseItem.Message(
                                role = "assistant",
                                content = result.content
                            )
                        )
                    }

                    val hasCompletionTool = result.toolCalls.any { it.name == "complete_task" }
                    val hasNonCompletionTool = result.toolCalls.any { it.name != "complete_task" }
                    val selectedTool = result.toolCalls.firstOrNull { it.name != "complete_task" }
                        ?: result.toolCalls.firstOrNull()
                    val toolCallsToExecute = selectedTool?.let { listOf(it) } ?: emptyList()
                    if (result.toolCalls.size > 1 && selectedTool != null) {
                        Log.w(
                            TAG,
                            "Turn $turnNumber: Multiple tool calls returned: ${result.toolCalls.map { it.name }}, executing: ${selectedTool.name}"
                        )
                        eventDispatcher.status("⚠️ Multiple actions returned; executing ${selectedTool.name} only")
                    }
                    if (hasCompletionTool && hasNonCompletionTool) {
                        Log.w(
                            TAG,
                            "Turn $turnNumber: complete_task returned alongside other tools; completion deferred"
                        )
                        eventDispatcher.status("⚠️ Completion returned with other actions; executing action first")
                    }

                    // 3. ACT
                    if (toolCallsToExecute.isNotEmpty()) {
                        eventDispatcher.turnPhaseChanged(turnId, TurnPhase.EXECUTION)
                        eventDispatcher.status("💡 Executing actions...")

                        delay(200)
                        var currentSnapshot = snapshot
                        Log.d(TAG, "Using turn snapshot for actions: ${currentSnapshot.elements.size} elements")

                        for (toolCall in toolCallsToExecute) {
                            Log.d(TAG, "Executing tool: ${toolCall.name} with args: ${toolCall.arguments}")
                            trace.toolCall(turnId, turnNumber, toolCall)

                            eventDispatcher.actionProposed(
                                toolCall.id,
                                toolCall.name,
                                ActionDescriptionFormatter.format(toolCall)
                            )

                            services.historyManager.addItem(
                                ResponseItem.FunctionCall(
                                    id = toolCall.id,
                                    name = toolCall.name,
                                    arguments = toolCall.arguments
                                )
                            )

                            val context =
                                SimpleToolRouterContext(
                                    platform = services.platform,
                                    currentSnapshot = currentSnapshot
                                )

                            val toolResult =
                                services.toolRouter.execute(
                                    toolName = toolCall.name,
                                    params = toolCall.arguments,
                                    context = context,
                                    callId = toolCall.id,
                                    onApprovalRequired = { details ->
                                        try {
                                            eventEmitter(
                                                AgentEvent.ApprovalRequired(
                                                    sessionId = config.sessionId,
                                                    timestamp = System.currentTimeMillis(),
                                                    actionId = details.callId,
                                                    description = details.description,
                                                    details = details
                                                )
                                            )
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Failed to emit approval required event", e)
                                        }
                                    }
                                )

                            emitPlanningEvents(toolCall, toolResult)

                            var observation: Observation = Observation.TextOutput("No observation captured.")
                            var observedSnapshot: ScreenSnapshot? = null
                            var hasObservation = false

                            if (toolResult is ToolCallResult.Success) {
                                when (val toolObs = toolResult.observation) {
                                    is ToolObservation.ScreenState -> {
                                        observation = toolObs.toObservation()
                                        observedSnapshot = toolObs.snapshot
                                        hasObservation = true
                                    }
                                    is ToolObservation.TextOutput -> {
                                        observation = toolObs.toObservation()
                                        hasObservation = true
                                    }
                                    null -> Unit
                                }
                            }

                            if (!hasObservation) {
                                if (toolCall.name == "complete_task") {
                                    observation = Observation.TextOutput("Completion acknowledged; no screen captured.")
                                } else {
                                    val capture = captureObservationWithSnapshot()
                                    observation = capture.observation
                                    observedSnapshot = capture.snapshot
                                }
                            }

                            if (observedSnapshot != null) {
                                currentSnapshot = observedSnapshot
                                Log.d(TAG, "Updated snapshot for subsequent tools: ${currentSnapshot.elements.size} elements")
                                eventDispatcher.screenCaptured(
                                    snapshot = currentSnapshot,
                                    packageName = services.platform.getCurrentPackageName(),
                                    activityName = null,
                                    turnId = turnId,
                                    turnNumber = turnNumber,
                                    phase = ScreenStatePhase.POST_ACTION,
                                    traceRunId = services.config.traceRunId
                                )
                            }

                            val formatted = formatToolResult(toolResult, observation)

                            services.historyManager.addItem(
                                ResponseItem.FunctionCallOutput(
                                    callId = toolCall.id,
                                    content = formatted,
                                    success = toolResult is ToolCallResult.Success
                                )
                            )

                            trace.toolResult(
                                turnId = turnId,
                                turnNumber = turnNumber,
                                toolCall = toolCall,
                                toolResult = toolResult,
                                formattedResult = formatted,
                                observation = observation,
                                observedSnapshot = observedSnapshot
                            )

                            eventEmitter(
                                AgentEvent.ActionExecuted(
                                    sessionId = config.sessionId,
                                    timestamp = System.currentTimeMillis(),
                                    actionId = toolResult.callId,
                                    toolName = toolCall.name,
                                    success = toolResult is ToolCallResult.Success,
                                    result = toolResult.toContextString()
                                )
                            )

                            eventDispatcher.status("✓ ${toolCall.name} executed")
                        }
                    }

                    val shouldComplete = result.isComplete && !hasNonCompletionTool
                    if (shouldComplete) {
                        val completeTaskCall = result.toolCalls.find { it.name == "complete_task" }
                        val summary =
                            completeTaskCall?.arguments?.optString("summary")
                                ?: result.content
                                ?: "Goal achieved"
                        Log.i(TAG, "Turn $turnNumber: Task marked as complete - $summary")
                        TurnOutcome.Complete(summary)
                    } else {
                        TurnOutcome.Continue
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Turn execution failed", e)
                trace.turnError(turnId, turnNumber, e)

                val message = e.message ?: ""
                val isDnsFailure =
                    e is java.net.UnknownHostException ||
                        message.contains("Unable to resolve host", ignoreCase = true) ||
                        message.contains("No address associated", ignoreCase = true)

                val isTransientNetworkError =
                    !isDnsFailure &&
                        (e is java.net.SocketTimeoutException ||
                            message.contains("timeout", ignoreCase = true) ||
                            message.contains("connection refused", ignoreCase = true) ||
                            message.contains("connection reset", ignoreCase = true))

                val isContextLimit =
                    message.contains("context length", ignoreCase = true) ||
                        message.contains("maximum context", ignoreCase = true) ||
                        message.contains("context window", ignoreCase = true) ||
                        message.contains("too many tokens", ignoreCase = true) ||
                        message.contains("max tokens", ignoreCase = true)

                TurnOutcome.Error(
                    message = message.ifEmpty { "Unknown error" },
                    recoverable =
                        !isDnsFailure &&
                            !isContextLimit &&
                            (isTransientNetworkError || !message.contains("internet", ignoreCase = true))
                )
            } finally {
                eventDispatcher.turnCompleted(turnId, turnNumber)
                trace.turnCompleted(turnId, turnNumber)
            }

        return outcome
    }

    private data class ObservationCapture(
        val observation: Observation,
        val snapshot: ScreenSnapshot?
    )

    private suspend fun captureObservationWithSnapshot(): ObservationCapture {
        delay(500)
        val snapshot = services.platform.captureScreen()
        val accessibilityTree = Perceptor.toPromptJson(snapshot)
        return ObservationCapture(
            observation = Observation.ScreenState(
                accessibilityTree = accessibilityTree,
                summary = snapshot.toSummary(services.platform.getCurrentPackageName())
            ),
            snapshot = snapshot
        )
    }

    private suspend fun emitPlanningEvents(toolCall: ToolCallRequest, toolResult: ToolCallResult) {
        if (toolResult !is ToolCallResult.Success) return
        when (ToolName.from(toolCall.name)) {
            ToolName.WriteTodos -> {
                eventDispatcher.todosUpdated(services.sessionState.todos.get())
            }
            ToolName.Scratchpad -> {
                val action = toolCall.arguments.optString("action", "")
                if (action == "write" || action == "delete") {
                    val key = toolCall.arguments.optString("key", "")
                    if (key.isNotBlank()) {
                        eventDispatcher.scratchpadUpdated(key, action)
                    }
                }
            }
            else -> Unit
        }
    }

    private fun formatToolResult(result: ToolCallResult, observation: Observation): String {
        val resultText =
            when (result) {
                is ToolCallResult.Success -> "Success: ${result.output}"
                is ToolCallResult.Error -> "Error: ${result.error}"
                is ToolCallResult.Cancelled -> "Cancelled: ${result.reason}"
            }

        val observationText =
            when (observation) {
                is Observation.ScreenState -> "Screen after action: ${observation.summary}"
                is Observation.TextOutput -> observation.content
            }

        return "$resultText\n\n$observationText"
    }
}
