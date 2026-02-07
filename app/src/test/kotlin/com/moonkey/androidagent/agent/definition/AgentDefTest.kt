package com.moonkey.androidagent.agent.definition

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.agent.AgentExecutionRole
import org.junit.Test

class AgentDefTest {

    @Test
    fun `standalone definition has expected role and tools`() {
        assertThat(StandaloneAgentDef.executionRole).isEqualTo(AgentExecutionRole.STANDALONE)
        assertThat(StandaloneAgentDef.allowedTools)
            .containsExactly(
                "mobile_action",
                "system_button",
                "wait",
                "app_control",
                "scratchpad",
                "write_todos",
                "complete_task"
            )
        assertThat(StandaloneAgentDef.requiresDelegationToolRegistration).isFalse()
        assertThat(StandaloneAgentDef.systemPrompt).contains("standalone Android automation agent")
    }

    @Test
    fun `planner definition has expected role and tools`() {
        assertThat(PlannerAgentDef.executionRole).isEqualTo(AgentExecutionRole.PLANNER)
        assertThat(PlannerAgentDef.allowedTools)
            .containsExactly(
                "app_control",
                "write_todos",
                "scratchpad",
                "delegate_task",
                "complete_task"
            )
        assertThat(PlannerAgentDef.requiresDelegationToolRegistration).isTrue()
        assertThat(PlannerAgentDef.systemPrompt).contains("MAIN PLANNER")
    }

    @Test
    fun `executor definition has expected role and tools`() {
        assertThat(ExecutorAgentDef.executionRole).isEqualTo(AgentExecutionRole.EXECUTOR)
        assertThat(ExecutorAgentDef.allowedTools)
            .containsExactly(
                "mobile_action",
                "system_button",
                "wait",
                "app_control",
                "scratchpad",
                "complete_task"
            )
        assertThat(ExecutorAgentDef.requiresDelegationToolRegistration).isFalse()
        assertThat(ExecutorAgentDef.systemPrompt).contains("Executor agent")
    }
}
