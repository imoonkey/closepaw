package com.moonkey.androidagent.protocol

/** Agent is asking the user for help. */
data class AskUser(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val type: AskUserType,
        val message: String,
        val callId: String
) : AskUserDomainEvent
