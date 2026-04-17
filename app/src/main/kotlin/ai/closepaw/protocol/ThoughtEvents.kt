package ai.closepaw.protocol

/** Agent thought update for Smart Capsule. */
data class ThoughtUpdate(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val thought: String
) : ThoughtDomainEvent
