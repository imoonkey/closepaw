package ai.closepaw.agent

import com.google.common.truth.Truth.assertThat
import ai.closepaw.history.CompactionOutcome
import ai.closepaw.history.Compactor
import ai.closepaw.history.HistoryManager
import ai.closepaw.llm.ContextWindowExceededException
import ai.closepaw.llm.LLMClient
import ai.closepaw.llm.LLMStreamEvent
import ai.closepaw.llm.ResponsesResult
import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ToolInvocation
import ai.closepaw.tool.ToolRegistry
import ai.closepaw.tool.ToolSpec
import ai.closepaw.tool.ValidationResult
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * Verifies the reactive auto-compaction path in [Turn.runStreaming].
 *
 * Scenario A: provider throws [ContextWindowExceededException] on the first
 * streaming attempt; [Compactor.forceCompactNow] returns Compacted; the
 * caller-supplied rebuildInputItems lambda is invoked to produce a smaller
 * payload; the retry succeeds.
 *
 * Scenario B: provider throws on both attempts. Compactor returns Compacted;
 * the Turn must propagate the second exception as an Error event (no
 * infinite retry).
 *
 * Scenario C: no compactor wiring (null). The first exception propagates
 * immediately as an Error event.
 *
 * Scenario D: compactor returns NothingToCompact (or Failed/Stale). The
 * original exception is propagated wrapped with a clear message — never
 * retried with the same payload.
 */
class TurnReactiveCompactionTest {

    private val minimalInputItems = listOf(
        ResponseInputItem.ofEasyInputMessage(
            EasyInputMessage.builder()
                .role(EasyInputMessage.Role.USER)
                .content("Screen state (0 elements):\n```json\n[]\n```")
                .build()
        )
    )

    private val rebuiltInputItems = listOf(
        ResponseInputItem.ofEasyInputMessage(
            EasyInputMessage.builder()
                .role(EasyInputMessage.Role.USER)
                .content("smaller-after-compaction")
                .build()
        )
    )

    private val successfulRetryStream = listOf<LLMStreamEvent>(
        LLMStreamEvent.Created("resp-2"),
        LLMStreamEvent.TextDelta("Done. Task finished."),
        LLMStreamEvent.Completed,
    )

    @Test
    fun `runStreaming recovers from context-window exception via compactor and succeeds`() = runTest {
        val llm = SequencingStreamingLLMClient(
            attempts = listOf(
                StreamAttempt.Throw(ContextWindowExceededException("prompt_too_long")),
                StreamAttempt.Events(successfulRetryStream),
            )
        )
        val registry = registryWith("complete_task")
        val history = HistoryManager()
        val compactor = mockk<Compactor>()
        coEvery {
            compactor.forceCompactNow(any(), any())
        } returns CompactionOutcome.Compacted(before = 100_000L, after = 30_000L)

        val turn = Turn(
            toolRegistry = registry,
            llmClient = llm,
            compactor = compactor,
            historyManager = history,
            currentGoal = { "Open Settings" }
        )

        var rebuilds = 0
        val events = turn.runStreaming(
            systemPrompt = "planner",
            inputItems = minimalInputItems,
            model = "test-model",
            rebuildInputItems = {
                rebuilds += 1
                rebuiltInputItems
            }
        ).toList()

        assertThat(llm.attemptCount).isEqualTo(2)
        coVerify(exactly = 1) { compactor.forceCompactNow("Open Settings", history) }
        assertThat(rebuilds).isEqualTo(1)
        // Prompt rebuilt — second attempt used the rebuilt items, not the originals.
        assertThat(llm.attemptInputItems[1]).isEqualTo(rebuiltInputItems)
        assertThat(llm.attemptInputItems[1]).isNotEqualTo(llm.attemptInputItems[0])
        assertThat(events.filterIsInstance<TurnStreamEvent.Error>()).isEmpty()
        val complete = events.filterIsInstance<TurnStreamEvent.Complete>().single()
        assertThat(complete.result.content).isEqualTo("Done. Task finished.")
    }

    @Test
    fun `runStreaming propagates context-window exception on second attempt without further retry`() = runTest {
        val llm = SequencingStreamingLLMClient(
            attempts = listOf(
                StreamAttempt.Throw(ContextWindowExceededException("prompt_too_long")),
                StreamAttempt.Throw(ContextWindowExceededException("prompt_too_long again")),
            )
        )
        val registry = registryWith("complete_task")
        val history = HistoryManager()
        val compactor = mockk<Compactor>()
        coEvery {
            compactor.forceCompactNow(any(), any())
        } returns CompactionOutcome.Compacted(before = 100_000L, after = 30_000L)

        val turn = Turn(
            toolRegistry = registry,
            llmClient = llm,
            compactor = compactor,
            historyManager = history,
            currentGoal = { "Open Settings" }
        )

        val events = turn.runStreaming(
            systemPrompt = "planner",
            inputItems = minimalInputItems,
            model = "test-model",
            rebuildInputItems = { rebuiltInputItems }
        ).toList()

        // Exactly two LLM calls — never three. Compactor invoked once.
        assertThat(llm.attemptCount).isEqualTo(2)
        coVerify(exactly = 1) { compactor.forceCompactNow(any(), any()) }
        val error = events.filterIsInstance<TurnStreamEvent.Error>().single()
        assertThat(error.error).isInstanceOf(ContextWindowExceededException::class.java)
        assertThat(events.filterIsInstance<TurnStreamEvent.Complete>()).isEmpty()
    }

