package com.moonkey.androidagent.agent

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.history.HistoryManager
import com.moonkey.androidagent.llm.LLMClient
import com.moonkey.androidagent.llm.LLMClientFactory
import com.moonkey.androidagent.llm.LLMStreamEvent
import com.moonkey.androidagent.llm.ModelCatalog
import com.moonkey.androidagent.llm.ResponsesResult
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.protocol.SessionId
import com.moonkey.androidagent.protocol.SessionLlmConfig
import com.moonkey.androidagent.session.AgentSessionState
import com.moonkey.androidagent.session.SessionServices
import com.moonkey.androidagent.test.FakeAndroidPlatform
import com.moonkey.androidagent.tool.PolicyEngine
import com.moonkey.androidagent.tool.ToolRegistry
import com.moonkey.androidagent.tool.ToolRouter
import com.moonkey.androidagent.trace.AgentTrace
import com.moonkey.androidagent.trace.TraceArtifactRef
import com.moonkey.androidagent.trace.TraceEventRecord
import com.moonkey.androidagent.trace.TraceRecorder
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class AgentTraceObservabilityTest {

        @Test
        fun `llm request stores full prompt and input items artifacts with redaction`() = runTest {
                val recorder = RecordingTraceRecorder()
                val trace =
                        AgentTrace(
                                sessionId = SessionId("session-1"),
                                services = buildServices(recorder)
                        )
                val inputItems =
                        listOf(
                                ResponseInputItem.ofEasyInputMessage(
                                        EasyInputMessage.builder()
                                                .role(EasyInputMessage.Role.USER)
                                                .content(
                                                        "email me at user@example.com with token sk_live_ABC12345678901234567890"
                                                )
                                                .build()
                                )
                        )

                trace.sessionStarted(
                        AgentExecutionConfig(
                                goal = "test",
                                sessionId = SessionId("session-1"),
                                maxTurns = 1,
                                systemPrompt = "test prompt"
                        )
                )
                trace.llmRequest(
                        turnId = "turn-1",
                        turnNumber = 1,
                        snapshot = ScreenSnapshot(timestamp = 1L, elements = emptyList()),
                        systemPrompt = "Bearer abcdefghijklmnopqrstuvwxyz123456 user@example.com",
                        userContextText = "Authorization: token=abcdefghijklmnop1234567890",
                        history = emptyList(),
                        inputItems = inputItems,
                        modelName = "gpt-5.2",
                        modelId = "gpt-5.2"
                )
                trace.sessionStopped(AgentStopReason.GoalAchieved(), turnsExecuted = 1)

                val sessionStarted = recorder.findEvent("session_started")
                assertThat(sessionStarted).isNotNull()
                val dataJson = Json.parseToJsonElement(sessionStarted!!.data.toString()).jsonObject
                assertThat(dataJson["agent_role"]?.jsonPrimitive?.content).isEqualTo("standalone")
                assertThat(dataJson["agent_id"]?.jsonPrimitive?.content).isEqualTo("session-1")
                assertThat(dataJson["model"]?.jsonPrimitive?.content).isEqualTo("gpt-5.2")
                assertThat(dataJson["main_model"]?.jsonPrimitive?.content).isEqualTo("gpt-5.2")

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
        val config =
                SessionConfig(
                        maxTurns = 1,
                        actionDelayMs = 0,
                        llm = SessionLlmConfig(backendType = LLMBackendType.OPENAI)
                )
        val testCatalog =
                ModelCatalog.fromJson(
                        """{"gpt-5.2":{"display_name":"GPT-5.2","provider":"OPENAI","api":"response","model_id":"gpt-5.2"}}"""
                )
        val noopClient = NoopLLMClient()
        return SessionServices(
                toolRegistry = toolRegistry,
                toolRouter = toolRouter,
                historyManager = HistoryManager(),
                sessionState = AgentSessionState(),
                policyEngine = policyEngine,
                platform = platform,
                config = config,
                llmClient = noopClient,
                modelCatalog = testCatalog,
                llmClientFactory = LLMClientFactory.forTest(testCatalog, noopClient),
                traceRecorder = traceRecorder,
                recordingService = io.mockk.mockk(relaxed = true)
        )
}

private class RecordingTraceRecorder : TraceRecorder {
        override val enabled: Boolean = true
        override val runId: String = "run-test"

        private val seq = AtomicLong(0L)
        private val storedTexts = mutableMapOf<String, String>()
        private val recordedEvents = mutableListOf<TraceEventRecord>()

        override fun nextSeq(): Long = seq.incrementAndGet()

        override fun record(event: TraceEventRecord) {
                recordedEvents.add(event)
        }

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

        fun findEvent(type: String): TraceEventRecord? {
                return recordedEvents.firstOrNull { it.type == type }
        }
}

private class NoopLLMClient : LLMClient() {
        override suspend fun chatWithTools(
                systemPrompt: String,
                inputItems: List<ResponseInputItem>,
                tools: List<FunctionTool>,
                model: String
        ): ResponsesResult {
                return ResponsesResult(
                        textContent = "",
                        toolCalls = emptyList(),
                        responseId = "noop"
                )
        }

        override fun chatWithToolsStreaming(
                systemPrompt: String,
                inputItems: List<ResponseInputItem>,
                tools: List<FunctionTool>,
                model: String
        ): Flow<LLMStreamEvent> = emptyFlow()
}
