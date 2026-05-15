package ai.closepaw.agent.definition

import ai.closepaw.protocol.AgentMode

internal object AgentDefRegistry {

    private val allRoles = listOf(DefaultRoleDef)

    /** Single main agent role. Mode parameter no longer affects selection. */
    val main: AgentRoleDef = DefaultRoleDef

    /**
     * Backward-compat shim while sibling refactors (uam-remove-agentmode-enum) land.
     * The mode parameter is ignored — there is only one role now.
     */
    @Deprecated("Use AgentDefRegistry.main", ReplaceWith("AgentDefRegistry.main"))
    fun mainFor(@Suppress("UNUSED_PARAMETER") mode: AgentMode): AgentRoleDef = main

    fun delegatableRoles(): List<AgentRoleDef> = allRoles.filter { it.delegatable }
}
