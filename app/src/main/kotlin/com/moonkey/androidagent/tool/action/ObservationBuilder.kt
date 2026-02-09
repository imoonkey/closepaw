package com.moonkey.androidagent.tool.action

import android.util.Log
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.perception.toSummary
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.tool.ToolObservation

private const val TAG = "ObservationBuilder"

/** Build a ToolObservation from a post-action snapshot. Mode-aware for screenshot-only. */
internal fun buildObservation(
    snapshot: ScreenSnapshot,
    platform: AndroidPlatform
): ToolObservation.ScreenState? {
    return try {
        val tree = if (snapshot.hasElements) {
            Perceptor.toPromptJson(snapshot)
        } else {
            "No accessibility data (screenshot-only mode)"
        }
        ToolObservation.ScreenState(
            accessibilityTree = tree,
            elementCount = snapshot.elements.size,
            summary = snapshot.toSummary(platform.getCurrentPackageName()),
            snapshot = snapshot
        )
    } catch (e: Exception) {
        Log.w(TAG, "Failed to build observation: ${e.message}")
        null
    }
}
