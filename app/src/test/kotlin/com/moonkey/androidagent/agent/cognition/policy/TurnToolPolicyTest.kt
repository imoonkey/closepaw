package com.moonkey.androidagent.agent.cognition.policy

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.agent.ToolCallRequest
import com.moonkey.androidagent.agent.TurnResult
import org.json.JSONObject
import org.junit.Test

class TurnToolPolicyTest {
    private val engine = TurnToolPolicy()

    @Test
    fun `arbitrateToolCalls keeps all cognitive tools and one screen tool`() {
        val calls =
                listOf(
                        toolCall(name = "write_todos"),
                        toolCall(name = "mobile_action"),
                        toolCall(name = "scratchpad")
                )

        val result = engine.arbitrateToolCalls(calls)

        assertThat(result.selectedToolCalls.map { it.name })
                .containsExactly("write_todos", "scratchpad", "mobile_action")
                .inOrder()
        assertThat(result.hasCompletionTool).isFalse()
        assertThat(result.hasScreenAction).isTrue()
        assertThat(result.droppedToolCalls).isEmpty()
    }

    @Test
    fun `arbitrateToolCalls keeps completion with cognitive tools when no screen tool exists`() {
        val calls =
                listOf(
                        toolCall(name = "complete_task"),
                        toolCall(name = "write_todos"),
                        toolCall(name = "scratchpad")
                )

        val result = engine.arbitrateToolCalls(calls)

        assertThat(result.selectedToolCalls.map { it.name })
                .containsExactly("write_todos", "scratchpad", "complete_task")
                .inOrder()
        assertThat(result.hasCompletionTool).isTrue()
        assertThat(result.hasScreenAction).isFalse()
    }

    @Test
    fun `arbitrateToolCalls defers completion when screen tool exists`() {
        val calls =
                listOf(
                        toolCall(name = "scratchpad"),
                        toolCall(
                                name = "complete_task",
                                arguments = JSONObject("""{"answer":"done"}""")
                        ),
                        toolCall(name = "mobile_action")
                )

        val result = engine.arbitrateToolCalls(calls)

        assertThat(result.selectedToolCalls.map { it.name })
                .containsExactly("scratchpad", "mobile_action")
                .inOrder()
        assertThat(result.hasCompletionTool).isTrue()
        assertThat(result.hasScreenAction).isTrue()
        assertThat(result.droppedToolCalls.map { it.name }).containsExactly("complete_task")
    }

    @Test
    fun `arbitrateToolCalls treats unknown tool as screen affecting`() {
        val calls =
                listOf(
                        toolCall(name = "scratchpad"),
                        toolCall(name = "future_tool"),
                        toolCall(name = "write_todos")
                )

        val result = engine.arbitrateToolCalls(calls)

        assertThat(result.selectedToolCalls.map { it.name })
                .containsExactly("scratchpad", "write_todos", "future_tool")
                .inOrder()
        assertThat(result.hasScreenAction).isTrue()
        assertThat(result.droppedToolCalls).isEmpty()
    }

    @Test
    fun `arbitrateToolCalls keeps all screen affecting tools`() {
        val calls =
                listOf(
                        toolCall(name = "delegate_task"),
                        toolCall(name = "mobile_action"),
                        toolCall(name = "scratchpad")
                )

        val result = engine.arbitrateToolCalls(calls)

        assertThat(result.selectedToolCalls.map { it.name })
                .containsExactly("scratchpad", "delegate_task", "mobile_action")
                .inOrder()
        assertThat(result.hasScreenAction).isTrue()
        assertThat(result.droppedToolCalls).isEmpty()
    }

    @Test
    fun `decideCompletion defers completion when screen action exists`() {
        val calls =
                listOf(
                        toolCall(
                                name = "complete_task",
                                arguments = JSONObject("""{"answer":"done"}""")
                        ),
                        toolCall(name = "mobile_action")
                )
        val turnResult = TurnResult(content = "text", toolCalls = calls, isComplete = true)
        val arbitration = engine.arbitrateToolCalls(calls)

        val decision = engine.decideCompletion(turnResult, arbitration)

        assertThat(decision.shouldComplete).isFalse()
        assertThat(decision.summary).isNull()
        assertThat(decision.success).isFalse()
    }

    @Test
    fun `decideCompletion uses answer from complete_task when available`() {
        val calls =
                listOf(
                        toolCall(
                                name = "complete_task",
                                arguments = JSONObject("""{"answer":"final answer"}""")
                        )
                )
        val turnResult = TurnResult(content = "fallback", toolCalls = calls, isComplete = true)
        val arbitration = engine.arbitrateToolCalls(calls)

        val decision = engine.decideCompletion(turnResult, arbitration)

        assertThat(decision.shouldComplete).isTrue()
        assertThat(decision.summary).isEqualTo("final answer")
        assertThat(decision.success).isTrue()
    }

    @Test
    fun `decideCompletion allows completion with cognitive tools only`() {
        val calls =
                listOf(
                        toolCall(name = "write_todos"),
                        toolCall(
                                name = "complete_task",
                                arguments = JSONObject("""{"answer":"done"}""")
                        )
                )
        val turnResult = TurnResult(content = "fallback", toolCalls = calls, isComplete = true)
        val arbitration = engine.arbitrateToolCalls(calls)

        val decision = engine.decideCompletion(turnResult, arbitration)

        assertThat(decision.shouldComplete).isTrue()
        assertThat(decision.summary).isEqualTo("done")
        assertThat(decision.success).isTrue()
    }

    @Test
    fun `decideCompletion marks status failure as unsuccessful completion`() {
        val calls =
                listOf(
                        toolCall(
                                name = "complete_task",
                                arguments = JSONObject("""{"status":"failure","answer":"cannot finish"}""")
                        )
                )
        val turnResult = TurnResult(content = "fallback", toolCalls = calls, isComplete = true)
        val arbitration = engine.arbitrateToolCalls(calls)

        val decision = engine.decideCompletion(turnResult, arbitration)

        assertThat(decision.shouldComplete).isTrue()
        assertThat(decision.success).isFalse()
        assertThat(decision.summary).isEqualTo("cannot finish")
    }

    private fun toolCall(name: String, arguments: JSONObject = JSONObject()): ToolCallRequest {
        return ToolCallRequest(id = "call-$name", name = name, arguments = arguments)
    }
}
