package ai.closepaw.agent

import android.util.Log
import ai.closepaw.history.ResponseItem
import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.perception.Perceptor
import ai.closepaw.protocol.*
import ai.closepaw.session.SessionServices
import ai.closepaw.tool.SimpleToolRouterContext
import ai.closepaw.tool.ToolCallResult
import ai.closepaw.tool.ToolName
import ai.closepaw.tool.ToolObservation
import ai.closepaw.trace.AgentTrace
import kotlinx.coroutines.delay

/**
 * Executes selected tool calls for a turn and emits side effects (history, traces, UI events).
 *
 * This keeps execution concerns separate from AgentTurnRunner orchestration.
 */
internal class TurnExecutionPhaseRunner(
        private val config: AgentExecutionConfig,
        private val services: SessionServices,
        private val eventDispatcher: AgentEventDispatcher,
        private val trace: AgentTrace
) {
        companion object {
                private const val TAG = "TurnExecutionPhase"

                /** Brief pause after phase-change event so UI can render before tool execution starts. */
                private const val PRE_EXECUTION_DELAY_MS = 200L

                /** Wait for UI animations/transitions to settle before capturing post-action screen. */
                private const val POST_ACTION_SETTLE_MS = 500L
        }

        suspend fun executeActions(
                turnId: String,
                turnNumber: Int,
                initialSnapshot: ScreenSnapshot,
                toolCallsToExecute: List<ToolCallRequest>
        ): ExecutionPhaseResult {
                if (toolCallsToExecute.isEmpty()) return ExecutionPhaseResult.EMPTY

                eventDispatcher.turnPhaseChanged(turnId, TurnPhase.EXECUTION)
                eventDispatcher.status("💡 Executing actions...")
                delay(PRE_EXECUTION_DELAY_MS)

                var currentSnapshot = initialSnapshot
                Log.d(
                        TAG,
                        "Using turn snapshot for actions: ${currentSnapshot.elements.size} elements"
                )

                val executedToolIds = mutableSetOf<String>()
                var terminatedEarly = false
                var lastTerminalResult: ToolCallResult? = null
                for (toolCall in toolCallsToExecute) {
                        val result = executeSingleToolCall(
                                turnId = turnId,
                                turnNumber = turnNumber,
                                toolCall = toolCall,
                                currentSnapshot = currentSnapshot
                        )
                        currentSnapshot = result.snapshot
                        executedToolIds += toolCall.id
                        lastTerminalResult = result.toolResult
                        if (result.toolResult !is ToolCallResult.Success) {
                                Log.w(TAG, "Action ${toolCall.name} did not succeed; aborting remaining actions in this turn")
                                terminatedEarly = true
                                break
                        }
                }
                return ExecutionPhaseResult(
                        executedToolIds = executedToolIds,
                        terminatedEarly = terminatedEarly,
                        lastTerminalResult = lastTerminalResult
                )
        }

        private data class SingleToolCallResult(
                val snapshot: ScreenSnapshot,
                val toolResult: ToolCallResult
        )

        private suspend fun executeSingleToolCall(
                turnId: String,
                turnNumber: Int,
                toolCall: ToolCallRequest,
                currentSnapshot: ScreenSnapshot
        ): SingleToolCallResult {
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
                                packageName = services.platform.getCurrentPackageName(),
                                callId = toolCall.id,
                                onApprovalRequired = { details -> emitApprovalRequired(details) }
                        )

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

                val outcome = toolResult.toActionOutcome()
                eventDispatcher.actionExecuted(
                        actionId = toolResult.callId,
                        toolName = toolCall.name,
                        outcome = outcome,
                        result = toolResult.toContextString()
                )
                val statusSymbol = when (outcome) {
                        ActionOutcome.SUCCESS -> "✓ ${toolCall.name} executed"
                        ActionOutcome.FAILED -> "✗ ${toolCall.name} failed"
                        ActionOutcome.SKIPPED -> "⊘ ${toolCall.name} skipped"
                }
                eventDispatcher.status(statusSymbol)
                return SingleToolCallResult(
                        snapshot = snapshotForNextTool,
                        toolResult = toolResult
                )
        }

        private suspend fun emitApprovalRequired(details: ApprovalDetails) {
                eventDispatcher.approvalRequired(
                        description = details.description,
                        details = details
                )
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
                                is ToolObservation.TextOutput ->
                                        ObservationCapture(observation, null)
                        }
                }

                if (toolCall.name == ToolName.CompleteTask.raw) {
                        Log.d(TAG, "Skipping post-action capture for complete_task")
                        return ObservationCapture(
                                observation =
                                        ToolObservation.TextOutput(
                                                "Completion acknowledged; no screen captured."
                                        ),
                                snapshot = null
                        )
                }

                return runCatching { captureObservationWithSnapshot() }
                        .getOrElse { e ->
                                Log.w(TAG, "Post-action screen capture failed; falling back to text-only observation", e)
                                ObservationCapture(
                                        observation = ToolObservation.TextOutput(formatToolResult(toolResult)),
                                        snapshot = null
                                )
                        }
        }

        private data class ObservationCapture(
                val observation: ToolObservation,
                val snapshot: ScreenSnapshot?
        )

        /** Captures a fresh post-action screen snapshot and wraps it as a screen observation. */
        private suspend fun captureObservationWithSnapshot(): ObservationCapture {
                delay(POST_ACTION_SETTLE_MS)
                val rawSnapshot = services.platform.captureScreen()
                val currentPkg = services.platform.getCurrentPackageName()
                // Perception gate: mask BLOCKED app content in post-action captures too
                val snapshot = services.appClassifier.maskIfBlocked(rawSnapshot, currentPkg)
                val accessibilityTree = Perceptor.toPromptJson(snapshot)
                return ObservationCapture(
                        observation =
                                ToolObservation.ScreenState(
                                        accessibilityTree = accessibilityTree,
                                        elementCount = snapshot.elements.size,
                                        summary =
                                                buildString {
                                                        append(
                                                                services.platform.getCurrentPackageName()
                                                                        ?: "unknown app"
                                                        )
                                                        append(" | elements=")
                                                        append(snapshot.elements.size)
                                                },
                                        snapshot = snapshot
                                ),
                        snapshot = snapshot
                )
        }

        /** Meta-only: no screen state in tool results. */
        private fun formatToolResult(result: ToolCallResult): String =
                when (result) {
                        is ToolCallResult.Success -> "Success: ${result.output}"
                        is ToolCallResult.Error -> "Error: ${result.error}"
                        is ToolCallResult.Cancelled -> "Cancelled: ${result.reason}"
                }

}

internal fun ToolCallResult.toActionOutcome(): ActionOutcome =
        when (this) {
                is ToolCallResult.Success -> ActionOutcome.SUCCESS
                is ToolCallResult.Error -> ActionOutcome.FAILED
                is ToolCallResult.Cancelled -> ActionOutcome.SKIPPED
        }
