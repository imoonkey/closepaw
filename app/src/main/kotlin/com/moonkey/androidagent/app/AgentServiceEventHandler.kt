package com.moonkey.androidagent.app

import android.util.Log
import com.moonkey.androidagent.history.SessionRecordingService
import com.moonkey.androidagent.history.model.ScreenStateRecord
import com.moonkey.androidagent.protocol.*
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
            is StatusUpdate -> {
                updateStatus(event.status)
            }
            is SessionStarted -> {
                Log.i(logTag, "Session started: ${event.sessionId}, goal: ${event.goal}")
            }

            // ===== Task Events (for SmartCapsule streaming) =====
            is TaskStarted -> {
                recordingService?.recordUserMessage(
                    UUID.randomUUID().toString(),
                    event.timestamp,
                    event.input
                )
                recordingService?.startAgentMessage(event.taskId, event.timestamp)
                overlay?.onTaskStarted(event.taskId, event.input)
            }
            is MessageDelta -> {
                recordingService?.appendTextDelta(event.delta)
            }
            is ThoughtUpdate -> {
                overlay?.onThoughtUpdate(event.thought)
            }
            is TurnPhaseChanged -> {
                overlay?.onTurnPhaseChanged(event.phase)
            }
            is ActionExecuted -> {
                val state = when (event.outcome) {
                    ActionOutcome.SUCCESS -> "success"
                    ActionOutcome.FAILED -> "failed"
                    ActionOutcome.SKIPPED -> "skipped"
                }
                recordingService?.updateActionState(event.actionId, state, event.result)
                overlay?.onActionExecuted(event.toolName, event.outcome == ActionOutcome.SUCCESS)
            }
            is SubAgentStarted -> {
                updateStatus("🤖 Delegating to ${event.agentName}...")
            }
            is SubAgentActivity -> {
                // Activity events can be very frequent; keep UI/log noise low.
            }
            is SubAgentCompleted -> {
                val status = if (event.success) "completed" else "failed"
                updateStatus("🤖 ${event.agentName} $status")
            }
            is TaskCompleted -> {
                Log.i(logTag, "Task completed: ${event.taskId}, outcome: ${event.outcome}")
                // Finalize the agent message buffer so the session file
                // is complete before Hot Idle. Without this, the agent
                // message stays in the buffer and the on-disk session
                // record is missing the final text/actions until the
                // next recordUserMessage() call triggers finalization.
                recordingService?.completeAgentMessage()
                recordingService?.recordTaskOutcome(event.outcome)
                overlay?.onTaskCompleted(event.outcome, event.result)
            }
            is ActionProposed -> {
                recordingService?.recordAction(
                    actionId = event.actionId,
                    toolName = event.toolName,
                    description = event.description,
                    state = "proposed"
                )
            }
            is ScreenCaptured -> {
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
            is SessionCompleted -> {
                Log.i(logTag, "Session completed: ${event.sessionId}, reason: ${event.reason}")
                recordingService?.completeSession()
                val statusMessage = when (event.reason) {
                    SessionEndReason.USER_STOPPED -> "🛑 Agent stopped"
                    SessionEndReason.INTERRUPTED -> "🛑 Session interrupted"
                    SessionEndReason.IDLE_TIMEOUT -> "💤 Session timed out"
                }
                updateStatus(statusMessage)
                overlay?.onSessionCompleted(event.reason)
                sessionCleared()
            }
            is SessionError -> {
                Log.e(logTag, "Session error: ${event.message}")
                updateStatus("❌ Error: ${event.message}")
                overlay?.onSessionError(event.message)
            }
            is SessionTakeover -> {
                Log.i(logTag, "Session takeover: ${event.sessionId}")
                overlay?.onSessionTakeover()
            }
            is SessionResumed -> {
                Log.i(logTag, "Session resumed: ${event.sessionId}")
                overlay?.onSessionResumed()
            }
            is SupplementReceived -> {
                Log.i(logTag, "Supplement received: ${event.text.take(30)}")
                // Same "user message splits conversation" as TaskStarted:
                // finalize current agent → record user message → start new agent segment
                recordingService?.recordUserMessage(
                    UUID.randomUUID().toString(),
                    event.timestamp,
                    event.text
                )
                recordingService?.startAgentMessage(
                    "supplement-${event.timestamp}",
                    event.timestamp
                )
                overlay?.onSupplementReceived(event.text)
            }
            is AskUser -> {
                Log.i(logTag, "AskUser: type=${event.type}, callId=${event.callId}")
                overlay?.onAskUser(event.type, event.message, event.callId)
            }
            is ApprovalRequired -> {
                Log.i(logTag, "ApprovalRequired: callId=${event.details.callId}")
                overlay?.onApprovalRequired(event.details)
            }

            else -> {
                Log.d(logTag, "Unhandled event type: ${event::class.simpleName}")
            }
        }
    }
}
