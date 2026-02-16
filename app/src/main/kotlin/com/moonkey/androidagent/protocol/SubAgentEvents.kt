package com.moonkey.androidagent.protocol

/** Parent agent delegated a task to a sub-agent. */
data class SubAgentStarted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val agentName: String,
        val query: String
) : SubAgentDomainEvent

/** Bridged activity emitted from a running sub-agent. */
data class SubAgentActivity(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val agentName: String,
        val activity: String
) : SubAgentDomainEvent

/** Sub-agent completed with success/failure status. */
data class SubAgentCompleted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val agentName: String,
        val success: Boolean,
        val message: String
) : SubAgentDomainEvent
