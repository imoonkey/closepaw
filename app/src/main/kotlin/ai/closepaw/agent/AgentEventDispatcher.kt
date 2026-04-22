package ai.closepaw.agent

import android.util.Log
import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.protocol.*

class AgentEventDispatcher(
    private val sessionId: SessionId,
    private val eventEmitter: suspend (AgentEvent) -> Unit
) {
    companion object {
        private const val TAG = "AgentEventDispatcher"
    }

    suspend fun status(status: String) {
        Log.d(TAG, "Status: $status")
        eventEmitter(StatusUpdate(
            sessionId = sessionId,
            timestamp = now(),
            status = status
        ))
    }

    suspend fun messageDelta(turnId: String, delta: String) {
        Log.d(TAG, "MessageDelta: turnId=$turnId, delta=${delta.take(50)}...")
        eventEmitter(MessageDelta(
            sessionId = sessionId,
            timestamp = now(),
            turnId = turnId,
            delta = delta
        ))
    }

    suspend fun actionProposed(actionId: String, toolName: String, description: String) {
        Log.d(TAG, "ActionProposed: $toolName - $description")
        eventEmitter(ActionProposed(
            sessionId = sessionId,
            timestamp = now(),
            actionId = actionId,
            toolName = toolName,
            description = description
        ))
    }

    suspend fun turnStarted(turnId: String, turnNumber: Int) {
        eventEmitter(TurnStarted(
            sessionId = sessionId,
            timestamp = now(),
            turnId = turnId,
            turnNumber = turnNumber
        ))
    }

    suspend fun turnPhaseChanged(turnId: String, phase: TurnPhase) {
        eventEmitter(TurnPhaseChanged(
            sessionId = sessionId,
            timestamp = now(),
            turnId = turnId,
            phase = phase
        ))
    }

    suspend fun turnCompleted(turnId: String, turnNumber: Int) {
        eventEmitter(TurnCompleted(
            sessionId = sessionId,
            timestamp = now(),
            turnId = turnId,
            turnNumber = turnNumber
        ))
    }

    suspend fun screenCaptured(
        snapshot: ScreenSnapshot,
        packageName: String?,
        activityName: String?,
        turnId: String,
        turnNumber: Int,
        phase: ScreenStatePhase,
        traceRunId: String?
    ) {
        eventEmitter(ScreenCaptured(
            sessionId = sessionId,
            timestamp = now(),
            elementCount = snapshot.elements.size,
            packageName = packageName,
            activityName = activityName,
            turnId = turnId,
            turnNumber = turnNumber,
            phase = phase,
            rawA11yTreePath = snapshot.debug?.rawA11yTreePath,
            sanitizedA11yTreePath = snapshot.debug?.sanitizedA11yTreePath,
            screenshotPath = snapshot.debug?.screenshotPath,
            traceRunId = traceRunId
        ))
    }

    /**
     * Emit a thought update for the Smart Capsule.
     * Extracted from agent_thought in tool call parameters.
     */
    suspend fun thoughtUpdate(full: String, compact: String) {
        Log.d(TAG, "ThoughtUpdate: $compact")
        eventEmitter(ThoughtUpdate(
            sessionId = sessionId,
            timestamp = now(),
            full = full,
            compact = compact
        ))
    }

    /**
     * Emit an AskUser event — agent is requesting user help.
     */
    suspend fun emitAskUser(
        type: ai.closepaw.protocol.AskUserType,
        message: String,
        callId: String
    ) {
        Log.d(TAG, "AskUser: type=$type, message=${message.take(40)}, callId=$callId")
        eventEmitter(AskUser(
            sessionId = sessionId,
            timestamp = now(),
            type = type,
            message = message,
            callId = callId
        ))
    }

    suspend fun actionExecuted(actionId: String, toolName: String, outcome: ActionOutcome, result: String?) {
        Log.d(TAG, "ActionExecuted: $toolName outcome=$outcome")
        eventEmitter(ActionExecuted(
            sessionId = sessionId,
            timestamp = now(),
            actionId = actionId,
            toolName = toolName,
            outcome = outcome,
            result = result
        ))
    }

    suspend fun approvalRequired(description: String, details: ApprovalDetails) {
        Log.d(TAG, "ApprovalRequired: $description")
        eventEmitter(ApprovalRequired(
            sessionId = sessionId,
            timestamp = now(),
            description = description,
            details = details
        ))
    }

    suspend fun subAgentStarted(agentName: String, query: String) {
        Log.d(TAG, "SubAgentStarted: $agentName")
        eventEmitter(SubAgentStarted(
            sessionId = sessionId,
            timestamp = now(),
            agentName = agentName,
            query = query
        ))
    }

    suspend fun subAgentActivity(agentName: String, activity: String) {
        Log.d(TAG, "SubAgentActivity: $agentName - $activity")
        eventEmitter(SubAgentActivity(
            sessionId = sessionId,
            timestamp = now(),
            agentName = agentName,
            activity = activity
        ))
    }

    suspend fun subAgentCompleted(agentName: String, success: Boolean, message: String) {
        Log.d(TAG, "SubAgentCompleted: $agentName success=$success")
        eventEmitter(SubAgentCompleted(
            sessionId = sessionId,
            timestamp = now(),
            agentName = agentName,
            success = success,
            message = message
        ))
    }

    private fun now(): Long = System.currentTimeMillis()
}
