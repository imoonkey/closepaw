package com.moonkey.androidagent.llm

import android.util.Log

internal sealed interface StreamRetryAction {
    data class Retry(val waitMs: Long, val nextBackoffMs: Long) : StreamRetryAction

    data class FailAndStop(val message: String) : StreamRetryAction

    data object Stop : StreamRetryAction
}

/** Shared retry decision policy for cloud streaming clients. */
internal object CloudStreamRetryPolicy {
    fun decide(
            tag: String,
            classified: Exception,
            attempt: Int,
            emittedEvent: Boolean,
            backoffMs: Long
    ): StreamRetryAction {
        val retryable = classified is RateLimitException || classified is TransientException

        if (retryable && emittedEvent) {
            Log.w(tag, "Stream error after output; skipping retry: ${classified.message}")
            return StreamRetryAction.FailAndStop(
                    "Stream interrupted after partial output: ${classified.message}"
            )
        }

        if (retryable && attempt < LLMClient.MAX_RETRIES) {
            val waitMs =
                    when (classified) {
                        is RateLimitException -> classified.retryAfterMs ?: backoffMs
                        else -> backoffMs
                    }
            Log.w(
                    tag,
                    "Retryable stream error (attempt $attempt/${LLMClient.MAX_RETRIES}), waiting ${waitMs}ms"
            )
            return StreamRetryAction.Retry(
                    waitMs = waitMs,
                    nextBackoffMs = CloudLlmRetry.advanceBackoff(backoffMs)
            )
        }

        Log.e(tag, "Streaming failed with non-retryable error", classified)
        return StreamRetryAction.Stop
    }
}
