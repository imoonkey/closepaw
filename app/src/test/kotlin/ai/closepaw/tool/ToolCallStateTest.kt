package ai.closepaw.tool

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

class ToolCallStateTest {

    @Test
    fun `isTerminal returns true for terminal states`() {
        val params = JSONObject()
        val success = ToolCallState.Success(
            callId = "c1",
            toolName = "tool",
            params = params,
            result = ToolExecutionResult.Success("ok")
        )
        val error = ToolCallState.Error(
            callId = "c2",
            toolName = "tool",
            params = params,
            error = "bad"
        )
        val cancelled = ToolCallState.Cancelled(
            callId = "c3",
            toolName = "tool",
            params = params,
            reason = "nope"
        )

        assertThat(success.isTerminal()).isTrue()
        assertThat(error.isTerminal()).isTrue()
        assertThat(cancelled.isTerminal()).isTrue()
    }

    @Test
    fun `isTerminal returns false for non-terminal states`() {
        val params = JSONObject()
        val validating = ToolCallState.Validating("c1", "tool", params)
        val awaiting = ToolCallState.AwaitingApproval(
            callId = "c2",
            toolName = "tool",
            params = params,
            invocation = FakeInvocation(),
            description = "desc"
        )
        val scheduled = ToolCallState.Scheduled("c3", "tool", params, FakeInvocation())
        val executing = ToolCallState.Executing("c4", "tool", params, FakeInvocation())

        assertThat(validating.isTerminal()).isFalse()
        assertThat(awaiting.isTerminal()).isFalse()
        assertThat(scheduled.isTerminal()).isFalse()
        assertThat(executing.isTerminal()).isFalse()
    }
}

private class FakeInvocation : ToolInvocation {
    override val toolName: String = "tool"
    override val params: JSONObject = JSONObject()

    override fun getDescription(): String = "fake"

    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        return ToolExecutionResult.Success("ok")
    }
}
