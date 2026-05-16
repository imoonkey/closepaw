package ai.closepaw.history

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Test

class HistoryManagerTest {

    // ── Normalization ────────────────────────────────────────────────────

    @Test
    fun `forPrompt adds placeholder output when missing`() {
        val manager = HistoryManager()
        val call = ResponseItem.FunctionCall(
            id = "call-1",
            name = "test_tool",
            arguments = JSONObject()
        )

        manager.addItem(call)

        val normalized = manager.forPrompt()
        val output = normalized.filterIsInstance<ResponseItem.FunctionCallOutput>().single()

        assertThat(output.callId).isEqualTo("call-1")
        assertThat(output.content).contains("Output not recorded")
        assertThat(output.success).isFalse()
        assertThat(normalized.indexOf(output)).isEqualTo(normalized.indexOf(call) + 1)
    }

    @Test
    fun `forPrompt removes orphaned outputs`() {
        val manager = HistoryManager()
        val orphan = ResponseItem.FunctionCallOutput(
            callId = "missing",
            content = "orphaned output"
        )

        manager.addItem(orphan)

        val normalized = manager.forPrompt()
        assertThat(normalized).isEmpty()
    }

    @Test
    fun `none policy preserves output content`() {
        val manager = HistoryManager(
            HistoryConfig(defaultTruncationPolicy = TruncationPolicy.NONE)
        )
        val longContent = "x".repeat(5000)

        manager.addItem(ResponseItem.FunctionCallOutput(callId = "call-3", content = longContent))

        val output = manager.getAll().single() as ResponseItem.FunctionCallOutput
        assertThat(output.content).isEqualTo(longContent)
        assertThat(output.truncated).isFalse()
    }

    // ── P0: Compression never deletes USER_INTENT ────────────────────────

    @Test
    fun `P0 compress never removes USER_INTENT messages`() {
        val manager = HistoryManager()
        manager.recordItems(
            listOf(
                ResponseItem.Message(kind = MessageKind.USER_INTENT, content = "open settings"),
                ResponseItem.FunctionCall(id = "call-1", name = "tool", arguments = JSONObject()),
                ResponseItem.FunctionCallOutput(callId = "call-1", content = "x".repeat(10_000)),
                ResponseItem.Message(kind = MessageKind.USER_INTENT, content = "set brightness to max"),
                ResponseItem.FunctionCall(id = "call-2", name = "tool", arguments = JSONObject()),
                ResponseItem.FunctionCallOutput(callId = "call-2", content = "x".repeat(10_000)),
                ResponseItem.Message(kind = MessageKind.USER_INTENT, content = "now go back"),
                ResponseItem.Message(kind = MessageKind.ASSISTANT_TEXT, content = "done")
            )
        )

        manager.compress(100)

        val remaining = manager.getAll()
        val userIntents = remaining.filterIsInstance<ResponseItem.Message>()
            .filter { it.kind == MessageKind.USER_INTENT }
            .map { it.content }

        assertThat(userIntents).containsExactly(
            "open settings",
            "set brightness to max",
            "now go back"
        )
    }

    // ── P0: Screen downgrade keeps last N full, rewrites older ────────────

    @Test
    fun `P0 proactive screen downgrade keeps last N full screens`() {
        val manager = HistoryManager(HistoryConfig(recentFullScreens = 2))

        // Add 4 screen observations
        repeat(4) { i ->
            manager.addItem(ResponseItem.Message(
                kind = MessageKind.SCREEN_OBSERVATION,
                content = "Screen state (${10 + i} elements):\n```json\n[]\n```"
            ))
        }

        val screens = manager.getAll().filterIsInstance<ResponseItem.Message>()
            .filter { it.kind == MessageKind.SCREEN_OBSERVATION }

        assertThat(screens).hasSize(4)

        // First 2 should be compressed
        assertThat(screens[0].content).contains("(compressed)")
        assertThat(screens[1].content).contains("(compressed)")

        // Last 2 should be full
        assertThat(screens[2].content).contains("```json")
        assertThat(screens[3].content).contains("```json")
    }

