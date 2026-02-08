package com.moonkey.androidagent.tool.handlers

import android.util.Log
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.perception.toSummary
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolObservation
import kotlinx.coroutines.delay

internal object TargetingInvocationUtils {
    private const val DEFAULT_UI_SETTLE_DELAY_MS = 300L

    data class UiChangeDetection(
        val changed: Boolean,
        val reason: String
    )

    fun buildElementNotFoundMessage(index: Int, snapshot: ScreenSnapshot): String {
        val available = snapshot.elements.map { it.index }
        val preview = available.take(20).joinToString(", ")
        val more = if (available.size > 20) " ... and ${available.size - 20} more" else ""
        return if (available.isNotEmpty()) {
            "Element not found: index $index. Available indices: $preview$more"
        } else {
            "Element not found: index $index. No elements available."
        }
    }

    suspend fun capturePostActionObservation(
            context: ToolExecutionContext,
            logTag: String,
            uiSettleDelayMs: Long = DEFAULT_UI_SETTLE_DELAY_MS
    ): ToolObservation? {
        return try {
            delay(uiSettleDelayMs)
            val snapshot = context.platform.captureScreen()
            val tree = Perceptor.toPromptJson(snapshot)
            ToolObservation.ScreenState(
                    accessibilityTree = tree,
                    elementCount = snapshot.elements.size,
                    summary = snapshot.toSummary(context.platform.getCurrentPackageName()),
                    snapshot = snapshot
            )
        } catch (e: Exception) {
            Log.w(logTag, "Failed to capture post-action observation: ${e.message}")
            null
        }
    }

    fun detectUiChange(
            preSnapshot: ScreenSnapshot?,
            postObservation: ToolObservation?
    ): UiChangeDetection {
        if (preSnapshot == null) {
            return UiChangeDetection(
                    changed = true,
                    reason = "Pre-action snapshot unavailable; treating as unverifiable success"
            )
        }

        val postSnapshot =
                (postObservation as? ToolObservation.ScreenState)?.snapshot
                        ?: return UiChangeDetection(
                                changed = true,
                                reason = "Post-action snapshot unavailable; treating as unverifiable success"
                        )

        val preFingerprint = snapshotFingerprint(preSnapshot)
        val postFingerprint = snapshotFingerprint(postSnapshot)
        return if (preFingerprint != postFingerprint) {
            UiChangeDetection(changed = true, reason = "Observable UI change detected")
        } else {
            UiChangeDetection(changed = false, reason = "No observable UI change after action")
        }
    }

    private fun snapshotFingerprint(snapshot: ScreenSnapshot): List<String> {
        return snapshot
                .elements
                .sortedBy { it.index }
                .map { element ->
                    listOf(
                                    element.index.toString(),
                                    element.resourceId,
                                    element.className,
                                    element.text,
                                    element.description,
                                    element.bounds.left.toString(),
                                    element.bounds.top.toString(),
                                    element.bounds.right.toString(),
                                    element.bounds.bottom.toString(),
                                    element.isFocused.toString(),
                                    element.isEnabled.toString()
                            )
                            .joinToString("|")
                }
    }

    /**
     * Detects if a swipe reached a scroll boundary by comparing before/after screen state.
     * Returns a warning message if the screen appears unchanged, null otherwise.
     */
    fun detectScrollBoundary(
            preSnapshot: ScreenSnapshot?,
            postObservation: ToolObservation?
    ): String? {
        if (preSnapshot == null) return null

        val postSnapshot =
                (postObservation as? ToolObservation.ScreenState)?.snapshot ?: return null

        // Extract text content from elements for comparison
        val preTexts =
                preSnapshot
                        .elements
                        .filter { it.text.isNotBlank() || it.description.isNotBlank() }
                        .map { "${it.text}|${it.description}|${it.bounds}" }
                        .sorted()

        val postTexts =
                postSnapshot
                        .elements
                        .filter { it.text.isNotBlank() || it.description.isNotBlank() }
                        .map { "${it.text}|${it.description}|${it.bounds}" }
                        .sorted()

        // If element lists are identical (same text, description, and bounds), screen didn't change
        if (preTexts == postTexts && preTexts.isNotEmpty()) {
            return "Screen content unchanged after swipe - may have reached scroll boundary"
        }

        return null
    }
}
