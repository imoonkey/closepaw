package com.moonkey.androidagent.agent

import android.util.Log
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.model.ScreenSnapshot
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
            You are an Android automation agent. You can interact with the device using tools.
            
            Your task is to achieve the user's goal by:
            1. Observing the current screen state (provided as a JSON list of UI elements)
            2. Deciding what action to take based on the screen
            3. Executing the action using available tools
            4. Observing the result and continuing until done
        """.trimIndent()
    }
    
    // State - using thread-safe primitives to avoid race conditions
    private var turnCount = 0
    private val pauseState = MutableStateFlow(false)
    private val stopRequested = AtomicBoolean(false)
    private val lifecycleMutex = Mutex()  // Protects pause/resume/stop operations
    
    /**
     * Run the agent until goal achieved, max turns, or stopped.
     */
    suspend fun run(): AgentStopReason {
        Log.i(TAG, "Starting agent for goal: ${config.goal}")
        emitStatus("🚀 Starting agent...")
        
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
                emitStatus("⏸️ Paused - waiting to resume...")
                pauseState.first { !it }
                emitStatus("▶️ Resuming...")
            }
            
            // Check max turns
            if (turnCount >= config.maxTurns) {
                Log.w(TAG, "Max turns (${config.maxTurns}) reached")
                emitStatus("⚠️ Max turns reached")
                return AgentStopReason.MaxTurnsReached
            }
            
            // Execute one turn
            val result = executeTurn()
            
            when (result) {
                is TurnOutcome.Continue -> {
                    delay(config.uiSettleDelayMs)
                }
                is TurnOutcome.Complete -> {
                    emitStatus("✅ Goal achieved!")
                    return AgentStopReason.GoalAchieved
                }
                is TurnOutcome.Error -> {
                    if (!result.recoverable) {
                        emitStatus("❌ Error: ${result.message}")
                        return AgentStopReason.Error(result.message)
                    }
                    // Recoverable error - continue with delay
                    emitStatus("⚠️ Error (retrying): ${result.message}")
                    delay(config.uiSettleDelayMs)
                }
                TurnOutcome.Cancelled -> {
                    emitStatus("🛑 Cancelled")
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

        emitTurnStarted(turnId)

        val outcome = try {
            // 1. PERCEPTION: Capture screen
            emitStatus("👀 Scanning screen...")

            val snapshot = services.platform.captureScreen()
            emitScreenCaptured(snapshot)

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
                emitTurnPhaseChanged(turnId, TurnPhase.PLANNING)
                emitStatus("🧠 Thinking...")

                val turn = Turn(services.historyManager, services.toolRegistry, services.llmClient)
                val systemPrompt = buildSystemPrompt()
                val userContext = buildUserContext(snapshot)

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
                            emitMessageDelta(turnId, event.text)
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

                // 3. ACT: Execute tool calls
                if (result.toolCalls.isNotEmpty()) {
                    emitTurnPhaseChanged(turnId, TurnPhase.EXECUTION)
                    emitStatus("💡 Executing actions...")

                    // Re-capture screen before first action to avoid stale element IDs
                    // The LLM call can take 1-5 seconds, during which the screen may have changed
                    delay(200)  // Brief settle time
                    var currentSnapshot = services.platform.captureScreen()
                    Log.d(TAG, "Re-captured screen before actions: ${currentSnapshot.elements.size} elements")

                    for (toolCall in result.toolCalls) {
                        Log.d(TAG, "Executing tool: ${toolCall.name} with args: ${toolCall.arguments}")
                        
                        // Emit ActionProposed before execution
                        emitActionProposed(toolCall.id, toolCall.name, formatActionDescription(toolCall))

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
                                    observation = toolObs.toAgentObservation()
                                    observedSnapshot = toolObs.snapshot
                                    hasObservation = true
                                }
                                is ToolObservation.TextOutput -> {
                                    observation = toolObs.toAgentObservation()
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

                        emitStatus("✓ ${toolCall.name} executed")
                    }
                }

                // Check if complete (complete_task tool was called)
                if (result.isComplete) {
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

        emitTurnCompleted(turnId)
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
    
    private fun buildSystemPrompt(): String {
        return config.systemPrompt ?: DEFAULT_SYSTEM_PROMPT
    }
    
    private fun buildUserContext(snapshot: ScreenSnapshot): String {
        val screenJson = Perceptor.toPromptJson(snapshot)
        val toolNames = services.toolRegistry.getNames().joinToString(", ")
        
        return """
            Current screen state (${snapshot.elements.size} elements):
            ```json
            $screenJson
            ```
            
            Available tools: $toolNames
            
            What action should I take next to achieve the goal?
        """.trimIndent()
    }
    
    private fun shouldContinue(): Boolean {
        return !stopRequested.get() && !cancellationSignal.isCompleted
    }
    
    // === Lifecycle Methods ===
    
    suspend fun pause() {
        lifecycleMutex.withLock {
            pauseState.value = true
        }
        emitStatus("⏸️ Paused")
    }
    
    suspend fun resume() {
        lifecycleMutex.withLock {
            pauseState.value = false
        }
        emitStatus("▶️ Resuming...")
    }
    
    /**
     * Request the agent to stop after the current action.
     */
    fun stop() {
        stopRequested.set(true)
        pauseState.value = false
    }
    
    // === Event Emission ===
    
    private suspend fun emitStatus(status: String) {
        Log.d(TAG, "Status: $status")
        eventEmitter(AgentEvent.StatusUpdate(
            sessionId = config.sessionId,
            timestamp = System.currentTimeMillis(),
            status = status
        ))
    }
    
    private suspend fun emitMessageDelta(turnId: String, delta: String) {
        Log.d(TAG, "MessageDelta: turnId=$turnId, delta=${delta.take(50)}...")
        eventEmitter(AgentEvent.MessageDelta(
            sessionId = config.sessionId,
            timestamp = System.currentTimeMillis(),
            turnId = turnId,
            delta = delta
        ))
    }
    
    private suspend fun emitActionProposed(actionId: String, toolName: String, description: String) {
        Log.d(TAG, "ActionProposed: $toolName - $description")
        eventEmitter(AgentEvent.ActionProposed(
            sessionId = config.sessionId,
            timestamp = System.currentTimeMillis(),
            actionId = actionId,
            toolName = toolName,
            description = description
        ))
    }
    
    /**
     * Format a human-readable description for an action.
     * 
     * Handles the consolidated tool schema:
     * - mobile_action: click, long_press, type, swipe, system_button, wait
     * - app_control: list_apps, open_app
     * - complete_task: finish task with status
     */
    private fun formatActionDescription(toolCall: ToolCallRequest): String {
        return when (toolCall.name.lowercase()) {
            "mobile_action" -> {
                val action = toolCall.arguments.optString("action", "")
                when (action) {
                    "click" -> {
                        val idx = toolCall.arguments.optInt("element_index", -1)
                        "Click element $idx"
                    }
                    "long_press" -> {
                        val idx = toolCall.arguments.optInt("element_index", -1)
                        "Long press element $idx"
                    }
                    "type" -> {
                        val text = toolCall.arguments.optString("text", "").take(30)
                        "Type \"$text\""
                    }
                    "swipe" -> {
                        val start = toolCall.arguments.optJSONArray("start")
                        val end = toolCall.arguments.optJSONArray("end")
                        if (start != null && end != null) {
                            "Swipe from (${start.optInt(0)},${start.optInt(1)}) to (${end.optInt(0)},${end.optInt(1)})"
                        } else {
                            "Swipe gesture"
                        }
                    }
                    "system_button" -> {
                        val button = toolCall.arguments.optString("button", "")
                        "Press $button button"
                    }
                    "wait" -> {
                        val ms = toolCall.arguments.optLong("duration_ms", 1000)
                        "Wait ${ms}ms"
                    }
                    else -> "Mobile action: $action"
                }
            }
            "app_control" -> {
                val action = toolCall.arguments.optString("action", "")
                when (action) {
                    "list_apps" -> {
                        val filter = toolCall.arguments.optString("filter", "")
                        if (filter.isNotEmpty()) "List apps matching '$filter'" else "List all apps"
                    }
                    "open_app" -> {
                        val name = toolCall.arguments.optString("app_name", "")
                        val pkg = toolCall.arguments.optString("package_name", "")
                        "Open app: ${name.ifEmpty { pkg }}"
                    }
                    else -> "App control: $action"
                }
            }
            "complete_task" -> {
                val status = toolCall.arguments.optString("status", "")
                val answer = toolCall.arguments.optString("answer", "").take(50)
                "Complete ($status): $answer"
            }
            else -> "Execute ${toolCall.name}"
        }
    }

    private suspend fun emitTurnStarted(turnId: String) {
        eventEmitter(AgentEvent.TurnStarted(
            sessionId = config.sessionId,
            timestamp = System.currentTimeMillis(),
            turnId = turnId,
            turnNumber = turnCount,
            phase = TurnPhase.PERCEPTION
        ))
    }

    private suspend fun emitTurnPhaseChanged(turnId: String, phase: TurnPhase) {
        eventEmitter(AgentEvent.TurnPhaseChanged(
            sessionId = config.sessionId,
            timestamp = System.currentTimeMillis(),
            turnId = turnId,
            phase = phase
        ))
    }

    private suspend fun emitTurnCompleted(turnId: String) {
        eventEmitter(AgentEvent.TurnCompleted(
            sessionId = config.sessionId,
            timestamp = System.currentTimeMillis(),
            turnId = turnId,
            turnNumber = turnCount
        ))
    }
    
    private suspend fun emitScreenCaptured(snapshot: ScreenSnapshot) {
        eventEmitter(AgentEvent.ScreenCaptured(
            sessionId = config.sessionId,
            timestamp = System.currentTimeMillis(),
            elementCount = snapshot.elements.size,
            packageName = services.platform.getCurrentPackageName(),
            activityName = null
        ))
    }
}

/**
 * Observation - Post-action state captured after tool execution.
 */
sealed class Observation {
    data class ScreenState(val accessibilityTree: String) : Observation()
    data class TextOutput(val content: String) : Observation()
}

/**
 * Extension to convert ToolObservation to Agent's Observation.
 */
fun ToolObservation.toAgentObservation(): Observation {
    return when (this) {
        is ToolObservation.ScreenState -> Observation.ScreenState(this.accessibilityTree)
        is ToolObservation.TextOutput -> Observation.TextOutput(this.content)
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