    @Test
    fun `P0 screen downgrade preserves element count in summary`() {
        val manager = HistoryManager(HistoryConfig(recentFullScreens = 1))

        manager.addItem(ResponseItem.Message(
            kind = MessageKind.SCREEN_OBSERVATION,
            content = "Screen state (42 elements):\n```json\n[{\"index\":0}]\n```"
        ))
        manager.addItem(ResponseItem.Message(
            kind = MessageKind.SCREEN_OBSERVATION,
            content = "Screen state (55 elements):\n```json\n[]\n```"
        ))

        val first = manager.getAll()[0] as ResponseItem.Message
        assertThat(first.content).isEqualTo("Screen: 42 elements (compressed)")
    }

    // ── P0: Call/output pairing survives compression ─────────────────────

    // (Lossy eviction removed — call/output pairing is now Compactor's
    // concern; previously this test asserted compress() drops both halves
    // together.)

    // ── P0: Recent window is protected from eviction ─────────────────────

    @Test
    fun `P0 recent window items are protected from eviction`() {
        val manager = HistoryManager(HistoryConfig(recentWindowSize = 4, recentFullScreens = 1))

        // Build history: old items + recent window
        manager.recordItems(
            listOf(
                ResponseItem.Message(kind = MessageKind.USER_INTENT, content = "goal"),
                // Old items (outside recent window)
                ResponseItem.Message(kind = MessageKind.ASSISTANT_TEXT, content = "x".repeat(5_000)),
                ResponseItem.FunctionCall(id = "c1", name = "tool", arguments = JSONObject()),
                ResponseItem.FunctionCallOutput(callId = "c1", content = "x".repeat(5_000)),
                // Recent window (last 4 items)
                ResponseItem.Message(kind = MessageKind.SCREEN_OBSERVATION, content = "Screen state (10 elements):\n```json\n[]\n```"),
                ResponseItem.Message(kind = MessageKind.ASSISTANT_TEXT, content = "recent action"),
                ResponseItem.FunctionCall(id = "c2", name = "tool", arguments = JSONObject()),
                ResponseItem.FunctionCallOutput(callId = "c2", content = "recent result")
            )
        )

        val result = manager.compress(200)

        // Recent window items should survive
        val remaining = manager.getAll()
        val recentAssistant = remaining.any {
            it is ResponseItem.Message && it.content == "recent action"
        }
        val recentCall = remaining.any {
            it is ResponseItem.FunctionCall && it.id == "c2"
        }
        assertThat(recentAssistant).isTrue()
        assertThat(recentCall).isTrue()
    }

    // ── P0: Repeated compress is idempotent ──────────────────────────────

    @Test
    fun `P0 repeated compress is idempotent once stabilized`() {
        val manager = HistoryManager()
        manager.recordItems(
            listOf(
                ResponseItem.Message(kind = MessageKind.USER_INTENT, content = "goal"),
                ResponseItem.Message(kind = MessageKind.ASSISTANT_TEXT, content = "x".repeat(5_000)),
                ResponseItem.FunctionCall(id = "c1", name = "tool", arguments = JSONObject()),
                ResponseItem.FunctionCallOutput(callId = "c1", content = "x".repeat(5_000))
            )
        )

        // First compress
        manager.compress(200)
        val afterFirst = manager.estimateTokenCount()
        val itemsAfterFirst = manager.getAll().toList()

        // Second compress — should be a noop
        val result = manager.compress(200)
        val afterSecond = manager.estimateTokenCount()

        assertThat(result).isInstanceOf(CompressionResult.Noop::class.java)
        assertThat(afterSecond).isEqualTo(afterFirst)
    }

    // ── P0: BudgetUnreachable ────────────────────────────────────────────

    // (Removed with the lossy-eviction pipeline — there is no longer a
    // "budget unreachable" branch; Compactor is responsible for context
    // pressure now.)

    // ── CompressionResult ────────────────────────────────────────────────

