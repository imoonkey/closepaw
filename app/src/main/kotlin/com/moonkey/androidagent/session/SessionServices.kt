package com.moonkey.androidagent.session

import android.content.Context
import android.util.Log
import com.moonkey.androidagent.history.HistoryConfig
import com.moonkey.androidagent.history.HistoryManager
import com.moonkey.androidagent.history.SessionRecordingService
import com.moonkey.androidagent.history.TruncationPolicy
import com.moonkey.androidagent.history.storage.SessionStorage
import com.moonkey.androidagent.llm.LLMClient
import com.moonkey.androidagent.llm.LLMClientFactory
import com.moonkey.androidagent.llm.ModelCatalog
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.protocol.SessionLlmConfig
import com.moonkey.androidagent.tool.PolicyEngine
import com.moonkey.androidagent.tool.ToolRegistry
import com.moonkey.androidagent.tool.ToolRouter
import com.moonkey.androidagent.tool.impl.AskUserTool
import com.moonkey.androidagent.tool.impl.CompleteTaskTool
import com.moonkey.androidagent.tool.impl.MobileActionTool
import com.moonkey.androidagent.tool.impl.OpenAppTool
import com.moonkey.androidagent.tool.impl.ScratchpadTool
import com.moonkey.androidagent.tool.impl.SystemButtonTool
import com.moonkey.androidagent.tool.impl.WaitTool
import com.moonkey.androidagent.tool.impl.WriteTodosTool
import com.moonkey.androidagent.trace.TraceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * SessionServices - Dependency Injection container for all session-scoped services.
 *
 * Pattern from Codex's SessionServices: A single object holding all services needed for a session.
 *
 * Each service has ONE clear responsibility:
 * - toolRegistry: Discovery and schema generation for tools
 * - toolRouter: Execution of tools with state machine (includes approval flow)
 * - historyManager: Conversation history with truncation/normalization
 * - sessionState: Planning state (todos + scratchpad)
 * - policyEngine: Decides ALLOW/DENY/ASK_USER for tool calls
 * - platform: Android-specific operations
 * - config: Session configuration
 *
 * Usage:
 * ```kotlin
 * // For OpenAI backend:
 * val services = SessionServices.create(config, platform, apiKeys = mapOf("OPENAI_API_KEY" to "sk-..."))
 *
 * // For local LLM backend:
 * val localConfig = config.copy(llm = SessionLlmConfig(backendType = LLMBackendType.LOCAL))
 * val services = SessionServices.create(localConfig, platform, context = context)
 * ```
 */
