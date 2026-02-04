package com.moonkey.androidagent.agent.cognition.policy

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.agent.ToolCallRequest
import com.moonkey.androidagent.agent.TurnResult
import com.moonkey.androidagent.agent.cognition.profile.BuiltinCognitionProfiles
import org.json.JSONObject
import org.junit.Test

class TurnPolicyEngineTest {
    private val engine = DefaultTurnPolicyEngine()

    @Test
    fun `arbitrateToolCalls prefers non completion call when mixed with complete_task`() {
        val calls =
            listOf(
                toolCall(name = "complete_task", arguments = JSONObject("""{"answer":"done"}""")),
                toolCall(name = "delegate_task")
            )

        val result = engine.arbitrateToolCalls(calls, BuiltinCognitionProfiles.baseline)

        assertThat(result.selectedToolCalls).hasSize(1)
        assertThat(result.selectedToolCalls.first().name).isEqualTo("delegate_task")
        assertThat(result.hasCompletionTool).isTrue()
        assertThat(result.hasNonCompletionTool).isTrue()
        assertThat(result.droppedToolCalls).hasSize(1)
    }

    @Test
    fun `arbitrateToolCalls keeps completion when it is only call`() {
        val calls = listOf(toolCall(name = "complete_task"))

        val result = engine.arbitrateToolCalls(calls, BuiltinCognitionProfiles.baseline)

        assertThat(result.selectedToolCalls).hasSize(1)
        assertThat(result.selectedToolCalls.first().name).isEqualTo("complete_task")
        assertThat(result.hasCompletionTool).isTrue()
        assertThat(result.hasNonCompletionTool).isFalse()
    }

    @Test
    fun `decideCompletion defers completion when non completion tool exists`() {
        val calls =
            listOf(
                toolCall(name = "complete_task", arguments = JSONObject("""{"answer":"done"}""")),
                toolCall(name = "delegate_task")
            )
        val turnResult = TurnResult(content = "text", toolCalls = calls, isComplete = true)
        val arbitration = engine.arbitrateToolCalls(calls, BuiltinCognitionProfiles.baseline)

        val decision = engine.decideCompletion(turnResult, arbitration, BuiltinCognitionProfiles.baseline)

        assertThat(decision.shouldComplete).isFalse()
        assertThat(decision.summary).isNull()
    }

    @Test
    fun `decideCompletion uses answer from complete_task when available`() {
        val calls =
            listOf(
                toolCall(name = "complete_task", arguments = JSONObject("""{"answer":"final answer"}"""))
            )
        val turnResult = TurnResult(content = "fallback", toolCalls = calls, isComplete = true)
        val arbitration = engine.arbitrateToolCalls(calls, BuiltinCognitionProfiles.baseline)

        val decision = engine.decideCompletion(turnResult, arbitration, BuiltinCognitionProfiles.baseline)

        assertThat(decision.shouldComplete).isTrue()
        assertThat(decision.summary).isEqualTo("final answer")
    }

    private fun toolCall(name: String, arguments: JSONObject = JSONObject()): ToolCallRequest {
        return ToolCallRequest(
            id = "call-$name",
            name = name,
            arguments = arguments
        )
    }
}
