package ai.closepaw.agent

import android.util.Log
import ai.closepaw.agent.cognition.policy.LoopDetectionPolicy
import ai.closepaw.agent.cognition.policy.isFinalTurn
import ai.closepaw.agent.cognition.policy.LoopDetectionResult
import ai.closepaw.agent.cognition.policy.ToolArbitrationResult
import ai.closepaw.agent.cognition.policy.TurnToolPolicy
import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.protocol.AppTier
import ai.closepaw.protocol.ScreenStatePhase
import ai.closepaw.session.SessionServices
import ai.closepaw.trace.AgentTrace
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
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
        private val cancellationSignal: CompletableDeferred<AgentStopReason>,
        private val stopRequested: AtomicBoolean,
        private val trace: AgentTrace,
        private val turnPolicyEngine: TurnToolPolicy
) {
        companion object {
                private const val TAG = "AgentTurnRunner"
        }
        private data class PreTurnContext(
                val snapshot: ScreenSnapshot,
                val currentPackageName: String?,
                val securityWarnings: List<String> = emptyList()
        )
        private val loopDetectionPolicy by lazy { LoopDetectionPolicy() }
        private val executionPhaseRunner by lazy {
                TurnExecutionPhaseRunner(
                        config = config,
                        services = services,
                        eventDispatcher = eventDispatcher,
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
                                val preTurnContext = capturePreTurnSnapshot(turnId, turnNumber)
                                val snapshot = preTurnContext.snapshot
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
                                                        currentPackageName =
                                                                preTurnContext.currentPackageName,
                                                        warnings = preTurnContext.securityWarnings + preparedTurn.warnings
                                                )

                                        val executionResult =
                                                executionPhaseRunner.executeActions(
                                                        turnId = turnId,
                                                        turnNumber = turnNumber,
                                                        initialSnapshot = snapshot,
                                                        toolCallsToExecute =
                                                                planningResult
                                                                        .arbitration
                                                                        .selectedToolCalls
                                                )

                                        decideTurnOutcome(
                                                turnNumber = turnNumber,
                                                result = planningResult.turnResult,
                                                arbitration = planningResult.arbitration,
                                                execution = executionResult
                                        )
                                }
                        } catch (e: CancellationException) {
                                throw e // Don't treat coroutine cancellation as a turn error
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
                val warnings: List<String>
        )

        private suspend fun capturePreTurnSnapshot(
                turnId: String,
                turnNumber: Int
        ): PreTurnContext {
                eventDispatcher.status("👀 Scanning screen...")
                val rawSnapshot = services.platform.captureScreen()
                val currentPackage = services.platform.getCurrentPackageName()

                // Layer 2: Perception Gate — mask BLOCKED app screens
                val tier = services.appClassifier.classify(currentPackage)
                val snapshot = services.appClassifier.maskIfBlocked(rawSnapshot, currentPackage)
                val securityWarnings = if (tier == AppTier.BLOCKED) {
                        Log.w(TAG, "Turn $turnNumber: BLOCKED app ($currentPackage) — masking screen")
                        listOf(
                                "⛔ Screen hidden: $currentPackage is a blocked app (financial/auth). " +
                                "Content masked by security policy. Use back or home to navigate away."
                        )
                } else {
                        emptyList()
                }

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
                return PreTurnContext(
                        snapshot = snapshot,
                        currentPackageName = currentPackage,
                        securityWarnings = securityWarnings
                )
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
                        state.navigationState.advance(snapshot = snapshot)

                val loopResult = loopDetectionPolicy.detect(navigationState)
                loopResult.warning?.let {
                        Log.w(TAG, "Turn $turnNumber loop warning: ${it.message}")
                        eventDispatcher.status("⚠️ ${it.message}")
                }

                val nextState = state.copy(navigationState = navigationState)

                val finalTurn = isFinalTurn(turnNumber, config.maxTurns)
                val warnings = buildWarnings(loopResult, finalTurn)

                return PreparedTurn(
                        nextState = nextState,
                        warnings = warnings
                )
        }

        /** Build plain-text warning strings for the current observation. */
        private fun buildWarnings(
                loopResult: LoopDetectionResult,
                isFinalTurn: Boolean
        ): List<String> = buildList {
                loopResult.warning?.let { add("⚠️ ${it.message}") }
                if (isFinalTurn) {
                        add("🛑 FINAL TURN (${config.maxTurns}). Complete now or report progress.")
                }
        }

        private fun decideTurnOutcome(
                turnNumber: Int,
                result: TurnResult,
                arbitration: ToolArbitrationResult,
                execution: ExecutionPhaseResult
        ): TurnOutcome {
                val outcome = decideTurnOutcome(
                        policy = turnPolicyEngine,
                        turnResult = result,
                        arbitration = arbitration,
                        execution = execution
                )
                if (outcome is TurnOutcome.Complete) {
                        Log.i(TAG, "Turn $turnNumber: Task marked as complete - ${outcome.message}")
                }
                return outcome
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
