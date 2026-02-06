package com.moonkey.androidagent.agent.definition

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.protocol.AgentMode
import org.junit.Test

class AgentDefRegistryTest {

    @Test
    fun `main definition resolves to standalone for basic mode`() {
        assertThat(AgentDefRegistry.mainFor(AgentMode.BASIC)).isEqualTo(StandaloneAgentDef)
    }

    @Test
    fun `main definition resolves to planner for pro mode`() {
        assertThat(AgentDefRegistry.mainFor(AgentMode.PRO)).isEqualTo(PlannerAgentDef)
    }

    @Test
    fun `executor definition resolves consistently`() {
        assertThat(AgentDefRegistry.executor()).isEqualTo(ExecutorAgentDef)
    }
}
