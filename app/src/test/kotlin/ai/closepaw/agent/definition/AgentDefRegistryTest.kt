package ai.closepaw.agent.definition

import com.google.common.truth.Truth.assertThat
import ai.closepaw.protocol.AgentMode
import org.junit.Test

class AgentDefRegistryTest {

    @Test
    fun `main definition resolves to standalone for basic mode`() {
        assertThat(AgentDefRegistry.mainFor(AgentMode.BASIC)).isEqualTo(StandaloneRoleDef)
    }

    @Test
    fun `main definition resolves to planner for pro mode`() {
        assertThat(AgentDefRegistry.mainFor(AgentMode.PRO)).isEqualTo(PlannerRoleDef)
    }

    @Test
    fun `delegatable roles includes executor`() {
        val roles = AgentDefRegistry.delegatableRoles()
        assertThat(roles).hasSize(1)
        assertThat(roles.single()).isEqualTo(ExecutorRoleDef)
    }
}
