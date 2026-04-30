package ai.closepaw.tool

/**
 * Canonical tool identifiers used across UI and policy layers.
 */
sealed class ToolName(
    val raw: String,
    val canonical: String,
    val displayName: String
) {
    val isScreenChanging: Boolean
        get() =
            when (this) {
                MobileAction, OpenApp, Wait, SystemButton, DelegateTask -> true
                CompleteTask, WriteTodos, Scratchpad, RememberExperience, AskUser, Shell,
                ActivateSkill -> false
                is Unknown -> true
            }

    data object MobileAction : ToolName(
        raw = "mobile_action",
        canonical = "mobile_action",
        displayName = "Mobile action"
    )
    data object OpenApp : ToolName(
        raw = "open_app",
        canonical = "open_app",
        displayName = "Open app"
    )
    data object Wait : ToolName(
        raw = "wait",
        canonical = "wait",
        displayName = "Wait"
    )
    data object SystemButton : ToolName(
        raw = "system_button",
        canonical = "system_button",
        displayName = "System button"
    )
    data object CompleteTask : ToolName(
        raw = "complete_task",
        canonical = "complete_task",
        displayName = "Complete task"
    )
    data object WriteTodos : ToolName(
        raw = "write_todos",
        canonical = "write_todos",
        displayName = "Write todos"
    )
    data object Scratchpad : ToolName(
        raw = "scratchpad",
        canonical = "scratchpad",
        displayName = "Scratchpad"
    )
    data object DelegateTask : ToolName(
        raw = "delegate_task",
        canonical = "delegate_task",
        displayName = "Delegate task"
    )
    data object RememberExperience : ToolName(
        raw = "remember_experience",
        canonical = "remember_experience",
        displayName = "Remember experience"
    )
    data object AskUser : ToolName(
        raw = "ask_user",
        canonical = "ask_user",
        displayName = "Ask user"
    )
    data object Shell : ToolName(
        raw = "shell",
        canonical = "shell",
        displayName = "Shell"
    )
    data object ActivateSkill : ToolName(
        raw = "activate_skill",
        canonical = "activate_skill",
        displayName = "Activate skill"
    )
    data class Unknown(private val name: String) : ToolName(
        raw = name,
        canonical = normalizeName(name),
        displayName = formatDisplayName(name)
    )

    companion object {
        fun from(raw: String): ToolName {
            return when (normalizeName(raw)) {
                MobileAction.canonical -> MobileAction
                OpenApp.canonical -> OpenApp
                Wait.canonical -> Wait
                SystemButton.canonical -> SystemButton
                CompleteTask.canonical -> CompleteTask
                WriteTodos.canonical -> WriteTodos
                Scratchpad.canonical -> Scratchpad
                DelegateTask.canonical -> DelegateTask
                RememberExperience.canonical -> RememberExperience
                AskUser.canonical -> AskUser
                Shell.canonical -> Shell
                ActivateSkill.canonical -> ActivateSkill
                else -> Unknown(raw)
            }
        }
    }
}

/**
 * Known action names for mobile_action and standalone action tools.
 */
sealed class MobileActionName(
    val raw: String,
    val canonical: String,
    val displayName: String
) {
    data object Click : MobileActionName(
        raw = "click",
        canonical = "click",
        displayName = "Click"
    )
    data object LongPress : MobileActionName(
        raw = "long_press",
        canonical = "long_press",
        displayName = "Long press"
    )
    data object Type : MobileActionName(
        raw = "type",
        canonical = "type",
        displayName = "Type"
    )
    data object Scroll : MobileActionName(
        raw = "scroll",
        canonical = "scroll",
        displayName = "Scroll"
    )
    data object Swipe : MobileActionName(
        raw = "swipe",
        canonical = "swipe",
        displayName = "Swipe"
    )
    data object Back : MobileActionName(
        raw = "back",
        canonical = "back",
        displayName = "Back"
    )
    data object Home : MobileActionName(
        raw = "home",
        canonical = "home",
        displayName = "Home"
    )
    data object Wait : MobileActionName(
        raw = "wait",
        canonical = "wait",
        displayName = "Wait"
    )
    data object SystemButton : MobileActionName(
        raw = "system_button",
        canonical = "system_button",
        displayName = "System button"
    )
    data class Unknown(private val name: String) : MobileActionName(
        raw = name,
        canonical = normalizeName(name),
        displayName = formatDisplayName(name)
    )

    companion object {
        fun from(raw: String): MobileActionName {
            return when (normalizeName(raw)) {
                Click.canonical -> Click
                LongPress.canonical -> LongPress
                Type.canonical -> Type
                Scroll.canonical -> Scroll
                Swipe.canonical -> Swipe
                Back.canonical -> Back
                Home.canonical -> Home
                Wait.canonical -> Wait
                SystemButton.canonical -> SystemButton
                else -> Unknown(raw)
            }
        }

        fun fromOrNull(raw: String): MobileActionName? {
            return when (normalizeName(raw)) {
                Click.canonical -> Click
                LongPress.canonical -> LongPress
                Type.canonical -> Type
                Scroll.canonical -> Scroll
                Swipe.canonical -> Swipe
                Back.canonical -> Back
                Home.canonical -> Home
                Wait.canonical -> Wait
                SystemButton.canonical -> SystemButton
                else -> null
            }
        }
    }
}

private fun normalizeName(raw: String): String {
    return raw.trim()
        .lowercase()
        .replace("-", "_")
        .replace("\\s+".toRegex(), "_")
}

private fun formatDisplayName(raw: String): String {
    val trimmed = raw.trim()
    val normalized = trimmed
        .replace("-", " ")
        .replace("_", " ")
        .trim()
    if (normalized.isEmpty()) return "Tool"
    return normalized.replaceFirstChar { it.uppercase() }
}
