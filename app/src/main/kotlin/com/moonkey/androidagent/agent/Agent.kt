package com.moonkey.androidagent.agent

import android.util.Log
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.history.ResponseItem
import com.moonkey.androidagent.tool.SimpleToolRouterContext
import com.moonkey.androidagent.tool.ToolCallResult
import com.moonkey.androidagent.tool.ToolObservation
import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.ApprovalDetails
import com.moonkey.androidagent.protocol.TurnPhase
import com.moonkey.androidagent.session.SessionServices
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Agent - Single ReAct agent that executes goals.
 * 
 * Reference: labmat's AgentCycle (cycle.py)
 * 
 * Design principles:
 * - Simple ReAct loop: Perceive → Think → Act → Observe
 * - No multi-agent complexity (Manager/Executor/Reflector removed)
 * - Tool results include post-action screen observation
 * - Streaming support: Uses a streaming turn, collects MessageDelta events,
 *   accumulates them into full messages, and then processes/emits the result
 */
class Agent(
    private val config: AgentConfig,
    private val services: SessionServices,
    private val eventEmitter: suspend (AgentEvent) -> Unit,
    private val cancellationSignal: CompletableDeferred<AgentStopReason>
) {
    companion object {
        private const val TAG = "Agent"
        
        // Base system prompt - Turn.kt will append tool usage guidelines
        private val DEFAULT_SYSTEM_PROMPT = """
            You are an Android automation agent. You control the device using tools.
            
            Your task is to achieve the user's goal by:
            1. Observing the current screen state (provided as a JSON list of UI elements)
            2. Deciding what action to take based on the screen
            3. Executing the action using available tools
            4. Observing the result and continuing until done
            5. If you have achieved the goal, call complete_task to wrap up. Do NOT call it prematurely.

            Your start screen maybe the Android Agent app itself, or any other screen. 
            Your actions should almost always start with directly opening or navigating to the right app/page first.
        """.trimIndent()

        private val LOCAL_PROMPT_SUFFIX = """
            ## LOCAL MODEL TOOL CALLING
            
            - Use function calling with the registered tools. Do NOT emit <action> tags or raw JSON.
            - Call exactly one tool per turn (mobile_action or app_control) unless you are completing.
            - When the goal is achieved, call complete_task with status and answer.
        """.trimIndent()
    }
    
    // State - using thread-safe primitives to avoid race conditions
    private var turnCount = 0
    private val pauseState = MutableStateFlow(false)
    private val stopRequested = AtomicBoolean(false)
    private val lifecycleMutex = Mutex()  // Protects pause/resume/stop operations
    private val promptBuilder = AgentPromptBuilder(
        basePrompt = config.systemPrompt,
        defaultPrompt = DEFAULT_SYSTEM_PROMPT,
        localPromptSuffix = LOCAL_PROMPT_SUFFIX,
        llmBackend = services.config.llmBackend,
        toolRegistry = services.toolRegistry
    )
    private val eventDispatcher = AgentEventDispatcher(
        sessionId = config.sessionId,
        eventEmitter = eventEmitter
    )
    
    /**
     * Run the agent until goal achieved, max turns, or stopped.
     */
    suspend fun run(): AgentStopReason {
        Log.i(TAG, "Starting agent for goal: ${config.goal}")
        eventDispatcher.status("🚀 Starting agent...")
        
        // Add initial user message to history
        services.historyManager.addItem(
            ResponseItem.Message(
                role = "user",
                content = "Goal: ${config.goal}"
            )
        )
        
        while (shouldContinue()) {
            // Check pause
            if (pauseState.value) {
                eventDispatcher.status("⏸️ Paused - waiting to resume...")
                pauseState.first { !it }
                eventDispatcher.status("▶️ Resuming...")
            }
            // Re-check stop/cancel after pause wait to avoid running another turn
            if (!shouldContinue()) {
                eventDispatcher.status("🛑 Cancelled")
                return AgentStopReason.UserRequested
            }
            
            // Check max turns
            if (turnCount >= config.maxTurns) {
                Log.w(TAG, "Max turns (${config.maxTurns}) reached")
                eventDispatcher.status("⚠️ Max turns reached")
                return AgentStopReason.MaxTurnsReached
            }
            
            // Execute one turn
            val result = executeTurn()
            
            when (result) {
                is TurnOutcome.Continue -> {
                    delay(config.uiSettleDelayMs)
                }
                is TurnOutcome.Complete -> {
                    eventDispatcher.status("✅ Goal achieved!")
                    return AgentStopReason.GoalAchieved
                }
                is TurnOutcome.Error -> {
                    if (!result.recoverable) {
                        eventDispatcher.status("❌ Error: ${result.message}")
                        return AgentStopReason.Error(result.message)
                    }
                    // Recoverable error - continue with delay
                    eventDispatcher.status("⚠️ Error (retrying): ${result.message}")
                    delay(config.uiSettleDelayMs)
                }
                TurnOutcome.Cancelled -> {
                    eventDispatcher.status("🛑 Cancelled")
                    return AgentStopReason.UserRequested
                }
            }
        }

        return when {
            stopRequested.get() -> AgentStopReason.UserRequested
            cancellationSignal.isCompleted -> AgentStopReason.UserRequested
            else -> AgentStopReason.GoalAchieved
        }
    }
    
    /**
     * Execute a single turn of the ReAct loop with streaming.
     */
    private suspend fun executeTurn(): TurnOutcome {
        turnCount++
        val turnId = "turn-$turnCount"
        Log.d(TAG, "=== TURN $turnCount START ===")

        eventDispatcher.turnStarted(turnId, turnCount)

        val outcome = try {
            // 1. PERCEPTION: Capture screen
            eventDispatcher.status("👀 Scanning screen...")

            val snapshot = services.platform.captureScreen()
            eventDispatcher.screenCaptured(
                snapshot = snapshot,
                packageName = services.platform.getCurrentPackageName(),
                activityName = null
            )

            Log.d(TAG, "Turn $turnCount: Screen has ${snapshot.elements.size} elements")
            
            // Log element details only in debug mode to avoid PII exposure in production
            if (config.debugMode) {
                Log.d(TAG, "Turn $turnCount: Elements (first 20):")
                snapshot.elements.take(20).forEach { elem ->
                    val text = elem.text.ifEmpty { elem.description }.take(25)
                    val flags = buildString {
                        if (elem.isClickable) append("C")
                        if (elem.isEditable) append("E")
                        if (elem.isScrollable) append("S")
                    }
                    Log.d(TAG, "  [${elem.index}] \"$text\" $flags @(${elem.center.x},${elem.center.y})")
                }
            }

            // Check cancellation
            if (cancellationSignal.isCompleted || stopRequested.get()) {
                TurnOutcome.Cancelled
            } else {
                // 2. THINK: Call LLM with streaming
                eventDispatcher.turnPhaseChanged(turnId, TurnPhase.PLANNING)
                eventDispatcher.status("🧠 Thinking...")

                val turn = Turn(services.historyManager, services.toolRegistry, services.llmClient)
                val systemPrompt = promptBuilder.buildSystemPrompt()
                val userContext = promptBuilder.buildUserContext(snapshot)

                // Collect streaming response
                // Note: Turn.runStreaming() accumulates text/toolCalls internally and returns
                // the final result via TurnStreamEvent.Complete. We only need to forward deltas
                // for UI streaming and capture the final result.
                var turnResult: TurnResult? = null
                var streamError: Throwable? = null
                
                turn.runStreaming(systemPrompt, userContext, services.config.model).collect { event ->
                    when (event) {
                        is TurnStreamEvent.TextDelta -> {
                            // Emit MessageDelta for UI streaming
                            eventDispatcher.messageDelta(turnId, event.text)
                        }
                        
                        is TurnStreamEvent.ToolCallReceived -> {
                            Log.d(TAG, "Turn $turnCount: Received tool call: ${event.toolCall.name}")
                        }
                        
                        is TurnStreamEvent.Complete -> {
                            turnResult = event.result
                            Log.d(TAG, "Turn $turnCount: Stream complete, isComplete=${event.result.isComplete}")
                        }
                        
                        is TurnStreamEvent.Error -> {
                            streamError = event.error
                            Log.e(TAG, "Turn $turnCount: Stream error", event.error)
                        }
                    }
                }
                
                // Handle stream error
                streamError?.let { throw it }
                
                // Get final result
                val result = turnResult ?: throw RuntimeException("Stream completed without result")
                
                Log.d(TAG, "Turn $turnCount: LLM response: ${result.content?.take(200)}...")
                Log.d(TAG, "Turn $turnCount: Tool calls: ${result.toolCalls.map { it.name }}")

                // Record assistant response in history
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
                        "Turn $turnCount: Multiple tool calls returned: ${result.toolCalls.map { it.name }}, executing: ${selectedTool.name}"
                    )
                    eventDispatcher.status("⚠️ Multiple actions returned; executing ${selectedTool.name} only")
                }
                if (hasCompletionTool && hasNonCompletionTool) {
                    Log.w(
                        TAG,
                        "Turn $turnCount: complete_task returned alongside other tools; completion deferred"
                    )
                    eventDispatcher.status("⚠️ Completion returned with other actions; executing action first")
                }

                // 3. ACT: Execute tool calls (one per turn)
                if (toolCallsToExecute.isNotEmpty()) {
                    eventDispatcher.turnPhaseChanged(turnId, TurnPhase.EXECUTION)
                    eventDispatcher.status("💡 Executing actions...")

                    // Re-capture screen before first action to avoid stale element IDs
                    // The LLM call can take 1-5 seconds, during which the screen may have changed
                    delay(200)  // Brief settle time
                    var currentSnapshot = services.platform.captureScreen()
                    Log.d(TAG, "Re-captured screen before actions: ${currentSnapshot.elements.size} elements")

                    for (toolCall in toolCallsToExecute) {
                        Log.d(TAG, "Executing tool: ${toolCall.name} with args: ${toolCall.arguments}")
                        
                        // Emit ActionProposed before execution
                        eventDispatcher.actionProposed(
                            toolCall.id,
                            toolCall.name,
                            ActionDescriptionFormatter.format(toolCall)
                        )

                        // Record tool call in history
                        services.historyManager.addItem(
                            ResponseItem.FunctionCall(
                                id = toolCall.id,
                                name = toolCall.name,
                                arguments = toolCall.arguments
                            )
                        )

                        // Execute via ToolRouter with current (possibly updated) snapshot
                        val context = SimpleToolRouterContext(
                            platform = services.platform,
                            currentSnapshot = currentSnapshot
                        )

                        val toolResult = services.toolRouter.execute(
                            toolName = toolCall.name,
                            params = toolCall.arguments,
                            context = context,
                            callId = toolCall.id,
                            onApprovalRequired = { details ->
                                // Use details.callId (from ToolRouter) for approval resolution
                                try {
                                    eventEmitter(AgentEvent.ApprovalRequired(
                                        sessionId = config.sessionId,
                                        timestamp = System.currentTimeMillis(),
                                        actionId = details.callId,
                                        description = details.description,
                                        details = details
                                    ))
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to emit approval required event", e)
                                }
                            }
                        )

                        // 4. OBSERVE: Prefer observation from tool execution result.
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

                        // Update snapshot for subsequent tools from the observation
                        if (observedSnapshot != null) {
                            currentSnapshot = observedSnapshot
                            Log.d(TAG, "Updated snapshot for subsequent tools: ${currentSnapshot.elements.size} elements")
                        }

                        // Record tool result with observation in history
                        services.historyManager.addItem(
                            ResponseItem.FunctionCallOutput(
                                callId = toolCall.id,
                                content = formatToolResult(toolResult, observation),
                                success = toolResult is ToolCallResult.Success
                            )
                        )

                        // Emit action executed event
                        eventEmitter(AgentEvent.ActionExecuted(
                            sessionId = config.sessionId,
                            timestamp = System.currentTimeMillis(),
                            actionId = toolResult.callId,
                            toolName = toolCall.name,
                            success = toolResult is ToolCallResult.Success,
                            result = toolResult.toContextString()
                        ))

                        eventDispatcher.status("✓ ${toolCall.name} executed")
                    }
                }

                // Check if complete (complete_task tool was called)
                val shouldComplete = result.isComplete && !hasNonCompletionTool
                if (shouldComplete) {
                    // Extract completion summary from complete_task tool if present
                    val completeTaskCall = result.toolCalls.find { it.name == "complete_task" }
                    val summary = completeTaskCall?.arguments?.optString("summary")
                        ?: result.content 
                        ?: "Goal achieved"
                    Log.i(TAG, "Turn $turnCount: Task marked as complete - $summary")
                    TurnOutcome.Complete(summary)
                } else {
                    TurnOutcome.Continue
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Turn execution failed", e)

            // Determine if error is recoverable
            val message = e.message ?: ""
            val isDnsFailure = e is java.net.UnknownHostException ||
                message.contains("Unable to resolve host", ignoreCase = true) ||
                message.contains("No address associated", ignoreCase = true)
            
            val isTransientNetworkError = !isDnsFailure && (
                e is java.net.SocketTimeoutException ||
                message.contains("timeout", ignoreCase = true) ||
                message.contains("connection refused", ignoreCase = true) ||
                message.contains("connection reset", ignoreCase = true)
            )

            TurnOutcome.Error(
                message = message.ifEmpty { "Unknown error" },
                recoverable = !isDnsFailure && (isTransientNetworkError || !message.contains("internet", ignoreCase = true))
            )
        }

        eventDispatcher.turnCompleted(turnId, turnCount)
        return outcome
    }
    
    /**
     * Capture post-action observation (screen state).
     */
    private data class ObservationCapture(
        val observation: Observation,
        val snapshot: ScreenSnapshot?
    )

    private suspend fun captureObservationWithSnapshot(): ObservationCapture {
        delay(500) // Brief delay for UI to settle
        val snapshot = services.platform.captureScreen()
        val accessibilityTree = Perceptor.toPromptJson(snapshot)
        return ObservationCapture(
            observation = Observation.ScreenState(accessibilityTree),
            snapshot = snapshot
        )
    }
    
    /**
     * Format tool result with observation for history.
     */
    private fun formatToolResult(result: ToolCallResult, observation: Observation): String {
        val resultText = when (result) {
            is ToolCallResult.Success -> "Success: ${result.output}"
            is ToolCallResult.Error -> "Error: ${result.error}"
            is ToolCallResult.Cancelled -> "Cancelled: ${result.reason}"
        }
        
        val observationText = when (observation) {
            is Observation.ScreenState -> "Screen after action:\n${observation.accessibilityTree}"
            is Observation.TextOutput -> observation.content
        }
        
        return "$resultText\n\n$observationText"
    }
    
    private fun shouldContinue(): Boolean {
        return !stopRequested.get() && !cancellationSignal.isCompleted
    }
    
    // === Lifecycle Methods ===
    
    suspend fun pause() {
        lifecycleMutex.withLock {
            pauseState.value = true
        }
        eventDispatcher.status("⏸️ Paused")
    }
    
    suspend fun resume() {
        lifecycleMutex.withLock {
            pauseState.value = false
        }
        eventDispatcher.status("▶️ Resuming...")
    }
    
    /**
     * Request the agent to stop after the current action.
     */
    fun stop() {
        stopRequested.set(true)
        pauseState.value = false
    }
    
}

/**
 * Outcome of a single turn.
 */
sealed class TurnOutcome {
    data object Continue : TurnOutcome()
    data class Complete(val message: String) : TurnOutcome()
    data class Error(val message: String, val recoverable: Boolean) : TurnOutcome()
    data object Cancelled : TurnOutcome()
}

/**
 * Reason why the agent stopped.
 */
sealed class AgentStopReason {
    data object GoalAchieved : AgentStopReason()
    data object UserRequested : AgentStopReason()
    data object MaxTurnsReached : AgentStopReason()
    data class Error(val message: String) : AgentStopReason()
}
