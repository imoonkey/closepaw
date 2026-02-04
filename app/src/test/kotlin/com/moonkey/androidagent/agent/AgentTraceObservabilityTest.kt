package com.moonkey.androidagent.agent

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.history.HistoryManager
import com.moonkey.androidagent.llm.LLMClient
import com.moonkey.androidagent.llm.LLMStreamEvent
import com.moonkey.androidagent.llm.ResponsesResult
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.protocol.SessionId
import com.moonkey.androidagent.session.AgentSessionState
import com.moonkey.androidagent.session.SessionServices
import com.moonkey.androidagent.test.FakeAndroidPlatform
import com.moonkey.androidagent.tool.PolicyEngine
import com.moonkey.androidagent.tool.ToolRegistry
import com.moonkey.androidagent.tool.ToolRouter
import com.moonkey.androidagent.trace.TraceArtifactRef
import com.moonkey.androidagent.trace.TraceEventRecord
import com.moonkey.androidagent.trace.TraceRecorder
import com.openai.models.ChatModel
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class AgentTraceObservabilityTest {

    @Test
    fun `llm request stores full prompt and input items artifacts with redaction`() = runTest {
        val recorder = RecordingTraceRecorder()
        val trace = AgentTrace(sessionId = SessionId("session-1"), services = buildServices(recorder))
        val inputItems = listOf(
            ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder()
                    .role(EasyInputMessage.Role.USER)
                    .content("email me at user@example.com with token sk_live_ABC12345678901234567890")
                    .build()
            )
        )

        trace.sessionStarted(
            AgentConfig(
                goal = "test",
                sessionId = SessionId("session-1"),
                maxTurns = 1
            )
        )
        trace.llmRequest(
            turnId = "turn-1",
            turnNumber = 1,
            snapshot = ScreenSnapshot(timestamp = 1L, elements = emptyList()),
            systemPrompt = "Bearer abcdefghijklmnopqrstuvwxyz123456 user@example.com",
            userContextText = "Authorization: token=abcdefghijklmnop1234567890",
            history = emptyList(),
            inputItems = inputItems
        )
        trace.sessionStopped(AgentStopReason.GoalAchieved, turnsExecuted = 1)

        val fullPrompt = recorder.findStored("turn_1_full_prompt.txt")
        val inputItemsJson = recorder.findStored("turn_1_llm_input_items.json")
        val runSummary = recorder.findStored("run_summary.json")

        assertThat(fullPrompt).isNotNull()
        assertThat(inputItemsJson).isNotNull()
        assertThat(runSummary).isNotNull()

        assertThat(fullPrompt).doesNotContain("user@example.com")
        assertThat(fullPrompt).contains("[REDACTED_EMAIL]")
        assertThat(fullPrompt).doesNotContain("abcdefghijklmnopqrstuvwxyz123456")
        assertThat(fullPrompt).contains("[REDACTED_TOKEN]")

        assertThat(inputItemsJson).contains("\"type\":\"message\"")
        assertThat(inputItemsJson).contains("[REDACTED_EMAIL]")
        assertThat(inputItemsJson).contains("[REDACTED_TOKEN]")
    }
}

private fun buildServices(traceRecorder: TraceRecorder): SessionServices {
    val toolRegistry = ToolRegistry()
    val policyEngine = PolicyEngine()
    val toolRouter = ToolRouter(toolRegistry, policyEngine)
    val platform = FakeAndroidPlatform()
    val config = SessionConfig(
        maxTurns = 1,
        actionDelayMs = 0,
        llmBackend = LLMBackendType.OPENAI
    )
    return SessionServices(
        toolRegistry = toolRegistry,
        toolRouter = toolRouter,
        historyManager = HistoryManager(),
        sessionState = AgentSessionState(),
        policyEngine = policyEngine,
        platform = platform,
        config = config,
        llmClient = NoopLLMClient(),
        traceRecorder = traceRecorder
    )
}

private class RecordingTraceRecorder : TraceRecorder {
    override val enabled: Boolean = true
    override val runId: String = "run-test"

    private val seq = AtomicLong(0L)
    private val storedTexts = mutableMapOf<String, String>()

    override fun nextSeq(): Long = seq.incrementAndGet()

    override fun record(event: TraceEventRecord) = Unit

    override fun storeText(
        kind: String,
        filenameHint: String,
        content: String,
        mimeType: String?,
        description: String?
    ): TraceArtifactRef {
        storedTexts[filenameHint] = content
        return TraceArtifactRef(
            kind = kind,
            path = "artifacts/$filenameHint",
            mimeType = mimeType,
            description = description
        )
    }

    override fun storeBytes(
        kind: String,
        filenameHint: String,
        bytes: ByteArray,
        mimeType: String?,
        description: String?
    ): TraceArtifactRef {
        return TraceArtifactRef(
            kind = kind,
            path = "artifacts/$filenameHint",
            mimeType = mimeType,
            description = description
        )
    }

    override suspend fun close() = Unit

    fun findStored(filenameHint: String): String? = storedTexts[filenameHint]
}

private class NoopLLMClient : LLMClient() {
    override suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel
    ): ResponsesResult {
        return ResponsesResult(textContent = "", toolCalls = emptyList(), responseId = "noop")
    }

    override fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel
    ): Flow<LLMStreamEvent> = emptyFlow()
}
