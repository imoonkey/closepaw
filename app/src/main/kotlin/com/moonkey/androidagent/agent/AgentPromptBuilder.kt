package com.moonkey.androidagent.agent

import com.moonkey.androidagent.agent.cognition.context.LoopWarning
import com.moonkey.androidagent.agent.cognition.context.LoopWarningSeverity
import com.moonkey.androidagent.agent.cognition.prompt.ExecutorPromptTemplate
import com.moonkey.androidagent.agent.cognition.prompt.PlannerPromptTemplate
import com.moonkey.androidagent.model.ScreenImage
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.protocol.TodoStatus
import com.moonkey.androidagent.session.AgentSessionState
import com.moonkey.androidagent.tool.ToolRegistry

class AgentPromptBuilder(
    private val basePrompt: String?,
    private val llmBackend: LLMBackendType,
    private val toolRegistry: ToolRegistry,
    private val sessionState: AgentSessionState,
    private val visibleToolNames: Set<String>? = null
) {
    data class UserContext(
        val text: String,
        val image: ScreenImage?
    )

    fun buildSystemPrompt(): String {
        val visibleTools =
            toolRegistry.getNames()
                .asSequence()
                .filter { name -> visibleToolNames?.contains(name) != false }
                .toSet()
        val prompt =
            basePrompt
                ?: if (isExecutorRole(visibleTools)) {
                    ExecutorPromptTemplate.systemPrompt
                } else {
                    PlannerPromptTemplate.defaultSystemPrompt
                }
        val stateContext = buildStateContext()

        return if (stateContext.isNotBlank()) {
            "$prompt\n\n$stateContext"
        } else {
            prompt
        }
    }

    fun buildUserContext(snapshot: ScreenSnapshot): UserContext {
        return buildBaseUserContext(snapshot)
    }

    internal fun buildUserContext(
        snapshot: ScreenSnapshot,
        loopWarning: LoopWarning? = null,
        systemReminders: List<String> = emptyList()
    ): UserContext {
        val baseContext = buildBaseUserContext(snapshot)
        val reminders =
            buildList {
                loopWarning?.let { add(formatLoopWarning(it)) }
                systemReminders.map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
                buildTodoReminder()?.let { add(it) }
                buildScratchpadReminder()?.let { add(it) }
            }
        if (reminders.isEmpty()) {
            return baseContext
        }
        return baseContext.copy(text = "${baseContext.text}\n\n${reminders.joinToString(separator = "\n\n")}")
    }

    private fun buildBaseUserContext(snapshot: ScreenSnapshot): UserContext {
        val screenJson = Perceptor.toPromptJson(snapshot)
        val toolNames = toolRegistry.getNames()
            .asSequence()
            .filter { name -> visibleToolNames?.contains(name) != false }
            .sorted()
            .joinToString(", ")
        val image = snapshot.image?.takeIf { llmBackend == LLMBackendType.OPENAI }
        val imageHint = if (image != null) {
            "\nScreenshot attached (compressed)."
        } else {
            ""
        }

        val text = """
            Current screen state (${snapshot.elements.size} elements):
            ```json
            $screenJson
            ```
            
            Available tools: $toolNames
            
            $imageHint
            What action should I take next to achieve the goal?
        """.trimIndent()

        return UserContext(
            text = text,
            image = image
        )
    }

    private fun isExecutorRole(toolNames: Set<String>): Boolean {
        val hasDelegate = "delegate_task" in toolNames
        val hasMobileAction = "mobile_action" in toolNames
        return !(hasDelegate && !hasMobileAction)
    }

    private fun formatLoopWarning(loopWarning: LoopWarning): String {
        val priorityLabel = if (loopWarning.severity == LoopWarningSeverity.CRITICAL) "HIGH" else "MEDIUM"
        return """
            <system_reminder>
            LOOP WARNING ($priorityLabel): ${loopWarning.message}
            </system_reminder>
        """.trimIndent()
    }

    private fun buildTodoReminder(): String? {
        val todos = sessionState.todos.get()
        if (todos.isEmpty()) return null

        val actionableTodos =
            todos.filterNot { todo ->
                todo.status == TodoStatus.COMPLETED || todo.status == TodoStatus.CANCELLED
            }
        if (actionableTodos.isEmpty()) return null

        val inProgress = actionableTodos.firstOrNull { it.status == TodoStatus.IN_PROGRESS }?.description
        val pending = actionableTodos.filter { it.status == TodoStatus.PENDING }.take(2).map { it.description }
        val summary =
            buildString {
                append("Todo status: ${actionableTodos.size} actionable item(s) (${todos.size} total tracked).")
                if (inProgress != null) append(" In progress: $inProgress.")
                if (pending.isNotEmpty()) append(" Next: ${pending.joinToString(separator = "; ")}.")
            }
        return """
            <system_reminder>
            $summary
            </system_reminder>
        """.trimIndent()
    }

    private fun buildScratchpadReminder(): String? {
        val keys = sessionState.scratchpad.list()
        if (keys.isEmpty()) return null
        val preview = keys.take(4).joinToString(separator = ", ")
        return """
            <system_reminder>
            Scratchpad has ${keys.size} key(s). Reuse stored facts before repeating extraction. Keys: $preview
            </system_reminder>
        """.trimIndent()
    }

    private fun buildStateContext(): String {
        return buildString {
            val todosContext = sessionState.todos.toPromptContext()
            if (todosContext.isNotEmpty()) {
                appendLine("## Current Todos")
                appendLine(todosContext)
                appendLine()
            }

            val scratchpadContext = sessionState.scratchpad.toPromptContext()
            if (scratchpadContext.isNotEmpty()) {
                appendLine("## Scratchpad")
                appendLine(scratchpadContext)
            }
        }.trimEnd()
    }
}
