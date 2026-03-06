package com.moonkey.androidagent.tool.action

import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.tool.ToolObservation
import kotlinx.coroutines.delay

internal data class PostActionAnalysis(
    val observation: ToolObservation?,
    val changeResult: UiChangeDetector.ChangeResult,
    val warnings: List<String>
)

internal suspend fun capturePostActionAnalysis(
    preSnapshot: ScreenSnapshot?,
    platform: AndroidPlatform,
    settleDelayMs: Long
): PostActionAnalysis {
    delay(settleDelayMs)

    val postResult = runCatching { platform.captureScreen() }
    val postSnapshot = postResult.getOrNull()
    val observation = postSnapshot?.let { buildObservation(it, platform) }

    val warnings = mutableListOf<String>()
    val captureFailure = postResult.exceptionOrNull()?.message
    if (captureFailure != null) {
        warnings += "Post-action capture failed: $captureFailure"
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
            if (captureFailure == null) {
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
