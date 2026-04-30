package ai.closepaw.session

import android.content.Context
import android.util.Log
import ai.closepaw.app.AppSettingsStore
import ai.closepaw.agent.cognition.prompt.AppSkillRepository
import ai.closepaw.agent.cognition.prompt.AssetAppSkillRepository
import ai.closepaw.agent.cognition.prompt.EmptyAppSkillRepository
import ai.closepaw.agent.cognition.skills.AgentSkillManager
import ai.closepaw.auth.AuthStore
import ai.closepaw.history.HistoryManager
import ai.closepaw.history.SessionRecordingService
import ai.closepaw.llm.LLMClient
import ai.closepaw.llm.LLMClientFactory
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.memory.MemoryRecaller
import ai.closepaw.memory.MemoryStore
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.protocol.SessionLlmConfig
import ai.closepaw.tool.AppClassifier
import ai.closepaw.tool.PolicyEngine
import ai.closepaw.tool.ToolRegistry
import ai.closepaw.tool.ToolRouter
import ai.closepaw.tool.impl.RememberExperienceTool
import ai.closepaw.trace.TraceRecorder
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
 * val services = SessionServices.create(config, platform, authStore = AuthStore(context), context = context, ...)
 *
 * // For local LLM backend:
 * val localConfig = config.copy(llm = SessionLlmConfig(backendType = LLMBackendType.LOCAL))
 * val services = SessionServices.create(localConfig, platform, authStore = null, context = context, ...)
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
        val agentSkillManager: AgentSkillManager = AgentSkillManager(java.io.File("")),
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
         * @param authStore Unified credential store (OAuth + API keys). Null for test factories.
         * @param baseUrlOverrides Debug-only per-provider base URL overrides.
         * @param context Android context (required for LOCAL backend for model downloading)
         * @return Fully initialized SessionServices
         */
        fun create(
                config: SessionConfig,
                platform: AndroidPlatform,
                authStore: AuthStore?,
                baseUrlOverrides: Map<LLMProvider, String> = emptyMap(),
                context: Context,
                scope: CoroutineScope,
                traceRecorder: TraceRecorder,
                appClassifier: AppClassifier? = null
        ): SessionServices {
            Log.d(TAG, "Creating SessionServices...")

            val llmBootstrap = SessionLlmBootstrapper.create(config, context, authStore, baseUrlOverrides)
            val modelCatalog = llmBootstrap.modelCatalog
            val llmClientFactory = llmBootstrap.llmClientFactory
            val llmClient: LLMClient = llmBootstrap.llmClient
            Log.d(TAG, "Created LLMClient: ${llmClient.javaClass.simpleName}")

            val classifier = appClassifier ?: AppClassifier.fromAssets(context.assets)
            val agentSkillManager = AgentSkillManager(java.io.File(context.filesDir, "skills"))
            val settingsStore = AppSettingsStore(context)
            val persistentAllowList = settingsStore.loadPersistentAllowList()
            val tooling = SessionToolingBootstrapper.create(
                approvalMode = config.approvalMode,
                appClassifier = classifier,
                initialPersistentAllowList = persistentAllowList,
                onPersistentAllowListChanged = { packages -> settingsStore.savePersistentAllowList(packages) },
                agentSkillManager = agentSkillManager
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
            toolRegistry.register(RememberExperienceTool(memoryStore, classifier))

            Log.i(TAG, "SessionServices created successfully")

            return SessionServices(
                    toolRegistry = toolRegistry,
                    toolRouter = toolRouter,
                    historyManager = historyManager,
                    sessionState = sessionState,
                    policyEngine = policyEngine,
                    appClassifier = classifier,
                    platform = platform,
                    config = config,
                    llmClient = llmClient,
                    modelCatalog = modelCatalog,
                    llmClientFactory = llmClientFactory,
                    traceRecorder = traceRecorder,
                    recordingService = recordingService,
                    appSkillRepository = appSkillRepository,
                    agentSkillManager = agentSkillManager,
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
            agentSkillManager: AgentSkillManager = this.agentSkillManager,
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
                agentSkillManager = agentSkillManager,
                userResponseChannel = userResponseChannel,
                memoryStore = memoryStore,
                memoryRecaller = memoryRecaller
        )
    }

    /**
     * Cleanup all services. Aggregates per-step failures rather than aborting,
     * so callers can surface partial teardown errors.
     */
    suspend fun cleanup(): CleanupResult {
        Log.d(TAG, "Cleaning up SessionServices...")

        toolRouter.cancelAll()
        userResponseChannel.cancel()
        historyManager.clear()

        val failures = mutableListOf<CleanupFailure>()
        runStep("platform.stop", failures) { platform.stop() }
        runStep("llmClient.cleanup", failures) { llmClient.cleanup() }
        runStep("llmClientFactory.cleanupAll", failures) { llmClientFactory.cleanupAll() }
        // Flush/close trace last so we still capture teardown artifacts if needed
        runStep("traceRecorder.close", failures) { traceRecorder.close() }

        Log.i(TAG, "SessionServices cleaned up (failures=${failures.size})")
        return if (failures.isEmpty()) CleanupResult.Success else CleanupResult.PartialFailure(failures)
    }

    private suspend inline fun runStep(
        name: String,
        failures: MutableList<CleanupFailure>,
        block: () -> Unit
    ) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$name failed (non-fatal)", e)
            failures.add(CleanupFailure(name, e))
        }
    }
}

data class CleanupFailure(val step: String, val cause: Throwable)

sealed class CleanupResult {
    object Success : CleanupResult()
    data class PartialFailure(val failures: List<CleanupFailure>) : CleanupResult()
}
