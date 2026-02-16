package com.moonkey.androidagent.app

import android.util.Log
import com.moonkey.androidagent.history.SessionRecordingService
import com.moonkey.androidagent.history.model.ScreenStateRecord
import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.CompletionReason
import java.util.UUID

/**
 * Encapsulates AgentEvent side effects for AgentService.
 *
 * Keeping this outside AgentService reduces service surface area and keeps
 * lifecycle/control flow separate from event-specific UI/recording updates.
 */
internal class AgentServiceEventHandler(
    private val logTag: String,
    private val updateStatus: (String) -> Unit,
    private val sessionCleared: () -> Unit,
    private val overlayController: () -> ServiceOverlayController?
) {
    fun handleEvent(
        event: AgentEvent,
        recordingService: SessionRecordingService? = null
    ) {
        Log.d(logTag, "Received event: ${event::class.simpleName}")
        val overlay = overlayController()

        when (event) {
            is AgentEvent.StatusUpdate -> {
                val displayStatus = if (event.emoji != null) {
                    "${event.emoji} ${event.status}"
                } else {
                    event.status
                }
                updateStatus(displayStatus)
            }
            is AgentEvent.SessionStarted -> {
                Log.i(logTag, "Session started: ${event.sessionId}, goal: ${event.goal}")
            }

            // ===== Task Events (for SmartCapsule streaming) =====
            is AgentEvent.TaskStarted -> {
                recordingService?.recordUserMessage(
                    UUID.randomUUID().toString(),
                    event.timestamp,
                    event.input
                )
                recordingService?.startAgentMessage(event.taskId, event.timestamp)
                overlay?.onTaskStarted(event.taskId, event.input)
            }
            is AgentEvent.MessageDelta -> {
                recordingService?.appendTextDelta(event.delta)
            }
            is AgentEvent.ThoughtUpdate -> {
                overlay?.onThoughtUpdate(event.thought)
            }
            is AgentEvent.TurnPhaseChanged -> {
                overlay?.onTurnPhaseChanged(event.phase)
            }
            is AgentEvent.ActionExecuted -> {
                val state = if (event.success) "success" else "failed"
                recordingService?.updateActionState(event.actionId, state, event.result)
                overlay?.onActionExecuted(event.toolName, event.success)
            }
            is AgentEvent.SubAgentStarted -> {
                updateStatus("🤖 Delegating to ${event.agentName}...")
            }
            is AgentEvent.SubAgentActivity -> {
                // Activity events can be very frequent; keep UI/log noise low.
            }
            is AgentEvent.SubAgentCompleted -> {
                val status = if (event.success) "completed" else "failed"
                updateStatus("🤖 ${event.agentName} $status")
            }
            is AgentEvent.TaskCompleted -> {
                Log.i(logTag, "Task completed: ${event.taskId}, reason: ${event.reason}")
                recordingService?.completeAgentMessage()
                overlay?.onTaskCompleted(event.reason, event.result)
            }
            is AgentEvent.ActionProposed -> {
                recordingService?.recordAction(
                    actionId = event.actionId,
                    toolName = event.toolName,
                    description = event.description,
                    state = "proposed"
                )
            }
            is AgentEvent.ScreenCaptured -> {
                recordingService?.recordScreenState(
                    ScreenStateRecord(
                        id = UUID.randomUUID().toString(),
                        timestamp = event.timestamp,
                        turnId = event.turnId,
                        turnNumber = event.turnNumber,
                        phase = event.phase,
                        elementCount = event.elementCount,
                        packageName = event.packageName,
                        activityName = event.activityName,
                        rawA11yTreePath = event.rawA11yTreePath,
                        sanitizedA11yTreePath = event.sanitizedA11yTreePath,
                        screenshotPath = event.screenshotPath,
                        traceRunId = event.traceRunId
                    )
                )
            }

            // ===== Session Lifecycle Events =====
            is AgentEvent.SessionCompleted -> {
                Log.i(logTag, "Session completed: ${event.sessionId}, reason: ${event.reason}")
                val statusMessage = when (event.reason) {
                    CompletionReason.GOAL_ACHIEVED -> "✅ Goal achieved!"
                    CompletionReason.USER_STOPPED -> "🛑 Agent stopped"
                    CompletionReason.MAX_TURNS -> "⚠️ Max turns reached"
                    CompletionReason.TASK_IMPOSSIBLE -> "❌ Task cannot be completed"
                    CompletionReason.ERROR -> "❌ Session ended with error"
                    CompletionReason.INTERRUPTED -> "🛑 Session interrupted"
                }
                updateStatus(statusMessage)
                overlay?.onSessionCompleted(event.reason)
                sessionCleared()
            }
            is AgentEvent.SessionError -> {
                Log.e(logTag, "Session error: ${event.error.message}")
                updateStatus("❌ Error: ${event.error.message}")
                overlay?.onSessionError(event.error.message)
            }
            is AgentEvent.SessionTakeover -> {
                Log.i(logTag, "Session takeover: ${event.sessionId}")
                overlay?.onSessionTakeover()
            }
            is AgentEvent.SessionResumed -> {
                Log.i(logTag, "Session resumed: ${event.sessionId}")
                overlay?.onSessionResumed()
            }
            is AgentEvent.SupplementReceived -> {
                Log.i(logTag, "Supplement received: ${event.text.take(30)}")
                overlay?.onSupplementReceived(event.text)
            }
            is AgentEvent.AskUser -> {
                Log.i(logTag, "AskUser: type=${event.type}, callId=${event.callId}")
                overlay?.onAskUser(event.type, event.message, event.callId)
            }

            else -> {
                Log.d(logTag, "Unhandled event type: ${event::class.simpleName}")
            }
        }
    }
}
