package com.moonkey.androidagent.agent.definition

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.agent.AgentExecutionRole
import org.junit.Test

class AgentDefTest {

    @Test
    fun `standalone definition has expected role and tools`() {
        assertThat(StandaloneRoleDef.executionRole).isEqualTo(AgentExecutionRole.STANDALONE)
        assertThat(StandaloneRoleDef.allowedTools)
            .containsExactly(
                "mobile_action",
                "system_button",
                "wait",
                "open_app",
                "scratchpad",
                "shell",
                "write_todos",
                "complete_task",
                "ask_user",
                "remember_experience"
            )
        assertThat(StandaloneRoleDef.delegatable).isFalse()
        assertThat(StandaloneRoleDef.systemPrompt).contains("standalone Android automation agent")
    }

    @Test
    fun `planner definition has expected role and tools`() {
        assertThat(PlannerRoleDef.executionRole).isEqualTo(AgentExecutionRole.PLANNER)
        assertThat(PlannerRoleDef.allowedTools)
            .containsExactly(
                "open_app",
                "write_todos",
                "scratchpad",
                "delegate_task",
                "complete_task"
            )
        assertThat(PlannerRoleDef.allowedTools).contains("delegate_task")
        assertThat(PlannerRoleDef.systemPrompt).contains("MAIN PLANNER")
    }

    @Test
    fun `executor definition has expected role and tools`() {
        assertThat(ExecutorRoleDef.executionRole).isEqualTo(AgentExecutionRole.EXECUTOR)
        assertThat(ExecutorRoleDef.allowedTools)
            .containsExactly(
                "mobile_action",
                "system_button",
                "wait",
                "open_app",
                "scratchpad",
                "complete_task",
                "ask_user"
            )
        assertThat(ExecutorRoleDef.delegatable).isTrue()
        assertThat(ExecutorRoleDef.systemPrompt).contains("Executor agent")
    }
}
