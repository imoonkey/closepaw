package ai.closepaw.llm

/**
 * Thrown when the provider rejects a request because the prompt would exceed
 * the model's context window (HTTP 413, `prompt_too_long`, `request_too_long`,
 * `request_too_large`, Ollama "prompt too long; exceeded max context length").
 *
 * Distinct from [TransientException] / [RateLimitException]: the request is not
 * retried by [CloudStreamRetryPolicy] — context-window errors are not transient.
 * The caller (typically `Turn.runStreaming`) catches this, triggers a reactive
 * compaction, and retries the call once.
 */
class ContextWindowExceededException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
