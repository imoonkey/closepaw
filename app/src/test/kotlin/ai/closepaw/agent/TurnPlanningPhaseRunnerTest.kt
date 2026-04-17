package ai.closepaw.agent

import com.google.common.truth.Truth.assertThat
import ai.closepaw.agent.cognition.policy.TurnToolPolicy
import ai.closepaw.history.HistoryManager
import ai.closepaw.history.MessageKind
import ai.closepaw.history.ResponseItem
import ai.closepaw.llm.LLMClient
import ai.closepaw.llm.LLMClientFactory
import ai.closepaw.llm.LLMStreamEvent
import ai.closepaw.llm.LLMToolCall
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.llm.ResponsesResult
import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.protocol.AgentEvent
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.protocol.SessionId
import ai.closepaw.protocol.SessionLlmConfig
import ai.closepaw.protocol.StatusUpdate
import ai.closepaw.protocol.ThoughtUpdate
import ai.closepaw.session.AgentSessionState
import ai.closepaw.session.SessionServices
import ai.closepaw.test.FakeAndroidPlatform
import ai.closepaw.tool.AppClassifier
import ai.closepaw.tool.PolicyEngine
import ai.closepaw.tool.ToolRegistry
import ai.closepaw.tool.ToolRouter
import ai.closepaw.trace.AgentTrace
import ai.closepaw.trace.NoopTraceRecorder
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test

class TurnPlanningPhaseRunnerTest {

    @Test
    fun `planning phase writes screen observation to history before LLM call`() = runTest {
        val harness = PlanningHarness.build()
        harness.llmClient.historySnapshotProvider = {
            harness.services.historyManager.getAll()
        }

        harness.runner.runPlanningPhase(
            turnId = "turn-1",
            turnNumber = 1,
            snapshot = ScreenSnapshot(timestamp = 1L, elements = emptyList()),
            currentPackageName = null,
            warnings = emptyList()
        )

        val historyAtCall = harness.llmClient.historyAtCall
        assertThat(historyAtCall).isNotNull()
        val hasScreenObs = historyAtCall!!.any { item ->
            item is ResponseItem.Message && item.kind == MessageKind.SCREEN_OBSERVATION
        }
        assertThat(hasScreenObs).isTrue()
    }

    @Test
    fun `arbitration warning emitted when tools are dropped by policy`() = runTest {
        val harness = PlanningHarness.build(
            toolCalls = listOf(
                LLMToolCall(
                    callId = "call-action",
                    name = "mobile_action",
                    arguments = """{"action_type":"click","target_id":"1"}"""
                ),
                LLMToolCall(
                    callId = "call-complete",
                    name = "complete_task",
                    arguments = """{"status":"success","answer":"done"}"""
                )
            )
        )

        val output = harness.runner.runPlanningPhase(
            turnId = "turn-1",
            turnNumber = 1,
            snapshot = ScreenSnapshot(timestamp = 1L, elements = emptyList()),
            currentPackageName = null,
            warnings = emptyList()
        )

        assertThat(output.arbitration.droppedToolCalls.map { it.name })
            .containsExactly("complete_task")
        val statuses = harness.events.filterIsInstance<StatusUpdate>().map { it.status }
        assertThat(statuses.any { it.contains("Dropped 1 tool call") }).isTrue()
    }

    @Test
    fun `agent_thought event emitted during planning with LLM reasoning content`() = runTest {
        val harness = PlanningHarness.build(
            toolCalls = listOf(
                LLMToolCall(
                    callId = "call-1",
                    name = "mobile_action",
                    arguments = JSONObject()
                        .put("agent_thought", "Tapping settings icon")
                        .put("action_type", "click")
                        .toString()
                )
            )
        )

        harness.runner.runPlanningPhase(
            turnId = "turn-1",
            turnNumber = 1,
            snapshot = ScreenSnapshot(timestamp = 1L, elements = emptyList()),
            currentPackageName = null,
            warnings = emptyList()
        )

        val thoughts = harness.events.filterIsInstance<ThoughtUpdate>()
        assertThat(thoughts).hasSize(1)
        assertThat(thoughts[0].thought).isEqualTo("Tapping settings icon")
    }

