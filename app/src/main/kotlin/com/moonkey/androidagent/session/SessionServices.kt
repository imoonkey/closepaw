package com.moonkey.androidagent.session

import android.util.Log
import com.moonkey.androidagent.infra.history.HistoryConfig
import com.moonkey.androidagent.infra.history.HistoryManager
import com.moonkey.androidagent.infra.policy.PolicyEngine
import com.moonkey.androidagent.infra.registry.ToolRegistry
import com.moonkey.androidagent.infra.tools.ToolRouter
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.tools.impl.BackTool
import com.moonkey.androidagent.tools.impl.ClickTool
import com.moonkey.androidagent.tools.impl.HomeTool
import com.moonkey.androidagent.tools.impl.ScrollTool
import com.moonkey.androidagent.tools.impl.SwipeTool
import com.moonkey.androidagent.tools.impl.TypeTool
import com.moonkey.androidagent.tools.impl.WaitTool

/**
 * SessionServices - Dependency Injection container for all session-scoped services.
 * 
 * Pattern from Codex's SessionServices: A single object holding all services needed for a session.
 * 
 * Each service has ONE clear responsibility:
 * - toolRegistry: Discovery and schema generation for tools
 * - toolRouter: Execution of tools with state machine (includes approval flow)
 * - historyManager: Conversation history with truncation/normalization
 * - policyEngine: Decides ALLOW/DENY/ASK_USER for tool calls
 * - platform: Android-specific operations
 * - config: Session configuration
 * 
 * V2 Changes:
 * - Removed agentRegistry (no longer needed without multi-agent orchestration)
 * 
 * Usage:
 * ```kotlin
 * val services = SessionServices.create(config, platform)
 * // All services are now available via services.toolRegistry, services.historyManager, etc.
 * ```
 */
data class SessionServices(
    val toolRegistry: ToolRegistry,
    val toolRouter: ToolRouter,
    val historyManager: HistoryManager,
    val policyEngine: PolicyEngine,
    val platform: AndroidPlatform,
    val config: SessionConfig
) {
    companion object {
        private const val TAG = "SessionServices"
        
        /**
         * Create a new SessionServices container with all services initialized.
         * 
         * Services are created in dependency order:
         * 1. PolicyEngine (no dependencies)
         * 2. ToolRegistry (no dependencies)
         * 3. ToolRouter (depends on ToolRegistry, PolicyEngine)
         * 4. HistoryManager (depends on config)
         * 
         * @param config Session configuration
         * @param platform Android platform abstraction
         * @return Fully initialized SessionServices
         */
        suspend fun create(
            config: SessionConfig,
            platform: AndroidPlatform
        ): SessionServices {
            Log.d(TAG, "Creating SessionServices...")
            
            // 1. Create PolicyEngine with approval mode from config
            val policyEngine = PolicyEngine(config.approvalMode)
            Log.d(TAG, "Created PolicyEngine with mode: ${config.approvalMode}")
            
            // 2. Create and populate ToolRegistry with built-in tools
            val toolRegistry = ToolRegistry().apply {
                registerBuiltInTools()
            }
            Log.d(TAG, "Created ToolRegistry with ${toolRegistry.size()} tools")
            
            // 3. Create ToolRouter (depends on registry and policy engine)
            val toolRouter = ToolRouter(toolRegistry, policyEngine)
            Log.d(TAG, "Created ToolRouter")
            
            // 4. Create HistoryManager with config-based settings
            val historyConfig = HistoryConfig(
                autoCompress = true,
                maxTokenBudget = 100_000 // Could be configurable in SessionConfig
            )
            val historyManager = HistoryManager(historyConfig)
            Log.d(TAG, "Created HistoryManager")
            
            Log.i(TAG, "SessionServices created successfully")
            
            return SessionServices(
                toolRegistry = toolRegistry,
                toolRouter = toolRouter,
                historyManager = historyManager,
                policyEngine = policyEngine,
                platform = platform,
                config = config
            )
        }
        
        /**
         * Register all built-in tools in the registry.
         */
        private fun ToolRegistry.registerBuiltInTools() {
            // Core Mobile-Agent tools
            register(ClickTool())
            register(TypeTool())
            register(ScrollTool())
            register(SwipeTool())
            register(BackTool())
            register(HomeTool())
            register(WaitTool())
            
            Log.d(TAG, "Registered ${size()} built-in tools")
        }
    }
    
    /**
     * Update the approval mode at runtime.
     */
    fun updateApprovalMode(mode: com.moonkey.androidagent.protocol.ApprovalMode) {
        policyEngine.setApprovalMode(mode)
        Log.d(TAG, "Updated approval mode to: $mode")
    }
    
    /**
     * Get a summary of all services for debugging.
     */
    fun getSummary(): String {
        return buildString {
            appendLine("=== SessionServices Summary ===")
            appendLine()
            appendLine("Config:")
            appendLine("  Model: ${config.model}")
            appendLine("  Approval Mode: ${config.approvalMode}")
            appendLine("  Max Turns: ${config.maxTurns}")
            appendLine("  Action Delay: ${config.actionDelayMs}ms")
            appendLine("  Debug Mode: ${config.debugMode}")
            appendLine()
            appendLine("Tools (${toolRegistry.size()}):")
            toolRegistry.getNames().forEach { name ->
                appendLine("  - $name")
            }
            appendLine()
            appendLine("History:")
            appendLine("  Items: ${historyManager.size()}")
            appendLine("  Tokens: ~${historyManager.estimateTokenCount()}")
            appendLine()
            appendLine("Platform:")
            appendLine("  Permissions OK: ${platform.hasRequiredPermissions()}")
            appendLine("  Current Package: ${platform.getCurrentPackageName() ?: "unknown"}")
        }
    }
    
    /**
     * Cleanup all services.
     * 
     * Should be called when the session is ending.
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up SessionServices...")
        
        // Cancel any pending tool calls
        toolRouter.cancelAll()
        
        // Clear history
        historyManager.clear()
        
        Log.i(TAG, "SessionServices cleaned up")
    }
}

/**
 * Extension for creating SessionServices with additional options.
 */
object SessionServicesBuilder {
    
    /**
     * Create SessionServices with custom tool configuration.
     * 
     * @param config Session configuration
     * @param platform Android platform
     * @param additionalTools Additional tools to register
     * @param excludeTools Tools to exclude from registration
     */
    suspend fun createWithCustomTools(
        config: SessionConfig,
        platform: AndroidPlatform,
        additionalTools: List<com.moonkey.androidagent.infra.tools.ToolSpec> = emptyList(),
        excludeTools: Set<String> = emptySet()
    ): SessionServices {
        val services = SessionServices.create(config, platform)
        
        // Remove excluded tools
        excludeTools.forEach { name ->
            services.toolRegistry.unregister(name)
        }
        
        // Add additional tools
        additionalTools.forEach { tool ->
            services.toolRegistry.register(tool)
        }
        
        return services
    }
}
