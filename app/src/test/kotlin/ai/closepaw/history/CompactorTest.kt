package ai.closepaw.history

import ai.closepaw.llm.ApiType
import ai.closepaw.llm.LLMClient
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.LLMStreamEvent
import ai.closepaw.llm.ModelEntry
import ai.closepaw.llm.ResponsesResult
import com.google.common.truth.Truth.assertThat
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test

class CompactorTest {

    // ── helpers ──────────────────────────────────────────────────────────

    private fun model(window: Int = 1000) = ModelEntry(
        name = "test-model",
        displayName = "Test Model",
        provider = LLMProvider.OPENAI_API,
        api = ApiType.RESPONSE,
        modelId = "test-model",
        contextWindow = window,
    )

    /** A char string sized to produce ~target tokens (4 chars ≈ 1 token). */
    private fun textOfTokens(tokens: Int): String = "x".repeat(tokens * 4)

    private fun screen(tokens: Int) =
        ResponseItem.Message(MessageKind.SCREEN_OBSERVATION, textOfTokens(tokens))

    private fun assistant(tokens: Int = 5) =
        ResponseItem.Message(MessageKind.ASSISTANT_TEXT, textOfTokens(tokens))

    private fun userIntent(text: String) =
        ResponseItem.Message(MessageKind.USER_INTENT, text)

    private fun call(id: String) =
        ResponseItem.FunctionCall(id = id, name = "do_thing", arguments = JSONObject())

    private fun output(callId: String, tokens: Int) =
        ResponseItem.FunctionCallOutput(callId = callId, content = textOfTokens(tokens))

    private fun newCompactor(
        client: LLMClient,
        contextWindow: Int = 100_000,
        staticOverhead: Long = 0,
        reserve: Long = 10_000,
        keepRecent: Long = 5_000,
    ) = Compactor(
        llmClient = client,
        model = model(contextWindow),
        initialPrompt = "INITIAL_PROMPT",
        updatePrompt = "UPDATE_PROMPT",
        staticOverheadTokens = staticOverhead,
        reserveTokens = reserve,
        keepRecentTokens = keepRecent,
    )

    private class RecordingClient(
        private val response: String = "SUMMARY",
        private val onCall: (suspend () -> Unit)? = null,
    ) : LLMClient() {
        val capturedSystemPrompts = mutableListOf<String>()
        val capturedUserContents = mutableListOf<String>()
        val capturedTools = mutableListOf<List<FunctionTool>>()
        val callCount get() = capturedSystemPrompts.size

        override suspend fun chatWithTools(
            systemPrompt: String,
            inputItems: List<ResponseInputItem>,
            tools: List<FunctionTool>,
            model: String
        ): ResponsesResult {
            onCall?.invoke()
            capturedSystemPrompts.add(systemPrompt)
            capturedTools.add(tools)
            val userText = inputItems
                .mapNotNull { item ->
                    runCatching { item.asEasyInputMessage().content().asTextInput() }.getOrNull()
                }
                .joinToString("\n")
            capturedUserContents.add(userText)
            return ResponsesResult(textContent = response, toolCalls = emptyList(), responseId = "r-${callCount}")
        }

        override fun chatWithToolsStreaming(
            systemPrompt: String,
            inputItems: List<ResponseInputItem>,
            tools: List<FunctionTool>,
            model: String
        ): Flow<LLMStreamEvent> = flow { emit(LLMStreamEvent.Completed) }
    }

    // ── findSafeCutPoint ─────────────────────────────────────────────────

    @Test
    fun `findSafeCutPoint snaps forward past a FunctionCallOutput`() {
        val client = RecordingClient()
        val compactor = newCompactor(client)
        val items = listOf<ResponseItem>(
            userIntent("Goal: do thing"),       // 0  Message
            assistant(20),                       // 1  Message
            call("c1"),                          // 2  FunctionCall
            output("c1", 1_500),                 // 3  FunctionCallOutput  <- walk-back lands here
            assistant(20),                       // 4  Message              <- snap-forward target
            screen(20),                          // 5  Message
        )
        // Keep ~1500 tokens; walk-back accumulates the big FCO at index 3 first
        val cut = compactor.findSafeCutPoint(items, keepTokens = 1_000)
        assertThat(cut).isEqualTo(4)
        // The item at the cut is NOT a FunctionCallOutput
        assertThat(items[cut]).isNotInstanceOf(ResponseItem.FunctionCallOutput::class.java)
    }

