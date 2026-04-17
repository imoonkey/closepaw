package com.moonkey.androidagent.llm

import com.google.common.truth.Truth.assertThat
import com.openai.core.http.Headers
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

    // ── False-positive rejection (word-boundary matching) ────────────────

    @Test
    fun `14291 does NOT match 429 substring`() {
        val result = OpenAIErrorClassifier.classify(RuntimeException("error code 14291"))
        assertThat(result).isNotInstanceOf(RateLimitException::class.java)
    }

    @Test
    fun `5002 does NOT match 500 substring`() {
        val result = OpenAIErrorClassifier.classify(RuntimeException("error code 5002"))
        assertThat(result).isNotInstanceOf(TransientException::class.java)
        assertThat(result).isNotInstanceOf(RateLimitException::class.java)
    }

    @Test
    fun `5003 in RuntimeException is not server error`() {
        val result = OpenAIErrorClassifier.classify(RuntimeException("error 5003"))
        assertThat(result).isNotInstanceOf(TransientException::class.java)
    }

    @Test
    fun `HTTP 401 is non-retryable RuntimeException`() {
        val result = OpenAIErrorClassifier.classify(RuntimeException("HTTP 401 Unauthorized"))
        assertThat(result).isInstanceOf(RuntimeException::class.java)
        assertThat(result).isNotInstanceOf(TransientException::class.java)
        assertThat(result).isNotInstanceOf(RateLimitException::class.java)
    }

    @Test
    fun `HTTP 400 is non-retryable RuntimeException`() {
        val result = OpenAIErrorClassifier.classify(RuntimeException("HTTP 400 Bad Request"))
        assertThat(result).isInstanceOf(RuntimeException::class.java)
        assertThat(result).isNotInstanceOf(TransientException::class.java)
        assertThat(result).isNotInstanceOf(RateLimitException::class.java)
    }

    // ── Domain exception preservation ───────────────────────────────────

    @Test
    fun `RateLimitException is preserved with retryAfterMs intact`() {
        // Fixed: classify() now preserves existing domain exceptions
        val original = RateLimitException("Rate limit hit", retryAfterMs = 5000L)
        val result = OpenAIErrorClassifier.classify(original)

        assertThat(result).isSameInstanceAs(original)
        assertThat((result as RateLimitException).retryAfterMs).isEqualTo(5000L)
    }

    @Test
    fun `TransientException is preserved without reclassification`() {
        // Fixed: classify() now preserves existing domain exceptions
        val original = TransientException("Stream ended without completion event")
        val result = OpenAIErrorClassifier.classify(original)

        assertThat(result).isSameInstanceAs(original)
        assertThat(result).isInstanceOf(TransientException::class.java)
    }

    // ── Typed SDK exceptions ──────────────────────────────────────────────

    @Test
    fun `OpenAI SDK RateLimitException maps to domain RateLimitException`() {
        val sdkException = com.openai.errors.RateLimitException.builder()
            .headers(Headers.builder().build())
            .build()
        val result = OpenAIErrorClassifier.classify(sdkException)
        assertThat(result).isInstanceOf(RateLimitException::class.java)
    }

    @Test
    fun `OpenAI SDK RateLimitException preserves Retry-After header`() {
        val sdkException = com.openai.errors.RateLimitException.builder()
            .headers(Headers.builder().put("retry-after", "30").build())
            .build()
        val result = OpenAIErrorClassifier.classify(sdkException)
        assertThat(result).isInstanceOf(RateLimitException::class.java)
        assertThat((result as RateLimitException).retryAfterMs).isEqualTo(30_000L)
    }

    @Test
    fun `OpenAI SDK InternalServerException maps to TransientException`() {
        val sdkException = com.openai.errors.InternalServerException.builder()
            .statusCode(500)
            .headers(Headers.builder().build())
            .build()
        val result = OpenAIErrorClassifier.classify(sdkException)
        assertThat(result).isInstanceOf(TransientException::class.java)
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
