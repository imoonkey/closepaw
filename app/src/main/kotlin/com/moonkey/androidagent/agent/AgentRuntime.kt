package com.moonkey.androidagent.agent

import android.util.Log
import com.moonkey.androidagent.history.ResponseItem
import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.TurnPhase
import com.moonkey.androidagent.session.SessionServices
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

internal class AgentRuntime(
    private val config: AgentConfig,
    private val services: SessionServices,
    private val eventEmitter: suspend (AgentEvent) -> Unit,
    private val cancellationSignal: CompletableDeferred<AgentStopReason>
) {
    companion object {
        private const val TAG = "AgentRuntime"

        // Base system prompt - Turn.kt will append tool usage guidelines
        private val DEFAULT_SYSTEM_PROMPT =
            """
            You are an Android automation agent. You control the device using tools.

            Your task is to achieve the user's goal by:
            1. Observing the current screen state (provided as a JSON list of UI elements)
            2. Deciding what action to take based on the screen
            3. Executing the action using available tools
            4. Observing the result and continuing until done
            5. If you have achieved the goal, call complete_task to wrap up. Do NOT call it prematurely.

            Your start screen maybe the Android Agent app itself, or any other screen.
            Your actions should almost always start with directly opening or navigating to the right app/page first.

            Planning tools:
            - Use write_todos to track multi-step tasks (always send the full list).
            - Use scratchpad to store key-value facts you will need later.
        """.trimIndent()

        private val LOCAL_PROMPT_SUFFIX =
            """
            ## LOCAL MODEL TOOL CALLING

            - Use function calling with the registered tools. Do NOT emit <action> tags or raw JSON.
            - Call exactly one tool per turn (mobile_action or app_control) unless you are completing.
            - When the goal is achieved, call complete_task with status and answer.
        """.trimIndent()
    }

    private var turnCount = 0
    private val pauseState = MutableStateFlow(false)
    private val stopRequested = AtomicBoolean(false)
    private val lifecycleMutex = Mutex()

    private val promptBuilder =
        AgentPromptBuilder(
            basePrompt = config.systemPrompt,
            defaultPrompt = DEFAULT_SYSTEM_PROMPT,
            localPromptSuffix = LOCAL_PROMPT_SUFFIX,
            llmBackend = services.config.llmBackend,
            toolRegistry = services.toolRegistry,
            sessionState = services.sessionState
        )

    private val eventDispatcher =
        AgentEventDispatcher(
            sessionId = config.sessionId,
            eventEmitter = eventEmitter
        )

    private val trace = AgentTrace(config.sessionId, services)

    private val turnRunner =
        AgentTurnRunner(
            config = config,
            services = services,
            eventDispatcher = eventDispatcher,
            eventEmitter = eventEmitter,
            cancellationSignal = cancellationSignal,
            stopRequested = stopRequested,
            promptBuilder = promptBuilder,
            trace = trace
        )

    suspend fun run(): AgentStopReason {
        Log.i(TAG, "Starting agent for goal: ${config.goal}")
        eventDispatcher.status("🚀 Starting agent...")
        trace.sessionStarted(config)

        services.historyManager.addItem(
            ResponseItem.Message(
                role = "user",
                content = "Goal: ${config.goal}"
            )
        )

        var stopReason: AgentStopReason? = null
        while (shouldContinue()) {
            if (pauseState.value) {
                eventDispatcher.status("⏸️ Paused - waiting to resume...")
                pauseState.first { !it }
                eventDispatcher.status("▶️ Resuming...")
            }

            if (!shouldContinue()) {
                eventDispatcher.status("🛑 Cancelled")
                stopReason = AgentStopReason.UserRequested
                break
            }

            if (turnCount >= config.maxTurns) {
                Log.w(TAG, "Max turns (${config.maxTurns}) reached")
                eventDispatcher.status("⚠️ Max turns reached")
                stopReason = AgentStopReason.MaxTurnsReached
                break
            }

            turnCount++
            val turnId = "turn-$turnCount"
            Log.d(TAG, "=== TURN $turnCount START ===")
            eventDispatcher.turnStarted(turnId, turnCount)
            eventDispatcher.turnPhaseChanged(turnId, TurnPhase.PERCEPTION)

            val result = turnRunner.executeTurn(turnId, turnCount)
            when (result) {
                is TurnOutcome.Continue -> delay(config.uiSettleDelayMs)
                is TurnOutcome.Complete -> {
                    eventDispatcher.status("✅ Goal achieved!")
                    stopReason = AgentStopReason.GoalAchieved
                    break
                }
                is TurnOutcome.Error -> {
                    if (!result.recoverable) {
                        eventDispatcher.status("❌ Error: ${result.message}")
                        stopReason = AgentStopReason.Error(result.message)
                        break
                    }
                    eventDispatcher.status("⚠️ Error (retrying): ${result.message}")
                    delay(config.uiSettleDelayMs)
                }
                TurnOutcome.Cancelled -> {
                    eventDispatcher.status("🛑 Cancelled")
                    stopReason = AgentStopReason.UserRequested
                    break
                }
            }
        }

        val finalReason =
            stopReason
                ?: when {
                    stopRequested.get() -> AgentStopReason.UserRequested
                    cancellationSignal.isCompleted -> AgentStopReason.UserRequested
                    else -> AgentStopReason.GoalAchieved
                }

        trace.sessionStopped(finalReason, turnCount)
        return finalReason
    }

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

    fun stop() {
        stopRequested.set(true)
        pauseState.value = false
    }

    private fun shouldContinue(): Boolean {
        return !stopRequested.get() && !cancellationSignal.isCompleted
    }
}
