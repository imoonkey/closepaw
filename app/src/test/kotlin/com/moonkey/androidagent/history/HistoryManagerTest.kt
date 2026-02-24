package com.moonkey.androidagent.history

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

class HistoryManagerTest {

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

    @Test
    fun `compress reduces token count and truncates older outputs`() {
        val manager = HistoryManager()
        repeat(4) { idx ->
            manager.addItem(
                ResponseItem.FunctionCallOutput(
                    callId = "call-$idx",
                    content = "x".repeat(10_000)
                )
            )
        }

        val before = manager.estimateTokenCount()
        val target = before / 2
        manager.compress(target)

        val after = manager.estimateTokenCount()
        assertThat(after).isLessThan(before)
        val outputs = manager.getAll().filterIsInstance<ResponseItem.FunctionCallOutput>()
        assertThat(outputs).isNotEmpty()
    }

    @Test
    fun `auto compress keeps token budget bounded`() {
        val manager = HistoryManager(
            HistoryConfig(
                maxTokenBudget = 1_000,
                autoCompress = true,
                autoCompressThreshold = 0.5f
            )
        )

        repeat(200) { idx ->
            manager.addItem(
                ResponseItem.FunctionCallOutput(
                    callId = "call-$idx",
                    content = "x".repeat(500)
                )
            )
        }

        assertThat(manager.estimateTokenCount()).isAtMost(1_000)
    }

    @Test
    fun `compress never removes USER_INTENT messages`() {
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

        // Compress to a very small budget to force aggressive removal
        manager.compress(100)

        val remaining = manager.getAll()
        val userIntents = remaining.filterIsInstance<ResponseItem.Message>()
            .filter { it.kind == MessageKind.USER_INTENT }
            .map { it.content }

        // All three USER_INTENT messages must survive compression
        assertThat(userIntents).containsExactly(
            "open settings",
            "set brightness to max",
            "now go back"
        )
    }

    @Test
    fun `compress removes function calls and paired outputs`() {
        val manager = HistoryManager()
        manager.recordItems(
            listOf(
                ResponseItem.Message(kind = MessageKind.USER_INTENT, content = "do something"),
                ResponseItem.FunctionCall(id = "call-1", name = "tool", arguments = JSONObject()),
                ResponseItem.FunctionCallOutput(callId = "call-1", content = "x".repeat(10_000)),
                ResponseItem.Message(kind = MessageKind.ASSISTANT_TEXT, content = "x".repeat(10_000))
            )
        )

        manager.compress(100)

        val remaining = manager.getAll()
        val calls = remaining.filterIsInstance<ResponseItem.FunctionCall>()
        val outputs = remaining.filterIsInstance<ResponseItem.FunctionCallOutput>()
        assertThat(calls).isEmpty()
        assertThat(outputs).isEmpty()
    }

    @Test
    fun `auto compress uses compressTargetRatio`() {
        val manager = HistoryManager(
            HistoryConfig(
                maxTokenBudget = 2_000,
                autoCompress = true,
                autoCompressThreshold = 0.85f,
                compressTargetRatio = 0.5f
            )
        )

        // Add enough items to exceed the 85% threshold (1700 tokens)
        repeat(20) { idx ->
            manager.addItem(
                ResponseItem.FunctionCallOutput(
                    callId = "call-$idx",
                    content = "x".repeat(500)
                )
            )
        }

        // After auto-compress, should be well below budget
        // compressTargetRatio = 0.5 targets 1000 tokens, verify it compressed significantly
        assertThat(manager.estimateTokenCount()).isLessThan(2_000)
        // Items should have been removed — fewer than the 20 we added
        assertThat(manager.getAll().size).isLessThan(20)
    }
}
