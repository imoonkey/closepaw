package com.moonkey.androidagent.tool.impl

import com.moonkey.androidagent.tool.MultiActionTool
import com.moonkey.androidagent.tool.handlers.ActionHandler
import com.moonkey.androidagent.tool.impl.mobileaction.ClickActionHandler
import com.moonkey.androidagent.tool.impl.mobileaction.LongPressActionHandler
import com.moonkey.androidagent.tool.impl.mobileaction.SwipeActionHandler
import com.moonkey.androidagent.tool.impl.mobileaction.TypeActionHandler
import org.json.JSONObject

/**
 * MobileActionTool - Consolidated tool for all user-simulating mobile actions.
 * 
 * This follows the Mobile-Agent-v3 pattern of having a single "mobile_use" tool
 * with an action parameter, instead of separate tools for click, type, etc.
 * 
 * Supported actions:
 * - click: Tap target using multi-selector targeting
 * - long_press: Long press using multi-selector targeting
 * - type: Input text into focused/specified field using multi-selector targeting
 * - swipe: Swipe using explicit coordinates or direction/distance
 * 
 * Note: scroll is intentionally omitted as it's a subset of swipe.
 */
class MobileActionTool : MultiActionTool() {
    
    override val name: String = "mobile_action"
    
    override val description: String = """
Perform touch interactions on the mobile device screen.

Targeting (for click, long_press, type):
- element_index: index from current screen state
- text + text_index: visible text selector
- bounds (x1,y1,x2,y2): center point is used
- coordinates (x,y): absolute fallback

When multiple selectors are provided, fallback order is: bounds -> coordinates -> text -> element_index.

Actions:
- click: Tap target. Example: {"action":"click","element_index":3}
- long_press: Long press target. Example: {"action":"long_press","text":"Delete","duration_ms":1500}
- type: Type input_text into target field. Example: {"action":"type","input_text":"hello","text":"Search","clear":true}
- swipe: Swipe gesture. Example: {"action":"swipe","direction":"up"} or {"action":"swipe","start":[270,800],"end":[270,300]}

Swipe notes:
- direction: up/down/left/right
- direction="up" scrolls content DOWN (finger moves up)
- use direction+distance OR start+end coordinates
""".trimIndent()
    
    override val actionHandlers: Map<String, ActionHandler> = mapOf(
        "click" to ClickActionHandler(),
        "long_press" to LongPressActionHandler(),
        "type" to TypeActionHandler(),
        "swipe" to SwipeActionHandler()
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
                    description = "Index from current screen state. Preferred selector when available."
                ),
                "text_index" to PropertySpec(
                    type = "integer",
                    description = "Zero-based index when multiple elements match text (default 0)"
                ),
                "x" to PropertySpec(
                    type = "integer",
                    description = "Target X coordinate in pixels"
                ),
                "y" to PropertySpec(
                    type = "integer",
                    description = "Target Y coordinate in pixels"
                ),
                "x1" to PropertySpec(
                    type = "integer",
                    description = "Bounds left X in pixels (center used as tap point)"
                ),
                "y1" to PropertySpec(
                    type = "integer",
                    description = "Bounds top Y in pixels (center used as tap point)"
                ),
                "x2" to PropertySpec(
                    type = "integer",
                    description = "Bounds right X in pixels (center used as tap point)"
                ),
                "y2" to PropertySpec(
                    type = "integer",
                    description = "Bounds bottom Y in pixels (center used as tap point)"
                ),
                "text" to PropertySpec(
                    type = "string",
                    description = "Target element by visible text. Use text_index for disambiguation."
                ),
                "input_text" to PropertySpec(
                    type = "string",
                    description = "Text to type (type action only)"
                ),
                "clear" to PropertySpec(
                    type = "boolean",
                    description = "Clear field before typing (type action, default false)"
                ),
                "start" to PropertySpec(
                    type = "array",
                    description = "Swipe start coordinate [x, y] in pixels",
                    items = JSONObject().put("type", "integer")
                ),
                "end" to PropertySpec(
                    type = "array",
                    description = "Swipe end coordinate [x, y] in pixels",
                    items = JSONObject().put("type", "integer")
                ),
                "direction" to PropertySpec(
                    type = "string",
                    description = "Swipe direction (up/down/left/right). up/down scroll content opposite direction.",
                    enum = listOf("up", "down", "left", "right")
                ),
                "distance" to PropertySpec(
                    type = "string",
                    description = "Directional swipe travel distance: short=1/4, medium=1/2, long=3/4 screen (default medium)",
                    enum = listOf("short", "medium", "long")
                ),
                "duration_ms" to PropertySpec(
                    type = "integer",
                    description = "Hold duration for long_press in milliseconds (default 1000; 500=quick, 2000+=extended)"
                )
            )
        )
    }
}
