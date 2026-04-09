package com.moonkey.androidagent.agent

import org.json.JSONObject

/**
 * Normalized representation of which UI element a mobile_action targets.
 *
 * Decoded once from the raw JSON arguments, then consumed by both
 * [ActionDescriptionFormatter] (human-readable descriptions) and
 * [classifyActionSignature] (action signatures).
 */
data class ActionTarget(
    val text: String,
    val textIndex: Int,
    val bounds: Bounds?,
    val point: Point?,
    val elementIndex: Int?,
) {
    data class Bounds(val x1: Int, val y1: Int, val x2: Int, val y2: Int)
    data class Point(val x: Int, val y: Int)
}

/**
 * Decode the UI-element targeting fields from a mobile_action's JSON arguments.
 *
 * The [action] parameter controls how the `text` field is resolved for the
 * "type" action, where `text` is overloaded (it can mean "target element label"
 * or "text to type" depending on whether `input_text` is present).
 *
 * - **type + input_text present**: `text` → target element, `input_text` → content to type
 * - **type + no input_text** (legacy): `target_text` → target element, `text` → content to type
 * - **all other actions**: `text` → target element
 */
fun decodeActionTarget(args: JSONObject, action: String = ""): ActionTarget {
    val (text, textIndex) = when {
        action == "type" && args.has("input_text") ->
            args.optString("text", "").trim() to
                    args.optInt("text_index", args.optInt("target_text_index", 0))
        action == "type" ->
            args.optString("target_text", "").trim() to
                    args.optInt("target_text_index", args.optInt("text_index", 0))
        else ->
            args.optString("text", "").trim() to args.optInt("text_index", 0)
    }

    val bounds = if (args.has("x1") && args.has("y1") && args.has("x2") && args.has("y2")) {
        ActionTarget.Bounds(
            args.optInt("x1", -1), args.optInt("y1", -1),
            args.optInt("x2", -1), args.optInt("y2", -1),
        )
    } else null

    val point = if (args.has("x") && args.has("y")) {
        ActionTarget.Point(args.optInt("x", -1), args.optInt("y", -1))
    } else null

    val elementIndex = if (args.has("element_index")) {
        args.optInt("element_index", -1).takeIf { it >= 0 }
    } else null

    return ActionTarget(text, textIndex, bounds, point, elementIndex)
}
