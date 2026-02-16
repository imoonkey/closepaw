package com.moonkey.androidagent.agent

import android.util.Log
import com.moonkey.androidagent.agent.cognition.context.LoopWarning
import com.moonkey.androidagent.agent.cognition.context.LoopWarningSeverity
import com.moonkey.androidagent.agent.cognition.policy.ExecutorStepDecision
import com.moonkey.androidagent.agent.cognition.policy.ExecutorStepPolicy
import com.moonkey.androidagent.agent.cognition.policy.LoopDetectionPolicy
import com.moonkey.androidagent.agent.cognition.policy.ToolArbitrationResult
import com.moonkey.androidagent.agent.cognition.policy.TurnToolPolicy
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.ScreenStatePhase
import com.moonkey.androidagent.session.SessionServices
import com.moonkey.androidagent.trace.AgentTrace
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

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
        private val executionPhaseRunner by lazy {
                TurnExecutionPhaseRunner(
                        config = config,
                        services = services,
                        eventDispatcher = eventDispatcher,
                        eventEmitter = eventEmitter,
                        trace = trace
                )
        }
        private val planningPhaseRunner by lazy {
                TurnPlanningPhaseRunner(
                        config = config,
                        services = services,
                        eventDispatcher = eventDispatcher,
                        trace = trace,
                        turnPolicyEngine = turnPolicyEngine
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
                                val snapshot = capturePreTurnSnapshot(turnId, turnNumber)
                                if (isTurnCancelled()) {
                                        TurnOutcome.Cancelled
                                } else {
                                        val preparedTurn =
                                                prepareTurn(turnNumber, nextState, snapshot)
                                        nextState = preparedTurn.nextState

                                        val planningResult =
                                                planningPhaseRunner.runPlanningPhase(
                                                        turnId = turnId,
                                                        turnNumber = turnNumber,
                                                        snapshot = snapshot,
                                                        warnings = preparedTurn.warnings
                                                )

                                        val actionForNextTurn =
                                                executionPhaseRunner.executeActions(
                                                        turnId = turnId,
                                                        turnNumber = turnNumber,
                                                        initialSnapshot = snapshot,
                                                        toolCallsToExecute =
                                                                planningResult
                                                                        .arbitration
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

        /**
         * Build plain-text warning strings for the current observation.
         *
         * Per review: only loop warnings and final-turn warning. Turn budget approaching warnings
         * are intentionally omitted (less noise).
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
                val classification = TurnErrorClassifier.classify(error)
                return TurnOutcome.Error(
                        message = classification.message,
                        recoverable = classification.recoverable
                )
        }
}
