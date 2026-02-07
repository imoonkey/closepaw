package com.moonkey.androidagent.tool.handlers

import com.moonkey.androidagent.model.Bounds
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ToolObservation
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * Invocation for swipe action using direction + optional target selector.
 *
 * This mirrors reference systems that keep swipe explicit, while providing a semantic direction
 * mode to reduce brittle coordinate math in prompts.
 */
class SwipeTargetInvocation(override val params: JSONObject, private val description: String) :
        ToolInvocation {

    companion object {
        private const val TAG = "SwipeTargetInvocation"
        private const val DEFAULT_DIRECTIONAL_DURATION_MS = 400L
        private val VALID_DIRECTIONS = setOf("up", "down", "left", "right")
        private val VALID_DISTANCES = setOf("short", "medium", "long")
    }

    override val toolName: String = "mobile_action"

    override fun getDescription(): String {
        val agentThought = params.optString("agent_thought", "").trim()
        return if (agentThought.isNotEmpty()) {
            "$description (reason: $agentThought)"
        } else {
            description
        }
    }

    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        if (context.isCancelled()) {
            return ToolExecutionResult.Cancelled("Cancelled before execution")
        }

        val directionRaw = params.optString("direction", "").trim().lowercase()
        if (directionRaw.isEmpty()) {
            return ToolExecutionResult.Failure(
                    "Swipe direction is required when start/end are not provided"
            )
        }
        if (!VALID_DIRECTIONS.contains(directionRaw)) {
            return ToolExecutionResult.Failure("direction must be one of: up, down, left, right")
        }

        val distanceRaw = params.optString("distance", "medium").trim().lowercase()
        if (distanceRaw.isNotEmpty() && !VALID_DISTANCES.contains(distanceRaw)) {
            return ToolExecutionResult.Failure("distance must be one of: short, medium, long")
        }

        val display = context.platform.getDisplayInfo()
        val screenWidth = display.widthPixels
        val screenHeight = display.heightPixels
        if (screenWidth <= 1 || screenHeight <= 1) {
            return ToolExecutionResult.Failure(
                    "Invalid display dimensions ($screenWidth x $screenHeight)"
            )
        }

        val screenRect = Rect(0, 0, screenWidth - 1, screenHeight - 1)
        val safeInset = computeSafeInsetPx(screenWidth, screenHeight, display.density)
        val safeRect = screenRect.inset(safeInset).takeIf { it.isValid() } ?: screenRect

        val snapshot = context.currentSnapshot
        val selectorAttempts =
                MultiSelectorTargeting.attemptsFromParams(
                        params = params,
                        textKey = "text",
                        textIndexKey = "text_index",
                        textLabel = "text"
                )
        val hasSelector = selectorAttempts.isNotEmpty()

        val attemptErrors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val resolved =
                if (hasSelector) {
                    resolveTarget(
                            attempts = selectorAttempts,
                            snapshot = snapshot,
                            screenRect = screenRect,
                            safeRect = safeRect,
                            warnings = warnings,
                            attemptErrors = attemptErrors
                    )
                } else {
                    ResolvedTarget(
                            originX = safeRect.centerX(),
                            originY = safeRect.centerY(),
                            activeRect = safeRect,
                            label = "screen center"
                    )
                }

        if (resolved == null) {
            val details =
                    if (attemptErrors.isNotEmpty()) {
                        " Attempts: ${attemptErrors.joinToString("; ")}"
                    } else {
                        ""
                    }
            return ToolExecutionResult.Failure("Failed to resolve swipe target.$details")
        }

        val direction =
                SwipeDirection.from(directionRaw)
                        ?: return ToolExecutionResult.Failure(
                                "direction must be one of: up, down, left, right"
                        )
        val baseSize =
                if (direction.isVertical()) resolved.activeRect.height
                else resolved.activeRect.width
        if (baseSize <= 0) {
            return ToolExecutionResult.Failure("Swipe target area is too small")
        }

        val distancePx =
                computeDistancePx(
                        distance = distanceRaw.ifEmpty { "medium" },
                        baseSize = baseSize,
                        density = display.density
                )
        val delta = max(1, distancePx / 2)

        val (startX, startY, endX, endY) =
                computeSwipeEndpoints(
                        direction = direction,
                        originX = resolved.originX,
                        originY = resolved.originY,
                        delta = delta,
                        activeRect = resolved.activeRect
                )
                        ?: return ToolExecutionResult.Failure(
                                "Swipe distance too small after clamping"
                        )

        val durationMs = params.optLong("duration_ms", DEFAULT_DIRECTIONAL_DURATION_MS)
        val action = UIAction.Swipe(startX, startY, endX, endY, durationMs)
        val result = context.platform.performAction(action, snapshot)

        return when (result) {
            is ActionResult.Success -> {
                val observation =
                        TargetingInvocationUtils.capturePostActionObservation(context, TAG)

                // Compare pre-action and post-action screen state to detect scroll boundary
                val scrollBoundaryWarning = TargetingInvocationUtils.detectScrollBoundary(snapshot, observation)
                if (scrollBoundaryWarning != null) {
                    warnings.add(scrollBoundaryWarning)
                }

                val warningSuffix =
                        if (warnings.isNotEmpty()) {
                            " Warnings: ${warnings.joinToString("; ")}"
                        } else {
                            ""
                        }
                val labelSuffix = resolved.label?.let { " (using $it)" } ?: ""
                ToolExecutionResult.Success(
                        output =
                                "Swipe ${directionRaw} (${distanceRaw.ifEmpty { "medium" }}) " +
                                        "from ($startX,$startY) to ($endX,$endY)$labelSuffix.$warningSuffix",
                        observation = observation
                )
            }
            is ActionResult.Failure -> ToolExecutionResult.Failure(result.reason)
            is ActionResult.ElementNotFound -> {
                val reason =
                        if (snapshot != null) {
                            TargetingInvocationUtils.buildElementNotFoundMessage(
                                    result.elementIndex,
                                    snapshot
                            )
                        } else {
                            "Element not found: index ${result.elementIndex} (no snapshot available)"
                        }
                ToolExecutionResult.Failure(reason)
            }
            is ActionResult.Cancelled -> ToolExecutionResult.Cancelled(result.reason)
        }
    }

    private fun resolveTarget(
            attempts: List<MultiSelectorTargeting.Attempt>,
            snapshot: ScreenSnapshot?,
            screenRect: Rect,
            safeRect: Rect,
            warnings: MutableList<String>,
            attemptErrors: MutableList<String>
    ): ResolvedTarget? {
        for (attempt in attempts) {
            val selector = attempt.selector
            val label = attempt.label
            when (selector) {
                is MultiSelectorTargeting.Selector.Bounds -> {
                    val rect = rectFromBounds(selector.x1, selector.y1, selector.x2, selector.y2)
                    val resolved =
                            resolveFromRect(
                                    rect = rect,
                                    screenRect = screenRect,
                                    safeRect = safeRect,
                                    label = label,
                                    warnings = warnings,
                                    attemptErrors = attemptErrors
                            )
                    if (resolved != null) return resolved
                }
                is MultiSelectorTargeting.Selector.Point -> {
                    val pointResolved =
                            resolveFromPoint(
                                    x = selector.x,
                                    y = selector.y,
                                    screenRect = screenRect,
                                    safeRect = safeRect,
                                    label = label,
                                    warnings = warnings,
                                    attemptErrors = attemptErrors
                            )
                    if (pointResolved != null) return pointResolved
                }
                is MultiSelectorTargeting.Selector.Text -> {
                    if (snapshot == null) {
                        attemptErrors.add("$label: Snapshot required for text lookup")
                        continue
                    }
                    val elementIndex =
                            MultiSelectorTargeting.findElementIndexByTextOrDescription(
                                    snapshot = snapshot,
                                    text = selector.text,
                                    index = selector.index
                            )
                    if (elementIndex == null) {
                        val count =
                                MultiSelectorTargeting.matchCountByTextOrDescription(
                                        snapshot,
                                        selector.text
                                )
                        attemptErrors.add(
                                "text=\"${selector.text}\" index ${selector.index} out of range (found $count)"
                        )
                        continue
                    }
                    val element = snapshot.elements.firstOrNull { it.index == elementIndex }
                    if (element == null) {
                        attemptErrors.add(
                                "text=\"${selector.text}\" resolved to missing element index $elementIndex"
                        )
                        continue
                    }
                    if (!element.isScrollable) {
                        warnings.add("Target element is not marked scrollable")
                    }
                    val resolved =
                            resolveFromBounds(
                                    bounds = element.bounds,
                                    screenRect = screenRect,
                                    safeRect = safeRect,
                                    label = label,
                                    warnings = warnings,
                                    attemptErrors = attemptErrors
                            )
                    if (resolved != null) return resolved
                }
                is MultiSelectorTargeting.Selector.ElementIndex -> {
                    if (snapshot == null) {
                        attemptErrors.add("$label: Snapshot required for element_index lookup")
                        continue
                    }
                    val element =
                            snapshot.elements.firstOrNull { it.index == selector.elementIndex }
                    if (element == null) {
                        attemptErrors.add(
                                TargetingInvocationUtils.buildElementNotFoundMessage(
                                        selector.elementIndex,
                                        snapshot
                                )
                        )
                        continue
                    }
                    if (!element.isScrollable) {
                        warnings.add("Target element is not marked scrollable")
                    }
                    val resolved =
                            resolveFromBounds(
                                    bounds = element.bounds,
                                    screenRect = screenRect,
                                    safeRect = safeRect,
                                    label = label,
                                    warnings = warnings,
                                    attemptErrors = attemptErrors
                            )
                    if (resolved != null) return resolved
                }
            }
        }

        return null
    }

    private fun resolveFromBounds(
            bounds: Bounds,
            screenRect: Rect,
            safeRect: Rect,
            label: String,
            warnings: MutableList<String>,
            attemptErrors: MutableList<String>
    ): ResolvedTarget? {
        return resolveFromRect(
                rect = rectFromBounds(bounds),
                screenRect = screenRect,
                safeRect = safeRect,
                label = label,
                warnings = warnings,
                attemptErrors = attemptErrors
        )
    }

    private fun resolveFromRect(
            rect: Rect,
            screenRect: Rect,
            safeRect: Rect,
            label: String,
            warnings: MutableList<String>,
            attemptErrors: MutableList<String>
    ): ResolvedTarget? {
        val clippedToScreen = rect.intersect(screenRect)
        if (clippedToScreen == null) {
            attemptErrors.add("$label: bounds are off-screen")
            return null
        }

        val activeRect = clippedToScreen.intersect(safeRect)
        if (activeRect == null) {
            attemptErrors.add("$label: bounds outside safe area")
            return null
        }

        if (clippedToScreen != rect) {
            warnings.add("Target bounds clipped to screen")
        }
        if (activeRect != clippedToScreen) {
            warnings.add("Target bounds clipped to safe area")
        }

        return ResolvedTarget(
                originX = activeRect.centerX(),
                originY = activeRect.centerY(),
                activeRect = activeRect,
                label = label
        )
    }

    private fun resolveFromPoint(
            x: Int,
            y: Int,
            screenRect: Rect,
            safeRect: Rect,
            label: String,
            warnings: MutableList<String>,
            attemptErrors: MutableList<String>
    ): ResolvedTarget? {
        if (!screenRect.contains(x, y)) {
            attemptErrors.add("$label: coordinates out of screen bounds")
            return null
        }
        val clampedX = safeRect.clampX(x)
        val clampedY = safeRect.clampY(y)
        if (clampedX != x || clampedY != y) {
            warnings.add("Target coordinates clamped to safe area")
        }
        return ResolvedTarget(
                originX = clampedX,
                originY = clampedY,
                activeRect = safeRect,
                label = label
        )
    }

    private fun rectFromBounds(bounds: Bounds): Rect {
        return rectFromBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    private fun rectFromBounds(x1: Int, y1: Int, x2: Int, y2: Int): Rect {
        val left = min(x1, x2)
        val right = max(x1, x2)
        val top = min(y1, y2)
        val bottom = max(y1, y2)
        return Rect(left, top, right, bottom)
    }

    private fun computeDistancePx(distance: String, baseSize: Int, density: Float): Int {
        val factor =
                when (distance) {
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

    private fun computeSwipeEndpoints(
            direction: SwipeDirection,
            originX: Int,
            originY: Int,
            delta: Int,
            activeRect: Rect
    ): IntArray? {
        var startX = originX
        var startY = originY
        var endX = originX
        var endY = originY

        when (direction) {
            SwipeDirection.DOWN -> {
                // Finger moves down: start high (smaller Y), end low (larger Y)
                startY = originY - delta
                endY = originY + delta
            }
            SwipeDirection.UP -> {
                // Finger moves up: start low (larger Y), end high (smaller Y)
                startY = originY + delta
                endY = originY - delta
            }
            SwipeDirection.LEFT -> {
                // Finger moves left: start right (larger X), end left (smaller X)
                startX = originX + delta
                endX = originX - delta
            }
            SwipeDirection.RIGHT -> {
                // Finger moves right: start left (smaller X), end right (larger X)
                startX = originX - delta
                endX = originX + delta
            }
        }

        startX = activeRect.clampX(startX)
        endX = activeRect.clampX(endX)
        startY = activeRect.clampY(startY)
        endY = activeRect.clampY(endY)

        if (startX == endX && startY == endY) {
            return null
        }

        return intArrayOf(startX, startY, endX, endY)
    }

    private fun computeSafeInsetPx(screenWidth: Int, screenHeight: Int, density: Float): Int {
        val minDimension = min(screenWidth, screenHeight)
        val percentInset = (minDimension * 0.05f).roundToInt()
        val dpInset = (24f * density).roundToInt()
        return max(percentInset, dpInset)
    }

    private data class ResolvedTarget(
            val originX: Int,
            val originY: Int,
            val activeRect: Rect,
            val label: String?
    )

    private data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int = right - left
        val height: Int = bottom - top

        fun isValid(): Boolean = width > 0 && height > 0

        fun centerX(): Int = (left + right) / 2
        fun centerY(): Int = (top + bottom) / 2

        fun inset(pixels: Int): Rect {
            return Rect(left + pixels, top + pixels, right - pixels, bottom - pixels)
        }

        fun intersect(other: Rect): Rect? {
            val l = max(left, other.left)
            val t = max(top, other.top)
            val r = min(right, other.right)
            val b = min(bottom, other.bottom)
            return if (r > l && b > t) Rect(l, t, r, b) else null
        }

        fun contains(x: Int, y: Int): Boolean {
            return x in left..right && y in top..bottom
        }

        fun clampX(x: Int): Int = x.coerceIn(left, right)
        fun clampY(y: Int): Int = y.coerceIn(top, bottom)
    }
    private enum class SwipeDirection {
        UP,
        DOWN,
        LEFT,
        RIGHT;

        fun isVertical(): Boolean = this == UP || this == DOWN

        companion object {
            fun from(raw: String): SwipeDirection? {
                return when (raw.lowercase()) {
                    "up" -> UP
                    "down" -> DOWN
                    "left" -> LEFT
                    "right" -> RIGHT
                    else -> null
                }
            }
        }
    }
}
