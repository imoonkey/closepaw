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
    fun `dropLastNUserTurns removes last user turn and responses`() {
        val manager = HistoryManager()
        manager.recordItems(
            listOf(
                ResponseItem.Message(role = "user", content = "u1"),
                ResponseItem.Message(role = "assistant", content = "a1"),
                ResponseItem.Message(role = "user", content = "u2"),
                ResponseItem.FunctionCall(id = "call-2", name = "test_tool", arguments = JSONObject()),
                ResponseItem.FunctionCallOutput(callId = "call-2", content = "ok"),
                ResponseItem.Message(role = "assistant", content = "a2")
            )
        )

        manager.dropLastNUserTurns(1)

        val remaining = manager.getAll()
        assertThat(remaining).hasSize(2)
        val remainingMessages = remaining.filterIsInstance<ResponseItem.Message>().map { it.content }
        assertThat(remainingMessages).containsExactly("u1", "a1")
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
    fun `removeFirstItem removes paired output`() {
        val manager = HistoryManager()
        manager.recordItems(
            listOf(
                ResponseItem.FunctionCall(
                    id = "call-1",
                    name = "tool",
                    arguments = JSONObject()
                ),
                ResponseItem.FunctionCallOutput(
                    callId = "call-1",
                    content = "result"
                ),
                ResponseItem.Message(role = "assistant", content = "done")
            )
        )

        manager.removeFirstItem()

        val remaining = manager.getAll()
        assertThat(remaining).hasSize(1)
        assertThat(remaining.single()).isInstanceOf(ResponseItem.Message::class.java)
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
    fun `compress never removes user messages`() {
        val manager = HistoryManager()
        // Build a history with user messages interleaved with large tool outputs
        manager.recordItems(
            listOf(
                ResponseItem.Message(role = "user", content = "open settings"),
                ResponseItem.FunctionCall(id = "call-1", name = "tool", arguments = JSONObject()),
                ResponseItem.FunctionCallOutput(callId = "call-1", content = "x".repeat(10_000)),
                ResponseItem.Message(role = "user", content = "set brightness to max"),
                ResponseItem.FunctionCall(id = "call-2", name = "tool", arguments = JSONObject()),
                ResponseItem.FunctionCallOutput(callId = "call-2", content = "x".repeat(10_000)),
                ResponseItem.Message(role = "user", content = "now go back"),
                ResponseItem.Message(role = "assistant", content = "done")
            )
        )

        // Compress to a very small budget to force aggressive removal
        manager.compress(100)

        val remaining = manager.getAll()
        val userMessages = remaining.filterIsInstance<ResponseItem.Message>()
            .filter { it.role == "user" }
            .map { it.content }

        // All three user messages must survive compression
        assertThat(userMessages).containsExactly(
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
                ResponseItem.Message(role = "user", content = "do something"),
                ResponseItem.FunctionCall(id = "call-1", name = "tool", arguments = JSONObject()),
                ResponseItem.FunctionCallOutput(callId = "call-1", content = "x".repeat(10_000)),
                ResponseItem.Message(role = "assistant", content = "x".repeat(10_000))
            )
        )

        manager.compress(100)

        val remaining = manager.getAll()
        // Function call and its output should be removed (they are not user messages)
        val calls = remaining.filterIsInstance<ResponseItem.FunctionCall>()
        val outputs = remaining.filterIsInstance<ResponseItem.FunctionCallOutput>()
        assertThat(calls).isEmpty()
        assertThat(outputs).isEmpty()
    }
}
