package com.moonkey.androidagent.agent

import android.util.Log
import com.moonkey.androidagent.history.ResponseItem
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.protocol.*
import com.moonkey.androidagent.session.SessionServices
import com.moonkey.androidagent.tool.SimpleToolRouterContext
import com.moonkey.androidagent.tool.ToolCallResult
import com.moonkey.androidagent.tool.ToolName
import com.moonkey.androidagent.tool.ToolObservation
import com.moonkey.androidagent.trace.AgentTrace
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
        ) {
                if (toolCallsToExecute.isEmpty()) return

                eventDispatcher.turnPhaseChanged(turnId, TurnPhase.EXECUTION)
                eventDispatcher.status("💡 Executing actions...")
                delay(PRE_EXECUTION_DELAY_MS)

                var currentSnapshot = initialSnapshot
                Log.d(
                        TAG,
                        "Using turn snapshot for actions: ${currentSnapshot.elements.size} elements"
                )

                for (toolCall in toolCallsToExecute) {
                        val result = executeSingleToolCall(
                                turnId = turnId,
                                turnNumber = turnNumber,
                                toolCall = toolCall,
                                currentSnapshot = currentSnapshot
                        )
                        currentSnapshot = result.snapshot
                        if (!result.success) {
                                Log.w(TAG, "Action ${toolCall.name} failed; aborting remaining actions in this turn")
                                break
                        }
                }
        }

        private data class SingleToolCallResult(val snapshot: ScreenSnapshot, val success: Boolean)

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

                emitPlanningEvents(toolCall, toolResult)

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

                eventDispatcher.actionExecuted(
                        actionId = toolResult.callId,
                        toolName = toolCall.name,
                        success = toolResult is ToolCallResult.Success,
                        result = toolResult.toContextString()
                )
                eventDispatcher.status("✓ ${toolCall.name} executed")
                return SingleToolCallResult(
                        snapshot = snapshotForNextTool,
                        success = toolResult is ToolCallResult.Success
                )
        }

        private suspend fun emitApprovalRequired(details: ApprovalDetails) {
                try {
                        eventDispatcher.approvalRequired(
                                actionId = details.callId,
                                description = details.description,
                                details = details
                        )
                } catch (e: Exception) {
                        Log.e(TAG, "Failed to emit approval required event", e)
                }
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

                return captureObservationWithSnapshot()
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

        private suspend fun emitPlanningEvents(
                toolCall: ToolCallRequest,
                toolResult: ToolCallResult
        ) {
                if (toolResult !is ToolCallResult.Success) return
                when (ToolName.from(toolCall.name)) {
                        ToolName.WriteTodos -> {
                                eventDispatcher.todosUpdated(services.sessionState.todos.get())
                        }
                        ToolName.Scratchpad -> {
                                val action = toolCall.arguments.optString("action", "")
                                if (action == "write" || action == "delete") {
                                        val key = toolCall.arguments.optString("key", "")
                                        if (key.isNotBlank()) {
                                                eventDispatcher.scratchpadUpdated(key, action)
                                        }
                                }
                        }
                        else -> Unit
                }
        }

        /** Meta-only: no screen state in tool results. */
        private fun formatToolResult(result: ToolCallResult): String =
                when (result) {
                        is ToolCallResult.Success -> "Success: ${result.output}"
                        is ToolCallResult.Error -> "Error: ${result.error}"
                        is ToolCallResult.Cancelled -> "Cancelled: ${result.reason}"
                }

}
