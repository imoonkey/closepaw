package com.moonkey.androidagent.tool.action

import com.moonkey.androidagent.model.Point
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.DisplayInfo
import com.moonkey.androidagent.platform.UIAction
import kotlinx.coroutines.delay

/**
 * Click executor: resolve target, try ACTION_CLICK then gesture tap.
 *
 * Fallback table (same for all target types):
 *   Attempt 1: ClickNodeAt(x, y)   — accessibility ACTION_CLICK
 *   Attempt 2: TapAt(x, y)         — gesture tap
 *
 * Each attempt: dispatch → settle → verify UI change.
 */
class ClickExecutor(
    private val targetResolver: TargetResolver = TargetResolver,
    private val uiChangeDetector: UiChangeDetector = UiChangeDetector
) {
    companion object {
        private const val UI_SETTLE_DELAY_MS = 300L
        private const val RETRY_JITTER_PX = 12
        private const val MAX_TOTAL_ATTEMPTS = 12
        private val JITTER_OFFSETS =
            listOf(
                Pair(-RETRY_JITTER_PX, 0),
                Pair(RETRY_JITTER_PX, 0),
                Pair(0, -RETRY_JITTER_PX),
                Pair(0, RETRY_JITTER_PX)
            )
    }

    private data class ClickAttemptSpec(
        val label: String,
        val point: Point,
        val action: UIAction
    )

    suspend fun execute(
        target: Target,
        snapshot: ScreenSnapshot?,
        platform: AndroidPlatform,
        isCancelled: () -> Boolean
    ): ActionOutcome {
        val displayInfo = platform.getDisplayInfo()
        val initialPoint = targetResolver.resolve(target, snapshot)
            ?: return ActionOutcome.Failed(
                reason = targetResolver.describeFailure(target, snapshot),
                attemptTrail = emptyList()
            )
        if (!isWithinDisplayBounds(initialPoint, displayInfo)) {
            return ActionOutcome.Failed(
                reason =
                    "Resolved click target (${initialPoint.x},${initialPoint.y}) is outside display bounds " +
                        "${displayInfo.widthPixels}x${displayInfo.heightPixels}",
                attemptTrail = emptyList()
            )
        }

        val attemptTrail = mutableListOf<String>()
        val attempts = mutableListOf<ClickAttemptSpec>()
        attempts.addAll(buildPrimaryAttempts(initialPoint, "base"))

        var latestReferenceSnapshot = snapshot
        var addedReResolvedAttempts = false
        var addedJitterAttempts = false
        var index = 0
        while (index < attempts.size && index < MAX_TOTAL_ATTEMPTS) {
            val attempt = attempts[index]
            index += 1
            if (isCancelled()) return ActionOutcome.Cancelled("Cancelled between attempts")

            val result = platform.performAction(attempt.action)

            when (result) {
                is ActionResult.Failure -> {
                    attemptTrail.add("${attempt.label}: ${result.reason}")
                    continue
                }
                is ActionResult.Cancelled -> {
                    return ActionOutcome.Cancelled(result.reason)
                }
                is ActionResult.Success -> {
                    // Continue to post-action verification.
                }
            }

            delay(UI_SETTLE_DELAY_MS)
            val postResult = runCatching { platform.captureScreen() }
            val post = postResult.getOrNull()
            if (post == null) {
                val reason = postResult.exceptionOrNull()?.message ?: "unknown capture error"
                attemptTrail.add("${attempt.label}: dispatched, capture failed ($reason)")
                addedJitterAttempts =
                    scheduleJitterAttemptsIfNeeded(
                        attemptTrail = attemptTrail,
                        attempts = attempts,
                        basePoint = attempt.point,
                        displayInfo = displayInfo,
                        alreadyScheduled = addedJitterAttempts
                    )
                continue
            }
            val observation = buildObservation(post, platform)
            val change = uiChangeDetector.compare(latestReferenceSnapshot, post)

            when (change) {
                UiChangeDetector.ChangeResult.Changed -> {
                    attemptTrail.add("${attempt.label}: success (UI changed)")
                    return ActionOutcome.Success(
                        message = "Clicked (${attempt.point.x},${attempt.point.y}) via ${attempt.label}",
                        observation = observation,
                        attemptTrail = attemptTrail,
                        verified = true
                    )
                }
                UiChangeDetector.ChangeResult.Unverifiable -> {
                    attemptTrail.add("${attempt.label}: dispatched (unverifiable)")
                    return ActionOutcome.Success(
                        message =
                            "Clicked (${attempt.point.x},${attempt.point.y}) via ${attempt.label} [unverified]",
                        observation = observation,
                        attemptTrail = attemptTrail,
                        verified = false
                    )
                }
                UiChangeDetector.ChangeResult.Unchanged -> {
                    attemptTrail.add("${attempt.label}: dispatched, no UI change")
                    latestReferenceSnapshot = post

                    if (!addedReResolvedAttempts && target !is Target.Coordinate) {
                        addedReResolvedAttempts = true
                        val reResolvedPoint = targetResolver.resolve(target, post)
                        if (reResolvedPoint != null && reResolvedPoint != attempt.point) {
                            attemptTrail.add(
                                "re_resolve: refreshed target to (${reResolvedPoint.x},${reResolvedPoint.y})"
                            )
                            attempts.addAll(buildPrimaryAttempts(reResolvedPoint, "re_resolved"))
                        }
                    }

                    addedJitterAttempts =
                        scheduleJitterAttemptsIfNeeded(
                            attemptTrail = attemptTrail,
                            attempts = attempts,
                            basePoint = attempt.point,
                            displayInfo = displayInfo,
                            alreadyScheduled = addedJitterAttempts
                        )
                }
            }
        }

        return ActionOutcome.Failed(
            reason = "Click at (${initialPoint.x},${initialPoint.y}) failed after all attempts",
            attemptTrail = attemptTrail
        )
    }

    private fun buildPrimaryAttempts(point: Point, labelPrefix: String): List<ClickAttemptSpec> {
        return listOf(
            ClickAttemptSpec(
                label = "${labelPrefix}_ACTION_CLICK",
                point = point,
                action = UIAction.ClickNodeAt(point.x, point.y)
            ),
            ClickAttemptSpec(
                label = "${labelPrefix}_gesture_tap",
                point = point,
                action = UIAction.TapAt(point.x, point.y)
            )
        )
    }

    private fun buildJitterAttempts(basePoint: Point, displayInfo: DisplayInfo): List<ClickAttemptSpec> {
        val maxX = (displayInfo.widthPixels - 1).coerceAtLeast(0)
        val maxY = (displayInfo.heightPixels - 1).coerceAtLeast(0)
        return JITTER_OFFSETS.mapIndexed { index, (dx, dy) ->
            val jitteredPoint =
                Point(
                    x = (basePoint.x + dx).coerceIn(0, maxX),
                    y = (basePoint.y + dy).coerceIn(0, maxY)
                )
            ClickAttemptSpec(
                label = "jitter_${index + 1}_gesture_tap",
                point = jitteredPoint,
                action = UIAction.TapAt(jitteredPoint.x, jitteredPoint.y)
            )
        }
    }

    private fun scheduleJitterAttemptsIfNeeded(
        attemptTrail: MutableList<String>,
        attempts: MutableList<ClickAttemptSpec>,
        basePoint: Point,
        displayInfo: DisplayInfo,
        alreadyScheduled: Boolean
    ): Boolean {
        if (alreadyScheduled) return true
        attemptTrail.add("jitter: scheduling ${JITTER_OFFSETS.size} nearby tap retries")
        attempts.addAll(buildJitterAttempts(basePoint, displayInfo))
        return true
    }

    private fun isWithinDisplayBounds(point: Point, displayInfo: DisplayInfo): Boolean {
        if (displayInfo.widthPixels <= 0 || displayInfo.heightPixels <= 0) return true
        return point.x in 0 until displayInfo.widthPixels &&
            point.y in 0 until displayInfo.heightPixels
    }
}
