package com.moonkey.androidagent.agent.subagent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AgentRegistryTest {

    @Test
    fun `register and get returns definition`() {
        val registry = AgentRegistry()
        val definition = AgentDefinition(
            name = "test_agent",
            description = "Test agent",
            systemPrompt = "You are test",
            toolNames = listOf("complete_task")
        )

        registry.register(definition)

        assertThat(registry.get("test_agent")).isEqualTo(definition)
    }

    @Test
    fun `createDefault includes executor definition`() {
        val registry = AgentRegistry.createDefault()

        val executor = registry.get("executor")
        assertThat(executor).isNotNull()
        assertThat(executor?.toolNames).contains("complete_task")
    }

    @Test
    fun `directory prompt includes registered agents`() {
        val registry = AgentRegistry().apply {
            register(
                AgentDefinition(
                    name = "one",
                    description = "First",
                    systemPrompt = "one",
                    toolNames = listOf("complete_task")
                )
            )
            register(
                AgentDefinition(
                    name = "two",
                    description = "Second",
                    systemPrompt = "two",
                    toolNames = listOf("complete_task")
                )
            )
        }

        val prompt = registry.getDirectoryPrompt()

        assertThat(prompt).contains("- one: First")
        assertThat(prompt).contains("- two: Second")
    }
}
