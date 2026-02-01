package com.moonkey.androidagent.tool.impl.mobileaction

import com.moonkey.androidagent.platform.SystemButtonType
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ValidationResult
import com.moonkey.androidagent.tool.handlers.ActionHandler
import com.moonkey.androidagent.tool.handlers.UIActionInvocation
import org.json.JSONObject

/**
 * System button action - press back, home, enter, or menu.
 */
class SystemButtonActionHandler : ActionHandler {
    override val actionName = "system_button"

    override fun validate(params: JSONObject): ValidationResult {
        if (!params.has("button")) {
            return ValidationResult.Invalid("system_button action requires button parameter")
        }
        val button = params.optString("button", "").lowercase()
        val validButtons = listOf("back", "home", "enter", "recents")
        if (button !in validButtons) {
            return ValidationResult.Invalid(
                "button must be one of: ${validButtons.joinToString()}"
            )
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
            else -> SystemButtonType.BACK // Shouldn't happen after validation
        }

        return UIActionInvocation(
            toolName = "mobile_action",
            params = params,
            description = "Press $button button",
            uiAction = UIAction.SystemButton(buttonType)
        )
    }
}

