package com.moonkey.androidagent.llm

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Test

class OpenAIErrorClassifierTest {

    // ── Rate-limit detection ──────────────────────────────────────────────

    @Test
    fun `429 in message is classified as RateLimitException`() {
        val result = OpenAIErrorClassifier.classify(RuntimeException("HTTP 429 Too Many Requests"))
        assertThat(result).isInstanceOf(RateLimitException::class.java)
    }

    @Test
    fun `rate limit phrase in message is classified as RateLimitException`() {
        val result = OpenAIErrorClassifier.classify(RuntimeException("Rate limit exceeded"))
        assertThat(result).isInstanceOf(RateLimitException::class.java)
    }

    @Test
    fun `429 in cause message is classified as RateLimitException`() {
        val cause = RuntimeException("status 429")
        val result = OpenAIErrorClassifier.classify(RuntimeException("request failed", cause))
        assertThat(result).isInstanceOf(RateLimitException::class.java)
    }

    @Test
    fun `retry-after header value is extracted into retryAfterMs`() {
        val result = OpenAIErrorClassifier.classify(
            RuntimeException("429 Too Many Requests. Retry-After: 30")
        )
        assertThat(result).isInstanceOf(RateLimitException::class.java)
        assertThat((result as RateLimitException).retryAfterMs).isEqualTo(30_000L)
    }

    @Test
    fun `wait N seconds pattern extracts retry-after`() {
        val result = OpenAIErrorClassifier.classify(
            RuntimeException("429 - please wait 10 seconds before retrying")
        )
        assertThat(result).isInstanceOf(RateLimitException::class.java)
        assertThat((result as RateLimitException).retryAfterMs).isEqualTo(10_000L)
    }

    @Test
    fun `try again in N seconds pattern extracts retry-after`() {
        val result = OpenAIErrorClassifier.classify(
            RuntimeException("429 - try again in 5 seconds")
        )
        assertThat(result).isInstanceOf(RateLimitException::class.java)
        assertThat((result as RateLimitException).retryAfterMs).isEqualTo(5_000L)
    }

    // ── False-positive scenarios (KNOWN BUGS — capture current broken behavior) ──

    @Test
    fun `KNOWN BUG -- 14291 falsely matches 429 substring`() {
        // Bug: contains("429") matches "14291" → false positive rate-limit classification
        // Expected after fix: should NOT be RateLimitException
        val result = OpenAIErrorClassifier.classify(RuntimeException("error code 14291"))
        // Current broken behavior: classifies as RateLimitException
        assertThat(result).isInstanceOf(RateLimitException::class.java)
    }

    @Test
    fun `KNOWN BUG -- 5002 falsely matches 500 substring`() {
        // Bug: contains("500") matches "5002" → false positive server-error classification
        // Expected after fix: should NOT be TransientException
        val result = OpenAIErrorClassifier.classify(IOException("error code 5002"))
        // Current broken behavior: classifies as TransientException (server error)
        assertThat(result).isInstanceOf(TransientException::class.java)
    }

    @Test
    fun `KNOWN BUG -- 5003 falsely matches 500 substring`() {
        val result = OpenAIErrorClassifier.classify(RuntimeException("error 5003"))
        // Current broken behavior: classifies as TransientException instead of RuntimeException
        assertThat(result).isInstanceOf(TransientException::class.java)
    }

    // ── Domain exception reclassification (KNOWN BUG) ──────────────────

    @Test
    fun `KNOWN BUG -- RateLimitException is reclassified losing retryAfterMs`() {
        // Bug: classify() always reclassifies, even if input is already a domain exception.
        // A RateLimitException with retryAfterMs=5000 gets reclassified based on its message,
        // and the new instance may lose the original retryAfterMs value.
        val original = RateLimitException("Rate limit hit", retryAfterMs = 5000L)
        val result = OpenAIErrorClassifier.classify(original)

        // Current broken behavior: message contains "rate limit" so it gets re-detected,
        // but retryAfterMs is extracted from message text (not preserved from original).
        // The message "Rate limit hit" has no numeric pattern → retryAfterMs becomes null.
        assertThat(result).isInstanceOf(RateLimitException::class.java)
        assertThat((result as RateLimitException).retryAfterMs).isNull()
        // Expected after fix: should preserve original retryAfterMs=5000
    }

    @Test
    fun `KNOWN BUG -- TransientException is reclassified to RuntimeException`() {
        // Bug: a TransientException with generic message gets reclassified.
        // If message doesn't match any pattern, falls through to RuntimeException.
        val original = TransientException("Stream ended without completion event")
        val result = OpenAIErrorClassifier.classify(original)

        // Current broken behavior: message doesn't match 429/rate-limit/500/502/503/504,
        // not SocketTimeoutException, not UnknownHostException, not IOException
        // → falls to else branch → RuntimeException (loses retryability!)
        assertThat(result).isInstanceOf(RuntimeException::class.java)
        assertThat(result).isNotInstanceOf(TransientException::class.java)
    }

    // ── Server errors (5xx) ───────────────────────────────────────────────

    @Test
    fun `500 server error is TransientException`() {
        val result = OpenAIErrorClassifier.classify(RuntimeException("HTTP 500 Internal Server Error"))
        assertThat(result).isInstanceOf(TransientException::class.java)
    }

    @Test
    fun `502 bad gateway is TransientException`() {
        val result = OpenAIErrorClassifier.classify(RuntimeException("502 Bad Gateway"))
        assertThat(result).isInstanceOf(TransientException::class.java)
    }

    @Test
    fun `503 service unavailable is TransientException`() {
        val result = OpenAIErrorClassifier.classify(RuntimeException("503 Service Unavailable"))
        assertThat(result).isInstanceOf(TransientException::class.java)
    }

    @Test
    fun `504 gateway timeout is TransientException`() {
        val result = OpenAIErrorClassifier.classify(RuntimeException("504 Gateway Timeout"))
        assertThat(result).isInstanceOf(TransientException::class.java)
    }

    // ── Timeout / connectivity ────────────────────────────────────────────

    @Test
    fun `SocketTimeoutException is TransientException`() {
        val result = OpenAIErrorClassifier.classify(SocketTimeoutException("connect timed out"))
        assertThat(result).isInstanceOf(TransientException::class.java)
    }

    @Test
    fun `UnknownHostException is non-retryable RuntimeException`() {
        val result = OpenAIErrorClassifier.classify(UnknownHostException("api.openai.com"))
        assertThat(result).isInstanceOf(RuntimeException::class.java)
        assertThat(result).isNotInstanceOf(TransientException::class.java)
        assertThat(result).isNotInstanceOf(RateLimitException::class.java)
    }

    @Test
    fun `IOException with connectivity keywords is non-retryable`() {
        val result = OpenAIErrorClassifier.classify(IOException("Unable to resolve host"))
        assertThat(result).isInstanceOf(RuntimeException::class.java)
        assertThat(result).isNotInstanceOf(TransientException::class.java)
    }

    @Test
    fun `generic IOException is TransientException`() {
        val result = OpenAIErrorClassifier.classify(IOException("Connection reset"))
        assertThat(result).isInstanceOf(TransientException::class.java)
    }

    // ── Fallback ──────────────────────────────────────────────────────────

    @Test
    fun `unknown exception type becomes RuntimeException`() {
        val result = OpenAIErrorClassifier.classify(IllegalStateException("something broke"))
        assertThat(result).isInstanceOf(RuntimeException::class.java)
        assertThat(result).isNotInstanceOf(TransientException::class.java)
        assertThat(result.message).contains("LLM error")
    }
}
