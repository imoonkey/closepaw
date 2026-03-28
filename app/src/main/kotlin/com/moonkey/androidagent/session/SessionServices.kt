package com.moonkey.androidagent.session

import android.content.Context
import android.util.Log
import com.moonkey.androidagent.app.AppSettingsStore
import com.moonkey.androidagent.agent.cognition.prompt.AppSkillRepository
import com.moonkey.androidagent.agent.cognition.prompt.AssetAppSkillRepository
import com.moonkey.androidagent.agent.cognition.prompt.EmptyAppSkillRepository
import com.moonkey.androidagent.history.HistoryManager
import com.moonkey.androidagent.history.SessionRecordingService
import com.moonkey.androidagent.llm.LLMClient
import com.moonkey.androidagent.llm.LLMClientFactory
import com.moonkey.androidagent.llm.ModelCatalog
import com.moonkey.androidagent.memory.MemoryRecaller
import com.moonkey.androidagent.memory.MemoryStore
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.protocol.SessionLlmConfig
import com.moonkey.androidagent.tool.AppClassifier
import com.moonkey.androidagent.tool.PolicyEngine
import com.moonkey.androidagent.tool.ToolRegistry
import com.moonkey.androidagent.tool.ToolRouter
import com.moonkey.androidagent.tool.impl.RememberExperienceTool
import com.moonkey.androidagent.trace.TraceRecorder
import kotlinx.coroutines.CoroutineScope

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
class SessionServices internal constructor(
        val toolRegistry: ToolRegistry,
        val toolRouter: ToolRouter,
        val historyManager: HistoryManager,
        val sessionState: AgentSessionState,
        val policyEngine: PolicyEngine,
        val appClassifier: AppClassifier,
        val platform: AndroidPlatform,
        val config: SessionConfig,
        val llmClient: LLMClient,
        val modelCatalog: ModelCatalog,
        val llmClientFactory: LLMClientFactory,
        val traceRecorder: TraceRecorder,
        val recordingService: SessionRecordingService,
        internal val appSkillRepository: AppSkillRepository = EmptyAppSkillRepository,
        val userResponseChannel: UserResponseChannel = UserResponseChannel(),
        val memoryStore: MemoryStore = MemoryStore(java.io.File("")),
        val memoryRecaller: MemoryRecaller = MemoryRecaller(memoryStore)
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

            val appClassifier = AppClassifier.fromAssets(context.assets)
            val settingsStore = AppSettingsStore(context)
            val persistentAllowList = settingsStore.loadPersistentAllowList()
            val tooling = SessionToolingBootstrapper.create(
                approvalMode = config.approvalMode,
                appClassifier = appClassifier,
                initialPersistentAllowList = persistentAllowList,
                onPersistentAllowListChanged = { packages -> settingsStore.savePersistentAllowList(packages) }
            )
            val policyEngine = tooling.policyEngine
            val sessionState = tooling.sessionState
            val toolRegistry = tooling.toolRegistry
            val toolRouter = tooling.toolRouter

            val history = SessionHistoryBootstrapper.create(context, scope)
            val historyManager = history.historyManager
            val recordingService = history.recordingService
            val appSkillRepository = AssetAppSkillRepository(context.assets)

            // Memory system — eval hygiene is handled by the eval bridge clearing files/memory
            // before each task launch.
            val memoryDir = java.io.File(context.filesDir ?: java.io.File("/tmp"), "memory")
            val memoryStore = MemoryStore(memoryDir)
            val memoryRecaller = MemoryRecaller(memoryStore)
            toolRegistry.register(RememberExperienceTool(memoryStore, appClassifier))

            Log.i(TAG, "SessionServices created successfully")

            return SessionServices(
                    toolRegistry = toolRegistry,
                    toolRouter = toolRouter,
                    historyManager = historyManager,
                    sessionState = sessionState,
                    policyEngine = policyEngine,
                    appClassifier = appClassifier,
                    platform = platform,
                    config = config,
                    llmClient = llmClient,
                    modelCatalog = modelCatalog,
                    llmClientFactory = llmClientFactory,
                    traceRecorder = traceRecorder,
                    recordingService = recordingService,
                    appSkillRepository = appSkillRepository,
                    memoryStore = memoryStore,
                    memoryRecaller = memoryRecaller
            )
        }
    }

    /** Create a copy with optionally replaced services. */
    internal fun copy(
            toolRegistry: ToolRegistry = this.toolRegistry,
            toolRouter: ToolRouter = this.toolRouter,
            historyManager: HistoryManager = this.historyManager,
            sessionState: AgentSessionState = this.sessionState,
            policyEngine: PolicyEngine = this.policyEngine,
            appClassifier: AppClassifier = this.appClassifier,
            platform: AndroidPlatform = this.platform,
            config: SessionConfig = this.config,
            llmClient: LLMClient = this.llmClient,
            modelCatalog: ModelCatalog = this.modelCatalog,
            llmClientFactory: LLMClientFactory = this.llmClientFactory,
            traceRecorder: TraceRecorder = this.traceRecorder,
            recordingService: SessionRecordingService = this.recordingService,
            appSkillRepository: AppSkillRepository = this.appSkillRepository,
            userResponseChannel: UserResponseChannel = this.userResponseChannel,
            memoryStore: MemoryStore = this.memoryStore,
            memoryRecaller: MemoryRecaller = this.memoryRecaller
    ): SessionServices {
        return SessionServices(
                toolRegistry = toolRegistry,
                toolRouter = toolRouter,
                historyManager = historyManager,
                sessionState = sessionState,
                policyEngine = policyEngine,
                appClassifier = appClassifier,
                platform = platform,
                config = config,
                llmClient = llmClient,
                modelCatalog = modelCatalog,
                llmClientFactory = llmClientFactory,
                traceRecorder = traceRecorder,
                recordingService = recordingService,
                appSkillRepository = appSkillRepository,
                userResponseChannel = userResponseChannel,
                memoryStore = memoryStore,
                memoryRecaller = memoryRecaller
        )
    }

    /** Update the approval mode at runtime. */
    fun updateApprovalMode(mode: com.moonkey.androidagent.protocol.ApprovalMode) {
        policyEngine.setApprovalMode(mode)
        Log.d(TAG, "Updated approval mode to: $mode")
    }

    /** Get a summary of all services for debugging. */
    fun getSummary(): String {
        return SessionServicesSummaryFormatter.format(this)
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
