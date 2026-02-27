package com.moonkey.androidagent.agent

import android.util.Log
import com.moonkey.androidagent.agent.cognition.context.LoopWarningSeverity
import com.moonkey.androidagent.agent.cognition.policy.EscalationLevel
import com.moonkey.androidagent.agent.cognition.policy.ExecutorStepDecision
import com.moonkey.androidagent.agent.cognition.policy.ExecutorStepPolicy
import com.moonkey.androidagent.agent.cognition.policy.LoopDetectionPolicy
import com.moonkey.androidagent.agent.cognition.policy.LoopDetectionResult
import com.moonkey.androidagent.agent.cognition.policy.ToolArbitrationResult
import com.moonkey.androidagent.agent.cognition.policy.TurnToolPolicy
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.ScreenStatePhase
import com.moonkey.androidagent.session.SessionServices
import com.moonkey.androidagent.tool.ToolName
import com.moonkey.androidagent.trace.AgentTrace
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject

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

                                        // Tier 3: force task completion with failure status
                                        if (preparedTurn.escalation == EscalationLevel.FORCE_COMPLETE) {
                                                Log.w(TAG, "Turn $turnNumber: FORCE_COMPLETE — injecting synthetic complete_task(status=failure)")
                                                eventDispatcher.status("🛑 Loop escalation: forcing task completion (failure)")
                                                val syntheticCall =
                                                        buildSyntheticFailureCompletion(turnNumber)
                                                executionPhaseRunner.executeActions(
                                                        turnId = turnId,
                                                        turnNumber = turnNumber,
                                                        initialSnapshot = snapshot,
                                                        toolCallsToExecute = listOf(syntheticCall)
                                                )
                                                TurnOutcome.Complete(
                                                        message = "Task failed: agent stuck in a repeated action loop at turn $turnNumber.",
                                                        success = false
                                                )
                                        } else {
                                                val planningResult =
                                                        planningPhaseRunner.runPlanningPhase(
                                                                turnId = turnId,
                                                                turnNumber = turnNumber,
                                                                snapshot = snapshot,
                                                                warnings = preparedTurn.warnings,
                                                                blockedActions = preparedTurn.blockedActions
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
                val warnings: List<String>,
                val escalation: EscalationLevel = EscalationLevel.NONE,
                val blockedActions: Set<String> = emptySet()
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

                val loopResult = loopDetectionPolicy.detect(navigationState)
                loopResult.warning?.let {
                        Log.w(TAG, "Turn $turnNumber loop warning [${loopResult.escalation}]: ${it.message}")
                        eventDispatcher.status("⚠️ ${it.message}")
                }

                // Update loop escalation state on NavigationState.
                // Increment consecutiveLoopTurns on CRITICAL warnings; reset otherwise.
                val updatedNavState = when {
                        loopResult.warning?.severity == LoopWarningSeverity.CRITICAL -> {
                                val newLoopTurns = navigationState.consecutiveLoopTurns + 1
                                // At BLOCK level, collect recent action signatures to block.
                                // Exclude navigational escape actions so agent can still back out.
                                val newBlocked = if (loopResult.escalation >= EscalationLevel.BLOCK) {
                                        navigationState.recentActions.takeLast(3)
                                                .filterNot(::isEscapeActionSignature)
                                                .toSet()
                                } else {
                                        emptySet()
                                }
                                navigationState.copy(
                                        consecutiveLoopTurns = newLoopTurns,
                                        blockedActions = newBlocked
                                )
                        }
                        else -> {
                                // No critical loop this turn — reset escalation state
                                navigationState.copy(
                                        consecutiveLoopTurns = 0,
                                        blockedActions = emptySet()
                                )
                        }
                }

                val nextState = state.copy(navigationState = updatedNavState)

                val stepDecision =
                        executorStepPolicy.evaluate(
                                stepCount = turnNumber,
                                delegatedQuery = config.goal,
                                history = services.historyManager.getAll()
                        )
                val warnings = buildWarnings(loopResult, stepDecision)

                return PreparedTurn(
                        nextState = nextState,
                        warnings = warnings,
                        escalation = loopResult.escalation,
                        blockedActions = updatedNavState.blockedActions
                )
        }

        /**
         * Build plain-text warning strings for the current observation.
         *
         * Tier 1 (ADVISORY): standard loop warning.
         * Tier 2 (BLOCK): mandatory strategy-change directive listing blocked actions.
         * Tier 3 (FORCE_COMPLETE): not needed here (turn is short-circuited in executeTurn).
         */
        private fun buildWarnings(
                loopResult: LoopDetectionResult,
                stepDecision: ExecutorStepDecision
        ): List<String> = buildList {
                loopResult.warning?.let { warning ->
                        if (loopResult.escalation >= EscalationLevel.BLOCK) {
                                add("🚨 LOOP ESCALATION — You are stuck and MUST change strategy.\n" +
                                        "${warning.message}\n" +
                                        "Your recent actions are now BLOCKED. You MUST try a fundamentally " +
                                        "different approach (e.g. go back, use search, try a different menu, " +
                                        "or use shell). Do NOT repeat any variation of your recent actions.")
                        } else {
                                val emoji = if (warning.severity == LoopWarningSeverity.CRITICAL) "🚨" else "⚠️"
                                add("$emoji ${warning.message}")
                        }
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
                return TurnOutcome.Complete(message = summary, success = completion.success)
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

        /**
         * Build a synthetic `complete_task(status="failure")` tool call for Tier 3 forced completion.
         *
         * Uses normal tool execution path so the completion is recorded in history and trace
         * identically to an LLM-initiated completion.
         */
        private fun buildSyntheticFailureCompletion(turnNumber: Int): ToolCallRequest {
                val args = JSONObject().apply {
                        put("status", "failure")
                        put(
                                "answer",
                                "Task could not be completed for goal '${config.goal}': " +
                                        "detected repeated action loop at turn $turnNumber."
                        )
                }
                return ToolCallRequest(
                        id = "forced_${UUID.randomUUID()}",
                        name = ToolName.CompleteTask.raw,
                        arguments = args
                )
        }

        private fun isEscapeActionSignature(signature: String): Boolean {
                return signature.startsWith("mobile_action:system_button:") ||
                        signature.startsWith("open_app:")
        }
}
