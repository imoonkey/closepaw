package com.moonkey.androidagent.agent

import android.util.Log
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.protocol.*

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
            turnNumber = turnNumber,
            phase = TurnPhase.PERCEPTION
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

    suspend fun todosUpdated(todos: List<Todo>) {
        eventEmitter(TodosUpdated(
            sessionId = sessionId,
            timestamp = now(),
            todos = todos
        ))
    }

    suspend fun scratchpadUpdated(key: String, action: String) {
        eventEmitter(ScratchpadUpdated(
            sessionId = sessionId,
            timestamp = now(),
            key = key,
            action = action
        ))
    }

    /**
     * Emit a thought update for the Smart Capsule.
     * Extracted from agent_thought in tool call parameters.
     */
    suspend fun thoughtUpdate(thought: String) {
        Log.d(TAG, "ThoughtUpdate: $thought")
        eventEmitter(ThoughtUpdate(
            sessionId = sessionId,
            timestamp = now(),
            thought = thought
        ))
    }

    /**
     * Emit an AskUser event — agent is requesting user help.
     */
    suspend fun emitAskUser(
        type: com.moonkey.androidagent.protocol.AskUserType,
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

    suspend fun actionExecuted(actionId: String, toolName: String, success: Boolean, result: String?) {
        Log.d(TAG, "ActionExecuted: $toolName success=$success")
        eventEmitter(ActionExecuted(
            sessionId = sessionId,
            timestamp = now(),
            actionId = actionId,
            toolName = toolName,
            success = success,
            result = result
        ))
    }

    suspend fun approvalRequired(actionId: String, description: String, details: ApprovalDetails) {
        Log.d(TAG, "ApprovalRequired: $description")
        eventEmitter(ApprovalRequired(
            sessionId = sessionId,
            timestamp = now(),
            actionId = actionId,
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
