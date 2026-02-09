package com.moonkey.androidagent.tool.action

import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.AndroidPlatform
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
    }

    suspend fun execute(
        target: Target,
        snapshot: ScreenSnapshot?,
        platform: AndroidPlatform,
        isCancelled: () -> Boolean
    ): ActionOutcome {
        val point = targetResolver.resolve(target, snapshot)
            ?: return ActionOutcome.Failed(
                reason = targetResolver.describeFailure(target, snapshot),
                attemptTrail = emptyList()
            )

        val attemptTrail = mutableListOf<String>()
        val attempts = listOf(
            "ACTION_CLICK" to UIAction.ClickNodeAt(point.x, point.y),
            "gesture_tap" to UIAction.TapAt(point.x, point.y)
        )

        for ((label, action) in attempts) {
            if (isCancelled()) return ActionOutcome.Cancelled("Cancelled between attempts")

            val result = platform.performAction(action)

            if (result is ActionResult.Failure) {
                attemptTrail.add("$label: ${result.reason}")
                continue
            }

            delay(UI_SETTLE_DELAY_MS)
            val post = runCatching { platform.captureScreen() }.getOrNull()
            val observation = post?.let { buildObservation(it, platform) }
            val change = uiChangeDetector.compare(snapshot, post)

            when (change) {
                UiChangeDetector.ChangeResult.Changed -> {
                    attemptTrail.add("$label: success (UI changed)")
                    return ActionOutcome.Success(
                        message = "Clicked (${point.x},${point.y}) via $label",
                        observation = observation,
                        attemptTrail = attemptTrail,
                        verified = true
                    )
                }
                UiChangeDetector.ChangeResult.Unverifiable -> {
                    attemptTrail.add("$label: dispatched (unverifiable)")
                    return ActionOutcome.Success(
                        message = "Clicked (${point.x},${point.y}) via $label [unverified]",
                        observation = observation,
                        attemptTrail = attemptTrail,
                        verified = false
                    )
                }
                UiChangeDetector.ChangeResult.Unchanged -> {
                    attemptTrail.add("$label: dispatched, no UI change")
                }
            }
        }

        return ActionOutcome.Failed(
            reason = "Click at (${point.x},${point.y}) failed after all attempts",
            attemptTrail = attemptTrail
        )
    }
}
