package ai.closepaw.protocol

/** General status update for simple UI display. */
data class StatusUpdate(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val status: String
) : StatusDomainEvent
