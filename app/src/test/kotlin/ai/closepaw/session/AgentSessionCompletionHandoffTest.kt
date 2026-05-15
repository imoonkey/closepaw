package ai.closepaw.session

import android.accessibilityservice.AccessibilityService
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import ai.closepaw.history.HistoryManager
import ai.closepaw.llm.LLMClient
import ai.closepaw.llm.LLMClientFactory
import ai.closepaw.llm.LLMStreamEvent
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.llm.ResponsesResult
import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.platform.ActionResult
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.platform.AppInfo
import ai.closepaw.platform.DisplayInfo
import ai.closepaw.platform.UIAction
import ai.closepaw.protocol.*
import ai.closepaw.tool.AppClassifier
import ai.closepaw.tool.PolicyEngine
import ai.closepaw.tool.ToolRegistry
import ai.closepaw.tool.ToolRouter
import ai.closepaw.trace.NoopTraceRecorder
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentSessionCompletionHandoffTest {

    @Test
    fun `vd completion emits handoff with package and resolved label`() = runTest {
        val (session, _) = buildHandoffSession(
                scope = this,
                platformMode = PlatformMode.VIRTUAL_DISPLAY,
                currentPackage = "com.example.target",
                resolveLabel = { pkg -> if (pkg == "com.example.target") "Target App" else null },
        )
        val events = mutableListOf<AgentEvent>()
        val job = launch { session.events.collect { events.add(it) } }

        session.submit(Op.UserInput("goal"))
        advanceUntilIdle()

        val completed = events.filterIsInstance<TaskCompleted>().single()
        val handoff = completed.handoff
        assertThat(handoff).isNotNull()
        assertThat(handoff!!.appPackage).isEqualTo("com.example.target")
        assertThat(handoff.appLabel).isEqualTo("Target App")
        // Fake platform isn't a real VirtualDisplayPlatform — viewer availability degrades to false.
        assertThat(handoff.virtualDisplayAvailable).isFalse()

        job.cancel()
    }

    @Test
    fun `vd completion drops self and systemui packages`() = runTest {
        val (session, _) = buildHandoffSession(
                scope = this,
                platformMode = PlatformMode.VIRTUAL_DISPLAY,
                currentPackage = "ai.closepaw",
                resolveLabel = { _ -> "ShouldNotResolve" },
        )
        val events = mutableListOf<AgentEvent>()
        val job = launch { session.events.collect { events.add(it) } }

        session.submit(Op.UserInput("goal"))
        advanceUntilIdle()

        val completed = events.filterIsInstance<TaskCompleted>().single()
        val handoff = completed.handoff
        assertThat(handoff).isNotNull()
        assertThat(handoff!!.appPackage).isNull()
        assertThat(handoff.appLabel).isNull()

        job.cancel()
    }

    @Test
    fun `vd completion with unresolvable package emits null label`() = runTest {
        val (session, _) = buildHandoffSession(
                scope = this,
                platformMode = PlatformMode.VIRTUAL_DISPLAY,
                currentPackage = "com.uninstalled.app",
                resolveLabel = { _ -> null },
        )
        val events = mutableListOf<AgentEvent>()
        val job = launch { session.events.collect { events.add(it) } }

        session.submit(Op.UserInput("goal"))
        advanceUntilIdle()

        val handoff = events.filterIsInstance<TaskCompleted>().single().handoff
        assertThat(handoff).isNotNull()
        assertThat(handoff!!.appPackage).isEqualTo("com.uninstalled.app")
        assertThat(handoff.appLabel).isNull()

        job.cancel()
    }

    @Test
    fun `vd completion drops blocked-tier package`() = runTest {
        val (session, _) = buildHandoffSession(
                scope = this,
                platformMode = PlatformMode.VIRTUAL_DISPLAY,
                currentPackage = "com.chase.sig.android",
                resolveLabel = { _ -> "Chase" },
                appTiers = mapOf("com.chase.sig.android" to AppTier.BLOCKED),
        )
        val events = mutableListOf<AgentEvent>()
        val job = launch { session.events.collect { events.add(it) } }

        session.submit(Op.UserInput("goal"))
        advanceUntilIdle()

        val completed = events.filterIsInstance<TaskCompleted>().single()
        val handoff = completed.handoff
        assertThat(handoff).isNotNull()
        assertThat(handoff!!.appPackage).isNull()
        assertThat(handoff.appLabel).isNull()

        job.cancel()
    }

    @Test
    fun `accessibility completion emits no handoff`() = runTest {
        val (session, _) = buildHandoffSession(
                scope = this,
                platformMode = PlatformMode.ACCESSIBILITY,
                currentPackage = "com.example.target",
                resolveLabel = { _ -> "ignored" },
        )
        val events = mutableListOf<AgentEvent>()
        val job = launch { session.events.collect { events.add(it) } }

        session.submit(Op.UserInput("goal"))
        advanceUntilIdle()

        val completed = events.filterIsInstance<TaskCompleted>().single()
        assertThat(completed.handoff).isNull()

        job.cancel()
    }

    private fun buildHandoffSession(
            scope: CoroutineScope,
            platformMode: PlatformMode,
            currentPackage: String?,
            resolveLabel: (String) -> String?,
            appTiers: Map<String, AppTier> = emptyMap(),
    ): Pair<AgentSession, PackageManager> {
        val packageManager = mockk<PackageManager>()
        every { packageManager.getApplicationInfo(any<String>(), any<Int>()) } answers {
            val pkg = firstArg<String>()
            if (resolveLabel(pkg) != null) {
                ApplicationInfo().apply { packageName = pkg }
            } else {
                throw PackageManager.NameNotFoundException(pkg)
            }
        }
        every { packageManager.getApplicationLabel(any()) } answers {
            val info = firstArg<ApplicationInfo>()
            resolveLabel(info.packageName) ?: ""
        }
        val service = mockk<AccessibilityService>(relaxed = true)
        every { service.packageManager } returns packageManager
        every { service.packageName } returns "ai.closepaw"

        val platform = FixedModePlatform(mode = platformMode, packageName = currentPackage)
        val appClassifier = AppClassifier(appTiers)
        val toolRegistry = ToolRegistry()
        val policyEngine = PolicyEngine(appClassifier = appClassifier)
        val toolRouter = ToolRouter(toolRegistry, policyEngine)
        val config = SessionConfig(maxTurns = 2, actionDelayMs = 0, agentMode = AgentMode.PRO)
        val testCatalog =
                ModelCatalog.fromJson(
                        """{"gpt-5.2":{"display_name":"GPT-5.2","provider":"OPENAI_API","api":"response","model_id":"gpt-5.2"}}"""
                )
        val llm = QuickCompletionLLMClient()
        val services =
                SessionServices(
                        toolRegistry = toolRegistry,
                        toolRouter = toolRouter,
                        historyManager = HistoryManager(),
                        sessionState = AgentSessionState(),
                        policyEngine = policyEngine,
                        appClassifier = appClassifier,
                        platform = platform,
                        config = config,
                        llmClient = llm,
                        modelCatalog = testCatalog,
                        llmClientFactory = LLMClientFactory.forTest(testCatalog, llm),
                        traceRecorder = NoopTraceRecorder,
                        recordingService = mockk(relaxed = true)
                )
        val session = AgentSession.createWithServices(
                config = config,
                service = service,
                scope = scope,
                services = services
        )
        return session to packageManager
    }
}

