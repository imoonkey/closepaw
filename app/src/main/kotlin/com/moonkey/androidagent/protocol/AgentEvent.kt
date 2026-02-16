package com.moonkey.androidagent.protocol

/**
 * AgentEvent - Events emitted by the agent session to the UI layer.
 *
 * All state changes, progress updates, and results are emitted as immutable event objects.
 */
sealed interface AgentEvent {
    /** Session this event belongs to */
    val sessionId: SessionId

    /** When this event occurred */
    val timestamp: Long
}
