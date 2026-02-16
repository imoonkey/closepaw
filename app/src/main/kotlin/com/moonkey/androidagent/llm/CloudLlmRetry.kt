package com.moonkey.androidagent.llm

import android.util.Log
import kotlinx.coroutines.delay

/** Shared retry/backoff policy for cloud LLM clients. */
internal object CloudLlmRetry {
    fun advanceBackoff(currentMs: Long): Long =
            (currentMs * LLMClient.BACKOFF_MULTIPLIER).toLong()
                    .coerceAtMost(LLMClient.MAX_BACKOFF_MS)

    suspend fun <T> executeWithRetry(
            tag: String,
            operationName: String,
            block: suspend () -> T
    ): T {
        var lastException: Exception? = null
        var backoffMs = LLMClient.INITIAL_BACKOFF_MS

        for (attempt in 1..LLMClient.MAX_RETRIES) {
            try {
                return block()
            } catch (e: RateLimitException) {
                lastException = e
                if (attempt == LLMClient.MAX_RETRIES) {
                    Log.e(tag, "Max retries (${LLMClient.MAX_RETRIES}) exceeded for $operationName (rate limit)")
                    throw e
                }

                val waitMs = e.retryAfterMs ?: backoffMs
                Log.w(tag, "Rate limited (attempt $attempt/${LLMClient.MAX_RETRIES}), waiting ${waitMs}ms")
                delay(waitMs)
                backoffMs = advanceBackoff(backoffMs)
            } catch (e: TransientException) {
                lastException = e
                if (attempt == LLMClient.MAX_RETRIES) {
                    Log.e(tag, "Max retries (${LLMClient.MAX_RETRIES}) exceeded for $operationName (transient error)")
                    throw e.cause ?: e
                }

                Log.w(
                        tag,
                        "Transient error (attempt $attempt/${LLMClient.MAX_RETRIES}): ${e.message}, retrying in ${backoffMs}ms"
                )
                delay(backoffMs)
                backoffMs = advanceBackoff(backoffMs)
            }
        }

        throw lastException ?: RuntimeException("Unexpected error in retry loop for $operationName")
    }
}
