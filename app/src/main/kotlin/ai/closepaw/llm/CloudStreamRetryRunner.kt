package ai.closepaw.llm

import kotlinx.coroutines.delay

internal fun interface StreamAttemptEmitter {
    fun emit(event: LLMStreamEvent)
}

internal data class StreamRetryRunResult(
    val completed: Boolean,
    val failureEmitted: Boolean,
    val lastError: Exception?
) {
    /** Emit final failure (if needed) and close the flow channel. */
    fun closeFlow(
        emitToFlow: (LLMStreamEvent) -> Unit,
        closeFlow: () -> Unit
    ) {
        if (!completed && !failureEmitted) {
            val message = lastError?.message ?: "Unknown error"
            emitToFlow(LLMStreamEvent.Failed(message))
        }
        closeFlow()
    }
}

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
                if (event is LLMStreamEvent.TextDelta || event is LLMStreamEvent.ToolCallDone || event is LLMStreamEvent.Failed) {
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
            classifyContextWindowError(e)?.let { throw it }
            val classified = when (e) {
                is RateLimitException, is TransientException -> e
                else -> OpenAIErrorClassifier.classify(e)
            }
            classifyContextWindowError(classified)?.let { throw it }
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
                    if (!failureEmitted) {
                        emitter.emit(LLMStreamEvent.Failed(retryAction.message))
                    }
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

/**
 * Detect provider signals that indicate the request exceeded the model's context
 * window. Recognizes:
 *  - OpenAI/Anthropic error bodies containing `prompt_too_long`,
 *    `request_too_long`, or `request_too_large`.
 *  - HTTP 413 (Payload Too Large).
 *  - Ollama's `prompt too long; exceeded max context length` (or just
 *    `exceeded max context length`).
 *
 * Returns `null` when the exception does not match any of the above; otherwise
 * wraps the original error in a [ContextWindowExceededException] so the runner
 * can rethrow it past the generic retry/backoff policy.
 */
private val HTTP_413_CODE = Regex("""(?<![A-Za-z0-9])413(?![A-Za-z0-9])""")

private fun classifyContextWindowError(e: Throwable): ContextWindowExceededException? {
    if (e is ContextWindowExceededException) return e
    val message = e.message.orEmpty()
    val cause = e.cause?.message.orEmpty()
    val combined = "$message\n$cause"
    val lower = combined.lowercase()

    val matched = lower.contains("prompt_too_long") ||
        lower.contains("request_too_long") ||
        lower.contains("request_too_large") ||
        lower.contains("exceeded max context length") ||
        lower.contains("prompt too long") ||
        lower.contains("context_length_exceeded") ||
        lower.contains("payload too large") ||
        HTTP_413_CODE.containsMatchIn(combined)

    return if (matched) {
        ContextWindowExceededException(
            message = e.message ?: "Prompt exceeded context window",
            cause = e,
        )
    } else null
}
