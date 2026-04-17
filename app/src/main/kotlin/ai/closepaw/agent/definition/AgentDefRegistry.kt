package ai.closepaw.agent.definition

import ai.closepaw.protocol.AgentMode

internal object AgentDefRegistry {

    private val allRoles = listOf(StandaloneRoleDef, PlannerRoleDef, ExecutorRoleDef)

    fun mainFor(mode: AgentMode): AgentRoleDef {
        return when (mode) {
            AgentMode.BASIC -> StandaloneRoleDef
            AgentMode.PRO -> PlannerRoleDef
        }
    }

    fun delegatableRoles(): List<AgentRoleDef> = allRoles.filter { it.delegatable }
}
