package com.moonkey.androidagent.agent.cognition

internal enum class AgentRole {
    PLANNER,
    EXECUTOR;

    companion object {
        fun fromToolNames(toolNames: Set<String>): AgentRole {
            val hasDelegate = "delegate_task" in toolNames
            val hasMobileAction = "mobile_action" in toolNames
            return if (hasDelegate && !hasMobileAction) {
                PLANNER
            } else {
                EXECUTOR
            }
        }
    }
}
