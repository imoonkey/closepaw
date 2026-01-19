package com.moonkey.androidagent.agent

import android.util.Log
import com.moonkey.androidagent.data.perception.Perceptor
import com.moonkey.androidagent.domain.models.ScreenSnapshot
import com.moonkey.androidagent.infra.history.ResponseItem
import com.moonkey.androidagent.infra.tools.SimpleToolRouterContext
import com.moonkey.androidagent.infra.tools.ToolCallResult
import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.ApprovalDetails
import com.moonkey.androidagent.protocol.TurnPhase
import com.moonkey.androidagent.session.SessionServices
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Agent - Single ReAct agent that executes goals.
 * 
 * Reference: labmat's AgentCycle (cycle.py)
 * 
 * Design principles:
 * - Simple ReAct loop: Perceive → Think → Act → Observe
 * - No multi-agent complexity (Manager/Executor/Reflector removed)
 * - Tool results include post-action screen observation
 * - Can spawn sub-agents (interface preserved for future)
 */
class Agent(
    private val config: AgentConfig,
    private val services: SessionServices,
    private val eventEmitter: suspend (AgentEvent) -> Unit,
    private val cancellationSignal: CompletableDeferred<AgentStopReason>
) {
    companion object {
        private const val TAG = "Agent"
        
        private val DEFAULT_SYSTEM_PROMPT = """
            You are an Android automation agent. You can interact with the device using tools.
            
            Your task is to achieve the user's goal by:
            1. Observing the current screen state (provided as a JSON list of UI elements)
            2. Deciding what action to take based on the screen
            3. Executing the action using available tools
            4. Observing the result and continuing until done
            
            When the goal is achieved, respond with "DONE: [summary of what was accomplished]" without any tool calls.
            
            Important guidelines:
            - Each UI element has an "index" field - use this index when calling tools like click or type
            - Look for elements with "clickable": true for interactive items
            - Look for elements with "editable": true for text input fields
            - If you don't see the expected UI, try scrolling or navigating
            - Be patient and methodical - complete one step at a time
        """.trimIndent()
    }
    
    // State
    private var turnCount = 0
    private val pauseState = MutableStateFlow(false)
    private var stopRequested = false
    
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
            stopRequested -> AgentStopReason.UserRequested
            cancellationSignal.isCompleted -> AgentStopReason.UserRequested
            else -> AgentStopReason.GoalAchieved
        }
    }
    
    /**
     * Execute a single turn of the ReAct loop.
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

            // Check cancellation
            if (cancellationSignal.isCompleted || stopRequested) {
                TurnOutcome.Cancelled
            } else {
                // 2. THINK: Call LLM
                emitTurnPhaseChanged(turnId, TurnPhase.PLANNING)
                emitStatus("🧠 Thinking...")

                val turn = Turn(services.historyManager, services.toolRegistry)
                val systemPrompt = buildSystemPrompt()
                val userContext = buildUserContext(snapshot)

                val turnResult = turn.run(systemPrompt, userContext)

                Log.d(TAG, "Turn $turnCount: LLM response: ${turnResult.content?.take(200)}...")
                Log.d(TAG, "Turn $turnCount: Tool calls: ${turnResult.toolCalls.map { it.name }}")

                // Record assistant response in history
                if (turnResult.content != null) {
                    services.historyManager.addItem(
                        ResponseItem.Message(
                            role = "assistant",
                            content = turnResult.content
                        )
                    )
                }

                // 3. ACT: Execute tool calls
                if (turnResult.toolCalls.isNotEmpty()) {
                    emitTurnPhaseChanged(turnId, TurnPhase.EXECUTION)
                    emitStatus("💡 Executing actions...")

                    for (toolCall in turnResult.toolCalls) {
                        Log.d(TAG, "Executing tool: ${toolCall.name} with args: ${toolCall.arguments}")

                        // Record tool call in history
                        services.historyManager.addItem(
                            ResponseItem.FunctionCall(
                                id = toolCall.id,
                                name = toolCall.name,
                                arguments = toolCall.arguments
                            )
                        )

                        // Execute via ToolRouter
                        val context = SimpleToolRouterContext(
                            platform = services.platform,
                            currentSnapshot = snapshot
                        )

                        val result = services.toolRouter.execute(
                            toolName = toolCall.name,
                            params = toolCall.arguments,
                            context = context,
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

                        // 4. OBSERVE: Capture post-action screen
                        val observation = captureObservation()

                        // Record tool result with observation in history
                        services.historyManager.addItem(
                            ResponseItem.FunctionCallOutput(
                                callId = toolCall.id,
                                content = formatToolResult(result, observation),
                                success = result is ToolCallResult.Success
                            )
                        )

                        // Emit action executed event
                        eventEmitter(AgentEvent.ActionExecuted(
                            sessionId = config.sessionId,
                            timestamp = System.currentTimeMillis(),
                            actionId = result.callId,
                            toolName = toolCall.name,
                            success = result is ToolCallResult.Success,
                            result = result.toContextString()
                        ))

                        emitStatus("✓ ${toolCall.name} executed")
                    }
                }

                // Check if complete
                if (turnResult.isComplete) {
                    Log.i(TAG, "Turn $turnCount: Task marked as complete")
                    TurnOutcome.Complete(turnResult.content ?: "Goal achieved")
                } else {
                    TurnOutcome.Continue
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Turn execution failed", e)

            // Check if error is recoverable
            val message = e.message ?: ""
            val isNetworkError = message.contains("internet", ignoreCase = true) ||
                message.contains("network", ignoreCase = true) ||
                message.contains("connection", ignoreCase = true) ||
                e is java.net.UnknownHostException

            TurnOutcome.Error(
                message = message.ifEmpty { "Unknown error" },
                recoverable = !isNetworkError  // Network errors are not recoverable
            )
        }

        emitTurnCompleted(turnId)
        return outcome
    }
    
    /**
     * Capture post-action observation (screen state).
     */
    private suspend fun captureObservation(): Observation {
        delay(500) // Brief delay for UI to settle
        val snapshot = services.platform.captureScreen()
        val accessibilityTree = Perceptor.toPromptJson(snapshot)
        return Observation.ScreenState(accessibilityTree)
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
        return !stopRequested && !cancellationSignal.isCompleted
    }
    
    // === Lifecycle Methods ===
    
    suspend fun pause() {
        pauseState.value = true
        emitStatus("⏸️ Paused")
    }
    
    suspend fun resume() {
        pauseState.value = false
        emitStatus("▶️ Resuming...")
    }
    
    fun stop() {
        stopRequested = true
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