private class FixedModePlatform(
        override val mode: PlatformMode,
        private val packageName: String?,
) : AndroidPlatform {
    override suspend fun captureScreen(): ScreenSnapshot =
            ScreenSnapshot(timestamp = 0L, elements = emptyList())
    override suspend fun performAction(action: UIAction): ActionResult = ActionResult.Success()
    override fun hasRequiredPermissions(): Boolean = true
    override fun getCurrentPackageName(): String? = packageName
    override fun getDisplayInfo(): DisplayInfo =
            DisplayInfo(widthPixels = 1080, heightPixels = 1920, density = 2f)
    override suspend fun getInstalledApps(): List<AppInfo> = emptyList()
    override suspend fun launchApp(packageName: String): ActionResult = ActionResult.Success()
}

private class QuickCompletionLLMClient : LLMClient() {
    override suspend fun chatWithTools(
            systemPrompt: String,
            inputItems: List<ResponseInputItem>,
            tools: List<FunctionTool>,
            model: String
    ): ResponsesResult =
            ResponsesResult(textContent = "done", toolCalls = emptyList(), responseId = "resp")

    override fun chatWithToolsStreaming(
            systemPrompt: String,
            inputItems: List<ResponseInputItem>,
            tools: List<FunctionTool>,
            model: String
    ): Flow<LLMStreamEvent> = flow {
        emit(LLMStreamEvent.TextDelta("done"))
        emit(LLMStreamEvent.Completed)
    }
}
