package com.moonkey.androidagent.agent

import android.util.Log
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.ScreenStatePhase
import com.moonkey.androidagent.protocol.SessionId
import com.moonkey.androidagent.protocol.Todo
import com.moonkey.androidagent.protocol.TurnPhase

class AgentEventDispatcher(
    private val sessionId: SessionId,
    private val eventEmitter: suspend (AgentEvent) -> Unit
) {
    companion object {
        private const val TAG = "AgentEventDispatcher"
    }

    suspend fun status(status: String) {
        Log.d(TAG, "Status: $status")
        eventEmitter(AgentEvent.StatusUpdate(
            sessionId = sessionId,
            timestamp = now(),
            status = status
        ))
    }

    suspend fun messageDelta(turnId: String, delta: String) {
        Log.d(TAG, "MessageDelta: turnId=$turnId, delta=${delta.take(50)}...")
        eventEmitter(AgentEvent.MessageDelta(
            sessionId = sessionId,
            timestamp = now(),
            turnId = turnId,
            delta = delta
        ))
    }

    suspend fun actionProposed(actionId: String, toolName: String, description: String) {
        Log.d(TAG, "ActionProposed: $toolName - $description")
        eventEmitter(AgentEvent.ActionProposed(
            sessionId = sessionId,
            timestamp = now(),
            actionId = actionId,
            toolName = toolName,
            description = description
        ))
    }

    suspend fun turnStarted(turnId: String, turnNumber: Int) {
        eventEmitter(AgentEvent.TurnStarted(
            sessionId = sessionId,
            timestamp = now(),
            turnId = turnId,
            turnNumber = turnNumber,
            phase = TurnPhase.PERCEPTION
        ))
    }

    suspend fun turnPhaseChanged(turnId: String, phase: TurnPhase) {
        eventEmitter(AgentEvent.TurnPhaseChanged(
            sessionId = sessionId,
            timestamp = now(),
            turnId = turnId,
            phase = phase
        ))
    }

    suspend fun turnCompleted(turnId: String, turnNumber: Int) {
        eventEmitter(AgentEvent.TurnCompleted(
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
        eventEmitter(AgentEvent.ScreenCaptured(
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
        eventEmitter(AgentEvent.TodosUpdated(
            sessionId = sessionId,
            timestamp = now(),
            todos = todos
        ))
    }

    suspend fun scratchpadUpdated(key: String, action: String) {
        eventEmitter(AgentEvent.ScratchpadUpdated(
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
        eventEmitter(AgentEvent.ThoughtUpdate(
            sessionId = sessionId,
            timestamp = now(),
            thought = thought
        ))
    }

    private fun now(): Long = System.currentTimeMillis()
}
