package com.moonkey.androidagent.tool

import com.moonkey.androidagent.protocol.RiskLevel

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
                CompleteTask, WriteTodos, Scratchpad -> false
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
    val displayName: String,
    val defaultRiskLevel: RiskLevel
) {
    data object Click : MobileActionName(
        raw = "click",
        canonical = "click",
        displayName = "Click",
        defaultRiskLevel = RiskLevel.LOW
    )
    data object LongPress : MobileActionName(
        raw = "long_press",
        canonical = "long_press",
        displayName = "Long press",
        defaultRiskLevel = RiskLevel.LOW
    )
    data object Type : MobileActionName(
        raw = "type",
        canonical = "type",
        displayName = "Type",
        defaultRiskLevel = RiskLevel.MEDIUM
    )
    data object Scroll : MobileActionName(
        raw = "scroll",
        canonical = "scroll",
        displayName = "Scroll",
        defaultRiskLevel = RiskLevel.LOW
    )
    data object Swipe : MobileActionName(
        raw = "swipe",
        canonical = "swipe",
        displayName = "Swipe",
        defaultRiskLevel = RiskLevel.LOW
    )
    data object Back : MobileActionName(
        raw = "back",
        canonical = "back",
        displayName = "Back",
        defaultRiskLevel = RiskLevel.LOW
    )
    data object Home : MobileActionName(
        raw = "home",
        canonical = "home",
        displayName = "Home",
        defaultRiskLevel = RiskLevel.MEDIUM
    )
    data object Wait : MobileActionName(
        raw = "wait",
        canonical = "wait",
        displayName = "Wait",
        defaultRiskLevel = RiskLevel.LOW
    )
    data object SystemButton : MobileActionName(
        raw = "system_button",
        canonical = "system_button",
        displayName = "System button",
        defaultRiskLevel = RiskLevel.MEDIUM
    )
    data class Unknown(private val name: String) : MobileActionName(
        raw = name,
        canonical = normalizeName(name),
        displayName = formatDisplayName(name),
        defaultRiskLevel = RiskLevel.MEDIUM
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
