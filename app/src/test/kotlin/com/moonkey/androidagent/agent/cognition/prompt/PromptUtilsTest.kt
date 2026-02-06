package com.moonkey.androidagent.agent.cognition.prompt

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.agent.cognition.context.LoopWarning
import com.moonkey.androidagent.agent.cognition.context.LoopWarningSeverity
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.protocol.Todo
import com.moonkey.androidagent.protocol.TodoStatus
import org.junit.Test

class PromptUtilsTest {

    @Test
    fun `buildUserMessage includes screen tools`() {
        val context =
                PromptContext(
                        snapshot = ScreenSnapshot(timestamp = 1L, elements = emptyList()),
                        visibleToolNames = setOf("delegate_task", "complete_task"),
                        llmBackend = LLMBackendType.OPENAI
                )

        val userMessage = PromptUtils.buildUserMessage(context)

        assertThat(userMessage.text).contains("Current screen state (0 elements):")
        assertThat(userMessage.text).contains("Available tools: complete_task, delegate_task")
    }

    @Test
    fun `buildUserMessage formats filtered tools correctly`() {
        // PromptUtils assumes the list passed is already filtered, so we test formatting
        val context =
                PromptContext(
                        snapshot = ScreenSnapshot(timestamp = 1L, elements = emptyList()),
                        visibleToolNames =
                                setOf(
                                        "delegate_task",
                                        "complete_task",
                                        "write_todos",
                                        "scratchpad"
                                ),
                        llmBackend = LLMBackendType.OPENAI
                )

        val userMessage = PromptUtils.buildUserMessage(context)

        assertThat(userMessage.text).contains("delegate_task")
        assertThat(userMessage.text).contains("write_todos")
        assertThat(userMessage.text).contains("scratchpad")
        assertThat(userMessage.text).contains("complete_task")
    }

    @Test
    fun `buildUserMessage handles empty tools`() {
        val context =
                PromptContext(
                        snapshot = ScreenSnapshot(timestamp = 1L, elements = emptyList()),
                        visibleToolNames = emptySet(),
                        llmBackend = LLMBackendType.OPENAI
                )

        val userMessage = PromptUtils.buildUserMessage(context)

        assertThat(userMessage.text).contains("Available tools:")
        assertThat(userMessage.text).doesNotContain("delegate_task")
    }

    @Test
    fun `buildSystemPrompt uses provided prompt`() {
        val prompt = PromptUtils.buildSystemPrompt(basePrompt = "custom planner prompt")

        assertThat(prompt).contains("custom planner prompt")
    }

    @Test
    fun `buildSystemPrompt throws when prompt missing`() {
        val thrown = runCatching { PromptUtils.buildSystemPrompt(basePrompt = null) }.exceptionOrNull()
        assertThat(thrown).isNotNull()
        assertThat(thrown).hasMessageThat().contains("System prompt is required")
    }

    @Test
    fun `buildUserMessage appends loop and memory reminders`() {
        val todos =
                listOf(
                        Todo(description = "Open Gmail", status = TodoStatus.IN_PROGRESS),
                        Todo(description = "Count unread emails", status = TodoStatus.PENDING)
                )
        val scratchpadKeys = listOf("email_count")

        val loopWarning =
                LoopWarning(
                        message = "Screen unchanged for 3 turns.",
                        severity = LoopWarningSeverity.CRITICAL
                )

        val context =
                PromptContext(
                        snapshot = ScreenSnapshot(timestamp = 1L, elements = emptyList()),
                        visibleToolNames = setOf("delegate_task", "write_todos", "scratchpad"),
                        llmBackend = LLMBackendType.OPENAI,
                        loopWarning = loopWarning,
                        todos = todos,
                        scratchpadKeys = scratchpadKeys
                )

        val userMessage = PromptUtils.buildUserMessage(context)

        assertThat(userMessage.text).contains("LOOP WARNING")
        assertThat(userMessage.text).contains("Todo status")
        assertThat(userMessage.text).contains("Scratchpad has 1 key")
    }

    @Test
    fun `buildUserMessage skips todo reminder when all todos are completed`() {
        val todos =
                listOf(
                        Todo(description = "Open Gmail", status = TodoStatus.COMPLETED),
                        Todo(description = "Read inbox", status = TodoStatus.CANCELLED)
                )

        val context =
                PromptContext(
                        snapshot = ScreenSnapshot(timestamp = 1L, elements = emptyList()),
                        visibleToolNames = setOf("delegate_task", "write_todos"),
                        llmBackend = LLMBackendType.OPENAI,
                        todos = todos
                )

        val userMessage = PromptUtils.buildUserMessage(context)

        assertThat(userMessage.text).doesNotContain("Todo status:")
    }
}
