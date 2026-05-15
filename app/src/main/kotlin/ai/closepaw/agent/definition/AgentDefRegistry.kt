package ai.closepaw.agent.definition

internal object AgentDefRegistry {

    private val allRoles = listOf(DefaultRoleDef)

    /** Single main agent role. */
    val main: AgentRoleDef = DefaultRoleDef

    fun delegatableRoles(): List<AgentRoleDef> = allRoles.filter { it.delegatable }
}
