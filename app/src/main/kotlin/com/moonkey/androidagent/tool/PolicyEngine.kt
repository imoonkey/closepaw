package com.moonkey.androidagent.tool

import android.util.Log
import com.moonkey.androidagent.protocol.AppTier
import com.moonkey.androidagent.protocol.ApprovalMode
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * PolicyEngine — Decides whether tool calls should be allowed, denied, or require approval.
 *
 * Decision logic is based on AppTier (where), not action type (what).
 * BLOCKED apps are denied even in AUTO_APPROVE mode.
 */
class PolicyEngine(
    initialApprovalMode: ApprovalMode = ApprovalMode.SMART,
    val appClassifier: AppClassifier,
    initialPersistentAllowList: Set<String> = emptySet(),
    private val onPersistentAllowListChanged: ((Set<String>) -> Unit)? = null
) {
    private val approvalMode = AtomicReference(initialApprovalMode)

    /** Session-scoped allow-list — cleared on reset(). */
    private val sessionAllowedPackages: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Persistent allow-list — survives reset(), written to SharedPreferences via callback. */
    private val persistentAllowedPackages: MutableSet<String> =
        ConcurrentHashMap.newKeySet<String>().also { it.addAll(initialPersistentAllowList) }

    companion object {
        private const val TAG = "PolicyEngine"
    }

    /**
     * Check if a tool call should be allowed, denied, or requires approval.
     *
     * @param toolName The name of the tool
     * @param params The parameters for the tool call
     * @param packageName Current foreground app package name
     * @return PolicyDecision indicating how to proceed
     */
    fun check(toolName: String, params: JSONObject = JSONObject(), packageName: String? = null): PolicyDecision {
        val currentMode = approvalMode.get()
        val tier = appClassifier.classify(packageName)
        Log.d(TAG, "Policy check: tool=$toolName, pkg=$packageName, tier=$tier, mode=$currentMode")

        // Non-screen-changing tools → always allow
        if (!ToolName.from(toolName).isScreenChanging) return PolicyDecision.Allow

        // Escape actions (back/home) → always allow (agent must not be trapped)
        if (isEscape(toolName, params)) return PolicyDecision.Allow

        // BLOCKED is absolute floor — even AUTO_APPROVE cannot bypass
        if (tier == AppTier.BLOCKED) {
            return PolicyDecision.Deny("Blocked: financial/auth app ($packageName)")
        }

        // User-granted allow-list — but NOT in ALWAYS_ASK mode
        if (currentMode != ApprovalMode.ALWAYS_ASK && isUserAllowed(packageName)) {
            return PolicyDecision.Allow
        }

        // Apply approval mode
        return when (currentMode) {
            ApprovalMode.ALWAYS_ASK -> PolicyDecision.AskUser(
                reason = "User requested approval for all actions",
                appTier = tier
            )
            ApprovalMode.AUTO_APPROVE -> PolicyDecision.Allow
            ApprovalMode.SMART -> when (tier) {
                AppTier.CAUTIOUS -> PolicyDecision.AskUser(
                    reason = "Unknown app — action requires approval",
                    appTier = tier
                )
                AppTier.NORMAL -> PolicyDecision.Allow
                AppTier.BLOCKED -> PolicyDecision.Deny("unreachable")  // handled above
            }
        }
    }

    fun setApprovalMode(mode: ApprovalMode) {
        val oldMode = approvalMode.getAndSet(mode)
        Log.d(TAG, "Approval mode changed: $oldMode -> $mode")
    }

    fun getApprovalMode(): ApprovalMode = approvalMode.get()

    fun reset() {
        approvalMode.set(ApprovalMode.SMART)
        sessionAllowedPackages.clear()
        // persistent list NOT cleared on reset — survives across sessions
    }

    // ===== Allow-list mutations =====

    fun allowPackageForSession(packageName: String) {
        if (!isValidPackageName(packageName)) {
            Log.w(TAG, "Ignoring invalid package for session allow-list: $packageName")
            return
        }
        sessionAllowedPackages.add(packageName)
        Log.d(TAG, "Session allow-list: +$packageName")
    }

    fun allowPackagePersistent(packageName: String) {
        if (!isValidPackageName(packageName)) {
            Log.w(TAG, "Ignoring invalid package for persistent allow-list: $packageName")
            return
        }
        persistentAllowedPackages.add(packageName)
        sessionAllowedPackages.add(packageName) // also effective this session
        onPersistentAllowListChanged?.invoke(persistentAllowedPackages.toSet())
        Log.d(TAG, "Persistent allow-list: +$packageName")
    }

    private fun isValidPackageName(name: String): Boolean =
        name.isNotBlank() && '.' in name

    private fun isUserAllowed(packageName: String?): Boolean =
        packageName != null && (
            packageName in sessionAllowedPackages ||
            packageName in persistentAllowedPackages
        )

    private fun isEscape(toolName: String, params: JSONObject): Boolean {
        val tool = ToolName.from(toolName)
        // system_button(button="back"|"home")
        if (tool == ToolName.SystemButton) {
            val button = params.optString("button", "").lowercase()
            return button == "back" || button == "home"
        }
        return false
    }
}

/**
 * PolicyDecision — Result of policy evaluation.
 */
sealed interface PolicyDecision {
    /** Tool call is allowed to execute immediately */
    data object Allow : PolicyDecision

    /** Tool call is forbidden by policy */
    data class Deny(val reason: String) : PolicyDecision

    /** Tool call requires user approval */
    data class AskUser(
        val reason: String,
        val appTier: AppTier? = null
    ) : PolicyDecision
}
