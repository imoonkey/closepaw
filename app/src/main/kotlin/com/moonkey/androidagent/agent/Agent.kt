package com.moonkey.androidagent.agent

import android.util.Log
import com.moonkey.androidagent.agent.cognition.policy.TurnToolPolicy
import com.moonkey.androidagent.trace.AgentTrace
import com.moonkey.androidagent.history.MessageKind
import com.moonkey.androidagent.history.ResponseItem
import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.TurnPhase
import com.moonkey.androidagent.session.SessionServices
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Agent - Public entry point for running a single ReAct agent session. */
class Agent(
        private val config: AgentExecutionConfig,
        private val services: SessionServices,
        private val eventEmitter: suspend (AgentEvent) -> Unit,
        private val cancellationSignal: CompletableDeferred<AgentStopReason>
) {
    companion object {
        private const val TAG = "Agent"
        private const val MAX_RECOVERABLE_RETRIES = 1
    }

    private var turnCount = 0
    private val pauseState = MutableStateFlow(false)
    private var pauseConfirmed: CompletableDeferred<Unit>? = null
    private val stopRequested = AtomicBoolean(false)
    private val lifecycleMutex = Mutex()

    private val eventDispatcher =
            AgentEventDispatcher(sessionId = config.sessionId, eventEmitter = eventEmitter)

    private val trace = AgentTrace(config.sessionId, services)

    private val turnRunner =
            AgentTurnRunner(
                    config = config,
                    services = services,
                    eventDispatcher = eventDispatcher,
                    eventEmitter = eventEmitter,
                    cancellationSignal = cancellationSignal,
                    stopRequested = stopRequested,
                    trace = trace,
                    turnPolicyEngine = TurnToolPolicy()
            )

    suspend fun run(): AgentStopReason {
        Log.i(TAG, "Starting agent for goal: ${config.goal}")
        eventDispatcher.status("🚀 Starting agent...")
        trace.sessionStarted(config)

        services.historyManager.addItem(
                ResponseItem.Message(kind = MessageKind.USER_INTENT, content = "Goal: ${config.goal}")
        )

        var stopReason: AgentStopReason? = null
        var turnRunnerState = TurnRunnerState()
        var recoverableRetryCount = 0
        while (shouldContinue()) {
            if (pauseState.value) {
                // Signal that the agent is actually paused (current turn done)
                pauseConfirmed?.complete(Unit)
                pauseConfirmed = null
                eventDispatcher.status("⏸️ Paused - waiting to resume...")
                pauseState.first { !it }
                eventDispatcher.status("▶️ Resuming...")
            }

            if (!shouldContinue()) {
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

            val turnExecution = turnRunner.executeTurn(turnId, turnCount, turnRunnerState)
            turnRunnerState = turnExecution.nextState
            when (val result = turnExecution.outcome) {
                is TurnOutcome.Continue -> {
                    recoverableRetryCount = 0
                    delay(config.uiSettleDelayMs)
                }
                is TurnOutcome.Complete -> {
                    if (result.success) {
                        eventDispatcher.status("✅ Goal achieved!")
                        stopReason = AgentStopReason.GoalAchieved(result.message)
                    } else {
                        eventDispatcher.status("❌ Task failed: ${result.message}")
                        stopReason = AgentStopReason.Error(result.message)
                    }
                    break
                }
                is TurnOutcome.Error -> {
                    if (!result.recoverable) {
                        eventDispatcher.status("❌ Error: ${result.message}")
                        stopReason = AgentStopReason.Error(result.message)
                        break
                    }

                    val retryLimit = MAX_RECOVERABLE_RETRIES
                    val hasRemainingTurns = turnCount < config.maxTurns
                    val canRetry = hasRemainingTurns && recoverableRetryCount < retryLimit

                    if (!canRetry) {
                        eventDispatcher.status("❌ Error: ${result.message}")
                        stopReason = AgentStopReason.Error(result.message)
                        break
                    }

                    recoverableRetryCount++
                    eventDispatcher.status(
                            "⚠️ Error (retry $recoverableRetryCount/$retryLimit): ${result.message}"
                    )
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
                            stopRequested.get() || cancellationSignal.isCompleted ->
                                    AgentStopReason.UserRequested
                            else -> AgentStopReason.GoalAchieved()
                        }

        trace.sessionStopped(finalReason, turnCount)
        return finalReason
    }

    /**
     * Request pause. Returns a [Deferred] that completes when the agent
     * actually enters the paused state (i.e. the current turn finishes).
     */
    suspend fun pause(): Deferred<Unit> {
        val confirmed = CompletableDeferred<Unit>()
        lifecycleMutex.withLock {
            pauseState.value = true
            pauseConfirmed = confirmed
        }
        eventDispatcher.status("⏸️ Paused")
        return confirmed
    }

    suspend fun resume() {
        lifecycleMutex.withLock {
            pauseConfirmed?.complete(Unit)
            pauseConfirmed = null
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
