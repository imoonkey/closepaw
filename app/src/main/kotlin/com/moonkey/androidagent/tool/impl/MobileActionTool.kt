package com.moonkey.androidagent.tool.impl

import com.moonkey.androidagent.tool.MultiActionTool
import com.moonkey.androidagent.tool.handlers.ActionHandler
import com.moonkey.androidagent.tool.impl.mobileaction.ClickActionHandler
import com.moonkey.androidagent.tool.impl.mobileaction.LongPressActionHandler
import com.moonkey.androidagent.tool.impl.mobileaction.SystemButtonActionHandler
import com.moonkey.androidagent.tool.impl.mobileaction.SwipeActionHandler
import com.moonkey.androidagent.tool.impl.mobileaction.TypeActionHandler
import com.moonkey.androidagent.tool.impl.mobileaction.WaitActionHandler
import org.json.JSONObject

/**
 * MobileActionTool - Consolidated tool for all user-simulating mobile actions.
 * 
 * This follows the Mobile-Agent-v3 pattern of having a single "mobile_use" tool
 * with an action parameter, instead of separate tools for click, type, etc.
 * 
 * Supported actions:
 * - click: Tap on element by index
 * - long_press: Long press using multi-selector targeting
 * - type: Input text into focused/specified field using multi-selector targeting
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
- long_press: Long press using bounds, coordinates (x,y), resource_id, text, or element_index (duration_ms optional)
- type: Input into field (text required, clear optional). To focus first, use resource_id, target_text, bounds, x/y, or element_index.
- swipe: Swipe gesture using either explicit start/end coords or direction (up/down/left/right) with optional distance (short/medium/long) and target selectors.
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
                    description = "Resource id selector for click/long_press/type/swipe (e.g., 'com.app:id/button')"
                ),
                "resource_id_index" to PropertySpec(
                    type = "integer",
                    description = "Zero-based index when multiple elements share the same resource_id (default 0)"
                ),
                "text_index" to PropertySpec(
                    type = "integer",
                    description = "Zero-based index when multiple elements share the same text (click/long_press/swipe; default 0). For type with target_text, prefer target_text_index."
                ),
                "target_text" to PropertySpec(
                    type = "string",
                    description = "Text selector for type action targeting (matches element text or content-desc)"
                ),
                "target_text_index" to PropertySpec(
                    type = "integer",
                    description = "Zero-based index when multiple elements share the same target_text (default 0). Compatibility: text_index is accepted as an alias if target_text_index is omitted."
                ),
                "x" to PropertySpec(
                    type = "integer",
                    description = "X coordinate in pixels for click/long_press, or for focusing before type"
                ),
                "y" to PropertySpec(
                    type = "integer",
                    description = "Y coordinate in pixels for click/long_press, or for focusing before type"
                ),
                "x1" to PropertySpec(
                    type = "integer",
                    description = "Left X coordinate in pixels for bounds targeting (center is used)"
                ),
                "y1" to PropertySpec(
                    type = "integer",
                    description = "Top Y coordinate in pixels for bounds targeting (center is used)"
                ),
                "x2" to PropertySpec(
                    type = "integer",
                    description = "Right X coordinate in pixels for bounds targeting (center is used)"
                ),
                "y2" to PropertySpec(
                    type = "integer",
                    description = "Bottom Y coordinate in pixels for bounds targeting (center is used)"
                ),
                "text" to PropertySpec(
                    type = "string",
                    description = "Text to input for type action, or text selector for click/long_press/swipe"
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
                "direction" to PropertySpec(
                    type = "string",
                    description = "Direction for swipe (up, down, left, right). Mutually exclusive with start/end.",
                    enum = listOf("up", "down", "left", "right")
                ),
                "distance" to PropertySpec(
                    type = "string",
                    description = "Distance for directional swipe (short, medium, long). Default medium.",
                    enum = listOf("short", "medium", "long")
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
