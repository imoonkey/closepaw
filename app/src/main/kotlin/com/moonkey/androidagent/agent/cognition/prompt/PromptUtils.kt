package com.moonkey.androidagent.agent.cognition.prompt

import com.moonkey.androidagent.agent.cognition.context.LoopWarning
import com.moonkey.androidagent.agent.cognition.context.LoopWarningSeverity
import com.moonkey.androidagent.model.ScreenImage
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.protocol.Todo
import com.moonkey.androidagent.protocol.TodoStatus

/** Pure data context required to build the user message for the agent. */
/** Pure data context required to build the user message for the agent. */
internal data class PromptContext(
        val snapshot: ScreenSnapshot,
        val visibleToolNames: Set<String>,
        val llmBackend: LLMBackendType,
        val loopWarning: LoopWarning? = null,
        val systemReminders: List<String> = emptyList(),
        // Data required for dynamic reminders and context blocks
        val todos: List<Todo> = emptyList(),
        val scratchpadKeys: List<String> = emptyList(),
        // Pre-formatted markdown context blocks
        val additionalContextBlocks: List<String> = emptyList()
)

data class UserMessage(val text: String, val image: ScreenImage?)

internal object PromptUtils {

    fun buildSystemPrompt(basePrompt: String?): String {
        return requireNotNull(basePrompt) {
            "System prompt is required and must be provided by AgentDef."
        }
    }

    fun buildUserMessage(context: PromptContext): UserMessage {
        val baseText = buildBaseText(context)
        val reminders = buildReminders(context)

        val finalText =
                if (reminders.isNotEmpty()) {
                    "$baseText\n\n${reminders.joinToString(separator = "\n\n")}"
                } else {
                    baseText
                }

        return UserMessage(
                text = finalText,
                image =
                        context.snapshot.image?.takeIf {
                            context.llmBackend == LLMBackendType.OPENAI
                        }
        )
    }

    private fun buildBaseText(context: PromptContext): String {
        val screenJson = Perceptor.toPromptJson(context.snapshot)
        val toolNames = context.visibleToolNames.sorted().joinToString(", ")

        val imageHint =
                if (context.snapshot.image != null && context.llmBackend == LLMBackendType.OPENAI) {
                    "\nScreenshot attached (compressed)."
                } else {
                    ""
                }

        val contextBlocks =
                if (context.additionalContextBlocks.isNotEmpty()) {
                    "\n" + context.additionalContextBlocks.joinToString("\n\n")
                } else {
                    ""
                }

        return """
            Current screen state (${context.snapshot.elements.size} elements):
            ```json
            $screenJson
            ```
            
            Available tools: $toolNames
            $contextBlocks
            $imageHint
            What action should I take next to achieve the goal?
        """.trimIndent()
    }

    private fun buildReminders(context: PromptContext): List<String> {
        return buildList {
            context.loopWarning?.let { add(formatLoopWarning(it)) }
            context.systemReminders.map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
            buildTodoReminder(context.todos)?.let { add(it) }
            buildScratchpadReminder(context.scratchpadKeys)?.let { add(it) }
        }
    }

    private fun formatLoopWarning(loopWarning: LoopWarning): String {
        val priorityLabel =
                if (loopWarning.severity == LoopWarningSeverity.CRITICAL) "HIGH" else "MEDIUM"
        return """
            <system_reminder>
            LOOP WARNING ($priorityLabel): ${loopWarning.message}
            </system_reminder>
        """.trimIndent()
    }

    private fun buildTodoReminder(todos: List<Todo>): String? {
        if (todos.isEmpty()) return null

        val actionableTodos =
                todos.filterNot { todo ->
                    todo.status == TodoStatus.COMPLETED || todo.status == TodoStatus.CANCELLED
                }
        if (actionableTodos.isEmpty()) return null

        val inProgress =
                actionableTodos.firstOrNull { it.status == TodoStatus.IN_PROGRESS }?.description
        val pending =
                actionableTodos.filter { it.status == TodoStatus.PENDING }.take(2).map {
                    it.description
                }
        val summary = buildString {
            append(
                    "Todo status: ${actionableTodos.size} actionable item(s) (${todos.size} total tracked)."
            )
            if (inProgress != null) append(" In progress: $inProgress.")
            if (pending.isNotEmpty()) append(" Next: ${pending.joinToString(separator = "; ")}.")
        }
        return """
            <system_reminder>
            $summary
            </system_reminder>
        """.trimIndent()
    }

    private fun buildScratchpadReminder(keys: List<String>): String? {
        if (keys.isEmpty()) return null
        val preview = keys.take(4).joinToString(separator = ", ")
        return """
            <system_reminder>
            Scratchpad has ${keys.size} key(s). Reuse stored facts before repeating extraction. Keys: $preview
            </system_reminder>
        """.trimIndent()
    }
}
