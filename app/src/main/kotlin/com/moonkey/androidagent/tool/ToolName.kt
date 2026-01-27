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
    data object MobileAction : ToolName(
        raw = "mobile_action",
        canonical = "mobile_action",
        displayName = "Mobile action"
    )
    data object AppControl : ToolName(
        raw = "app_control",
        canonical = "app_control",
        displayName = "App control"
    )
    data object CompleteTask : ToolName(
        raw = "complete_task",
        canonical = "complete_task",
        displayName = "Complete task"
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
                AppControl.canonical -> AppControl
                CompleteTask.canonical -> CompleteTask
                else -> Unknown(raw)
            }
        }
    }
}

/**
 * Known action names for the mobile_action tool (plus legacy action-only tool names).
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
    if (trimmed.isEmpty()) return "Tool"
    return trimmed.replace("-", " ")
        .replace("_", " ")
        .replaceFirstChar { it.uppercase() }
}
