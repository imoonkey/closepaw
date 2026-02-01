package com.moonkey.androidagent.tool.impl

import com.moonkey.androidagent.platform.SystemButtonType
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tool.MultiActionTool
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ValidationResult
import com.moonkey.androidagent.tool.handlers.ActionHandler
import com.moonkey.androidagent.tool.handlers.ClickTargetInvocation
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
- click: Tap using one of element_index, resource_id, text, bounds (x1,y1,x2,y2), or coordinates (x,y).
- long_press: Long press element (element_index required, duration_ms optional)
- type: Input text into field (text required, element_index optional to focus first, clear optional)
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
                "agent_thought" to PropertySpec(
                    type = "string",
                    description = "Brief reason for why this action is being performed"
                ),
                "element_index" to PropertySpec(
                    type = "integer",
                    description = "Element index for click, long_press, or type (to focus first)"
                ),
                "resource_id" to PropertySpec(
                    type = "string",
                    description = "Resource id selector for click (e.g., 'com.app:id/button')"
                ),
                "resource_id_index" to PropertySpec(
                    type = "integer",
                    description = "Zero-based index when multiple elements share the same resource_id"
                ),
                "text_index" to PropertySpec(
                    type = "integer",
                    description = "Zero-based index when multiple elements share the same text"
                ),
                "x" to PropertySpec(
                    type = "integer",
                    description = "X coordinate in pixels for click"
                ),
                "y" to PropertySpec(
                    type = "integer",
                    description = "Y coordinate in pixels for click"
                ),
                "x1" to PropertySpec(
                    type = "integer",
                    description = "Left X coordinate in pixels for click bounds"
                ),
                "y1" to PropertySpec(
                    type = "integer",
                    description = "Top Y coordinate in pixels for click bounds"
                ),
                "x2" to PropertySpec(
                    type = "integer",
                    description = "Right X coordinate in pixels for click bounds"
                ),
                "y2" to PropertySpec(
                    type = "integer",
                    description = "Bottom Y coordinate in pixels for click bounds"
                ),
                "text" to PropertySpec(
                    type = "string",
                    description = "Text to input for type action, or text selector for click"
                ),
                "clear" to PropertySpec(
                    type = "boolean",
                    description = "Clear existing text before typing (default false)"
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
 * Click action - tap using multi-selector targeting with fallback order.
 */
class ClickActionHandler : ActionHandler {
    override val actionName = "click"
    
    override fun validate(params: JSONObject): ValidationResult {
        val hasBounds = params.has("x1") || params.has("y1") || params.has("x2") || params.has("y2")
        val hasPoint = params.has("x") || params.has("y")
        val hasElementIndex = params.has("element_index")
        val resourceId = params.optString("resource_id", "").trim()
        val text = params.optString("text", "").trim()

        if (!hasBounds && !hasPoint && !hasElementIndex && resourceId.isEmpty() && text.isEmpty()) {
            return ValidationResult.Invalid(
                "click action requires one of: bounds (x1,y1,x2,y2), x/y, resource_id, text, or element_index"
            )
        }

        if (hasElementIndex) {
            val idx = params.optInt("element_index", -1)
            if (idx < 0) {
                return ValidationResult.Invalid("element_index must be >= 0")
            }
        }

        if (hasPoint) {
            if (!params.has("x") || !params.has("y")) {
                return ValidationResult.Invalid("click action requires both x and y when using coordinates")
            }
            val x = params.optInt("x", -1)
            val y = params.optInt("y", -1)
            if (x < 0 || y < 0) {
                return ValidationResult.Invalid("x and y must be >= 0")
            }
        }

        if (hasBounds) {
            val required = listOf("x1", "y1", "x2", "y2")
            val missing = required.filterNot { params.has(it) }
            if (missing.isNotEmpty()) {
                return ValidationResult.Invalid("click bounds require ${missing.joinToString()}")
            }
            val x1 = params.optInt("x1", -1)
            val y1 = params.optInt("y1", -1)
            val x2 = params.optInt("x2", -1)
            val y2 = params.optInt("y2", -1)
            if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
                return ValidationResult.Invalid("x1, y1, x2, y2 must be >= 0")
            }
            if (x2 < x1 || y2 < y1) {
                return ValidationResult.Invalid("x2 must be >= x1 and y2 must be >= y1")
            }
        }

        if (params.has("resource_id_index") && resourceId.isEmpty()) {
            return ValidationResult.Invalid("resource_id_index requires resource_id")
        }

        if (params.has("text_index") && text.isEmpty()) {
            return ValidationResult.Invalid("text_index requires text")
        }

        return ValidationResult.Valid
    }
    
    override fun createInvocation(params: JSONObject): ToolInvocation {
        val description = buildClickDescription(params)
        return ClickTargetInvocation(
            params = params,
            description = description
        )
    }
}

private fun buildClickDescription(params: JSONObject): String {
    val resourceId = params.optString("resource_id", "").trim()
    val text = params.optString("text", "").trim()
    val hasBounds = params.has("x1") && params.has("y1") && params.has("x2") && params.has("y2")
    val hasPoint = params.has("x") && params.has("y")
    return when {
        resourceId.isNotEmpty() -> "Click resource_id '$resourceId' (index ${params.optInt("resource_id_index", 0)})"
        text.isNotEmpty() -> "Click text \"$text\" (index ${params.optInt("text_index", 0)})"
        hasBounds -> {
            val x1 = params.optInt("x1", -1)
            val y1 = params.optInt("y1", -1)
            val x2 = params.optInt("x2", -1)
            val y2 = params.optInt("y2", -1)
            "Click bounds ($x1,$y1)-($x2,$y2)"
        }
        hasPoint -> "Click at (${params.optInt("x", -1)},${params.optInt("y", -1)})"
        else -> "Click element ${params.optInt("element_index", -1)}"
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
        val clear = params.optBoolean("clear", false)
        
        val description = if (idx >= 0) {
            "Type '$text' into element $idx${if (clear) " (clear first)" else ""}"
        } else {
            "Type '$text' into focused field${if (clear) " (clear first)" else ""}"
        }
        
        return UIActionInvocation(
            toolName = "mobile_action",
            params = params,
            description = description,
            uiAction = UIAction.Type(text, if (idx >= 0) idx else null, clear)
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