    @Test
    fun `runStreaming without compactor propagates context-window exception immediately`() = runTest {
        val llm = SequencingStreamingLLMClient(
            attempts = listOf(
                StreamAttempt.Throw(ContextWindowExceededException("prompt_too_long")),
            )
        )
        val registry = registryWith("complete_task")
        val turn = Turn(toolRegistry = registry, llmClient = llm)

        val events = turn.runStreaming(
            systemPrompt = "planner",
            inputItems = minimalInputItems,
            model = "test-model"
        ).toList()

        assertThat(llm.attemptCount).isEqualTo(1)
        val error = events.filterIsInstance<TurnStreamEvent.Error>().single()
        assertThat(error.error).isInstanceOf(ContextWindowExceededException::class.java)
    }

    @Test
    fun `runStreaming does not retry when compactor reports NothingToCompact`() = runTest {
        val llm = SequencingStreamingLLMClient(
            attempts = listOf(
                StreamAttempt.Throw(ContextWindowExceededException("prompt_too_long")),
            )
        )
        val registry = registryWith("complete_task")
        val history = HistoryManager()
        val compactor = mockk<Compactor>()
        coEvery {
            compactor.forceCompactNow(any(), any())
        } returns CompactionOutcome.NothingToCompact

        val turn = Turn(
            toolRegistry = registry,
            llmClient = llm,
            compactor = compactor,
            historyManager = history,
            currentGoal = { "Open Settings" }
        )

        val events = turn.runStreaming(
            systemPrompt = "planner",
            inputItems = minimalInputItems,
            model = "test-model",
            rebuildInputItems = { rebuiltInputItems }
        ).toList()

        // Compactor ran once but produced nothing — must NOT retry the same payload.
        assertThat(llm.attemptCount).isEqualTo(1)
        val error = events.filterIsInstance<TurnStreamEvent.Error>().single()
        assertThat(error.error).isInstanceOf(ContextWindowExceededException::class.java)
        assertThat(error.error.message).contains("Compaction could not reduce history")
    }

    @Test
    fun `runStreaming does not retry when compactor reports Failed`() = runTest {
        val llm = SequencingStreamingLLMClient(
            attempts = listOf(
                StreamAttempt.Throw(ContextWindowExceededException("prompt_too_long")),
            )
        )
        val registry = registryWith("complete_task")
        val history = HistoryManager()
        val compactor = mockk<Compactor>()
        coEvery {
            compactor.forceCompactNow(any(), any())
        } returns CompactionOutcome.Failed("provider 500")

        val turn = Turn(
            toolRegistry = registry,
            llmClient = llm,
            compactor = compactor,
            historyManager = history,
            currentGoal = { "Open Settings" }
        )

        val events = turn.runStreaming(
            systemPrompt = "planner",
            inputItems = minimalInputItems,
            model = "test-model",
            rebuildInputItems = { rebuiltInputItems }
        ).toList()

        assertThat(llm.attemptCount).isEqualTo(1)
        val error = events.filterIsInstance<TurnStreamEvent.Error>().single()
        assertThat(error.error).isInstanceOf(ContextWindowExceededException::class.java)
        assertThat(error.error.message).contains("Compaction could not reduce history")
    }

    // ── Test helpers ──────────────────────────────────────────────────────

    private fun registryWith(vararg names: String): ToolRegistry =
        ToolRegistry().apply { names.forEach { register(TestStubTool(it)) } }
}

private sealed interface StreamAttempt {
    data class Events(val events: List<LLMStreamEvent>) : StreamAttempt
    data class Throw(val error: Throwable) : StreamAttempt
}

/**
 * Plays a scripted sequence of streaming attempts. Each call to
 * [chatWithToolsStreaming] consumes the next [StreamAttempt]; an extra attempt
 * past the scripted list fails the test.
 */
private class SequencingStreamingLLMClient(
    private val attempts: List<StreamAttempt>
) : LLMClient() {

    var attemptCount: Int = 0
        private set
    val attemptInputItems: MutableList<List<ResponseInputItem>> = mutableListOf()

    override suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): ResponsesResult = throw UnsupportedOperationException("non-streaming path not used")

    override fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): Flow<LLMStreamEvent> = flow {
        val idx = attemptCount
        attemptCount += 1
        attemptInputItems.add(inputItems)
        check(idx < attempts.size) { "Unexpected extra LLM attempt #${idx + 1}" }
        when (val script = attempts[idx]) {
            is StreamAttempt.Events -> script.events.forEach { emit(it) }
            is StreamAttempt.Throw -> throw script.error
        }
    }
}

private class TestStubTool(override val name: String) : ToolSpec {
    override val description: String = "test"
    override val parameterSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
        put("required", JSONArray())
        put("additionalProperties", false)
    }

    override fun validate(params: JSONObject): ValidationResult = ValidationResult.Valid

    override fun createInvocation(params: JSONObject): ToolInvocation =
        object : ToolInvocation {
            override val toolName: String = name
            override val params: JSONObject = params
            override fun getDescription(): String = "test"
            override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult =
                ToolExecutionResult.Success("ok")
        }
}
