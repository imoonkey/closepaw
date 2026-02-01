package com.moonkey.androidagent.tool.impl.mobileaction

import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ValidationResult
import com.moonkey.androidagent.tool.handlers.ActionHandler
import com.moonkey.androidagent.tool.handlers.UIActionInvocation
import org.json.JSONObject

/**
 * Wait action - pause for UI to settle.
 */
class WaitActionHandler : ActionHandler {
    override val actionName = "wait"

    companion object {
        private const val DEFAULT_WAIT_MS = 1000L
    }

    override fun validate(params: JSONObject): ValidationResult {
        // No required parameters
        val durationMs = params.optLong("duration_ms", DEFAULT_WAIT_MS)
        if (durationMs < 0) {
            return ValidationResult.Invalid("duration_ms must be non-negative")
        }
        if (durationMs > 30000) {
            return ValidationResult.Invalid("duration_ms must be <= 30000 (30 seconds)")
        }
        return ValidationResult.Valid
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val durationMs = params.optLong("duration_ms", DEFAULT_WAIT_MS)
        return UIActionInvocation(
            toolName = "mobile_action",
            params = params,
            description = "Wait ${durationMs}ms for UI to settle",
            uiAction = UIAction.Wait(durationMs)
        )
    }
}

