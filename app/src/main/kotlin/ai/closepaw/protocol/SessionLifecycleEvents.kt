package ai.closepaw.protocol

/** Session has started. */
data class SessionStarted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val goal: String
) : SessionLifecycleEvent

/** Session completed (successfully or via user stop). */
data class SessionCompleted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val reason: SessionEndReason
) : SessionLifecycleEvent

/** Session encountered an error. */
data class SessionError(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val message: String
) : SessionLifecycleEvent

/** User took over control (agent paused). */
data class SessionTakeover(
        override val sessionId: SessionId,
        override val timestamp: Long
) : SessionLifecycleEvent

/** Session was resumed after takeover. */
data class SessionResumed(
        override val sessionId: SessionId,
        override val timestamp: Long
) : SessionLifecycleEvent

/** User injected a mid-task supplement message. */
data class SupplementReceived(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val text: String
) : SessionLifecycleEvent
