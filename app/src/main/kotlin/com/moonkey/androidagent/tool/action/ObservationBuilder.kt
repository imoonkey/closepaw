package com.moonkey.androidagent.tool.action

import android.util.Log
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.perception.toSummary
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.tool.ToolObservation

private const val TAG = "ObservationBuilder"

/** Build a ToolObservation from a post-action snapshot. */
internal fun buildObservation(
    snapshot: ScreenSnapshot,
    platform: AndroidPlatform
): ToolObservation.ScreenState? {
    return try {
        val tree = Perceptor.toPromptJson(snapshot)
        ToolObservation.ScreenState(
            accessibilityTree = tree,
            elementCount = snapshot.elements.orEmpty().size,
            summary = snapshot.toSummary(platform.getCurrentPackageName()),
            snapshot = snapshot
        )
    } catch (e: Exception) {
        Log.w(TAG, "Failed to build observation: ${e.message}")
        null
    }
}
