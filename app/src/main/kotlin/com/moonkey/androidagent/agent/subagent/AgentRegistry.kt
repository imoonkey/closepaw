package com.moonkey.androidagent.agent.subagent

/**
 * Simple in-memory registry for sub-agent definitions.
 */
class AgentRegistry {
    private val agents = linkedMapOf<String, AgentDefinition>()

    fun register(definition: AgentDefinition) {
        agents[definition.name] = definition
    }

    fun get(name: String): AgentDefinition? = agents[name]

    fun getAll(): List<AgentDefinition> = agents.values.toList()

    fun getDirectoryPrompt(): String =
        agents.values.joinToString("\n") { "- ${it.name}: ${it.description}" }

    companion object {
        fun createDefault(): AgentRegistry = AgentRegistry().apply {
            register(ExecutorAgent.definition)
        }
    }
}
