package ai.closepaw.tool.action

import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.tool.AppClassifier
import ai.closepaw.tool.ToolObservation
import kotlinx.coroutines.delay

internal data class PostActionAnalysis(
    val observation: ToolObservation?,
    val changeResult: UiChangeDetector.ChangeResult,
    val warnings: List<String>
)

private const val RETRY_SETTLE_DELAY_MS = 500L
private const val SLOW_TRANSITION_DELAY_MS = 1000L

internal suspend fun capturePostActionAnalysis(
    preSnapshot: ScreenSnapshot?,
    platform: AndroidPlatform,
    settleDelayMs: Long,
    appClassifier: AppClassifier? = null
): PostActionAnalysis {
    val captures = mutableListOf<CaptureAttempt>()
    captures += captureAttempt(platform, settleDelayMs)

    var latestResult = if (captures.last().snapshot == null) {
        UiChangeDetector.ChangeResult.Unverifiable
    } else {
        UiChangeDetector.compare(preSnapshot, captures.last().snapshot)
    }

    // Retry with increasing delays for slow transitions (e.g. intent resolution).
    // Total budget: settleDelayMs (300) + 500 + 1000 = 1800ms.
    if (latestResult == UiChangeDetector.ChangeResult.Unchanged) {
        captures += captureAttempt(platform, RETRY_SETTLE_DELAY_MS)
        latestResult = captures.last().snapshot?.let {
            UiChangeDetector.compare(preSnapshot, it)
        } ?: UiChangeDetector.ChangeResult.Unverifiable
    }
    if (latestResult == UiChangeDetector.ChangeResult.Unchanged) {
        captures += captureAttempt(platform, SLOW_TRANSITION_DELAY_MS)
    }

    val finalAttempt = captures.last()
    val postSnapshot = finalAttempt.snapshot
    val observation = postSnapshot?.let { buildObservation(it, platform, appClassifier) }

    val warnings = mutableListOf<String>()
    captures.mapNotNullTo(warnings) { attempt ->
        attempt.failureMessage?.let { "Post-action capture failed: $it" }
    }

    val changeResult = if (postSnapshot == null) {
        UiChangeDetector.ChangeResult.Unverifiable
    } else {
        UiChangeDetector.compare(preSnapshot, postSnapshot)
    }

    when (changeResult) {
        UiChangeDetector.ChangeResult.Changed -> Unit
        UiChangeDetector.ChangeResult.Unchanged ->
            warnings += "No observable screen change detected after action"
        UiChangeDetector.ChangeResult.Unverifiable -> {
            if (captures.none { it.failureMessage != null }) {
                warnings += "Post-action change check unavailable"
            }
        }
    }

    return PostActionAnalysis(
        observation = observation,
        changeResult = changeResult,
        warnings = warnings
    )
}

private data class CaptureAttempt(
    val snapshot: ScreenSnapshot?,
    val failureMessage: String?
)

private suspend fun captureAttempt(
    platform: AndroidPlatform,
    delayMs: Long
): CaptureAttempt {
    delay(delayMs)
    val result = runCatching { platform.captureScreen() }
    return CaptureAttempt(
        snapshot = result.getOrNull(),
        failureMessage = result.exceptionOrNull()?.message
    )
}
