package com.moonkey.androidagent.protocol

import org.json.JSONObject

/**
 * ApprovalDecision - User's response to an approval request.
 */
enum class ApprovalDecision {
    /** User approved the action */
    APPROVED,
    
    /** User denied the action (skip this action, continue session) */
    DENIED,
    
    /** User wants to abort the entire session */
    ABORT
}

/**
 * RiskLevel - Classification of action risk for approval decisions.
 */
enum class RiskLevel {
    /** Low risk - typically auto-approved (e.g., read-only, reversible) */
    LOW,
    
    /** Medium risk - may require approval depending on policy */
    MEDIUM,
    
    /** High risk - typically requires explicit approval (e.g., destructive actions) */
    HIGH
}

/**
 * ApprovalRequirement - Whether and why an action needs approval.
 */
sealed interface ApprovalRequirement {
    /** No approval needed */
    data object None : ApprovalRequirement
    
    /** Approval required before execution */
    data class Required(
        val reason: String,
        val riskLevel: RiskLevel
    ) : ApprovalRequirement
    
    /** Action is forbidden and cannot be approved */
    data class Forbidden(val reason: String) : ApprovalRequirement
}

/**
 * ApprovalDetails - Information about what is being approved.
 */
data class ApprovalDetails(
    /** Unique ID for this approval request (from ToolRouter) */
    val callId: String,
    
    /** Name of the tool being invoked */
    val toolName: String,
    
    /** Arguments passed to the tool */
    val args: JSONObject,
    
    /** Human-readable description of the action */
    val description: String = "",
    
    /** Risk assessment */
    val riskLevel: RiskLevel = RiskLevel.MEDIUM
)

