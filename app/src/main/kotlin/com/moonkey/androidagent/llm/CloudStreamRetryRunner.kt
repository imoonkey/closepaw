package com.moonkey.androidagent.llm

import kotlinx.coroutines.delay

internal fun interface StreamAttemptEmitter {
    fun emit(event: LLMStreamEvent)
}

internal data class StreamRetryRunResult(
    val completed: Boolean,
    val failureEmitted: Boolean,
    val lastError: Exception?
)

/**
 * Shared retry scaffold for cloud streaming calls.
 *
 * The caller provides a single-attempt block that emits events through [StreamAttemptEmitter].
 * This helper owns retry/backoff/fail-fast policy decisions.
 */
internal suspend fun streamWithRetry(
    tag: String,
    emitToFlow: (LLMStreamEvent) -> Unit,
    maxRetries: Int = LLMClient.MAX_RETRIES,
    initialBackoffMs: Long = LLMClient.INITIAL_BACKOFF_MS,
    attemptBlock: suspend (attempt: Int, emitter: StreamAttemptEmitter) -> Unit
): StreamRetryRunResult {
    var lastException: Exception? = null
    var backoffMs = initialBackoffMs
    var failureEmitted = false

    for (attempt in 1..maxRetries) {
        var emittedEvent = false
        val emitter =
            StreamAttemptEmitter { event ->
                if (event is LLMStreamEvent.Failed) {
                    failureEmitted = true
                }
                if (event is LLMStreamEvent.TextDelta || event is LLMStreamEvent.ToolCallDone) {
                    emittedEvent = true
                }
                emitToFlow(event)
            }

        try {
            attemptBlock(attempt, emitter)
            return StreamRetryRunResult(
                completed = true,
                failureEmitted = failureEmitted,
                lastError = null
            )
        } catch (e: Exception) {
            val classified = when (e) {
                is RateLimitException, is TransientException -> e
                else -> OpenAIErrorClassifier.classify(e)
            }
            lastException = classified
            when (
                val retryAction =
                    CloudStreamRetryPolicy.decide(
                        tag = tag,
                        classified = classified,
                        attempt = attempt,
                        emittedEvent = emittedEvent,
                        backoffMs = backoffMs
                    )
            ) {
                is StreamRetryAction.FailAndStop -> {
                    emitter.emit(LLMStreamEvent.Failed(retryAction.message))
                    return StreamRetryRunResult(
                        completed = false,
                        failureEmitted = failureEmitted,
                        lastError = classified
                    )
                }
                is StreamRetryAction.Retry -> {
                    delay(retryAction.waitMs)
                    backoffMs = retryAction.nextBackoffMs
                }
                StreamRetryAction.Stop -> {
                    return StreamRetryRunResult(
                        completed = false,
                        failureEmitted = failureEmitted,
                        lastError = classified
                    )
                }
            }
        }
    }

    return StreamRetryRunResult(
        completed = false,
        failureEmitted = failureEmitted,
        lastError = lastException
    )
}
