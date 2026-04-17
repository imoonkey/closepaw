package com.moonkey.androidagent.tool.impl

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.agent.AgentEventDispatcher
import com.moonkey.androidagent.agent.AgentExecutionRole
import com.moonkey.androidagent.agent.definition.AgentRoleDef
import com.moonkey.androidagent.agent.subagent.SubAgentRequest
import com.moonkey.androidagent.agent.subagent.SubAgentResult
import com.moonkey.androidagent.agent.subagent.SubAgentRunner
import com.moonkey.androidagent.protocol.*
import com.moonkey.androidagent.test.FakeAndroidPlatform
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ValidationResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DelegateTaskToolTest {

    private val executorRole = AgentRoleDef(
        name = "executor",
        executionRole = AgentExecutionRole.EXECUTOR,
        systemPrompt = "prompt",
        allowedTools = setOf("complete_task"),
        delegatable = true,
        description = "Exec"
    )

    @Test
    fun `validate fails when query missing`() {
        val tool = DelegateTaskTool(
            delegatableRoles = listOf(executorRole),
            runnerFactory = { error("not used") },
            eventDispatcher = AgentEventDispatcher(SessionId("session-1")) { }
        )

        val result = tool.validate(JSONObject())

        assertThat(result is ValidationResult.Invalid).isTrue()
    }

    @Test
    fun `execute emits start and completion events with successful result`() = runTest {
        val capturedRequests = mutableListOf<SubAgentRequest>()
        val events = mutableListOf<AgentEvent>()

        val tool = DelegateTaskTool(
            delegatableRoles = listOf(executorRole),
            runnerFactory = {
                SubAgentRunner { request ->
                    capturedRequests.add(request)
                    SubAgentResult(success = true, message = "done")
                }
            },
            eventDispatcher = AgentEventDispatcher(SessionId("session-1")) { events.add(it) }
        )

        val invocation = tool.createInvocation(JSONObject().apply {
            put("query", "tap login")
            put("current_subgoal", "open sign in")
            put("important_notes", JSONArray(listOf("button near bottom", "do not open settings")))
        })

        val result = invocation.execute(TestToolExecutionContext(callId = "call-123"))

        assertThat(result is ToolExecutionResult.Success).isTrue()
        val success = result as ToolExecutionResult.Success
        assertThat(success.output).isEqualTo("done")
        assertThat(capturedRequests).hasSize(1)
        assertThat(capturedRequests.single().query).isEqualTo("tap login")
        assertThat(capturedRequests.single().currentSubgoal).isEqualTo("open sign in")
        assertThat(capturedRequests.single().importantNotes).containsExactly(
            "button near bottom",
            "do not open settings"
        )
        assertThat(capturedRequests.single().delegationCallId).isEqualTo("call-123")
        assertThat(events.filterIsInstance<SubAgentStarted>()).hasSize(1)
        assertThat(events.filterIsInstance<SubAgentCompleted>()).hasSize(1)
    }

    @Test
    fun `execute returns failure when sub-agent fails`() = runTest {
        val tool = DelegateTaskTool(
            delegatableRoles = listOf(executorRole),
            runnerFactory = {
                SubAgentRunner { _ ->
                    SubAgentResult(success = false, message = "Timeout after 1000ms")
                }
            },
            eventDispatcher = AgentEventDispatcher(SessionId("session-1")) { }
        )

        val invocation = tool.createInvocation(JSONObject().apply {
            put("query", "tap login")
        })

        val result = invocation.execute(TestToolExecutionContext())

        assertThat(result is ToolExecutionResult.Failure).isTrue()
        val failure = result as ToolExecutionResult.Failure
        assertThat(failure.error).isEqualTo("Sub-agent failed: Timeout after 1000ms")
    }
}

private class TestToolExecutionContext(
    override val callId: String? = null
) : ToolExecutionContext {
    override val platform = FakeAndroidPlatform()
    override val currentSnapshot = null
    override fun isCancelled(): Boolean = false
}
