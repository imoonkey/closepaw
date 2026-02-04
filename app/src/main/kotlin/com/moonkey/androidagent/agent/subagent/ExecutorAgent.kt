package com.moonkey.androidagent.agent.subagent

import com.moonkey.androidagent.agent.cognition.prompt.ExecutorPromptTemplate

/**
 * Built-in executor that grounds high-level instructions into UI actions.
 */
object ExecutorAgent {
    val definition: AgentDefinition = AgentDefinition(
        name = "executor",
        description = "Execute ONE atomic UI action on the current screen",
        systemPrompt = ExecutorPromptTemplate.systemPrompt,
        // app_control is available here so executor can recover when delegation lands outside target app.
        toolNames = listOf("mobile_action", "app_control", "scratchpad", "complete_task"),
        maxTurns = 5,  // Reduced from 10 - atomic actions should complete in 1-3 turns
        timeoutMs = 30_000  // Reduced from 60s - atomic actions are fast
    )
}
