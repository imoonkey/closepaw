package com.moonkey.androidagent.protocol

/** A text delta from the streaming response. */
data class MessageDelta(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val turnId: String,
        val delta: String
) : StreamingDomainEvent
