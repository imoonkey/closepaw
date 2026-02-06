package com.moonkey.androidagent.agent

import android.util.Log
import com.moonkey.androidagent.agent.cognition.policy.ExecutorStepDecision
import com.moonkey.androidagent.agent.cognition.policy.ExecutorStepPolicy
import com.moonkey.androidagent.agent.cognition.policy.LoopDetectionPolicy
import com.moonkey.androidagent.agent.cognition.policy.TurnPolicyEngine
import com.moonkey.androidagent.agent.cognition.trace.ArbitrationDecision
import com.moonkey.androidagent.agent.cognition.trace.DropReason
import com.moonkey.androidagent.agent.cognition.trace.DroppedToolCall
import com.moonkey.androidagent.history.ResponseItem
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.perception.toSummary
import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.ScreenStatePhase
import com.moonkey.androidagent.protocol.TurnPhase
import com.moonkey.androidagent.session.SessionServices
import com.moonkey.androidagent.tool.MobileActionName
import com.moonkey.androidagent.tool.ToolName
import com.moonkey.androidagent.tool.SimpleToolRouterContext
import com.moonkey.androidagent.tool.ToolCallResult
import com.moonkey.androidagent.tool.ToolObservation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Executes one full agent turn and returns the next-loop decision.
 *
 * Turn pipeline:
 * 1) PERCEPTION: capture screen and update navigation state
 * 2) THINKING: build prompt/input and get LLM tool calls
 * 3) ACTION: execute selected tools, collect observations, persist history/trace
 */
