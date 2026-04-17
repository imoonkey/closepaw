package ai.closepaw.tool.action

import android.util.Log
import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.perception.Perceptor
import ai.closepaw.perception.toSummary
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.protocol.AppTier
import ai.closepaw.tool.AppClassifier
import ai.closepaw.tool.ToolObservation

private const val TAG = "ObservationBuilder"

/**
 * Build a ToolObservation from a post-action snapshot. Mode-aware for screenshot-only.
 *
 * When [appClassifier] is provided, BLOCKED-app snapshots are masked before building
 * the observation (defense-in-depth — the capture layer should already mask).
 */
internal fun buildObservation(
    snapshot: ScreenSnapshot,
    platform: AndroidPlatform,
    appClassifier: AppClassifier? = null
): ToolObservation.ScreenState? {
    return try {
        val packageName = platform.getCurrentPackageName()
        val isBlocked = appClassifier?.classify(packageName) == AppTier.BLOCKED
        val effective = if (isBlocked && appClassifier != null) {
            appClassifier.maskIfBlocked(snapshot, packageName)
        } else {
            snapshot
        }

        val tree = when {
            isBlocked -> "[BLOCKED] App content masked by privacy policy"
            effective.hasElements -> Perceptor.toPromptJson(effective)
            else -> "No accessibility data (screenshot-only mode)"
        }
        ToolObservation.ScreenState(
            accessibilityTree = tree,
            elementCount = effective.elements.size,
            summary = effective.toSummary(packageName),
            snapshot = effective
        )
    } catch (e: Exception) {
        Log.w(TAG, "Failed to build observation: ${e.message}")
        null
    }
}
