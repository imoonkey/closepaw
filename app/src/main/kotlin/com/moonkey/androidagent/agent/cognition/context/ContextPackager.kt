package com.moonkey.androidagent.agent.cognition.context

import com.moonkey.androidagent.agent.AgentPromptBuilder
import com.moonkey.androidagent.agent.cognition.profile.CognitionProfile
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.protocol.TodoStatus
import com.moonkey.androidagent.session.ScratchpadState
import com.moonkey.androidagent.session.TodoState

internal data class RawTurnData(
    val snapshot: ScreenSnapshot,
    val loopWarning: LoopWarning? = null,
    val systemReminders: List<String> = emptyList()
)

internal data class PackagedTurnInput(
    val userContext: AgentPromptBuilder.UserContext
)

internal interface ContextPackager {
    fun buildTurnInput(
        profile: CognitionProfile,
        raw: RawTurnData
    ): PackagedTurnInput
}

internal class DefaultContextPackager(
    private val promptBuilder: AgentPromptBuilder,
    private val todoState: TodoState? = null,
    private val scratchpadState: ScratchpadState? = null
) : ContextPackager {
    override fun buildTurnInput(profile: CognitionProfile, raw: RawTurnData): PackagedTurnInput {
        val baseContext = promptBuilder.buildUserContext(raw.snapshot)
        val reminders = buildReminders(profile, raw)
        if (reminders.isEmpty()) {
            return PackagedTurnInput(userContext = baseContext)
        }

        val reminderBlock = reminders.joinToString(separator = "\n\n")
        val enrichedText = "${baseContext.text}\n\n$reminderBlock"
        return PackagedTurnInput(
            userContext =
                baseContext.copy(
                    text = enrichedText
                )
        )
    }

    private fun buildReminders(profile: CognitionProfile, raw: RawTurnData): List<String> {
        return buildList {
            raw.loopWarning?.let { add(formatLoopWarning(it)) }
            raw.systemReminders.map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
            if (profile.todoListEnabled) {
                buildTodoReminder()?.let { add(it) }
            }
            buildScratchpadReminder()?.let { add(it) }
        }
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
        val todos = todoState?.get() ?: return null
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
        val keys = scratchpadState?.list() ?: return null
        if (keys.isEmpty()) return null
        val preview = keys.take(4).joinToString(separator = ", ")
        return """
            <system_reminder>
            Scratchpad has ${keys.size} key(s). Reuse stored facts before repeating extraction. Keys: $preview
            </system_reminder>
        """.trimIndent()
    }
}
