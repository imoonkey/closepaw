package com.moonkey.androidagent.tool

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
    // Use AtomicReference for thread-safe approval mode changes
    private val approvalMode = AtomicReference(initialApprovalMode)
    private val lock = Any()
    
    companion object {
        private const val TAG = "PolicyEngine"
        
        // TODO (M8): Consider loading risk levels from configuration file for per-deployment customization.
        // Default risk levels for core tools (mobile actions are resolved per action).
        private val DEFAULT_RISK_LEVELS = mapOf(
            ToolName.MobileAction.canonical to RiskLevel.MEDIUM,
            ToolName.SystemButton.canonical to RiskLevel.MEDIUM,
            ToolName.Wait.canonical to RiskLevel.LOW,
            ToolName.AppControl.canonical to RiskLevel.MEDIUM,
            ToolName.CompleteTask.canonical to RiskLevel.MEDIUM,
            ToolName.WriteTodos.canonical to RiskLevel.LOW,
            ToolName.Scratchpad.canonical to RiskLevel.LOW,
            ToolName.DelegateTask.canonical to RiskLevel.MEDIUM
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
        val canonicalName = ToolName.from(toolName).canonical
        Log.d(TAG, "Checking policy for: $toolName (mode: $currentMode)")

        return synchronized(lock) {
            // Check deny list first
            if (canonicalName in denyList) {
                Log.d(TAG, "Tool $toolName is in deny list")
                return@synchronized PolicyDecision.Deny("Tool '$toolName' is forbidden by policy")
            }

            // Check allow list (overrides everything except deny list)
            if (canonicalName in allowList) {
                Log.d(TAG, "Tool $toolName is in allow list")
                return@synchronized PolicyDecision.Allow
            }

            // Apply approval mode (M2: read atomic value)
            return@synchronized when (currentMode) {
                ApprovalMode.ALWAYS_ASK -> {
                    PolicyDecision.AskUser(
                        reason = "Approval required for all actions",
                        riskLevel = getRiskLevelLocked(toolName, params)
                    )
                }

                ApprovalMode.AUTO_APPROVE -> {
                    PolicyDecision.Allow
                }

                ApprovalMode.SMART -> {
                    evaluateRiskLocked(toolName, params)
                }
            }
        }
    }
    
    /**
     * Evaluate risk-based policy for SMART mode.
     */
    private fun evaluateRiskLocked(toolName: String, params: JSONObject): PolicyDecision {
        val riskLevel = getRiskLevelLocked(toolName, params)
        
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
        return synchronized(lock) {
            getRiskLevelLocked(toolName)
        }
    }

    private fun getRiskLevelLocked(toolName: String, params: JSONObject = JSONObject()): RiskLevel {
        val action = resolveActionName(toolName, params)
        val riskKey = action?.canonical ?: ToolName.from(toolName).canonical

        // Check custom overrides first
        riskOverrides[riskKey]?.let { return it }

        // Use default risk levels
        return action?.defaultRiskLevel ?: DEFAULT_RISK_LEVELS[riskKey] ?: RiskLevel.MEDIUM
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
        val riskKey = resolveRiskKey(toolName)
        synchronized(lock) {
            riskOverrides[riskKey] = level
        }
        Log.d(TAG, "Risk level for $toolName set to $level")
    }
    
    /**
     * Add a tool to the allow list (always allowed in SMART mode).
     */
    fun allowTool(toolName: String) {
        val canonicalName = ToolName.from(toolName).canonical
        synchronized(lock) {
            allowList.add(canonicalName)
            denyList.remove(canonicalName)
        }
        Log.d(TAG, "Tool $toolName added to allow list")
    }
    
    /**
     * Add a tool to the deny list (always denied).
     */
    fun denyTool(toolName: String) {
        val canonicalName = ToolName.from(toolName).canonical
        synchronized(lock) {
            denyList.add(canonicalName)
            allowList.remove(canonicalName)
        }
        Log.d(TAG, "Tool $toolName added to deny list")
    }
    
    /**
     * Remove a tool from allow/deny lists.
     */
    fun resetTool(toolName: String) {
        val canonicalName = ToolName.from(toolName).canonical
        synchronized(lock) {
            allowList.remove(canonicalName)
            denyList.remove(canonicalName)
            riskOverrides.remove(canonicalName)
        }
    }
    
    /**
     * Reset all policy customizations.
     */
    fun reset() {
        synchronized(lock) {
            allowList.clear()
            denyList.clear()
            riskOverrides.clear()
        }
        approvalMode.set(ApprovalMode.SMART)
    }
}

private fun resolveRiskKey(toolName: String): String {
    val action = MobileActionName.fromOrNull(toolName)
    return action?.canonical ?: ToolName.from(toolName).canonical
}

private fun resolveActionName(toolName: String, params: JSONObject): MobileActionName? {
    MobileActionName.fromOrNull(toolName)?.let { return it }
    return if (ToolName.from(toolName) == ToolName.MobileAction) {
        MobileActionName.fromOrNull(params.optString("action"))
    } else null
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
