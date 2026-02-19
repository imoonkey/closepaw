package com.moonkey.androidagent.tool.action

import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.UIAction
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Swipe executor: direction+distance or explicit start/end coordinates.
 *
 * No node-based fallback — swipe is gesture only.
 * Detects scroll boundary via before/after snapshot comparison.
 */
class SwipeExecutor(
    private val targetResolver: TargetResolver = TargetResolver,
    private val uiChangeDetector: UiChangeDetector = UiChangeDetector
) {
    companion object {
        private const val UI_SETTLE_DELAY_MS = 300L
        private const val DEFAULT_SWIPE_DURATION_MS = 400L
    }

    suspend fun execute(
        params: JSONObject,
        snapshot: ScreenSnapshot?,
        platform: AndroidPlatform,
        isCancelled: () -> Boolean
    ): ActionOutcome {
        if (isCancelled()) return ActionOutcome.Cancelled("Cancelled before swipe")

        val hasStart = params.has("start")
        val hasEnd = params.has("end")
        val direction = params.optString("direction", "").trim().lowercase()
        val durationMs = params.optLong("duration_ms", DEFAULT_SWIPE_DURATION_MS)

        // Explicit start/end coordinates take precedence when both forms are provided.
        if (hasStart && hasEnd) {
            return executeExplicitSwipe(params, durationMs, snapshot, platform)
        }

        // Direction-based swipe
        return executeDirectionalSwipe(params, direction, durationMs, snapshot, platform)
    }

    private suspend fun executeExplicitSwipe(
        params: JSONObject,
        durationMs: Long,
        snapshot: ScreenSnapshot?,
        platform: AndroidPlatform
    ): ActionOutcome {
        val start = params.getJSONArray("start")
        val end = params.getJSONArray("end")
        val sx = start.getInt(0)
        val sy = start.getInt(1)
        val ex = end.getInt(0)
        val ey = end.getInt(1)

        val action = UIAction.Swipe(sx, sy, ex, ey, durationMs)
        return dispatchSwipe(action, "($sx,$sy)→($ex,$ey)", snapshot, platform)
    }

    private suspend fun executeDirectionalSwipe(
        params: JSONObject,
        direction: String,
        durationMs: Long,
        snapshot: ScreenSnapshot?,
        platform: AndroidPlatform
    ): ActionOutcome {
        val display = platform.getDisplayInfo()
        val screenWidth = display.widthPixels
        val screenHeight = display.heightPixels
        if (screenWidth <= 1 || screenHeight <= 1) {
            return ActionOutcome.Failed(
                reason = "Invalid display dimensions ($screenWidth x $screenHeight)",
                attemptTrail = emptyList()
            )
        }

        val safeInset = computeSafeInset(screenWidth, screenHeight, display.density)
        val safeLeft = safeInset
        val safeTop = safeInset
        val safeRight = screenWidth - 1 - safeInset
        val safeBottom = screenHeight - 1 - safeInset

        // Resolve optional target for swipe origin
        val target = parseOptionalTarget(params)
        val originX: Int
        val originY: Int
        val swipeAreaWidth: Int
        val swipeAreaHeight: Int

        if (target != null) {
            val resolvedTarget = targetResolver.resolve(target, snapshot)
            if (resolvedTarget is TargetResolver.ResolveResult.Resolved) {
                val point = resolvedTarget.point
                originX = point.x.coerceIn(safeLeft, safeRight)
                originY = point.y.coerceIn(safeTop, safeBottom)
            } else {
                originX = (safeLeft + safeRight) / 2
                originY = (safeTop + safeBottom) / 2
            }
            swipeAreaWidth = safeRight - safeLeft
            swipeAreaHeight = safeBottom - safeTop
        } else {
            originX = (safeLeft + safeRight) / 2
            originY = (safeTop + safeBottom) / 2
            swipeAreaWidth = safeRight - safeLeft
            swipeAreaHeight = safeBottom - safeTop
        }

        val distanceRaw = params.optString("distance", "medium").trim().lowercase().ifEmpty { "medium" }
        val isVertical = direction == "up" || direction == "down"
        val baseSize = if (isVertical) swipeAreaHeight else swipeAreaWidth
        if (baseSize <= 0) {
            return ActionOutcome.Failed(
                reason = "Swipe area too small",
                attemptTrail = emptyList()
            )
        }

        val distancePx = computeDistancePx(distanceRaw, baseSize, display.density)
        val delta = max(1, distancePx / 2)

        val endpoints = computeEndpoints(direction, originX, originY, delta,
            safeLeft, safeTop, safeRight, safeBottom)
            ?: return ActionOutcome.Failed(
                reason = "Swipe distance too small after clamping",
                attemptTrail = emptyList()
            )

        val action = UIAction.Swipe(endpoints[0], endpoints[1], endpoints[2], endpoints[3], durationMs)
        val label = "swipe $direction ($distanceRaw)"
        return dispatchSwipe(action, label, snapshot, platform)
    }

    private suspend fun dispatchSwipe(
        action: UIAction.Swipe,
        label: String,
        preSnapshot: ScreenSnapshot?,
        platform: AndroidPlatform
    ): ActionOutcome {
        val result = platform.performAction(action)
        if (result is ActionResult.Failure) {
            return ActionOutcome.Failed(
                reason = result.reason,
                attemptTrail = listOf("$label: ${result.reason}")
            )
        }

        delay(UI_SETTLE_DELAY_MS)
        val post = runCatching { platform.captureScreen() }.getOrNull()
        val observation = post?.let { buildObservation(it, platform) }

        val warnings = mutableListOf<String>()
        val boundary = uiChangeDetector.detectScrollBoundary(preSnapshot, post)
        if (boundary != null) warnings.add(boundary)

        val message = buildString {
            append("Swipe $label from (${action.startX},${action.startY}) to (${action.endX},${action.endY})")
            if (warnings.isNotEmpty()) append(". Warnings: ${warnings.joinToString("; ")}")
        }

        return ActionOutcome.Success(
            message = message,
            observation = observation,
            attemptTrail = listOf("$label: success"),
            verified = true
        )
    }

    // --- Geometry helpers ---

    private fun parseOptionalTarget(params: JSONObject): Target? = when {
        params.has("element_index") ->
            Target.ElementIndex(params.getInt("element_index"))
        params.optString("text", "").trim().isNotEmpty() ->
            Target.Text(params.getString("text"), params.optInt("text_index", 0))
        params.has("x") && params.has("y") ->
            Target.Coordinate(params.getInt("x"), params.getInt("y"))
        else -> null
    }

    private fun computeDistancePx(distance: String, baseSize: Int, density: Float): Int {
        val factor = when (distance) {
            "short" -> 0.15f
            "medium" -> 0.4f
            "long" -> 0.7f
            else -> 0.4f
        }
        val raw = (baseSize * factor).roundToInt()
        val minPx = max((16f * density).roundToInt(), (baseSize * 0.1f).roundToInt())
        val maxPx = max(1, (baseSize * 0.9f).roundToInt())
        val minBound = min(minPx, maxPx)
        val maxBound = max(minPx, maxPx)
        return raw.coerceIn(minBound, maxBound)
    }

    private fun computeEndpoints(
        direction: String,
        originX: Int, originY: Int,
        delta: Int,
        safeLeft: Int, safeTop: Int, safeRight: Int, safeBottom: Int
    ): IntArray? {
        var startX = originX; var startY = originY
        var endX = originX; var endY = originY

        when (direction) {
            "down" -> { startY = originY - delta; endY = originY + delta }
            "up" -> { startY = originY + delta; endY = originY - delta }
            "left" -> { startX = originX + delta; endX = originX - delta }
            "right" -> { startX = originX - delta; endX = originX + delta }
        }

        startX = startX.coerceIn(safeLeft, safeRight)
        endX = endX.coerceIn(safeLeft, safeRight)
        startY = startY.coerceIn(safeTop, safeBottom)
        endY = endY.coerceIn(safeTop, safeBottom)

        return if (startX == endX && startY == endY) null
        else intArrayOf(startX, startY, endX, endY)
    }

    private fun computeSafeInset(screenWidth: Int, screenHeight: Int, density: Float): Int {
        val minDimension = min(screenWidth, screenHeight)
        val percentInset = (minDimension * 0.05f).roundToInt()
        val dpInset = (24f * density).roundToInt()
        return max(percentInset, dpInset)
    }
}
