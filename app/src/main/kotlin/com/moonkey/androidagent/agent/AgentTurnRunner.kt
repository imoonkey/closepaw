package com.moonkey.androidagent.agent

import android.util.Log
import com.moonkey.androidagent.agent.cognition.context.LoopWarning
import com.moonkey.androidagent.agent.cognition.context.LoopWarningSeverity
import com.moonkey.androidagent.agent.cognition.policy.ExecutorStepDecision
import com.moonkey.androidagent.agent.cognition.policy.ExecutorStepPolicy
import com.moonkey.androidagent.agent.cognition.policy.LoopDetectionPolicy
import com.moonkey.androidagent.agent.cognition.policy.ToolArbitrationResult
import com.moonkey.androidagent.agent.cognition.policy.TurnToolPolicy
import com.moonkey.androidagent.agent.cognition.prompt.PromptBuilder
import com.moonkey.androidagent.trace.AgentTrace
import com.moonkey.androidagent.trace.ArbitrationDecision
import com.moonkey.androidagent.trace.DropReason
import com.moonkey.androidagent.trace.DroppedToolCall
import com.moonkey.androidagent.history.ResponseItem
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.perception.toSummary
import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.ApprovalDetails
import com.moonkey.androidagent.protocol.ScreenStatePhase
import com.moonkey.androidagent.protocol.TurnPhase
import com.moonkey.androidagent.session.SessionServices
import com.moonkey.androidagent.tool.MobileActionName
import com.moonkey.androidagent.tool.SimpleToolRouterContext
import com.moonkey.androidagent.tool.ToolCallResult
import com.moonkey.androidagent.tool.ToolName
import com.moonkey.androidagent.tool.ToolObservation
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * Executes one full agent turn and returns the next-loop decision.
 *
 * Turn pipeline: 1) PERCEPTION: capture screen and update navigation state 2) THINKING: build
 * prompt/input and get LLM tool calls 3) ACTION: execute selected tools, collect observations,
 * persist history/trace
 */
