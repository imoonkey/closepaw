package com.moonkey.androidagent.tool.handlers

import android.util.Log
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolObservation
import kotlinx.coroutines.delay

internal object TargetingInvocationUtils {
    private const val DEFAULT_UI_SETTLE_DELAY_MS = 300L

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
                snapshot = snapshot
            )
        } catch (e: Exception) {
            Log.w(logTag, "Failed to capture post-action observation: ${e.message}")
            null
        }
    }
}

