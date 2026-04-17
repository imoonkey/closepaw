package ai.closepaw.tool.impl

import com.google.common.truth.Truth.assertThat
import ai.closepaw.agent.AgentEventDispatcher
import ai.closepaw.protocol.SessionId
import ai.closepaw.session.UserResponseChannel
import ai.closepaw.test.FakeAndroidPlatform
import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ValidationResult
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test

class AskUserToolTest {

    private fun buildContext(callId: String? = "call-1"): ToolExecutionContext {
        val cid = callId
        return object : ToolExecutionContext {
            override val callId: String? = cid
            override val platform = FakeAndroidPlatform()
            override val currentSnapshot = null
            override fun isCancelled(): Boolean = false
        }
    }

    private fun buildDispatcher(): AgentEventDispatcher =
        AgentEventDispatcher(SessionId("test-session")) { /* no-op emitter */ }

    private fun validParams() = JSONObject()
        .put("type", "question")
        .put("message", "What is your name?")

    @Test
    fun `second ask_user request rejected while first is pending`() = runTest {
        val channel = UserResponseChannel()
        val tool = AskUserTool(channel, buildDispatcher())

        // First invocation: start and let it suspend.
        val first = async {
            tool.createInvocation(validParams()).execute(buildContext("call-1"))
        }
        // Yield until the first coroutine has registered as pending.
        while (!channel.hasPending) delay(1)

        // Second validate should reject due to pending.
        val secondValidation = tool.validate(validParams())
        assertThat(secondValidation).isInstanceOf(ValidationResult.Invalid::class.java)
        assertThat((secondValidation as ValidationResult.Invalid).errors.joinToString())
            .contains("pending")

        // Clean up first request.
        channel.cancel()
        first.await()
    }

    @Test
    fun `timeout produces formatted timeout output`() = runTest {
        val channel = UserResponseChannel()
        val tool = AskUserTool(channel, buildDispatcher())

        val invocation = tool.createInvocation(validParams())
        val result = invocation.execute(buildContext("call-timeout"))

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        val output = (result as ToolExecutionResult.Success).output
        assertThat(output).contains("did not respond")
        assertThat(output).contains("timeout")
        assertThat(channel.hasPending).isFalse()
    }

    @Test
    fun `cancellation maps to Cancelled result`() = runTest {
        val channel = UserResponseChannel()
        val tool = AskUserTool(channel, buildDispatcher())

        val deferred = async {
            tool.createInvocation(validParams()).execute(buildContext("call-cancel"))
        }
        while (!channel.hasPending) delay(1)

        channel.cancel()
        val result = deferred.await()

        assertThat(result).isInstanceOf(ToolExecutionResult.Cancelled::class.java)
        assertThat((result as ToolExecutionResult.Cancelled).reason).contains("ask_user")
    }

    @Test
    fun `successful user response forwarded as tool output`() = runTest {
        val channel = UserResponseChannel()
        val tool = AskUserTool(channel, buildDispatcher())

        val deferred = async {
            tool.createInvocation(validParams()).execute(buildContext("call-ok"))
        }
        while (!channel.hasPending) delay(1)

        assertThat(channel.deliver("call-ok", "Alice")).isTrue()
        val result = deferred.await()

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        val output = (result as ToolExecutionResult.Success).output
        assertThat(output).contains("Alice")
        assertThat(output).contains("User answered")
    }

    @Test
    fun `successful action response signals completion`() = runTest {
        val channel = UserResponseChannel()
        val tool = AskUserTool(channel, buildDispatcher())
        val params = JSONObject()
            .put("type", "action")
            .put("message", "Please complete captcha")

        val deferred = async {
            tool.createInvocation(params).execute(buildContext("call-action"))
        }
        while (!channel.hasPending) delay(1)

        channel.deliver("call-action", "done")
        val result = deferred.await()

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        val output = (result as ToolExecutionResult.Success).output
        assertThat(output).contains("completed the requested action")
    }
}
