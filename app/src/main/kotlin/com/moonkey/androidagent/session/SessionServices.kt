package com.moonkey.androidagent.session

import android.content.Context
import android.util.Log
import com.moonkey.androidagent.history.HistoryConfig
import com.moonkey.androidagent.history.HistoryManager
import com.moonkey.androidagent.history.SessionRecordingService
import com.moonkey.androidagent.history.TruncationPolicy
import com.moonkey.androidagent.history.storage.SessionStorage
import com.moonkey.androidagent.llm.LFMLLMClient
import com.moonkey.androidagent.llm.LLMClient
import com.moonkey.androidagent.llm.LLMClientFactory
import com.moonkey.androidagent.llm.LocalLLMConfig
import com.moonkey.androidagent.llm.ModelCatalog
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.protocol.AgentMode
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.protocol.SessionConfig
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
 * val localConfig = config.copy(llmBackend = LLMBackendType.LOCAL)
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
        @Suppress("DEPRECATION")
        fun create(
                config: SessionConfig,
                platform: AndroidPlatform,
                apiKeys: Map<String, String> = emptyMap(),
                context: Context,
                scope: CoroutineScope,
                traceRecorder: TraceRecorder
        ): SessionServices {
            Log.d(TAG, "Creating SessionServices with backend: ${config.llmBackend}...")
            Log.d(TAG, "API keys available for providers: ${apiKeys.keys}")

            val modelCatalog = loadModelCatalog(context)
            Log.d(
                    TAG,
                    "Loaded ModelCatalog with ${modelCatalog.size} models: ${modelCatalog.names()}"
            )

            val llmClientFactory =
                    LLMClientFactory(
                            catalog = modelCatalog,
                            apiKeyResolver = { envVar -> apiKeys[envVar] }
                    )
            Log.d(TAG, "Created LLMClientFactory")

            val llmClient: LLMClient =
                    when (config.llmBackend) {
                        LLMBackendType.OPENAI -> {
                            ensureRequiredCloudKeys(config, modelCatalog, apiKeys)
                            llmClientFactory.create(config.mainModel)
                        }
                        LLMBackendType.LOCAL -> {
                            requireNotNull(context) { "Context is required for local LLM backend" }
                            val localConfig = config.localLLMConfig ?: LocalLLMConfig()
                            LFMLLMClient(context, localConfig)
                        }
                    }
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
         * Load ModelCatalog from assets/llm_models.json. Falls back to a minimal single-model
         * catalog if context is unavailable or the asset is missing.
         *
         * Note: Performs blocking I/O on the calling thread. Callers must ensure this runs off the
         * main thread (e.g. `Dispatchers.IO`). Asset reads are typically sub-millisecond, but the
         * guarantee matters.
         */
        private fun loadModelCatalog(context: Context?): ModelCatalog {
            if (context == null) {
                Log.w(TAG, "No context available; using fallback single-model catalog")
                return ModelCatalog.fromJson(FALLBACK_CATALOG_JSON)
            }
            return try {
                val json =
                        context.assets.open("llm_models.json").bufferedReader().use {
                            it.readText()
                        }
                ModelCatalog.fromJson(json)
            } catch (e: java.io.IOException) {
                Log.w(TAG, "Failed to read llm_models.json from assets; using fallback", e)
                ModelCatalog.fromJson(FALLBACK_CATALOG_JSON)
            } catch (e: kotlinx.serialization.SerializationException) {
                Log.w(TAG, "Failed to parse llm_models.json; using fallback", e)
                ModelCatalog.fromJson(FALLBACK_CATALOG_JSON)
            }
        }

        private const val FALLBACK_CATALOG_JSON =
                """
        {
          "gpt-5.2": {
            "display_name": "GPT-5.2",
            "provider": "OPENAI",
            "api": "response",
            "model_id": "gpt-5.2"
          }
        }
        """

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

        private fun ensureRequiredCloudKeys(
                config: SessionConfig,
                catalog: ModelCatalog,
                apiKeys: Map<String, String>
        ) {
            val requiredModels = linkedSetOf(config.mainModel)
            if (config.agentMode == AgentMode.PRO) {
                config.executorModel?.let(requiredModels::add)
            }

            requiredModels.forEach { modelName ->
                val entry = catalog.resolve(modelName)
                val requiredEnv = entry.effectiveApiKeyEnv
                if (apiKeys[requiredEnv].isNullOrBlank()) {
                    throw IllegalStateException(
                            "Missing API key '$requiredEnv' for model '$modelName' " +
                                    "(provider=${entry.provider}, api=${entry.api})."
                    )
                }
            }
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