    @Test
    fun `compress returns Noop when already under budget`() {
        val manager = HistoryManager()
        manager.addItem(ResponseItem.Message(
            kind = MessageKind.USER_INTENT, content = "small"
        ))

        val result = manager.compress(10_000)

        assertThat(result).isInstanceOf(CompressionResult.Noop::class.java)
    }

    // (Removed: `compress returns Compressed with stats` — the test depended
    // on Phase 2 eviction; with eviction gone, compress() rarely returns
    // Compressed and the test added no remaining value.)

    // ── COMPACTION_SUMMARY breadcrumb ─────────────────────────────────────

    // (Removed: `compress inserts COMPACTION_SUMMARY breadcrumb after eviction`
    // — breadcrumb insertion died with the eviction body it described.)

    // ── Auto compress ────────────────────────────────────────────────────

    // (Removed: `auto compress keeps token budget bounded` and
    // `auto compress uses compressTargetRatio` — autoCompressIfNeeded plus
    // its config fields were removed when Compactor took over. Compactor
    // has its own coverage in CompactorTest.kt.)

    // ── Screen Compression Patterns ─────────────────────────────────────

    @Test
    fun `compressScreenContent handles accessibility tree block`() {
        val content = """
            Screen state (42 elements):
            keyboard_visible: true (BACK will dismiss keyboard first, not navigate back)
            ```json
            [{"idx":0,"text":"OK"}]
            ```
        """.trimIndent()
        val manager = HistoryManager()
        assertThat(manager.compressScreenContent(content)).isEqualTo("Screen: 42 elements (compressed)")
    }

    @Test
    fun `compressScreenContent handles screenshot-only canonical block`() {
        // This is the exact text from TurnObservation.screenBlock in screenshot-only mode
        val content = "(Screenshot-only mode — no accessibility tree)"
        val manager = HistoryManager()
        assertThat(manager.compressScreenContent(content)).isEqualTo("Screen: screenshot only (compressed)")
    }

    // ── Token accounting after large compression ────────────────────────

    // (Removed: `compress leaves token estimate matching actual sum after
    // large eviction` — relied on Phase 2 eviction to produce a Compressed
    // result. Token-accounting invariants for the live pipeline are covered
    // implicitly by the screen-downgrade tests above.)

    @Test
    fun `compress evicts call and paired output together in large history`() {
        val manager = HistoryManager(HistoryConfig(recentWindowSize = 2))
        manager.addItem(ResponseItem.Message(kind = MessageKind.USER_INTENT, content = "task"))
        repeat(100) { idx ->
            manager.addItem(
                ResponseItem.FunctionCall(
                    id = "call-$idx",
                    name = "tool",
                    arguments = JSONObject()
                )
            )
            manager.addItem(
                ResponseItem.FunctionCallOutput(
                    callId = "call-$idx",
                    content = "result ${"y".repeat(200)}"
                )
            )
        }

        manager.compress(500)

        val remaining = manager.getAll()
        val calls = remaining.filterIsInstance<ResponseItem.FunctionCall>().map { it.id }.toSet()
        val outputs = remaining.filterIsInstance<ResponseItem.FunctionCallOutput>().map { it.callId }.toSet()
        // Every surviving call should have its output; no orphan outputs remain
        assertThat(outputs).isEqualTo(calls)
    }

    // ── Revision + CAS ───────────────────────────────────────────────────

    @Test
    fun `revision starts at zero and increments on every mutation`() {
        val manager = HistoryManager()
        assertThat(manager.revision).isEqualTo(0L)

        manager.addItem(ResponseItem.Message(MessageKind.USER_INTENT, "a"))
        val afterAdd = manager.revision
        assertThat(afterAdd).isGreaterThan(0L)

        manager.recordItems(listOf(ResponseItem.Message(MessageKind.ASSISTANT_TEXT, "b")))
        assertThat(manager.revision).isGreaterThan(afterAdd)
        val afterRecord = manager.revision

        manager.replaceAll(listOf(ResponseItem.Message(MessageKind.USER_INTENT, "c")))
        assertThat(manager.revision).isGreaterThan(afterRecord)
        val afterReplace = manager.revision

        manager.clear()
        assertThat(manager.revision).isGreaterThan(afterReplace)
    }

