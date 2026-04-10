package com.moonkey.androidagent.tool.action

import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tool.AppClassifier
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.max

/**
 * Swipe executor: raw coordinate-based gesture from start to end.
 *
 * No direction/distance abstraction — coordinates map directly to gesture.
 * Used for precision gestures: sliders, drag-and-drop, carousels.
 */
class SwipeExecutor {
    companion object {
        private const val DEFAULT_SWIPE_DURATION_MS = 400L
        private const val MIN_SETTLE_DELAY_MS = 300L
    }

    suspend fun execute(
        params: JSONObject,
        snapshot: ScreenSnapshot?,
        platform: AndroidPlatform,
        isCancelled: () -> Boolean,
        appClassifier: AppClassifier? = null
    ): ActionOutcome {
        if (isCancelled()) return ActionOutcome.Cancelled("Cancelled before swipe")

        val start = params.getJSONArray("start")
        val end = params.getJSONArray("end")
        val sx = start.getInt(0)
        val sy = start.getInt(1)
        val ex = end.getInt(0)
        val ey = end.getInt(1)
        val durationMs = params.optLong("duration_ms", DEFAULT_SWIPE_DURATION_MS)

        val action = UIAction.Swipe(sx, sy, ex, ey, durationMs)
        val result = platform.performAction(action)

        if (result is ActionResult.Failure) {
            return ActionOutcome.Failed(
                reason = "Swipe ($sx,$sy)→($ex,$ey) failed: ${result.reason}",
                attemptTrail = listOf("swipe: ${result.reason}")
            )
        }

        if (result is ActionResult.Cancelled) {
            return ActionOutcome.Cancelled(
                "Swipe ($sx,$sy)→($ex,$ey) cancelled: ${result.reason}"
            )
        }

        val analysis = capturePostActionAnalysis(
            preSnapshot = snapshot,
            platform = platform,
            settleDelayMs = max(MIN_SETTLE_DELAY_MS, (durationMs * 0.75).toLong()),
            appClassifier = appClassifier
        )

        return ActionOutcome.Success(
            message = formatActionMessage(
                "Swiped ($sx,$sy)→($ex,$ey) over ${durationMs}ms",
                analysis.warnings
            ),
            observation = analysis.observation,
            attemptTrail = listOf("swipe: success"),
            verified = analysis.changeResult == UiChangeDetector.ChangeResult.Changed
        )
    }
}
