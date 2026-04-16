package com.moonkey.androidagent.agent

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.agent.cognition.policy.TurnToolPolicy
import com.moonkey.androidagent.tool.ToolCallResult
import org.json.JSONObject
import org.junit.Test

class TurnOutcomeDecisionTest {
    private val policy = TurnToolPolicy()

    @Test
    fun `complete_task executed normally yields Complete`() {
        val complete = toolCall("complete_task", JSONObject("""{"answer":"done"}"""))
        val calls = listOf(complete)
        val arbitration = policy.arbitrateToolCalls(calls)
        val turnResult = TurnResult(content = null, toolCalls = calls, isComplete = true)
        val execution = ExecutionPhaseResult(
            executedToolIds = setOf(complete.id),
            terminatedEarly = false,
            lastTerminalResult = ToolCallResult.Success(complete.id, "ok")
        )

        val outcome = decideTurnOutcome(policy, turnResult, arbitration, execution)

        assertThat(outcome).isInstanceOf(TurnOutcome.Complete::class.java)
        assertThat((outcome as TurnOutcome.Complete).message).isEqualTo("done")
        assertThat(outcome.success).isTrue()
    }

    @Test
    fun `failure before complete_task yields Error not Complete`() {
        val screen = toolCall("mobile_action")
        val complete = toolCall("complete_task", JSONObject("""{"answer":"done"}"""))
        val calls = listOf(screen, complete)
        val arbitration = policy.arbitrateToolCalls(calls)
        val turnResult = TurnResult(content = null, toolCalls = calls, isComplete = true)
        val execution = ExecutionPhaseResult(
            executedToolIds = setOf(screen.id),
            terminatedEarly = true,
            lastTerminalResult = ToolCallResult.Error(screen.id, "tap missed target")
        )

        val outcome = decideTurnOutcome(policy, turnResult, arbitration, execution)

        assertThat(outcome).isInstanceOf(TurnOutcome.Error::class.java)
        assertThat((outcome as TurnOutcome.Error).message).isEqualTo("tap missed target")
    }

    @Test
    fun `cancellation before complete_task yields Cancelled`() {
        val screen = toolCall("mobile_action")
        val complete = toolCall("complete_task", JSONObject("""{"answer":"done"}"""))
        val calls = listOf(screen, complete)
        val arbitration = policy.arbitrateToolCalls(calls)
        val turnResult = TurnResult(content = null, toolCalls = calls, isComplete = true)
        val execution = ExecutionPhaseResult(
            executedToolIds = setOf(screen.id),
            terminatedEarly = true,
            lastTerminalResult = ToolCallResult.Cancelled(screen.id, "user")
        )

        val outcome = decideTurnOutcome(policy, turnResult, arbitration, execution)

        assertThat(outcome).isEqualTo(TurnOutcome.Cancelled)
    }

    @Test
    fun `no complete_task planned and tools succeed yields Continue`() {
        val screen = toolCall("mobile_action")
        val calls = listOf(screen)
        val arbitration = policy.arbitrateToolCalls(calls)
        val turnResult = TurnResult(content = null, toolCalls = calls, isComplete = false)
        val execution = ExecutionPhaseResult(
            executedToolIds = setOf(screen.id),
            terminatedEarly = false,
            lastTerminalResult = ToolCallResult.Success(screen.id, "ok")
        )

        val outcome = decideTurnOutcome(policy, turnResult, arbitration, execution)

        assertThat(outcome).isEqualTo(TurnOutcome.Continue)
    }

    @Test
    fun `complete_task planned but not executed yields Error`() {
        // Pathological: complete_task was planned and wasn't dropped by arbitration,
        // but somehow never executed. Treat as Error, not Complete.
        val complete = toolCall("complete_task", JSONObject("""{"answer":"done"}"""))
        val calls = listOf(complete)
        val arbitration = policy.arbitrateToolCalls(calls)
        val turnResult = TurnResult(content = null, toolCalls = calls, isComplete = true)
        val execution = ExecutionPhaseResult(
            executedToolIds = emptySet(),
            terminatedEarly = false,
            lastTerminalResult = null
        )

        val outcome = decideTurnOutcome(policy, turnResult, arbitration, execution)

        assertThat(outcome).isInstanceOf(TurnOutcome.Error::class.java)
    }

    @Test
    fun `complete_task dropped by arbitration due to screen action yields Continue`() {
        val screen = toolCall("mobile_action")
        val complete = toolCall("complete_task", JSONObject("""{"answer":"done"}"""))
        val calls = listOf(screen, complete)
        val arbitration = policy.arbitrateToolCalls(calls)
        // Sanity: arbitration dropped complete_task because of the screen action.
        assertThat(arbitration.selectedToolCalls.map { it.id }).doesNotContain(complete.id)
        val turnResult = TurnResult(content = null, toolCalls = calls, isComplete = true)
        val execution = ExecutionPhaseResult(
            executedToolIds = setOf(screen.id),
            terminatedEarly = false,
            lastTerminalResult = ToolCallResult.Success(screen.id, "ok")
        )

        val outcome = decideTurnOutcome(policy, turnResult, arbitration, execution)

        assertThat(outcome).isEqualTo(TurnOutcome.Continue)
    }

    @Test
    fun `complete_task with status failure yields Complete with success=false`() {
        val complete = toolCall(
            "complete_task",
            JSONObject("""{"status":"failure","answer":"cannot do it"}""")
        )
        val calls = listOf(complete)
        val arbitration = policy.arbitrateToolCalls(calls)
        val turnResult = TurnResult(content = null, toolCalls = calls, isComplete = true)
        val execution = ExecutionPhaseResult(
            executedToolIds = setOf(complete.id),
            terminatedEarly = false,
            lastTerminalResult = ToolCallResult.Success(complete.id, "ok")
        )

        val outcome = decideTurnOutcome(policy, turnResult, arbitration, execution)

        assertThat(outcome).isInstanceOf(TurnOutcome.Complete::class.java)
        assertThat((outcome as TurnOutcome.Complete).success).isFalse()
    }

    private fun toolCall(name: String, arguments: JSONObject = JSONObject()): ToolCallRequest {
        return ToolCallRequest(id = "call-$name", name = name, arguments = arguments)
    }
}