    @Test
    fun `model resolution selects correct model for planning phase`() = runTest {
        val catalogJson =
            """{"planner":{"display_name":"Planner","provider":"OPENAI","api":"response","model_id":"planner-actual-id"}}"""
        val harness = PlanningHarness.build(
            catalogJson = catalogJson,
            modelName = "planner"
        )

        harness.runner.runPlanningPhase(
            turnId = "turn-1",
            turnNumber = 1,
            snapshot = ScreenSnapshot(timestamp = 1L, elements = emptyList()),
            currentPackageName = null,
            warnings = emptyList()
        )

        assertThat(harness.llmClient.lastModel).isEqualTo("planner-actual-id")
        assertThat(harness.llmClient.streamingCalls).isEqualTo(1)
    }
}

private class PlanningHarness(
    val runner: TurnPlanningPhaseRunner,
    val services: SessionServices,
    val llmClient: CapturingLLMClient,
    val events: CopyOnWriteArrayList<AgentEvent>
) {
    companion object {
        fun build(
            toolCalls: List<LLMToolCall> = emptyList(),
            textContent: String? = null,
            catalogJson: String =
                """{"gpt-5.2":{"display_name":"GPT-5.2","provider":"OPENAI","api":"response","model_id":"gpt-5.2"}}""",
            modelName: String = "gpt-5.2"
        ): PlanningHarness {
            val events = CopyOnWriteArrayList<AgentEvent>()
            val llmClient = CapturingLLMClient(toolCalls = toolCalls, textContent = textContent)
            val catalog = ModelCatalog.fromJson(catalogJson)
            val toolRegistry = ToolRegistry()
            val policyEngine = PolicyEngine(appClassifier = AppClassifier(emptyMap()))
            val services = SessionServices(
                toolRegistry = toolRegistry,
                toolRouter = ToolRouter(toolRegistry, policyEngine),
                historyManager = HistoryManager(),
                sessionState = AgentSessionState(),
                policyEngine = policyEngine,
                appClassifier = AppClassifier(emptyMap()),
                platform = FakeAndroidPlatform(),
                config = SessionConfig(
                    maxTurns = 1,
                    actionDelayMs = 0,
                    mainModel = modelName,
                    llm = SessionLlmConfig(backendType = LLMBackendType.OPENAI)
                ),
                llmClient = llmClient,
                modelCatalog = catalog,
                llmClientFactory = LLMClientFactory.forTest(catalog, llmClient),
                traceRecorder = NoopTraceRecorder,
                recordingService = io.mockk.mockk(relaxed = true)
            )
            val sessionId = SessionId("session-planner")
            val dispatcher = AgentEventDispatcher(
                sessionId = sessionId,
                eventEmitter = { events.add(it) }
            )
            val trace = AgentTrace(sessionId = sessionId, services = services)
            val config = AgentExecutionConfig(
                goal = "goal",
                sessionId = sessionId,
                maxTurns = 1,
                uiSettleDelayMs = 0,
                systemPrompt = "test prompt",
                modelName = modelName
            )
            val runner = TurnPlanningPhaseRunner(
                config = config,
                services = services,
                eventDispatcher = dispatcher,
                trace = trace,
                turnPolicyEngine = TurnToolPolicy()
            )
            return PlanningHarness(runner, services, llmClient, events)
        }
    }
}

private class CapturingLLMClient(
    private val toolCalls: List<LLMToolCall> = emptyList(),
    private val textContent: String? = null
) : LLMClient() {
    var lastModel: String? = null
    var historyAtCall: List<ResponseItem>? = null
    var streamingCalls: Int = 0
    var historySnapshotProvider: (() -> List<ResponseItem>)? = null

    override suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): ResponsesResult =
        ResponsesResult(textContent = textContent, toolCalls = emptyList(), responseId = "noop")

    override fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): Flow<LLMStreamEvent> = flow {
        lastModel = model
        historyAtCall = historySnapshotProvider?.invoke()
        streamingCalls += 1
        emit(LLMStreamEvent.Created("stream-1"))
        textContent?.let { emit(LLMStreamEvent.TextDelta(it)) }
        toolCalls.forEach { emit(LLMStreamEvent.ToolCallDone(it)) }
        emit(LLMStreamEvent.Completed)
    }
}
