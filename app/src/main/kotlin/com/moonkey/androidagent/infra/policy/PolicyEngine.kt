package com.moonkey.androidagent.infra.policy

import android.util.Log
import com.moonkey.androidagent.protocol.ApprovalMode
import com.moonkey.androidagent.protocol.RiskLevel
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * PolicyEngine - Decides whether tool calls should be allowed, denied, or require approval.
 * 
 * The policy engine evaluates each tool call and returns a decision:
 * - ALLOW: Execute immediately without user approval
 * - DENY: Reject the tool call (forbidden by policy)
 * - ASK_USER: Request user approval before execution
 * 
 * Pattern from Gemini CLI's PolicyEngine.
 */
class PolicyEngine(
    initialApprovalMode: ApprovalMode = ApprovalMode.SMART
) {
    // M2 fix: Use AtomicReference for thread-safe approval mode changes
    private val approvalMode = AtomicReference(initialApprovalMode)
    
    companion object {
        private const val TAG = "PolicyEngine"
        
        // TODO (M8): Consider loading risk levels from configuration file for per-deployment customization.
        // Default risk levels for common Mobile-Agent tools
        private val DEFAULT_RISK_LEVELS = mapOf(
            // Low risk - typically reversible or read-only
            "click" to RiskLevel.LOW,
            "scroll" to RiskLevel.LOW,
            "swipe" to RiskLevel.LOW,
            "back" to RiskLevel.LOW,
            "wait" to RiskLevel.LOW,
            
            // Medium risk - may modify state
            "type" to RiskLevel.MEDIUM,
            "home" to RiskLevel.MEDIUM,
            
            // High risk - potentially destructive (reserved for future tools)
            // M5: These are reserved for future tool implementations (e.g., app management, e-commerce)
            "install" to RiskLevel.HIGH,
            "uninstall" to RiskLevel.HIGH,
            "delete" to RiskLevel.HIGH,
            "purchase" to RiskLevel.HIGH,
            "send" to RiskLevel.HIGH
        )
    }
    
    // Custom risk overrides
    private val riskOverrides = mutableMapOf<String, RiskLevel>()
    
    // Explicitly allowed/denied tools
    private val allowList = mutableSetOf<String>()
    private val denyList = mutableSetOf<String>()
    
    /**
     * Check if a tool call should be allowed, denied, or requires approval.
     * 
     * @param toolName The name of the tool
     * @param params The parameters for the tool call
     * @return PolicyDecision indicating how to proceed
     */
    fun check(toolName: String, params: JSONObject = JSONObject()): PolicyDecision {
        val currentMode = approvalMode.get()
        Log.d(TAG, "Checking policy for: $toolName (mode: $currentMode)")
        
        // Check deny list first
        if (toolName in denyList) {
            Log.d(TAG, "Tool $toolName is in deny list")
            return PolicyDecision.Deny("Tool '$toolName' is forbidden by policy")
        }
        
        // Check allow list (overrides everything except deny list)
        if (toolName in allowList) {
            Log.d(TAG, "Tool $toolName is in allow list")
            return PolicyDecision.Allow
        }
        
        // Apply approval mode (M2: read atomic value)
        return when (currentMode) {
            ApprovalMode.ALWAYS_ASK -> {
                PolicyDecision.AskUser(
                    reason = "Approval required for all actions",
                    riskLevel = getRiskLevel(toolName)
                )
            }
            
            ApprovalMode.AUTO_APPROVE -> {
                PolicyDecision.Allow
            }
            
            ApprovalMode.SMART -> {
                evaluateRisk(toolName, params)
            }
        }
    }
    
    /**
     * Evaluate risk-based policy for SMART mode.
     */
    private fun evaluateRisk(toolName: String, params: JSONObject): PolicyDecision {
        val riskLevel = getRiskLevel(toolName)
        
        return when (riskLevel) {
            RiskLevel.LOW -> {
                PolicyDecision.Allow
            }
            RiskLevel.MEDIUM -> {
                // For medium risk, allow but could be configurable
                // For now, allow to maintain good UX
                PolicyDecision.Allow
            }
            RiskLevel.HIGH -> {
                PolicyDecision.AskUser(
                    reason = "High-risk action requires approval",
                    riskLevel = RiskLevel.HIGH
                )
            }
        }
    }
    
    /**
     * Get the risk level for a tool.
     */
    fun getRiskLevel(toolName: String): RiskLevel {
        // Check custom overrides first
        riskOverrides[toolName]?.let { return it }
        
        // Use default risk levels
        return DEFAULT_RISK_LEVELS[toolName] ?: RiskLevel.MEDIUM
    }
    
    // ===== Configuration Methods =====
    
    /**
     * Set the approval mode (M2: thread-safe via AtomicReference).
     */
    fun setApprovalMode(mode: ApprovalMode) {
        val oldMode = approvalMode.getAndSet(mode)
        Log.d(TAG, "Approval mode changed: $oldMode -> $mode")
    }
    
    /**
     * Get the current approval mode (M2: thread-safe read).
     */
    fun getApprovalMode(): ApprovalMode = approvalMode.get()
    
    /**
     * Set a custom risk level for a tool.
     */
    fun setRiskLevel(toolName: String, level: RiskLevel) {
        riskOverrides[toolName] = level
        Log.d(TAG, "Risk level for $toolName set to $level")
    }
    
    /**
     * Add a tool to the allow list (always allowed in SMART mode).
     */
    fun allowTool(toolName: String) {
        allowList.add(toolName)
        denyList.remove(toolName)
        Log.d(TAG, "Tool $toolName added to allow list")
    }
    
    /**
     * Add a tool to the deny list (always denied).
     */
    fun denyTool(toolName: String) {
        denyList.add(toolName)
        allowList.remove(toolName)
        Log.d(TAG, "Tool $toolName added to deny list")
    }
    
    /**
     * Remove a tool from allow/deny lists.
     */
    fun resetTool(toolName: String) {
        allowList.remove(toolName)
        denyList.remove(toolName)
        riskOverrides.remove(toolName)
    }
    
    /**
     * Reset all policy customizations.
     */
    fun reset() {
        allowList.clear()
        denyList.clear()
        riskOverrides.clear()
        approvalMode.set(ApprovalMode.SMART)
    }
}

/**
 * PolicyDecision - Result of policy evaluation.
 */
sealed interface PolicyDecision {
    /** Tool call is allowed to execute immediately */
    data object Allow : PolicyDecision
    
    /** Tool call is forbidden by policy */
    data class Deny(val reason: String) : PolicyDecision
    
    /** Tool call requires user approval */
    data class AskUser(
        val reason: String,
        val riskLevel: RiskLevel
    ) : PolicyDecision
}

