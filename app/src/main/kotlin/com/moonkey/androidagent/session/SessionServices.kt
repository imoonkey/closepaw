package com.moonkey.androidagent.session

import android.content.Context
import android.util.Log
import com.moonkey.androidagent.llm.LLMClient
import com.moonkey.androidagent.llm.OpenAILLMClient
import com.moonkey.androidagent.llm.LFMLLMClient
import com.moonkey.androidagent.llm.LocalLLMConfig
import com.moonkey.androidagent.history.HistoryConfig
import com.moonkey.androidagent.history.HistoryManager
import com.moonkey.androidagent.history.TruncationPolicy
import com.moonkey.androidagent.tool.PolicyEngine
import com.moonkey.androidagent.tool.ToolRegistry
import com.moonkey.androidagent.tool.ToolRouter
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.tool.impl.AppControlTool
import com.moonkey.androidagent.tool.impl.CompleteTaskTool
import com.moonkey.androidagent.tool.impl.MobileActionTool

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
 * - Added llmClient as instance-based service (not singleton)
 * 
 * Usage:
 * ```kotlin
 * // For OpenAI backend:
 * val services = SessionServices.create(config, platform, apiKey = "sk-...")
 * 
 * // For local LLM backend:
 * val localConfig = config.copy(llmBackend = LLMBackendType.LOCAL)
 * val services = SessionServices.create(localConfig, platform, context = context)
 * ```
 */
data class SessionServices(
    val toolRegistry: ToolRegistry,
    val toolRouter: ToolRouter,
    val historyManager: HistoryManager,
    val policyEngine: PolicyEngine,
    val platform: AndroidPlatform,
    val config: SessionConfig,
    val llmClient: LLMClient
) {
    companion object {
        private const val TAG = "SessionServices"
        
        /**
         * Create a new SessionServices container with all services initialized.
         * 
         * Services are created in dependency order:
         * 1. LLMClient (depends on config.llmBackend, apiKey, context)
         * 2. PolicyEngine (no dependencies)
         * 3. ToolRegistry (no dependencies)
         * 4. ToolRouter (depends on ToolRegistry, PolicyEngine)
         * 5. HistoryManager (depends on config)
         * 
         * @param config Session configuration
         * @param platform Android platform abstraction
         * @param apiKey OpenAI API key for LLM client (required for OPENAI backend, optional for LOCAL)
         * @param context Android context (required for LOCAL backend for model downloading)
         * @return Fully initialized SessionServices
         */
        fun create(
            config: SessionConfig,
            platform: AndroidPlatform,
            apiKey: String? = null,
            context: Context? = null
        ): SessionServices {
            Log.d(TAG, "Creating SessionServices with backend: ${config.llmBackend}...")
            
            // 1. Create LLMClient based on backend type
            val llmClient: LLMClient = when (config.llmBackend) {
                LLMBackendType.OPENAI -> {
                    requireNotNull(apiKey) { "API key is required for OpenAI backend" }
                    OpenAILLMClient(apiKey)
                }
                LLMBackendType.LOCAL -> {
                    requireNotNull(context) { "Context is required for local LLM backend" }
                    val localConfig = config.localLLMConfig?.let {
                        LocalLLMConfig(
                            modelSlug = it.modelSlug,
                            quantizationSlug = it.quantizationSlug
                        )
                    } ?: LocalLLMConfig()
                    LFMLLMClient(context, localConfig)
                }
            }
            Log.d(TAG, "Created LLMClient: ${llmClient.javaClass.simpleName}")
            
            // 2. Create PolicyEngine with approval mode from config
            val policyEngine = PolicyEngine(config.approvalMode)
            Log.d(TAG, "Created PolicyEngine with mode: ${config.approvalMode}")
            
            // 3. Create and populate ToolRegistry with built-in tools
            val toolRegistry = ToolRegistry().apply {
                registerBuiltInTools()
            }
            Log.d(TAG, "Created ToolRegistry with ${toolRegistry.size()} tools")
            
            // 4. Create ToolRouter (depends on registry and policy engine)
            val toolRouter = ToolRouter(toolRegistry, policyEngine)
            Log.d(TAG, "Created ToolRouter")
            
            // 5. Create HistoryManager with config-based settings
            // Use AGGRESSIVE truncation to keep screen observations smaller
            val historyConfig = HistoryConfig(
                defaultTruncationPolicy = TruncationPolicy.AGGRESSIVE, // 2000 tokens vs 8000
                autoCompress = true,
                maxTokenBudget = 18_000 // Leave headroom for tools (~700), screen (~5-10K), and response
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
                config = config,
                llmClient = llmClient
            )
        }
        
        /**
         * Register all built-in tools in the registry.
         * 
         * Uses the consolidated tool pattern from pragmatic_tool_design.md:
         * - complete_task: Agent metatool for finishing tasks
         * - mobile_action: All UI interactions (click, type, swipe, system_button, wait)
         * - app_control: App discovery and launching (list_apps, open_app)
         */
        private fun ToolRegistry.registerBuiltInTools() {
            // P0: Agent metatool
            register(CompleteTaskTool())
            
            // P0: Consolidated UI interactions (replaces click, type, swipe, scroll, back, home, wait)
            register(MobileActionTool())
            
            // P0: App control (list_apps, open_app)
            register(AppControlTool())
            
            Log.d(TAG, "Registered ${size()} built-in tools: ${getNames().joinToString()}")
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
    suspend fun cleanup() {
        Log.d(TAG, "Cleaning up SessionServices...")
        
        // Cancel any pending tool calls
        toolRouter.cancelAll()
        
        // Clear history
        historyManager.clear()

        // Release LLM resources (especially important for local models)
        llmClient.cleanup()
        
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
     * @param apiKey OpenAI API key (required for OPENAI backend)
     * @param context Android context (required for LOCAL backend)
     * @param additionalTools Additional tools to register
     * @param excludeTools Tools to exclude from registration
     */
    fun createWithCustomTools(
        config: SessionConfig,
        platform: AndroidPlatform,
        apiKey: String? = null,
        context: Context? = null,
        additionalTools: List<com.moonkey.androidagent.tool.ToolSpec> = emptyList(),
        excludeTools: Set<String> = emptySet()
    ): SessionServices {
        val services = SessionServices.create(config, platform, apiKey, context)
        
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
