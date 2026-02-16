package com.moonkey.androidagent.protocol

/** User approval is required for an action. */
data class ApprovalRequired(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val actionId: String,
        val description: String,
        val details: ApprovalDetails
) : ApprovalDomainEvent

/** Approval request was resolved (approved, denied, or timed out). */
data class ApprovalResolved(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val actionId: String,
        val decision: ApprovalDecision
) : ApprovalDomainEvent