    @Test
    fun `snapshot returns matching revision and items list`() {
        val manager = HistoryManager()
        manager.addItem(ResponseItem.Message(MessageKind.USER_INTENT, "goal"))
        manager.addItem(ResponseItem.Message(MessageKind.ASSISTANT_TEXT, "step"))

        val (rev, snap) = manager.snapshot()
        assertThat(rev).isEqualTo(manager.revision)
        assertThat(snap).hasSize(2)

        // Snapshot is an immutable copy: mutating history doesn't change it.
        manager.addItem(ResponseItem.Message(MessageKind.ASSISTANT_TEXT, "more"))
        assertThat(snap).hasSize(2)
        assertThat(manager.revision).isGreaterThan(rev)
    }

    @Test
    fun `replaceAllIfRevision swaps on match and bumps revision`() {
        val manager = HistoryManager()
        manager.addItem(ResponseItem.Message(MessageKind.USER_INTENT, "goal"))
        val (rev, _) = manager.snapshot()

        val newItems = listOf(
            ResponseItem.Message(MessageKind.USER_INTENT, "Goal: goal"),
            ResponseItem.Message(MessageKind.COMPACTION_SUMMARY, "summary"),
        )
        val swapped = manager.replaceAllIfRevision(rev, newItems)

        assertThat(swapped).isTrue()
        assertThat(manager.getAll()).hasSize(2)
        assertThat(manager.revision).isGreaterThan(rev)
    }

    @Test
    fun `replaceAllIfRevision returns false when revision moved`() {
        val manager = HistoryManager()
        manager.addItem(ResponseItem.Message(MessageKind.USER_INTENT, "goal"))
        val (rev, _) = manager.snapshot()

        // Concurrent mutation bumps revision out from under us.
        manager.addItem(ResponseItem.Message(MessageKind.USER_INTENT, "supplement: do also Y"))

        val swapped = manager.replaceAllIfRevision(
            rev,
            listOf(ResponseItem.Message(MessageKind.COMPACTION_SUMMARY, "summary"))
        )

        assertThat(swapped).isFalse()
        // History untouched by the failed CAS.
        val items = manager.getAll().filterIsInstance<ResponseItem.Message>()
        assertThat(items.map { it.content }).containsExactly(
            "goal",
            "supplement: do also Y",
        ).inOrder()
    }

    @Test
    fun `concurrent supplement during simulated long compactor causes CAS to fail and supplement survives`() = runBlocking {
        val manager = HistoryManager()
        manager.addItem(ResponseItem.Message(MessageKind.USER_INTENT, "Goal: open settings"))
        manager.addItem(ResponseItem.Message(MessageKind.ASSISTANT_TEXT, "I will tap the icon"))

        // Compactor's first step: atomic read.
        val (snapRev, snapItems) = manager.snapshot()

        // Simulate a long-running LLM summarization on an IO worker; in
        // parallel, a supplement arrives from the user and is appended.
        val compactor = async(Dispatchers.IO) {
            delay(50) // pretend the LLM is summarizing
            val newItems = listOf(
                ResponseItem.Message(MessageKind.USER_INTENT, "Goal: open settings"),
                ResponseItem.Message(MessageKind.COMPACTION_SUMMARY, "summary of $snapItems"),
            )
            manager.replaceAllIfRevision(snapRev, newItems)
        }

        // Supplement injected mid-flight (main thread style — happens before LLM returns).
        withContext(Dispatchers.Default) {
            delay(10)
            manager.addItem(ResponseItem.Message(MessageKind.USER_INTENT, "also: turn on dark mode"))
        }

        val casResult = compactor.await()
        assertThat(casResult).isFalse()

        // Supplement is preserved; compaction's half-baked summary is dropped.
        val finalItems = manager.getAll().filterIsInstance<ResponseItem.Message>()
        assertThat(finalItems.map { it.content }).contains("also: turn on dark mode")
        assertThat(finalItems.none { it.kind == MessageKind.COMPACTION_SUMMARY }).isTrue()
    }
}
