package com.moonkey.androidagent.protocol

/** A new turn has started. */
data class TurnStarted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val turnId: String,
        val turnNumber: Int
) : TurnDomainEvent

/** A turn has completed. */
data class TurnCompleted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val turnId: String,
        val turnNumber: Int
) : TurnDomainEvent

/** Turn phase has changed. */
data class TurnPhaseChanged(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val turnId: String,
        val phase: TurnPhase
) : TurnDomainEvent
