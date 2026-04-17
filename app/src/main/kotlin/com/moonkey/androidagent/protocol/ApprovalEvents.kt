package com.moonkey.androidagent.protocol

/** User approval is required for an action. */
data class ApprovalRequired(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val description: String,
        val details: ApprovalDetails
) : ApprovalDomainEvent