    @Test
    fun `findSafeCutPoint returns items size when tail is unsafe`() {
        val compactor = newCompactor(RecordingClient())
        val items = listOf<ResponseItem>(
            userIntent("Goal"),
            assistant(10),
            call("c1"),
            output("c1", 5_000),  // Huge FCO at the end; only safe boundary is the FC before it
        )
        // Walk back: FCO (5000 tokens) immediately exceeds keepTokens=2000 → hitIdx=3 (FCO)
        // Snap forward from 3 lands on FCO → continue → falls off end → returns items.size
        val cut = compactor.findSafeCutPoint(items, keepTokens = 2_000)
        assertThat(cut).isEqualTo(items.size)
    }

    @Test
    fun `findSafeCutPoint at supplement-only boundary cuts at USER_INTENT`() {
        val compactor = newCompactor(RecordingClient())
        val items = listOf<ResponseItem>(
            userIntent("Goal: original"),    // 0
            call("c1"),                       // 1
            output("c1", 100),                // 2
            call("c2"),                       // 3
            output("c2", 100),                // 4
            userIntent("Supplement: also..."),// 5  <- supplement Message
            assistant(5),                     // 6
            assistant(5),                     // 7
        )
        // keepTokens spans items 5..7 (~34 tokens) plus FCO at 4 (~104) → walk-back stops at 4 (FCO),
        // snap-forward lands on the supplement at 5.
        val cut = compactor.findSafeCutPoint(items, keepTokens = 50)
        assertThat(cut).isEqualTo(5)
        assertThat((items[cut] as ResponseItem.Message).kind).isEqualTo(MessageKind.USER_INTENT)
    }

    @Test
    fun `findSafeCutPoint returns 0 when total below keepTokens`() {
        val compactor = newCompactor(RecordingClient())
        val items = listOf<ResponseItem>(assistant(5), assistant(5))
        assertThat(compactor.findSafeCutPoint(items, keepTokens = 10_000)).isEqualTo(0)
    }

    // ── maybeCompact: outcomes ──────────────────────────────────────────

    @Test
    fun `maybeCompact returns Skipped when under threshold`() = runBlocking {
        val client = RecordingClient()
        val compactor = newCompactor(client, contextWindow = 100_000, reserve = 10_000)
        val history = HistoryManager()
        history.addItem(userIntent("Goal"))
        history.addItem(assistant(10))

        val outcome = compactor.maybeCompact("Open Settings", history)

        assertThat(outcome).isEqualTo(CompactionOutcome.Skipped)
        assertThat(client.callCount).isEqualTo(0)
    }

    @Test
    fun `maybeCompact returns NothingToCompact for oversized newest turn`() = runBlocking {
        // Construct so that total > trigger but the only safe cut would lose everything.
        val client = RecordingClient()
        val compactor = newCompactor(
            client,
            contextWindow = 1_000,
            reserve = 100,
            keepRecent = 200,
        )
        val history = HistoryManager()
        history.addItem(call("c1"))
        history.addItem(output("c1", 950))  // single huge FCO, no Message after it
        // Walk back from FCO: acc=950 >= 200 → hitIdx is FCO → snap forward → items.size

        val outcome = compactor.maybeCompact("Goal", history)
        assertThat(outcome).isEqualTo(CompactionOutcome.NothingToCompact)
        assertThat(client.callCount).isEqualTo(0)
    }

    @Test
    fun `maybeCompact replaces prefix with USER_INTENT + SUMMARY + kept`() = runBlocking {
        val client = RecordingClient(response = "## Progress\n### Done\n- thing")
        val compactor = newCompactor(
            client,
            contextWindow = 2_000,
            reserve = 200,
            keepRecent = 200,
        )
        val history = HistoryManager()
        // ~1850 history tokens, well over trigger (1800)
        repeat(6) { history.addItem(assistant(300)) }    // 6 messages * ~300 tokens
        history.addItem(assistant(50))                    // tail kept

        val before = history.estimateTokenCount()
        val outcome = compactor.maybeCompact("Open Settings", history)

        assertThat(outcome).isInstanceOf(CompactionOutcome.Compacted::class.java)
        val items = history.getAll()
        assertThat((items[0] as ResponseItem.Message).kind).isEqualTo(MessageKind.USER_INTENT)
        assertThat((items[0] as ResponseItem.Message).content).isEqualTo("Goal: Open Settings")
        assertThat((items[1] as ResponseItem.Message).kind).isEqualTo(MessageKind.COMPRESSION_DIGEST)
        assertThat((items[1] as ResponseItem.Message).content).contains("Progress")
        // Tail preserved
        assertThat(items.size).isGreaterThan(2)
        assertThat(history.estimateTokenCount()).isLessThan(before)
        // initial prompt used (no previous summary)
        assertThat(client.capturedSystemPrompts.single()).isEqualTo("INITIAL_PROMPT")
    }

