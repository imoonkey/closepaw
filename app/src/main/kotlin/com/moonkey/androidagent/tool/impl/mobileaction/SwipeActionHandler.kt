package com.moonkey.androidagent.tool.impl.mobileaction

import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ValidationResult
import com.moonkey.androidagent.tool.handlers.ActionHandler
import com.moonkey.androidagent.tool.handlers.UIActionInvocation
import org.json.JSONObject

/**
 * Swipe action - swipe from start to end coordinates.
 */
class SwipeActionHandler : ActionHandler {
    override val actionName = "swipe"

    companion object {
        private const val DEFAULT_SWIPE_DURATION_MS = 300L
    }

    override fun validate(params: JSONObject): ValidationResult {
        // Check start coordinate
        if (!params.has("start")) {
            return ValidationResult.Invalid("swipe action requires start coordinate [x, y]")
        }
        val start = params.optJSONArray("start")
        if (start == null || start.length() != 2) {
            return ValidationResult.Invalid("start must be an array of [x, y]")
        }

        // Check end coordinate
        if (!params.has("end")) {
            return ValidationResult.Invalid("swipe action requires end coordinate [x, y]")
        }
        val end = params.optJSONArray("end")
        if (end == null || end.length() != 2) {
            return ValidationResult.Invalid("end must be an array of [x, y]")
        }

        // Validate coordinates are non-negative
        try {
            val sx = start.getInt(0)
            val sy = start.getInt(1)
            val ex = end.getInt(0)
            val ey = end.getInt(1)

            if (sx < 0 || sy < 0 || ex < 0 || ey < 0) {
                return ValidationResult.Invalid("Coordinates must be non-negative")
            }
        } catch (e: Exception) {
            return ValidationResult.Invalid("Coordinates must be integers")
        }

        return ValidationResult.Valid
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val start = params.getJSONArray("start")
        val end = params.getJSONArray("end")
        val durationMs = params.optLong("duration_ms", DEFAULT_SWIPE_DURATION_MS)

        val sx = start.getInt(0)
        val sy = start.getInt(1)
        val ex = end.getInt(0)
        val ey = end.getInt(1)

        return UIActionInvocation(
            toolName = "mobile_action",
            params = params,
            description = "Swipe from ($sx,$sy) to ($ex,$ey)",
            uiAction = UIAction.Swipe(sx, sy, ex, ey, durationMs)
        )
    }
}

