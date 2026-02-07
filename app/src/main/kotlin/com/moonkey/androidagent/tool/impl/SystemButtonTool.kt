package com.moonkey.androidagent.tool.impl

import com.moonkey.androidagent.platform.SystemButtonType
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ToolSpec
import com.moonkey.androidagent.tool.ValidationResult
import com.moonkey.androidagent.tool.handlers.UIActionInvocation
import org.json.JSONArray
import org.json.JSONObject

/**
 * SystemButtonTool - deterministic system-key actions without screen targeting.
 */
class SystemButtonTool : ToolSpec {
    companion object {
        private val VALID_BUTTONS = listOf("back", "home", "enter", "recents")
    }

    override val name: String = "system_button"

    override val description: String = """
Press an Android system button. This does not require element targeting.

Buttons:
- back
- home
- enter
- recents

Examples:
- system_button(button="back")
- system_button(button="home")
""".trimIndent()

    override val parameterSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject().apply {
                put(
                    "agent_thought",
                    JSONObject().apply {
                        put("type", "string")
                        put("description", "Brief reason for pressing this button")
                    }
                )
                put(
                    "button",
                    JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray(VALID_BUTTONS))
                        put("description", "System button to press")
                    }
                )
            }
        )
        put("required", JSONArray(listOf("button")))
        put("additionalProperties", false)
    }

    override fun validate(params: JSONObject): ValidationResult {
        if (!params.has("button")) {
            return ValidationResult.Invalid("Missing required parameter: button")
        }
        val button = params.optString("button", "").lowercase()
        if (button !in VALID_BUTTONS) {
            return ValidationResult.Invalid("button must be one of: ${VALID_BUTTONS.joinToString()}")
        }
        return ValidationResult.Valid
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val button = params.getString("button").lowercase()
        val buttonType = when (button) {
            "back" -> SystemButtonType.BACK
            "home" -> SystemButtonType.HOME
            "enter" -> SystemButtonType.ENTER
            "recents" -> SystemButtonType.RECENTS
            else -> SystemButtonType.BACK
        }
        return UIActionInvocation(
            toolName = name,
            params = params,
            description = "Press $button button",
            uiAction = UIAction.SystemButton(buttonType)
        )
    }
}
