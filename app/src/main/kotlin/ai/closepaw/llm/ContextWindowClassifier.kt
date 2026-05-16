package ai.closepaw.llm

/**
 * Detects provider/runtime signals that the prompt exceeded the model's
 * context window. Centralized so cloud retry, the streaming Failed branch,
 * and the generic catch in `Turn.runStreaming` all agree on what counts as
 * an overflow.
 *
 * Recognized patterns (case-insensitive substring match against the message
 * plus any cause message):
 *   - `prompt_too_long`, `request_too_long`, `request_too_large`
 *   - `context_length_exceeded`
 *   - `maximum context length`, `exceeded max context length`,
 *     `prompt too long`
 *   - `payload too large`
 *   - HTTP 413 (in the message text or the optional explicit code arg)
 *
 * Returns a [ContextWindowExceededException] wrapping the original message
 * and (optionally) cause when the input matches; `null` otherwise.
 */
private val HTTP_413_CODE = Regex("""(?<![A-Za-z0-9])413(?![A-Za-z0-9])""")

fun classifyContextWindowExceeded(
    message: String?,
    cause: Throwable? = null,
    httpCode: Int? = null,
): ContextWindowExceededException? {
    if (httpCode == 413) {
        return ContextWindowExceededException(
            message = message ?: "Prompt exceeded context window (HTTP 413)",
            cause = cause,
        )
    }
    val combined = buildString {
        message?.let { append(it) }
        cause?.message?.let {
            if (isNotEmpty()) append('\n')
            append(it)
        }
    }
    if (combined.isEmpty()) return null
    val lower = combined.lowercase()
    val matched = lower.contains("prompt_too_long") ||
        lower.contains("request_too_long") ||
        lower.contains("request_too_large") ||
        lower.contains("context_length_exceeded") ||
        lower.contains("exceeded max context length") ||
        lower.contains("maximum context length") ||
        lower.contains("prompt too long") ||
        lower.contains("payload too large") ||
        HTTP_413_CODE.containsMatchIn(combined)
    return if (matched) {
        ContextWindowExceededException(
            message = message ?: "Prompt exceeded context window",
            cause = cause,
        )
    } else null
}

/** Convenience: classify by inspecting a [Throwable]'s message and cause chain. */
fun classifyContextWindowExceeded(error: Throwable): ContextWindowExceededException? {
    if (error is ContextWindowExceededException) return error
    return classifyContextWindowExceeded(message = error.message, cause = error.cause)
}
