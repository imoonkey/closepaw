package com.moonkey.androidagent.agent

import android.util.Log
import com.moonkey.androidagent.history.ResponseItem
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.perception.toSummary
import com.moonkey.androidagent.protocol.*
import com.moonkey.androidagent.session.SessionServices
import com.moonkey.androidagent.tool.MobileActionName
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
        private val eventEmitter: suspend (AgentEvent) -> Unit,
        private val trace: AgentTrace
) {
        companion object {
                private const val TAG = "TurnExecutionPhase"
        }

        suspend fun executeActions(
                turnId: String,
                turnNumber: Int,
                initialSnapshot: ScreenSnapshot,
                toolCallsToExecute: List<ToolCallRequest>
        ): String? {
                if (toolCallsToExecute.isEmpty()) return null

                eventDispatcher.turnPhaseChanged(turnId, TurnPhase.EXECUTION)
                eventDispatcher.status("💡 Executing actions...")
                delay(200)

                var currentSnapshot = initialSnapshot
                var actionForNextTurn: String? = null
                Log.d(
                        TAG,
                        "Using turn snapshot for actions: ${currentSnapshot.elements.size} elements"
                )

                for (toolCall in toolCallsToExecute) {
                        if (actionForNextTurn == null) {
                                actionForNextTurn = classifyAction(toolCall)
                        }
                        currentSnapshot =
                                executeSingleToolCall(
                                        turnId = turnId,
                                        turnNumber = turnNumber,
                                        toolCall = toolCall,
                                        currentSnapshot = currentSnapshot
                                )
                }
                return actionForNextTurn
        }

        private suspend fun executeSingleToolCall(
                turnId: String,
                turnNumber: Int,
                toolCall: ToolCallRequest,
                currentSnapshot: ScreenSnapshot
        ): ScreenSnapshot {
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

                eventEmitter(
                        ActionExecuted(
                                sessionId = config.sessionId,
                                timestamp = System.currentTimeMillis(),
                                actionId = toolResult.callId,
                                toolName = toolCall.name,
                                success = toolResult is ToolCallResult.Success,
                                result = toolResult.toContextString()
                        )
                )
                eventDispatcher.status("✓ ${toolCall.name} executed")
                return snapshotForNextTool
        }

        private suspend fun emitApprovalRequired(details: ApprovalDetails) {
                try {
                        eventEmitter(
                                ApprovalRequired(
                                        sessionId = config.sessionId,
                                        timestamp = System.currentTimeMillis(),
                                        actionId = details.callId,
                                        description = details.description,
                                        details = details
                                )
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
                delay(500)
                val snapshot = services.platform.captureScreen()
                val accessibilityTree = Perceptor.toPromptJson(snapshot)
                return ObservationCapture(
                        observation =
                                ToolObservation.ScreenState(
                                        accessibilityTree = accessibilityTree,
                                        elementCount = snapshot.elements.size,
                                        summary =
                                                snapshot.toSummary(
                                                        services.platform.getCurrentPackageName()
                                                ),
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

        private fun classifyAction(toolCall: ToolCallRequest): String {
                when (ToolName.from(toolCall.name)) {
                        ToolName.Wait -> return "mobile_action:wait"
                        ToolName.SystemButton -> return "mobile_action:system_button"
                        else -> Unit
                }

                if (toolCall.name != ToolName.MobileAction.raw) {
                        return toolCall.name.lowercase()
                }

                val action = toolCall.arguments.optString("action", "").trim().lowercase()
                val mobileActionName = MobileActionName.from(action)
                return when (mobileActionName) {
                        MobileActionName.Scroll -> "scroll:legacy"
                        MobileActionName.Swipe -> {
                                val direction =
                                        toolCall.arguments
                                                .optString("direction", "")
                                                .trim()
                                                .lowercase()
                                "scroll:${direction.ifBlank { "unknown" }}"
                        }
                        else -> "mobile_action:${mobileActionName.canonical}"
                }
        }
}
