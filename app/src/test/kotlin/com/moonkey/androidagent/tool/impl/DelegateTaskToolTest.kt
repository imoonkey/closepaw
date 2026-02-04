package com.moonkey.androidagent.tool.impl

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.agent.subagent.AgentDefinition
import com.moonkey.androidagent.agent.subagent.AgentRegistry
import com.moonkey.androidagent.agent.subagent.SubAgentRequest
import com.moonkey.androidagent.agent.subagent.SubAgentResult
import com.moonkey.androidagent.agent.subagent.SubAgentRunner
import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.SessionId
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

    @Test
    fun `validate fails for unknown agent`() {
        val registry = AgentRegistry()
        val tool = DelegateTaskTool(
            sessionId = SessionId("session-1"),
            registry = registry,
            runnerFactory = { error("not used") },
            eventEmitter = { }
        )

        val result = tool.validate(JSONObject().apply {
            put("agent_name", "missing")
            put("query", "tap login")
        })

        assertThat(result is ValidationResult.Invalid).isTrue()
    }

    @Test
    fun `execute emits start and completion events with successful result`() = runTest {
        val definition = AgentDefinition(
            name = "executor",
            description = "Exec",
            systemPrompt = "prompt",
            toolNames = listOf("complete_task")
        )
        val registry = AgentRegistry().apply { register(definition) }
        val capturedRequests = mutableListOf<SubAgentRequest>()
        val events = mutableListOf<AgentEvent>()

        val tool = DelegateTaskTool(
            sessionId = SessionId("session-1"),
            registry = registry,
            runnerFactory = {
                SubAgentRunner { request ->
                    capturedRequests.add(request)
                    SubAgentResult(success = true, message = "done")
                }
            },
            eventEmitter = { events.add(it) }
        )

        val invocation = tool.createInvocation(JSONObject().apply {
            put("agent_name", "executor")
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
        assertThat(events.filterIsInstance<AgentEvent.SubAgentStarted>()).hasSize(1)
        assertThat(events.filterIsInstance<AgentEvent.SubAgentCompleted>()).hasSize(1)
    }

    @Test
    fun `execute wraps failed sub-agent result as success text output`() = runTest {
        val definition = AgentDefinition(
            name = "executor",
            description = "Exec",
            systemPrompt = "prompt",
            toolNames = listOf("complete_task")
        )
        val registry = AgentRegistry().apply { register(definition) }

        val tool = DelegateTaskTool(
            sessionId = SessionId("session-1"),
            registry = registry,
            runnerFactory = {
                SubAgentRunner { _ ->
                    SubAgentResult(success = false, message = "Timeout after 1000ms")
                }
            },
            eventEmitter = { }
        )

        val invocation = tool.createInvocation(JSONObject().apply {
            put("agent_name", "executor")
            put("query", "tap login")
        })

        val result = invocation.execute(TestToolExecutionContext())

        assertThat(result is ToolExecutionResult.Success).isTrue()
        val success = result as ToolExecutionResult.Success
        assertThat(success.output).isEqualTo("Sub-agent failed: Timeout after 1000ms")
    }
}

private class TestToolExecutionContext(
    override val callId: String? = null
) : ToolExecutionContext {
    override val platform = FakeAndroidPlatform()
    override val currentSnapshot = null
    override fun isCancelled(): Boolean = false
}
