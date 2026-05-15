package ai.closepaw.agent.definition

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AgentDefRegistryTest {

    @Test
    fun `main role is the default role`() {
        assertThat(AgentDefRegistry.main).isEqualTo(DefaultRoleDef)
    }

    @Test
    fun `delegatable roles is the default role only`() {
        val roles = AgentDefRegistry.delegatableRoles()
        assertThat(roles).hasSize(1)
        assertThat(roles.single()).isEqualTo(DefaultRoleDef)
    }
}