    @Test
    fun `iterative compaction uses UPDATE prompt and drops the previous summary`() = runBlocking {
        val client = RecordingClient(response = "## Progress\n### Done\n- step1")
        val compactor = newCompactor(
            client,
            contextWindow = 2_000,
            reserve = 200,
            keepRecent = 200,
        )
        val history = HistoryManager()
        repeat(6) { history.addItem(assistant(300)) }
        history.addItem(assistant(50))

        // First compaction → INITIAL prompt
        val first = compactor.maybeCompact("Open Settings", history)
        assertThat(first).isInstanceOf(CompactionOutcome.Compacted::class.java)
        assertThat(client.capturedSystemPrompts.last()).isEqualTo("INITIAL_PROMPT")

        // Add more bulk so the next maybeCompact triggers again
        repeat(6) { history.addItem(assistant(300)) }

        // The new client returns a refreshed summary so we can distinguish it
        val client2 = RecordingClient(response = "## Progress\n### Done\n- step1\n- step2")
        val compactor2 = Compactor(
            llmClient = client2,
            model = model(2_000),
            initialPrompt = "INITIAL_PROMPT",
            updatePrompt = "UPDATE_PROMPT",
            staticOverheadTokens = 0,
            reserveTokens = 200,
            keepRecentTokens = 200,
        )
        val second = compactor2.maybeCompact("Open Settings", history)
        assertThat(second).isInstanceOf(CompactionOutcome.Compacted::class.java)
        // UPDATE prompt path
        assertThat(client2.capturedSystemPrompts.single()).isEqualTo("UPDATE_PROMPT")
        // User content carries the previous summary inside <previous-summary>
        assertThat(client2.capturedUserContents.single()).contains("<previous-summary>")
        assertThat(client2.capturedUserContents.single()).contains("step1")

        // Result history has exactly ONE COMPRESSION_DIGEST (not duplicated)
        val digestCount = history.getAll().count {
            it is ResponseItem.Message && it.kind == MessageKind.COMPRESSION_DIGEST
        }
        assertThat(digestCount).isEqualTo(1)
        // …and it contains the *new* summary (step2)
        val digest = history.getAll().first {
            it is ResponseItem.Message && it.kind == MessageKind.COMPRESSION_DIGEST
        } as ResponseItem.Message
        assertThat(digest.content).contains("step2")
    }

    // ── cancellation ────────────────────────────────────────────────────

    @Test
    fun `CancellationException during LLM call propagates`() {
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val client = RecordingClient(onCall = {
            started.complete(Unit)
            never.await()
        })
        val compactor = newCompactor(
            client,
            contextWindow = 2_000,
            reserve = 200,
            keepRecent = 200,
        )
        val history = HistoryManager()
        repeat(6) { history.addItem(assistant(300)) }
        history.addItem(assistant(50))

        var caught: Throwable? = null
        runBlocking {
            val job = async { compactor.maybeCompact("Goal", history) }
            started.await()
            job.cancel()
            try {
                job.await()
            } catch (t: Throwable) {
                caught = t
            }
        }
        assertThat(caught).isInstanceOf(CancellationException::class.java)
    }

    // ── LLM failure path ────────────────────────────────────────────────

    @Test
    fun `LLM exception returns Failed (not throws)`() = runBlocking {
        val client = object : LLMClient() {
            override suspend fun chatWithTools(
                systemPrompt: String,
                inputItems: List<ResponseInputItem>,
                tools: List<FunctionTool>,
                model: String
            ): ResponsesResult = throw RuntimeException("provider 500")

            override fun chatWithToolsStreaming(
                systemPrompt: String,
                inputItems: List<ResponseInputItem>,
                tools: List<FunctionTool>,
                model: String
            ): Flow<LLMStreamEvent> = flow { emit(LLMStreamEvent.Completed) }
        }
        val compactor = newCompactor(
            client,
            contextWindow = 2_000,
            reserve = 200,
            keepRecent = 200,
        )
        val history = HistoryManager()
        repeat(6) { history.addItem(assistant(300)) }
        history.addItem(assistant(50))

        val outcome = compactor.maybeCompact("Goal", history)
        assertThat(outcome).isInstanceOf(CompactionOutcome.Failed::class.java)
        assertThat((outcome as CompactionOutcome.Failed).reason).contains("provider 500")
    }
}
