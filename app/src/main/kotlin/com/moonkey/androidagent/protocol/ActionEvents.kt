package com.moonkey.androidagent.protocol

/** An action has been proposed (before execution). */
data class ActionProposed(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val actionId: String,
        val toolName: String,
        val description: String
) : ActionDomainEvent

/** An action has been executed. */
data class ActionExecuted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val actionId: String,
        val toolName: String,
        val success: Boolean,
        val result: String?
) : ActionDomainEvent
