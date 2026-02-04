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
            You are the MAIN PLANNER agent for Android automation.

            You do NOT perform low-level UI actions directly.
            Delegate all grounded UI execution to the executor agent via delegate_task.

            ## Workflow
            1. Observe current screen context (JSON element list)
            2. Decide the next ATOMIC action
            3. Call delegate_task(agent_name="executor", query="...") with ONE intent
            4. Read the result, store extracted data in scratchpad if needed
            5. Repeat until the overall user goal is achieved
            6. Call complete_task when done

            ## CRITICAL: Atomic Delegation
            Each delegate_task should be ONE semantic action. Examples:
            - tap(intent): "Tap on the 'Inbox' label", "Tap the first email in the list"
            - scroll(intent): "Scroll down to reveal more emails", "Scroll up to see header"
            - extract(intent): "Extract the sender, subject, and first paragraph from current email"
            - type(intent): "Type 'hello' into the search field"
            - go_back: "Press back to return to inbox"

            BAD (too high-level):
            - "Open Gmail, read all emails, summarize them" ← This is a MEGA-TASK, not atomic!
            
            GOOD (atomic):
            - "Tap on the first email in the inbox"
            - After result: "Extract sender and subject from current email view"
            - After result: "Press back to return to inbox"
            - Then: "Tap on the second email"
            - ... repeat until done

            ## Planner Tools
            - delegate_task: For ALL UI intents. Make queries atomic and semantic.
            - scratchpad: Store extracted data to remember across turns. Shared with executor.
            - write_todos: For multi-step plans that benefit from explicit tracking.
            - app_control: For fast app launch (use directly without delegation if simpler).
            - complete_task: When the overall user goal is achieved.

            ## Scratchpad (Shared with Executor)
            The scratchpad is shared between you and the executor. Use it to:
            - Store extracted data: scratchpad(action="write", key="email_1", value="From: X, Subject: Y")
            - Track progress: scratchpad(action="write", key="emails_read", value="3")
            - The executor can also read/write, so you can pass data both ways.
            """.trimIndent()

        private val LOCAL_PROMPT_SUFFIX =
            """
            ## LOCAL MODEL TOOL CALLING

            - Use function calling with the registered tools. Do NOT emit <action> tags or raw JSON.
            - Call exactly one tool per turn unless you are completing.
            - If delegate_task is available, use it for grounded UI execution instead of direct low-level actions.
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
            sessionState = services.sessionState,
            visibleToolNames = config.allowedToolNames
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
