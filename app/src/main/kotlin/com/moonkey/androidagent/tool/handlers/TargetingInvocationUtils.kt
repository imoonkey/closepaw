package com.moonkey.androidagent.tool.handlers

import android.util.Log
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.perception.toSummary
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolObservation
import kotlinx.coroutines.delay

internal object TargetingInvocationUtils {
    private const val DEFAULT_UI_SETTLE_DELAY_MS = 300L

    sealed interface AttemptOutcome {
        data class Success(
            val message: String,
            val observation: ToolObservation?
        ) : AttemptOutcome

        data class Retry(val reason: String) : AttemptOutcome

        data class Cancelled(val reason: String) : AttemptOutcome
    }

    data class UiChangeDetection(
        val changed: Boolean,
        val reason: String
    )

    suspend fun executeAttempt(
        context: ToolExecutionContext,
        action: UIAction,
        snapshotForAction: ScreenSnapshot?,
        snapshotForUiChange: ScreenSnapshot? = snapshotForAction,
        requireUiChange: Boolean = false,
        captureObservationOnSuccess: Boolean = true,
        logTag: String,
        uiSettleDelayMs: Long = DEFAULT_UI_SETTLE_DELAY_MS
    ): AttemptOutcome {
        val result = context.platform.performAction(action, snapshotForAction)
        return when (result) {
            is ActionResult.Success -> {
                val shouldCaptureObservation = captureObservationOnSuccess || requireUiChange
                val observation =
                    if (shouldCaptureObservation) {
                        capturePostActionObservation(context, logTag, uiSettleDelayMs)
                    } else {
                        null
                    }

                if (requireUiChange) {
                    val uiChange = detectUiChange(snapshotForUiChange, observation)
                    if (!uiChange.changed) {
                        AttemptOutcome.Retry(uiChange.reason)
                    } else {
                        AttemptOutcome.Success(result.message, observation)
                    }
                } else {
                    AttemptOutcome.Success(result.message, observation)
                }
            }
            is ActionResult.Failure -> AttemptOutcome.Retry(result.reason)
            is ActionResult.ElementNotFound -> {
                val reason =
                    if (snapshotForAction != null) {
                        buildElementNotFoundMessage(result.elementIndex, snapshotForAction)
                    } else {
                        "Element not found: index ${result.elementIndex} (no snapshot available)"
                    }
                AttemptOutcome.Retry(reason)
            }
            is ActionResult.Cancelled -> AttemptOutcome.Cancelled(result.reason)
        }
    }

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

    private fun snapshotFingerprint(snapshot: ScreenSnapshot): Long {
        var fingerprint = 1469598103934665603L
        for (element in snapshot.elements.sortedBy { it.index }) {
            fingerprint = mixFingerprint(fingerprint, element.index.toLong())
            fingerprint = mixFingerprint(fingerprint, element.resourceId.hashCode().toLong())
            fingerprint = mixFingerprint(fingerprint, element.className.hashCode().toLong())
            fingerprint = mixFingerprint(fingerprint, element.text.hashCode().toLong())
            fingerprint = mixFingerprint(fingerprint, element.description.hashCode().toLong())
            fingerprint = mixFingerprint(fingerprint, element.bounds.left.toLong())
            fingerprint = mixFingerprint(fingerprint, element.bounds.top.toLong())
            fingerprint = mixFingerprint(fingerprint, element.bounds.right.toLong())
            fingerprint = mixFingerprint(fingerprint, element.bounds.bottom.toLong())
            fingerprint = mixFingerprint(fingerprint, element.isFocused.hashCode().toLong())
            fingerprint = mixFingerprint(fingerprint, element.isEnabled.hashCode().toLong())
        }
        return fingerprint
    }

    private fun mixFingerprint(current: Long, value: Long): Long {
        return (current xor value) * 1099511628211L
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
