package com.moonkey.androidagent.agent.definition

import com.moonkey.androidagent.protocol.AgentMode

internal object AgentDefRegistry {
    fun mainFor(mode: AgentMode): AgentDef {
        return when (mode) {
            AgentMode.BASIC -> StandaloneAgentDef
            AgentMode.PRO -> PlannerAgentDef
        }
    }

    fun executor(): AgentDef = ExecutorAgentDef
}
