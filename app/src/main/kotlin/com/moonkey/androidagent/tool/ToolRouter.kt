package com.moonkey.androidagent.tool

import android.util.Log
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.tool.impl.AppAliases
import com.moonkey.androidagent.protocol.ApprovalDecision
import com.moonkey.androidagent.protocol.ApprovalDetails
import com.moonkey.androidagent.protocol.AppTier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ToolRouter - Executes tool calls with state machine and policy-based approval.
 * 
 * Implements the tool call lifecycle:
 * 1. VALIDATING - Validate tool exists and parameters are correct
 * 2. POLICY CHECK - Ask PolicyEngine if allowed/denied/ask-user
 * 3. AWAITING_APPROVAL - (if needed) Wait for user decision
 * 4. EXECUTING - Run the tool
 * 5. SUCCESS/ERROR/CANCELLED - Terminal state
 * 
 * Pattern from Gemini CLI's CoreToolScheduler.
 */
class ToolRouter(
    private val registry: ToolRegistry,
    private val policyEngine: PolicyEngine
) {
    
    companion object {
        private const val TAG = "ToolRouter"
        
        /** Timeout for user approval - if not responded within this time, action is cancelled */
        private const val APPROVAL_TIMEOUT_MS = 60_000L  // 60 seconds
    }
    
    // Track active tool calls for cancellation and state queries
    private val activeToolCalls = ConcurrentHashMap<String, ToolCallState>()
    
    // Pending approval handlers (call ID -> deferred decision)
    private val pendingApprovals = ConcurrentHashMap<String, CompletableDeferred<ApprovalDecision>>()
    
    /**
     * Execute a tool call with full state machine lifecycle.
     * 
     * @param toolName Name of the tool to invoke
     * @param params Parameters for the tool
     * @param context Execution context with platform access
     * @param callId Optional caller-provided ID (use LLM call_id when available)
     * @param onStateChange Callback for state changes (for UI updates)
     * @param onApprovalRequired Suspend callback when user approval is needed.
     *        The callback receives ApprovalDetails which includes the callId
     *        that must be used when resolving the approval.
     * @return The final result of the tool call
     */
    suspend fun execute(
        toolName: String,
        params: JSONObject,
        context: ToolRouterContext,
        packageName: String? = null,
        callId: String? = null,
        onStateChange: ((ToolCallState) -> Unit)? = null,
        onApprovalRequired: (suspend (ApprovalDetails) -> Unit)? = null
    ): ToolCallResult {
        val resolvedCallId = callId ?: generateCallId()
        
        Log.d(TAG, "Starting tool call: $resolvedCallId ($toolName)")
        
        // === STATE: VALIDATING ===
        var state: ToolCallState = ToolCallState.Validating(resolvedCallId, toolName, params)
        updateState(state, onStateChange)
        
        // Check tool exists
        val tool = registry.get(toolName)
        if (tool == null) {
            val errorState = ToolCallState.Error(resolvedCallId, toolName, params, "Unknown tool: $toolName")
            updateState(errorState, onStateChange)
            activeToolCalls.remove(resolvedCallId)
            return ToolCallResult.Error(resolvedCallId, "Unknown tool: $toolName")
        }
        
        // Validate parameters
        val validation = tool.validate(params)
        if (validation is ValidationResult.Invalid) {
            val errorMsg = "Validation failed: ${validation.errors.joinToString(", ")}"
            val errorState = ToolCallState.Error(resolvedCallId, toolName, params, errorMsg)
            updateState(errorState, onStateChange)
            activeToolCalls.remove(resolvedCallId)
            return ToolCallResult.Error(resolvedCallId, errorMsg)
        }
        
        // Create invocation
        val invocation = tool.createInvocation(params)
        
        // Track if approval was required (for snapshot refresh after approval wait)
        var approvalWasRequired = false
        
        // === POLICY CHECK ===
        val destinationPackage = if (toolName == "open_app") {
            resolveOpenAppDestination(params, context.platform)
        } else null
        val policyDecision = policyEngine.check(toolName, params, packageName, destinationPackage)
        Log.d(TAG, "Policy decision for $toolName: $policyDecision")
        
        when (policyDecision) {
            is PolicyDecision.Deny -> {
                val errorState = ToolCallState.Error(resolvedCallId, toolName, params, policyDecision.reason)
                updateState(errorState, onStateChange)
                activeToolCalls.remove(resolvedCallId)
                return ToolCallResult.Error(resolvedCallId, "Policy denied: ${policyDecision.reason}")
            }
            
            is PolicyDecision.AskUser -> {
                // === STATE: AWAITING_APPROVAL ===
                state = ToolCallState.AwaitingApproval(
                    callId = resolvedCallId,
                    toolName = toolName,
                    params = params,
                    invocation = invocation,
                    description = invocation.getDescription()
                )
                updateState(state, onStateChange)
                
                // Prepare approval tracking BEFORE notifying UI to avoid race with fast approvals
                val deferred = CompletableDeferred<ApprovalDecision>()
                pendingApprovals[resolvedCallId] = deferred

                // Notify that approval is required (includes callId for proper resolution)
                val approvalDetails = ApprovalDetails(
                    callId = resolvedCallId,
                    toolName = toolName,
                    args = params,
                    description = invocation.getDescription(),
                    packageName = packageName,
                    appTier = policyDecision.appTier,
                    reason = policyDecision.reason
                )
                try {
                    onApprovalRequired?.invoke(approvalDetails)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to request approval for $resolvedCallId", e)
                    pendingApprovals.remove(resolvedCallId)
                    val errorState = ToolCallState.Error(
                        resolvedCallId,
                        toolName,
                        params,
                        "Approval request failed: ${e.message}"
                    )
                    updateState(errorState, onStateChange)
                    activeToolCalls.remove(resolvedCallId)
                    return ToolCallResult.Error(resolvedCallId, "Approval request failed: ${e.message}")
                }
                
                // Wait for approval with timeout
                
                val decision = try {
                    withTimeout(APPROVAL_TIMEOUT_MS) {
                        deferred.await()
                    }
                } catch (e: TimeoutCancellationException) {
                    // Timeout: return directly with proper message (not reusing DENIED path)
                    Log.w(TAG, "Approval timeout for $resolvedCallId")
                    pendingApprovals.remove(resolvedCallId)
                    val cancelledState = ToolCallState.Cancelled(
                        resolvedCallId, toolName, params, "Approval timed out", null
                    )
                    updateState(cancelledState, onStateChange)
                    activeToolCalls.remove(resolvedCallId)
                    return ToolCallResult.Cancelled(resolvedCallId, "Approval timed out")
                } finally {
                    pendingApprovals.remove(resolvedCallId)
                }
                
                Log.d(TAG, "Approval decision for $resolvedCallId: $decision")
                
                when (decision) {
                    ApprovalDecision.DENIED -> {
                        val cancelledState = ToolCallState.Cancelled(
                            resolvedCallId, toolName, params, "User denied", decision
                        )
                        updateState(cancelledState, onStateChange)
                        activeToolCalls.remove(resolvedCallId)
                        return ToolCallResult.Cancelled(resolvedCallId, "User denied")
                    }
                    ApprovalDecision.ABORT -> {
                        val cancelledState = ToolCallState.Cancelled(
                            resolvedCallId, toolName, params, "User aborted", decision
                        )
                        updateState(cancelledState, onStateChange)
                        activeToolCalls.remove(resolvedCallId)
                        return ToolCallResult.Cancelled(resolvedCallId, "User aborted session")
                    }
                    ApprovalDecision.APPROVED -> {
                        // TOCTOU guard: re-check foreground app hasn't changed during approval wait.
                        val currentPkg = context.platform.getCurrentPackageName()
                        if (packageName != null && currentPkg != packageName) {
                            Log.w(TAG, "Foreground app changed during approval wait: $packageName -> $currentPkg")
                            val cancelledState = ToolCallState.Cancelled(
                                resolvedCallId, toolName, params,
                                "App changed during approval wait", decision
                            )
                            updateState(cancelledState, onStateChange)
                            activeToolCalls.remove(resolvedCallId)
                            return ToolCallResult.Cancelled(
                                resolvedCallId,
                                "App changed during approval wait"
                            )
                        } else if (packageName == null && currentPkg != null) {
                            // Original package was unknown — now check if landed on a BLOCKED app
                            val currentTier = policyEngine.appClassifier.classify(currentPkg)
                            if (currentTier == AppTier.BLOCKED) {
                                Log.w(TAG, "Blocked app detected after approval: $currentPkg")
                                val cancelledState = ToolCallState.Cancelled(
                                    resolvedCallId, toolName, params,
                                    "Blocked app detected after approval", decision
                                )
                                updateState(cancelledState, onStateChange)
                                activeToolCalls.remove(resolvedCallId)
                                return ToolCallResult.Cancelled(
                                    resolvedCallId,
                                    "Blocked app detected after approval"
                                )
                            }
                        }
                        // Continue to execution
                        approvalWasRequired = true
                    }
                }
            }
            
            PolicyDecision.Allow -> {
                // === STATE: SCHEDULED ===
                state = ToolCallState.Scheduled(resolvedCallId, toolName, params, invocation)
                updateState(state, onStateChange)
            }
        }
        
        // Check for cancellation before execution
        if (context.isCancelled()) {
            val cancelledState = ToolCallState.Cancelled(resolvedCallId, toolName, params, "Cancelled before execution")
            updateState(cancelledState, onStateChange)
            activeToolCalls.remove(resolvedCallId)
            return ToolCallResult.Cancelled(resolvedCallId, "Cancelled before execution")
        }
        
        // === STATE: EXECUTING ===
        state = ToolCallState.Executing(resolvedCallId, toolName, params, invocation)
        updateState(state, onStateChange)
        
        // Re-capture snapshot if approval was required (UI may have changed during wait)
        val executionSnapshot = if (approvalWasRequired) {
            Log.d(TAG, "Re-capturing snapshot after approval wait")
            val raw = context.platform.captureScreen()
            // Perception gate: mask if user navigated to BLOCKED app during approval wait
            policyEngine.appClassifier.maskIfBlocked(raw, packageName)
        } else {
            context.currentSnapshot
        }
        
        // Execute the tool (with finally block to ensure cleanup on abnormal exit - M1)
        val executionResult = try {
            val execContext = object : ToolExecutionContext {
                override val callId: String = resolvedCallId
                override val platform: AndroidPlatform = context.platform
                override val currentSnapshot: ScreenSnapshot? = executionSnapshot
                override val appClassifier: AppClassifier = policyEngine.appClassifier
                override fun isCancelled(): Boolean = context.isCancelled()
            }
            invocation.execute(execContext)
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution failed: $toolName", e)
            ToolExecutionResult.Failure(e.message ?: "Execution failed", e)
        }
        
        // === TERMINAL STATE === (cleanup in finally to handle any unexpected exceptions - M1)
        return try {
            when (executionResult) {
                is ToolExecutionResult.Success -> {
                    val successState = ToolCallState.Success(resolvedCallId, toolName, params, executionResult)
                    updateState(successState, onStateChange)
                    ToolCallResult.Success(
                        callId = resolvedCallId,
                        output = executionResult.output,
                        data = executionResult.data,
                        observation = executionResult.observation
                    )
                }
                
                is ToolExecutionResult.Failure -> {
                    val errorState = ToolCallState.Error(
                        resolvedCallId, toolName, params, executionResult.error, executionResult.exception
                    )
                    updateState(errorState, onStateChange)
                    ToolCallResult.Error(resolvedCallId, executionResult.error, executionResult.exception)
                }
                
                is ToolExecutionResult.Cancelled -> {
                    val cancelledState = ToolCallState.Cancelled(resolvedCallId, toolName, params, executionResult.reason)
                    updateState(cancelledState, onStateChange)
                    ToolCallResult.Cancelled(resolvedCallId, executionResult.reason)
                }
            }
        } finally {
            // Ensure cleanup regardless of how we exit (M1 fix)
            activeToolCalls.remove(resolvedCallId)
        }
    }
    
    /**
     * Resolve a pending approval.
     * 
     * Called when user responds to an approval request.
     * 
     * @param callId The tool call ID to resolve
     * @param decision The user's approval decision
     * @return true if approval was resolved, false if no pending approval found for callId
     */
    fun resolveApproval(callId: String, decision: ApprovalDecision): Boolean {
        val deferred = pendingApprovals[callId]
        return if (deferred != null) {
            deferred.complete(decision)
            Log.d(TAG, "Resolved approval for $callId: $decision")
            true
        } else {
            Log.w(TAG, "No pending approval found for $callId")
            false
        }
    }
    
    /**
     * Cancel a tool call.
     */
    fun cancel(callId: String) {
        // Cancel pending approval if any
        pendingApprovals[callId]?.complete(ApprovalDecision.ABORT)
        activeToolCalls.remove(callId)
        Log.d(TAG, "Cancelled tool call: $callId")
    }
    
    /**
     * Cancel all active tool calls.
     */
    fun cancelAll() {
        pendingApprovals.values.forEach { it.complete(ApprovalDecision.ABORT) }
        pendingApprovals.clear()
        activeToolCalls.clear()
        Log.d(TAG, "Cancelled all tool calls")
    }
    
    /**
     * Get the current state of a tool call.
     */
    fun getState(callId: String): ToolCallState? = activeToolCalls[callId]
    
    /**
     * Get all active (non-terminal) tool calls.
     */
    fun getActiveCallIds(): Set<String> = activeToolCalls.keys.toSet()
    
    /**
     * Check if there are any pending approvals.
     */
    fun hasPendingApprovals(): Boolean = pendingApprovals.isNotEmpty()
    
    private fun updateState(state: ToolCallState, callback: ((ToolCallState) -> Unit)?) {
        if (!state.isTerminal()) {
            activeToolCalls[state.callId] = state
        }
        callback?.invoke(state)
        Log.d(TAG, "State: ${state.callId} -> ${state::class.simpleName}")
    }
    
    private fun generateCallId(): String = UUID.randomUUID().toString().take(8)

    /**
     * Best-effort pre-flight resolution of open_app destination package.
     * Returns null if unresolved (policy falls back to current-tier-only).
     */
    private suspend fun resolveOpenAppDestination(
        params: JSONObject,
        platform: AndroidPlatform
    ): String? {
        val appName = params.optString("app_name", "").trim().lowercase()
        if (appName.isEmpty()) return null

        // 1. Well-known alias (cheap, no I/O)
        AppAliases.PACKAGE_MAP[appName]?.let { return it }

        // 2. Installed apps lookup (same data OpenAppInvocation uses)
        val apps = platform.getInstalledApps()
        apps.find { it.label.equals(appName, ignoreCase = true) }?.let { return it.packageName }
        apps.find { it.label.contains(appName, ignoreCase = true) }?.let { return it.packageName }

        return null
    }
}

/**
 * Context provided to ToolRouter for execution.
 */
interface ToolRouterContext {
    val platform: AndroidPlatform
    val currentSnapshot: ScreenSnapshot?
    fun isCancelled(): Boolean
}

/**
 * Simple implementation of ToolRouterContext.
 */
class SimpleToolRouterContext(
    override val platform: AndroidPlatform,
    override val currentSnapshot: ScreenSnapshot? = null,
    private val cancellationFlag: AtomicBoolean = AtomicBoolean(false)
) : ToolRouterContext {
    override fun isCancelled(): Boolean = cancellationFlag.get()
    
    fun cancel() {
        cancellationFlag.set(true)
    }
}
