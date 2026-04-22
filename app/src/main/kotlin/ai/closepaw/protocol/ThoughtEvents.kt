package ai.closepaw.protocol

/**
 * Agent thought update for Smart Capsule and chat trace.
 *
 * @param full untouched (whitespace-trimmed) agent_thought; canonical for chat + history.
 * @param compact ~80-char single-line slice; for surfaces that opt into a compact form
 *   (e.g. reduced-motion capsule fallback). Produced once via [compactThought].
 */
data class ThoughtUpdate(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val full: String,
        val compact: String
) : ThoughtDomainEvent