internal class AgentTurnRunner(
        private val config: AgentExecutionConfig,
        private val services: SessionServices,
        private val eventDispatcher: AgentEventDispatcher,
        private val eventEmitter: suspend (AgentEvent) -> Unit,
        private val cancellationSignal: CompletableDeferred<AgentStopReason>,
        private val stopRequested: AtomicBoolean,
        private val trace: AgentTrace,
        private val turnPolicyEngine: TurnToolPolicy
) {
        companion object {
                private const val TAG = "AgentTurnRunner"
        }
        private val loopDetectionPolicy by lazy { LoopDetectionPolicy() }
        private val executorStepPolicy by lazy {
                ExecutorStepPolicy(maxSteps = config.maxTurns, narrativeSummaryOnLimit = true)
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
                                val snapshot = capturePreTurnSnapshot(turnId, turnNumber)
                                if (isTurnCancelled()) {
                                        TurnOutcome.Cancelled
                                } else {
                                        val preparedTurn =
                                                prepareTurn(turnNumber, nextState, snapshot)
                                        nextState = preparedTurn.nextState

                                        val planningResult =
                                                runPlanningPhase(
                                                        turnId = turnId,
                                                        turnNumber = turnNumber,
                                                        snapshot = snapshot,
                                                        warnings = preparedTurn.warnings
                                                )

                                        val actionForNextTurn =
                                                executeActions(
                                                        turnId = turnId,
                                                        turnNumber = turnNumber,
                                                        initialSnapshot = snapshot,
                                                        toolCallsToExecute =
                                                                planningResult.arbitration
                                                                        .selectedToolCalls
                                                )
                                        nextState =
                                                nextState.copy(
                                                        previousActionSignature = actionForNextTurn
                                                )

                                        decideTurnOutcome(
                                                turnNumber = turnNumber,
                                                result = planningResult.turnResult,
                                                arbitration = planningResult.arbitration
                                        )
                                }
                        } catch (e: Exception) {
                                handleTurnFailure(turnId, turnNumber, e)
                        } finally {
                                eventDispatcher.turnCompleted(turnId, turnNumber)
                                trace.turnCompleted(turnId, turnNumber)
                        }

                return TurnExecutionResult(outcome = outcome, nextState = nextState)
        }

        private data class PreparedTurn(
                val nextState: TurnRunnerState,
                val loopWarning: LoopWarning?,
                val warnings: List<String>
        )

        private data class PlanningPhaseResult(
                val turnResult: TurnResult,
                val arbitration: ToolArbitrationResult
        )

        private suspend fun capturePreTurnSnapshot(
                turnId: String,
                turnNumber: Int
        ): ScreenSnapshot {
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
                logSnapshotElements(turnNumber, snapshot)
                return snapshot
        }

        private fun logSnapshotElements(turnNumber: Int, snapshot: ScreenSnapshot) {
                Log.d(TAG, "Turn $turnNumber: Screen has ${snapshot.elements.size} elements")
                if (!config.debugMode) return
                Log.d(TAG, "Turn $turnNumber: Elements (first 20):")
                snapshot.elements.take(20).forEach { elem ->
                        val text = elem.text.ifEmpty { elem.description }.take(25)
                        val flags = buildString {
                                if (elem.isClickable) append("C")
                                if (elem.isEditable) append("E")
                                if (elem.isScrollable) append("S")
                        }
                        Log.d(
                                TAG,
                                "  [${elem.index}] \"$text\" $flags @(${elem.center.x},${elem.center.y})"
                        )
                }
        }

        private fun isTurnCancelled(): Boolean {
                return cancellationSignal.isCompleted || stopRequested.get()
        }

        private suspend fun prepareTurn(
                turnNumber: Int,
                state: TurnRunnerState,
                snapshot: ScreenSnapshot
        ): PreparedTurn {
                val navigationState =
                        state.navigationState.advance(
                                snapshot = snapshot,
                                previousAction = state.previousActionSignature
                        )
                val nextState = state.copy(navigationState = navigationState)

                val loopWarning = loopDetectionPolicy.detect(navigationState)
                loopWarning?.let {
                        Log.w(TAG, "Turn $turnNumber loop warning: ${it.message}")
                        eventDispatcher.status("⚠️ ${it.message}")
                }

                val stepDecision =
                        executorStepPolicy.evaluate(
                                stepCount = turnNumber,
                                delegatedQuery = config.goal,
                                history = services.historyManager.getAll()
                        )
                val warnings = buildWarnings(loopWarning, stepDecision)

                return PreparedTurn(
                        nextState = nextState,
                        loopWarning = loopWarning,
                        warnings = warnings
                )
        }

        private suspend fun runPlanningPhase(
                turnId: String,
                turnNumber: Int,
                snapshot: ScreenSnapshot,
                warnings: List<String>
        ): PlanningPhaseResult {
                eventDispatcher.turnPhaseChanged(turnId, TurnPhase.PLANNING)
                eventDispatcher.status("🧠 Thinking...")

                val modelEntry = services.modelCatalog.resolveOrNull(config.modelName)
                val llmClient = if (modelEntry != null) {
                        services.llmClientFactory.create(config.modelName)
                } else {
                        Log.w(TAG, "Model '${config.modelName}' not in catalog; using legacy llmClient")
                        services.llmClient
                }
                val modelId = modelEntry?.modelId ?: config.modelName
                val supportsVision = modelEntry?.supportsVision ?: true

                val turn =
                        Turn(
                                toolRegistry = services.toolRegistry,
                                llmClient = llmClient,
                                allowedToolNames = config.allowedToolNames
                        )
                val systemPrompt = requireNotNull(config.systemPrompt) {
                        "System prompt must be provided by AgentDef."
                }
                val promptBuilder = PromptBuilder(
                        historyManager = services.historyManager,
                        sessionState = services.sessionState,
                        supportsVision = supportsVision,
                        perceptionConfig = services.config.perceptionConfig
                )
                val inputItems = promptBuilder.buildInputItems(
                        snapshot = snapshot,
                        image = snapshot.image,
                        warnings = warnings
                )

                // Record screen observation for future turns (after prompt built)
                recordScreenObservation(snapshot)

                trace.llmRequest(
                        turnId = turnId,
                        turnNumber = turnNumber,
                        snapshot = snapshot,
                        systemPrompt = systemPrompt,
                        userContextText = "(built by PromptBuilder)",
                        history = services.historyManager.forPrompt(),
                        inputItems = inputItems
                )

                var turnResult: TurnResult? = null
                var streamError: Throwable? = null
                turn.runStreaming(
                                systemPrompt = systemPrompt,
                                inputItems = inputItems,
                                model = modelId
                        )
                        .collect { event ->
                                when (event) {
                                        is TurnStreamEvent.TextDelta ->
                                                eventDispatcher.messageDelta(turnId, event.text)
                                        is TurnStreamEvent.ToolCallReceived ->
                                                Log.d(
                                                        TAG,
                                                        "Turn $turnNumber: Received tool call: ${event.toolCall.name}"
                                                )
                                        is TurnStreamEvent.Complete -> {
                                                turnResult = event.result
                                                Log.d(
                                                        TAG,
                                                        "Turn $turnNumber: Stream complete, isComplete=${event.result.isComplete}"
                                                )
                                        }
                                        is TurnStreamEvent.Error -> {
                                                streamError = event.error
                                                Log.e(
                                                        TAG,
                                                        "Turn $turnNumber: Stream error",
                                                        event.error
                                                )
                                        }
                                }
                        }

                streamError?.let { throw it }
                val result =
                        turnResult ?: throw RuntimeException("Stream completed without result")

                Log.d(TAG, "Turn $turnNumber: LLM response: ${result.content?.take(200)}...")
                Log.d(TAG, "Turn $turnNumber: Tool calls: ${result.toolCalls.map { it.name }}")

                trace.llmResponse(turnId, turnNumber, result)
                result.content?.let { content ->
                        services.historyManager.addItem(
                                ResponseItem.Message(role = "assistant", content = content)
                        )
                }

                val arbitration = turnPolicyEngine.arbitrateToolCalls(result.toolCalls)
                trace.arbitrationDecision(
                        turnId = turnId,
                        turnNumber = turnNumber,
                        decision = buildArbitrationDecision(result.toolCalls, arbitration)
                )
                emitArbitrationWarnings(turnNumber, result, arbitration)
                return PlanningPhaseResult(turnResult = result, arbitration = arbitration)
        }

        /**
         * Build plain-text warning strings for the current observation.
         *
         * Per review: only loop warnings and final-turn warning.
         * Turn budget approaching warnings are intentionally omitted (less noise).
         */
        private fun buildWarnings(
                loopWarning: LoopWarning?,
                stepDecision: ExecutorStepDecision
        ): List<String> = buildList {
                loopWarning?.let {
                        val emoji = if (it.severity == LoopWarningSeverity.CRITICAL) "🚨" else "⚠️"
                        add("$emoji ${it.message}")
                }
                if (stepDecision is ExecutorStepDecision.ForceStop) {
                        add("🛑 FINAL TURN (${config.maxTurns}). Complete now or report progress.")
                }
        }

        /**
         * Record the current screen observation into history so future turns
         * can see what this turn saw. Called after prompt is built but before
         * the LLM call, so the prompt doesn't duplicate the current screen.
         *
         * In screenshot-only mode the a11y tree is omitted from history to
         * keep the context consistent with what the LLM actually sees.
         */
        private fun recordScreenObservation(snapshot: ScreenSnapshot) {
                val pc = services.config.perceptionConfig
                val text = if (pc.capturesAccessibility) {
                        val screenJson = Perceptor.toPromptJson(snapshot)
                        buildString {
                                appendLine("Screen state (${snapshot.elements.size} elements):")
                                appendLine("```json")
                                appendLine(screenJson)
                                append("```")
                        }
                } else {
                        "(Screenshot-only mode — accessibility tree omitted from history)"
                }
                services.historyManager.addItem(
                        ResponseItem.Message(
                                role = "user",
                                content = text.trim(),
                                isScreenObservation = true
                        )
                )
        }

        private suspend fun emitArbitrationWarnings(
                turnNumber: Int,
                result: TurnResult,
                arbitration: ToolArbitrationResult
        ) {
                if (result.toolCalls.size > 1 && arbitration.selectedTool != null) {
                        val toolNames = result.toolCalls.map { it.name }
                        Log.w(
                                TAG,
                                "Turn $turnNumber: Multiple tool calls returned: $toolNames, executing: ${arbitration.selectedTool.name}"
                        )
                        eventDispatcher.status(
                                "⚠️ Multiple actions returned; executing ${arbitration.selectedTool.name} only"
                        )
                }
                if (arbitration.hasCompletionTool && arbitration.hasNonCompletionTool) {
                        Log.w(
                                TAG,
                                "Turn $turnNumber: complete_task returned alongside other tools; completion deferred"
                        )
                        eventDispatcher.status(
                                "⚠️ Completion returned with other actions; executing action first"
                        )
                }
        }

        private suspend fun executeActions(
                turnId: String,
                turnNumber: Int,
                initialSnapshot: ScreenSnapshot,
                toolCallsToExecute: List<ToolCallRequest>
        ): String? {
                if (toolCallsToExecute.isEmpty()) return null

                eventDispatcher.turnPhaseChanged(turnId, TurnPhase.EXECUTION)
                eventDispatcher.status("💡 Executing actions...")
                delay(200)

                var currentSnapshot = initialSnapshot
                var actionForNextTurn: String? = null
                Log.d(
                        TAG,
                        "Using turn snapshot for actions: ${currentSnapshot.elements.size} elements"
                )

                for (toolCall in toolCallsToExecute) {
                        actionForNextTurn = classifyAction(toolCall)
                        currentSnapshot =
                                executeSingleToolCall(
                                        turnId = turnId,
                                        turnNumber = turnNumber,
                                        toolCall = toolCall,
                                        currentSnapshot = currentSnapshot
                                )
                }
                return actionForNextTurn
        }

        private suspend fun executeSingleToolCall(
                turnId: String,
                turnNumber: Int,
                toolCall: ToolCallRequest,
                currentSnapshot: ScreenSnapshot
        ): ScreenSnapshot {
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

                val toolResult =
                        services.toolRouter.execute(
                                toolName = toolCall.name,
                                params = toolCall.arguments,
                                context =
                                        SimpleToolRouterContext(
                                                platform = services.platform,
                                                currentSnapshot = currentSnapshot
                                        ),
                                callId = toolCall.id,
                                onApprovalRequired = { details -> emitApprovalRequired(details) }
                        )

                emitPlanningEvents(toolCall, toolResult)

                val observationCapture = resolveObservation(toolCall, toolResult)
                val observation = observationCapture.observation
                val observedSnapshot = observationCapture.snapshot
                val snapshotForNextTool = observedSnapshot ?: currentSnapshot

                if (observedSnapshot != null) {
                        Log.d(
                                TAG,
                                "Updated snapshot for subsequent tools: ${observedSnapshot.elements.size} elements"
                        )
                        eventDispatcher.screenCaptured(
                                snapshot = observedSnapshot,
                                packageName = services.platform.getCurrentPackageName(),
                                activityName = null,
                                turnId = turnId,
                                turnNumber = turnNumber,
                                phase = ScreenStatePhase.POST_ACTION,
                                traceRunId = services.config.traceRunId
                        )
                }

                val formatted = formatToolResult(toolResult)
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
                return snapshotForNextTool
        }

        private suspend fun emitApprovalRequired(details: ApprovalDetails) {
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

        private suspend fun resolveObservation(
                toolCall: ToolCallRequest,
                toolResult: ToolCallResult
        ): ObservationCapture {
                if (toolResult is ToolCallResult.Success && toolResult.observation != null) {
                        val observation = toolResult.observation
                        return when (observation) {
                                is ToolObservation.ScreenState ->
                                        ObservationCapture(observation, observation.snapshot)
                                is ToolObservation.TextOutput -> ObservationCapture(observation, null)
                        }
                }

                if (toolCall.name == "complete_task") {
                        Log.d(TAG, "Skipping post-action capture for complete_task")
                        return ObservationCapture(
                                observation =
                                        ToolObservation.TextOutput(
                                                "Completion acknowledged; no screen captured."
                                        ),
                                snapshot = null
                        )
                }

                return captureObservationWithSnapshot()
        }

        private fun decideTurnOutcome(
                turnNumber: Int,
                result: TurnResult,
                arbitration: ToolArbitrationResult
        ): TurnOutcome {
                val completion = turnPolicyEngine.decideCompletion(result, arbitration)
                if (!completion.shouldComplete) {
                        return TurnOutcome.Continue
                }
                val summary = completion.summary ?: "Goal achieved"
                Log.i(TAG, "Turn $turnNumber: Task marked as complete - $summary")
                return TurnOutcome.Complete(summary)
        }

        private fun handleTurnFailure(
                turnId: String,
                turnNumber: Int,
                error: Exception
        ): TurnOutcome.Error {
                Log.e(TAG, "Turn execution failed", error)
                trace.turnError(turnId, turnNumber, error)

                val message = error.message.orEmpty()
                val causes = generateSequence(error as Throwable?) { it.cause }.toList()

                fun anyMessageContains(keyword: String): Boolean {
                        return causes.any { cause ->
                                cause.message?.contains(keyword, ignoreCase = true) == true
                        }
                }

                val isDnsFailure =
                        causes.any { it is java.net.UnknownHostException } ||
                                anyMessageContains("Unable to resolve host") ||
                                anyMessageContains("No address associated")

                val isContextLimit =
                        anyMessageContains("context length") ||
                                anyMessageContains("maximum context") ||
                                anyMessageContains("context window") ||
                                anyMessageContains("too many tokens") ||
                                anyMessageContains("max tokens")

                val isTransientNetworkError =
                        !isDnsFailure &&
                                !isContextLimit &&
                                (causes.any { it is java.net.SocketTimeoutException } ||
                                        anyMessageContains("timeout") ||
                                        anyMessageContains("connection refused") ||
                                        anyMessageContains("connection reset"))

                return TurnOutcome.Error(
                        message = message.ifEmpty { "Unknown error" },
                        recoverable = isTransientNetworkError
                )
        }

        private data class ObservationCapture(
                val observation: ToolObservation,
                val snapshot: ScreenSnapshot?
        )

        /** Captures a fresh post-action screen snapshot and wraps it as a screen observation. */
        private suspend fun captureObservationWithSnapshot(): ObservationCapture {
                delay(500)
                val snapshot = services.platform.captureScreen()
                val accessibilityTree = Perceptor.toPromptJson(snapshot)
                return ObservationCapture(
                        observation =
                                ToolObservation.ScreenState(
                                        accessibilityTree = accessibilityTree,
                                        elementCount = snapshot.elements.size,
                                        summary =
                                                snapshot.toSummary(
                                                        services.platform.getCurrentPackageName()
                                                ),
                                        snapshot = snapshot
                                ),
                        snapshot = snapshot
                )
        }

        private suspend fun emitPlanningEvents(
                toolCall: ToolCallRequest,
                toolResult: ToolCallResult
        ) {
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

        /** Meta-only: no screen state in tool results. */
        private fun formatToolResult(result: ToolCallResult): String = when (result) {
                is ToolCallResult.Success -> "Success: ${result.output}"
                is ToolCallResult.Error -> "Error: ${result.error}"
                is ToolCallResult.Cancelled -> "Cancelled: ${result.reason}"
        }

        private fun classifyAction(toolCall: ToolCallRequest): String {
                when (ToolName.from(toolCall.name)) {
                        ToolName.Wait -> return "mobile_action:wait"
                        ToolName.SystemButton -> return "mobile_action:system_button"
                        else -> Unit
                }

                if (toolCall.name != ToolName.MobileAction.raw) {
                        return toolCall.name.lowercase()
                }

                val action = toolCall.arguments.optString("action", "").trim().lowercase()
                val mobileActionName = MobileActionName.from(action)
                return when (mobileActionName) {
                        MobileActionName.Scroll -> "scroll:legacy"
                        MobileActionName.Swipe -> {
                                val direction =
                                        toolCall.arguments
                                                .optString("direction", "")
                                                .trim()
                                                .lowercase()
                                "scroll:${direction.ifBlank { "unknown" }}"
                        }
                        else -> "mobile_action:${mobileActionName.canonical}"
                }
        }

        private fun buildArbitrationDecision(
                originalCalls: List<ToolCallRequest>,
                arbitration: ToolArbitrationResult
        ): ArbitrationDecision {
                val originalNameCounts = originalCalls.groupingBy { it.name }.eachCount()
                val selectedToolIds = arbitration.selectedToolCalls.map { it.id }.toSet()
                val dropped =
                        originalCalls.filterNot { it.id in selectedToolIds }.map { call ->
                                val reason =
                                        when {
                                                call.name == "complete_task" &&
                                                        arbitration.hasNonCompletionTool ->
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
