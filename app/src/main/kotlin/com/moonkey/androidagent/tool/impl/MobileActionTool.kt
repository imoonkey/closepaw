package com.moonkey.androidagent.tool.impl

import com.moonkey.androidagent.platform.SystemButtonType
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tool.MultiActionTool
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ValidationResult
import com.moonkey.androidagent.tool.handlers.ActionHandler
import com.moonkey.androidagent.tool.handlers.UIActionInvocation
import org.json.JSONObject

/**
 * MobileActionTool - Consolidated tool for all user-simulating mobile actions.
 * 
 * This follows the Mobile-Agent-v3 pattern of having a single "mobile_use" tool
 * with an action parameter, instead of separate tools for click, type, etc.
 * 
 * Supported actions:
 * - click: Tap on element by index
 * - long_press: Long press on element
 * - type: Input text into focused/specified field
 * - swipe: Swipe from (x1,y1) to (x2,y2) 
 * - system_button: Press back, home, enter, or menu
 * - wait: Wait for UI to settle
 * 
 * Note: scroll is intentionally omitted as it's a subset of swipe.
 */
class MobileActionTool : MultiActionTool() {
    
    override val name: String = "mobile_action"
    
    override val description: String = """
Perform touch interactions on the mobile device screen.

Actions:
- click: Tap on element by index (element_index required)
- long_press: Long press element (element_index required, duration_ms optional)
- type: Input text into field (text required, element_index optional to focus first)
- swipe: Swipe gesture (start and end coordinates required as [x,y] arrays). Coordinates beyond screen bounds are clamped.
- system_button: Press system button (button required: back/home/enter/recents)
- wait: Wait for UI updates (duration_ms optional, default 1000ms)
""".trimIndent()
    
    override val actionHandlers: Map<String, ActionHandler> = mapOf(
        "click" to ClickActionHandler(),
        "long_press" to LongPressActionHandler(),
        "type" to TypeActionHandler(),
        "swipe" to SwipeActionHandler(),
        "system_button" to SystemButtonActionHandler(),
        "wait" to WaitActionHandler()
    )
    
    override val parameterSchema: JSONObject by lazy {
        createActionSchema(
            actionDescription = "The action to perform",
            additionalProperties = mapOf(
                "element_index" to PropertySpec(
                    type = "integer",
                    description = "Element index for click, long_press, or type (to focus first)"
                ),
                "text" to PropertySpec(
                    type = "string",
                    description = "Text to input for type action"
                ),
                "start" to PropertySpec(
                    type = "array",
                    description = "[x, y] start coordinates in pixels for swipe",
                    items = JSONObject().put("type", "integer")
                ),
                "end" to PropertySpec(
                    type = "array",
                    description = "[x, y] end coordinates in pixels for swipe",
                    items = JSONObject().put("type", "integer")
                ),
                "button" to PropertySpec(
                    type = "string",
                    description = "System button for system_button action",
                    enum = listOf("back", "home", "enter", "recents")
                ),
                "duration_ms" to PropertySpec(
                    type = "integer",
                    description = "Duration in ms for wait (default 1000) or long_press (default 1000)"
                )
            )
        )
    }
}

// =============================================================================
// Action Handlers
// =============================================================================

/**
 * Click action - tap on element by index.
 */
class ClickActionHandler : ActionHandler {
    override val actionName = "click"
    
    override fun validate(params: JSONObject): ValidationResult {
        if (!params.has("element_index")) {
            return ValidationResult.Invalid("click action requires element_index")
        }
        val idx = params.optInt("element_index", -1)
        if (idx < 0) {
            return ValidationResult.Invalid("element_index must be >= 0")
        }
        return ValidationResult.Valid
    }
    
    override fun createInvocation(params: JSONObject): ToolInvocation {
        val idx = params.getInt("element_index")
        return UIActionInvocation(
            toolName = "mobile_action",
            params = params,
            description = "Click element $idx",
            uiAction = UIAction.Click(idx)
        )
    }
}

/**
 * Long press action - press and hold on element.
 */
class LongPressActionHandler : ActionHandler {
    override val actionName = "long_press"
    
    companion object {
        private const val DEFAULT_DURATION_MS = 1000L
    }
    
    override fun validate(params: JSONObject): ValidationResult {
        if (!params.has("element_index")) {
            return ValidationResult.Invalid("long_press action requires element_index")
        }
        val idx = params.optInt("element_index", -1)
        if (idx < 0) {
            return ValidationResult.Invalid("element_index must be >= 0")
        }
        return ValidationResult.Valid
    }
    
    override fun createInvocation(params: JSONObject): ToolInvocation {
        val idx = params.getInt("element_index")
        val durationMs = params.optLong("duration_ms", DEFAULT_DURATION_MS)
        return UIActionInvocation(
            toolName = "mobile_action",
            params = params,
            description = "Long press element $idx for ${durationMs}ms",
            uiAction = UIAction.LongClick(idx, durationMs)
        )
    }
}

/**
 * Type action - input text into a field.
 * 
 * If element_index is provided, focuses that element first.
 */
class TypeActionHandler : ActionHandler {
    override val actionName = "type"
    
    override fun validate(params: JSONObject): ValidationResult {
        if (!params.has("text")) {
            return ValidationResult.Invalid("type action requires text")
        }
        // element_index is optional (can type into currently focused field)
        val idx = params.optInt("element_index", -1)
        if (params.has("element_index") && idx < 0) {
            return ValidationResult.Invalid("element_index must be >= 0 if provided")
        }
        return ValidationResult.Valid
    }
    
    override fun createInvocation(params: JSONObject): ToolInvocation {
        val text = params.getString("text")
        val idx = params.optInt("element_index", -1)
        
        val description = if (idx >= 0) {
            "Type '$text' into element $idx"
        } else {
            "Type '$text' into focused field"
        }
        
        return UIActionInvocation(
            toolName = "mobile_action",
            params = params,
            description = description,
            uiAction = UIAction.Type(text, if (idx >= 0) idx else null)
        )
    }
}

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