data class SessionServices(
        val toolRegistry: ToolRegistry,
        val toolRouter: ToolRouter,
        val historyManager: HistoryManager,
        val sessionState: AgentSessionState,
        val policyEngine: PolicyEngine,
        val platform: AndroidPlatform,
        val config: SessionConfig,
        val llmClient: LLMClient,
        val modelCatalog: ModelCatalog,
        val llmClientFactory: LLMClientFactory,
        val traceRecorder: TraceRecorder,
        val recordingService: SessionRecordingService,
        val userResponseChannel: UserResponseChannel = UserResponseChannel()
) {
    companion object {
        private const val TAG = "SessionServices"

        /**
         * Create a new SessionServices container with all services initialized.
         *
         * @param config Session configuration
         * @param platform Android platform abstraction
         * @param apiKeys Per-provider API keys, keyed by env var name
         * ```
         *               (e.g. "OPENAI_API_KEY" → "sk-...", "OPENROUTER_API_KEY" → "sk-or-...")
         * @param context
         * ```
         * Android context (required for LOCAL backend for model downloading)
         * @return Fully initialized SessionServices
         */
        fun create(
                config: SessionConfig,
                platform: AndroidPlatform,
                apiKeys: Map<String, String> = emptyMap(),
                context: Context,
                scope: CoroutineScope,
                traceRecorder: TraceRecorder
        ): SessionServices {
            Log.d(TAG, "Creating SessionServices...")
            Log.d(TAG, "API keys available for providers: ${apiKeys.keys}")

            val llmBootstrap = SessionLlmBootstrapper.create(config, context, apiKeys)
            val modelCatalog = llmBootstrap.modelCatalog
            val llmClientFactory = llmBootstrap.llmClientFactory
            val llmClient: LLMClient = llmBootstrap.llmClient
            Log.d(TAG, "Created LLMClient: ${llmClient.javaClass.simpleName}")

            val policyEngine = PolicyEngine(config.approvalMode)
            Log.d(TAG, "Created PolicyEngine with mode: ${config.approvalMode}")

            val sessionState = AgentSessionState()
            val toolRegistry = ToolRegistry().apply { registerBuiltInTools(sessionState) }
            Log.d(TAG, "Created ToolRegistry with ${toolRegistry.size()} tools")

            val toolRouter = ToolRouter(toolRegistry, policyEngine)
            Log.d(TAG, "Created ToolRouter")

            val historyConfig =
                    HistoryConfig(
                            defaultTruncationPolicy =
                                    TruncationPolicy.AGGRESSIVE, // 2000 tokens vs 8000
                            autoCompress = true,
                            maxTokenBudget =
                                    18_000 // Leave headroom for tools (~700), screen (~5-10K), and
                            // response
                            )
            val historyManager = HistoryManager(historyConfig)
            Log.d(TAG, "Created HistoryManager")

            val storage = SessionStorage(context, Dispatchers.IO)
            val recordingService = SessionRecordingService(storage, scope)
            Log.d(TAG, "Created SessionRecordingService")

            Log.i(TAG, "SessionServices created successfully")

            return SessionServices(
                    toolRegistry = toolRegistry,
                    toolRouter = toolRouter,
                    historyManager = historyManager,
                    sessionState = sessionState,
                    policyEngine = policyEngine,
                    platform = platform,
                    config = config,
                    llmClient = llmClient,
                    modelCatalog = modelCatalog,
                    llmClientFactory = llmClientFactory,
                    traceRecorder = traceRecorder,
                    recordingService = recordingService
            )
        }
        /**
         * Registers built-in tools: complete_task, mobile_action, system_button, wait, open_app,
         * write_todos, scratchpad.
         */
        private fun ToolRegistry.registerBuiltInTools(sessionState: AgentSessionState) {
            register(CompleteTaskTool())
            register(MobileActionTool())
            register(SystemButtonTool())
            register(WaitTool())
            register(OpenAppTool())
            register(WriteTodosTool(sessionState.todos))
            register(ScratchpadTool(sessionState.scratchpad))

            Log.d(TAG, "Registered ${size()} built-in tools: ${getNames().joinToString()}")
        }
    }

    /** Update the approval mode at runtime. */
    fun updateApprovalMode(mode: com.moonkey.androidagent.protocol.ApprovalMode) {
        policyEngine.setApprovalMode(mode)
        Log.d(TAG, "Updated approval mode to: $mode")
    }

    /** Get a summary of all services for debugging. */
    fun getSummary(): String {
        return buildString {
            appendLine("=== SessionServices Summary ===")
            appendLine()
            appendLine("Config:")
            appendLine("  Main Model: ${config.mainModel}")
            config.executorModel?.let { appendLine("  Executor Model: $it") }
            appendLine("  Approval Mode: ${config.approvalMode}")
            appendLine("  Max Turns: ${config.maxTurns}")
            appendLine("  Action Delay: ${config.actionDelayMs}ms")
            appendLine("  Debug Mode: ${config.debugMode}")
            appendLine()
            appendLine("Tools (${toolRegistry.size()}):")
            toolRegistry.getNames().forEach { name -> appendLine("  - $name") }
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

        // Cancel any pending ask_user request
        userResponseChannel.cancel()

        // Clear history
        historyManager.clear()

        // Release platform resources (VirtualDisplayPlatform releases display here).
        try {
            platform.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Platform stop failed (non-fatal)", e)
        }

        // Release LLM resources (especially important for local models)
        llmClient.cleanup()
        llmClientFactory.cleanupAll()

        // Flush/close trace last so we still capture teardown artifacts if needed
        traceRecorder.close()

        Log.i(TAG, "SessionServices cleaned up")
    }
}

/** Extension for creating SessionServices with additional options. */
object SessionServicesBuilder {

    /** Create SessionServices with custom tool configuration. */
    fun createWithCustomTools(
            config: SessionConfig,
            platform: AndroidPlatform,
            apiKeys: Map<String, String> = emptyMap(),
            context: Context,
            scope: CoroutineScope,
            traceRecorder: TraceRecorder,
            additionalTools: List<com.moonkey.androidagent.tool.ToolSpec> = emptyList(),
            excludeTools: Set<String> = emptySet()
    ): SessionServices {
        val services =
                SessionServices.create(config, platform, apiKeys, context, scope, traceRecorder)

        // Remove excluded tools
        excludeTools.forEach { name -> services.toolRegistry.unregister(name) }

        // Add additional tools
        additionalTools.forEach { tool -> services.toolRegistry.register(tool) }

        return services
    }
}