internal class AgentTurnRunner(
    private val config: AgentConfig,
    private val services: SessionServices,
    private val eventDispatcher: AgentEventDispatcher,
    private val eventEmitter: suspend (AgentEvent) -> Unit,
    private val cancellationSignal: CompletableDeferred<AgentStopReason>,
    private val stopRequested: AtomicBoolean,
    private val promptBuilder: AgentPromptBuilder,
    private val trace: AgentTrace,
    private val turnPolicyEngine: TurnPolicyEngine
) {
    companion object {
        private const val TAG = "AgentTurnRunner"
    }
    private val loopDetectionPolicy by lazy { LoopDetectionPolicy() }
    private val executorStepPolicy by lazy {
        ExecutorStepPolicy(
            maxSteps = config.maxTurns,
            narrativeSummaryOnLimit = true
        )
    }

    /**
     * Runs one turn and never mutates outer `Agent` state directly.
     *
     * All cross-turn state is passed in/out via [TurnRunnerState].
     */
    suspend fun executeTurn(
        turnId: String,
        turnNumber: Int,
        state: TurnRunnerState
    ): TurnExecutionResult {
        trace.turnStarted(turnId, turnNumber)
        var nextState = state

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
                    val navigationState =
                        state.navigationState.advance(
                            snapshot = snapshot,
                            previousAction = state.previousActionSignature
                        )
                    nextState = nextState.copy(navigationState = navigationState)

                    val loopWarning = loopDetectionPolicy.detect(navigationState)
                    if (loopWarning != null) {
                        Log.w(TAG, "Turn $turnNumber loop warning: ${loopWarning.message}")
                        eventDispatcher.status("⚠️ ${loopWarning.message}")
                    }

                    val stepDecision =
                        executorStepPolicy.evaluate(
                            stepCount = turnNumber,
                            delegatedQuery = config.goal,
                            history = services.historyManager.getAll()
                        )
                    val stepReminder = buildStepReminder(turnNumber, stepDecision)
                    if (stepReminder != null) {
                        eventDispatcher.status("⚠️ Turn budget warning: approaching limit")
                    }

                    // 2. THINK (LLM)
                    eventDispatcher.turnPhaseChanged(turnId, TurnPhase.PLANNING)
                    eventDispatcher.status("🧠 Thinking...")

                    val turn = Turn(
                        historyManager = services.historyManager,
                        toolRegistry = services.toolRegistry,
                        llmClient = services.llmClient,
                        allowedToolNames = config.allowedToolNames
                    )
                    val systemPrompt = promptBuilder.buildSystemPrompt()
                    val userContext =
                        promptBuilder.buildUserContext(
                            snapshot = snapshot,
                            loopWarning = loopWarning,
                            systemReminders = listOfNotNull(stepReminder)
                        )
                    val inputItems = turn.buildInputItems(userContext)

                    trace.llmRequest(
                        turnId = turnId,
                        turnNumber = turnNumber,
                        snapshot = snapshot,
                        systemPrompt = systemPrompt,
                        userContextText = userContext.text,
                        history = services.historyManager.forPrompt(),
                        inputItems = inputItems
                    )

                    var turnResult: TurnResult? = null
                    var streamError: Throwable? = null

                    turn.runStreaming(
                        systemPrompt = systemPrompt,
                        userContext = userContext,
                        modelName = services.config.model,
                        inputItemsOverride = inputItems
                    ).collect { event ->
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

                    val arbitration = turnPolicyEngine.arbitrateToolCalls(result.toolCalls)
                    trace.arbitrationDecision(
                        turnId = turnId,
                        turnNumber = turnNumber,
                        decision = buildArbitrationDecision(result.toolCalls, arbitration)
                    )
                    val toolCallsToExecute = arbitration.selectedToolCalls
                    if (result.toolCalls.size > 1 && arbitration.selectedTool != null) {
                        val toolNames = result.toolCalls.map { it.name }
                        Log.w(TAG, "Turn $turnNumber: Multiple tool calls returned: $toolNames, executing: ${arbitration.selectedTool.name}")
                        eventDispatcher.status("⚠️ Multiple actions returned; executing ${arbitration.selectedTool.name} only")
                    }
                    if (arbitration.hasCompletionTool && arbitration.hasNonCompletionTool) {
                        Log.w(TAG, "Turn $turnNumber: complete_task returned alongside other tools; completion deferred")
                        eventDispatcher.status("⚠️ Completion returned with other actions; executing action first")
                    }

                    // 3. ACT
                    var actionForNextTurn: String? = null
                    if (toolCallsToExecute.isNotEmpty()) {
                        eventDispatcher.turnPhaseChanged(turnId, TurnPhase.EXECUTION)
                        eventDispatcher.status("💡 Executing actions...")

                        delay(200)
                        var currentSnapshot = snapshot
                        Log.d(TAG, "Using turn snapshot for actions: ${currentSnapshot.elements.size} elements")

                        for (toolCall in toolCallsToExecute) {
                            actionForNextTurn = classifyAction(toolCall)
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

                            val (observation, observedSnapshot) = when {
                                toolResult is ToolCallResult.Success && toolResult.observation != null -> {
                                    val obs = toolResult.observation
                                    when (obs) {
                                        is ToolObservation.ScreenState -> obs to obs.snapshot
                                        is ToolObservation.TextOutput -> obs to null
                                    }
                                }
                                toolCall.name == "complete_task" -> {
                                    Log.d(TAG, "Skipping post-action capture for complete_task")
                                    ToolObservation.TextOutput("Completion acknowledged; no screen captured.") to null
                                }
                                else -> {
                                    val capture = captureObservationWithSnapshot()
                                    capture.observation to capture.snapshot
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
                    nextState = nextState.copy(previousActionSignature = actionForNextTurn)

                    val completion = turnPolicyEngine.decideCompletion(result, arbitration)
                    if (completion.shouldComplete) {
                        val summary = completion.summary ?: "Goal achieved"
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
                val causes = generateSequence(e as Throwable?) { it.cause }.toList()

                fun anyMessageContains(keyword: String): Boolean {
                    return causes.any { cause ->
                        cause.message?.contains(keyword, ignoreCase = true) == true
                    }
                }

                val isDnsFailure = causes.any { it is java.net.UnknownHostException } ||
                    anyMessageContains("Unable to resolve host") ||
                    anyMessageContains("No address associated")

                val isContextLimit = anyMessageContains("context length") ||
                    anyMessageContains("maximum context") ||
                    anyMessageContains("context window") ||
                    anyMessageContains("too many tokens") ||
                    anyMessageContains("max tokens")

                val isTransientNetworkError = !isDnsFailure && !isContextLimit &&
                    (causes.any { it is java.net.SocketTimeoutException } ||
                        anyMessageContains("timeout") ||
                        anyMessageContains("connection refused") ||
                        anyMessageContains("connection reset"))

                TurnOutcome.Error(
                    message = message.ifEmpty { "Unknown error" },
                    recoverable = isTransientNetworkError
                )
            } finally {
                eventDispatcher.turnCompleted(turnId, turnNumber)
                trace.turnCompleted(turnId, turnNumber)
            }

        return TurnExecutionResult(
            outcome = outcome,
            nextState = nextState
        )
    }

    private data class ObservationCapture(
        val observation: ToolObservation,
        val snapshot: ScreenSnapshot?
    )

    /**
     * Captures a fresh post-action screen snapshot and wraps it as a screen observation.
     */
    private suspend fun captureObservationWithSnapshot(): ObservationCapture {
        delay(500)
        val snapshot = services.platform.captureScreen()
        val accessibilityTree = Perceptor.toPromptJson(snapshot)
        return ObservationCapture(
            observation = ToolObservation.ScreenState(
                accessibilityTree = accessibilityTree,
                elementCount = snapshot.elements.size,
                summary = snapshot.toSummary(services.platform.getCurrentPackageName()),
                snapshot = snapshot
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

    private fun formatToolResult(result: ToolCallResult, observation: ToolObservation): String {
        val resultText =
            when (result) {
                is ToolCallResult.Success -> "Success: ${result.output}"
                is ToolCallResult.Error -> "Error: ${result.error}"
                is ToolCallResult.Cancelled -> "Cancelled: ${result.reason}"
            }

        val observationText =
            when (observation) {
                is ToolObservation.ScreenState -> "Screen after action: ${observation.summary}"
                is ToolObservation.TextOutput -> observation.content
            }

        return "$resultText\n\n$observationText"
    }

    private fun buildStepReminder(turnNumber: Int, stepDecision: ExecutorStepDecision): String? {
        return when (stepDecision) {
            ExecutorStepDecision.Continue -> null
            ExecutorStepDecision.WarnApproaching ->
                """
                <system_reminder>
                TURN BUDGET WARNING: turn $turnNumber of ${config.maxTurns}. Prioritize decisive action and avoid repeating failed attempts.
                </system_reminder>
                """.trimIndent()
            is ExecutorStepDecision.ForceStop ->
                """
                <system_reminder>
                FINAL TURN WARNING: this turn is at configured limit (${config.maxTurns}).
                ${stepDecision.narrativeSummary}
                </system_reminder>
                """.trimIndent()
        }
    }

    private fun classifyAction(toolCall: ToolCallRequest): String {
        if (toolCall.name != ToolName.MobileAction.raw) {
            return toolCall.name.lowercase()
        }

        val action = toolCall.arguments.optString("action", "").trim().lowercase()
        val mobileActionName = MobileActionName.from(action)
        return when (mobileActionName) {
            MobileActionName.Scroll -> "scroll:legacy"
            MobileActionName.Swipe -> {
                val direction = toolCall.arguments.optString("direction", "").trim().lowercase()
                "scroll:${direction.ifBlank { "unknown" }}"
            }
            else -> "mobile_action:${mobileActionName.canonical}"
        }
    }

    private fun buildArbitrationDecision(
        originalCalls: List<ToolCallRequest>,
        arbitration: com.moonkey.androidagent.agent.cognition.policy.ToolArbitrationResult
    ): ArbitrationDecision {
        val originalNameCounts = originalCalls.groupingBy { it.name }.eachCount()
        val selectedToolIds = arbitration.selectedToolCalls.map { it.id }.toSet()
        val dropped =
            originalCalls
                .filterNot { it.id in selectedToolIds }
                .map { call ->
                    val reason =
                        when {
                            call.name == "complete_task" && arbitration.hasNonCompletionTool ->
                                DropReason.COMPLETE_TASK_DEFERRED
                            (originalNameCounts[call.name] ?: 0) > 1 ->
                                DropReason.DUPLICATE_TOOL
                            arbitration.selectedToolCalls.isNotEmpty() ->
                                DropReason.MAX_TOOLS_EXCEEDED
                            else -> DropReason.POLICY_REJECTION
                        }
                    DroppedToolCall(toolName = call.name, reason = reason)
                }

        return ArbitrationDecision(
            selectedTool = arbitration.selectedTool,
            droppedToolCalls = dropped,
            selectedToolCount = arbitration.selectedToolCalls.size,
            originalToolCount = originalCalls.size
        )
    }
}
