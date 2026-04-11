package com.moonkey.androidagent.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CloudStreamRetryPolicyTest {

    private val tag = "TestPolicy"

    // ── Retry allowed (retryable error, no emitted event, within budget) ──

    @Test
    fun `TransientException before any events triggers Retry`() {
        val action = CloudStreamRetryPolicy.decide(
            tag = tag,
            classified = TransientException("timeout"),
            attempt = 1,
            emittedEvent = false,
            backoffMs = 1000L
        )
        assertThat(action).isInstanceOf(StreamRetryAction.Retry::class.java)
    }

    @Test
    fun `RateLimitException before any events triggers Retry`() {
        val action = CloudStreamRetryPolicy.decide(
            tag = tag,
            classified = RateLimitException("rate limited"),
            attempt = 1,
            emittedEvent = false,
            backoffMs = 1000L
        )
        assertThat(action).isInstanceOf(StreamRetryAction.Retry::class.java)
    }

    @Test
    fun `RateLimitException with retryAfterMs uses that value for waitMs`() {
        val action = CloudStreamRetryPolicy.decide(
            tag = tag,
            classified = RateLimitException("rate limited", retryAfterMs = 5000L),
            attempt = 1,
            emittedEvent = false,
            backoffMs = 1000L
        )
        assertThat(action).isInstanceOf(StreamRetryAction.Retry::class.java)
        assertThat((action as StreamRetryAction.Retry).waitMs).isEqualTo(5000L)
    }

    @Test
    fun `RateLimitException without retryAfterMs falls back to backoffMs`() {
        val action = CloudStreamRetryPolicy.decide(
            tag = tag,
            classified = RateLimitException("rate limited"),
            attempt = 1,
            emittedEvent = false,
            backoffMs = 2000L
        )
        assertThat(action).isInstanceOf(StreamRetryAction.Retry::class.java)
        assertThat((action as StreamRetryAction.Retry).waitMs).isEqualTo(2000L)
    }

    @Test
    fun `TransientException uses backoffMs for waitMs`() {
        val action = CloudStreamRetryPolicy.decide(
            tag = tag,
            classified = TransientException("server error"),
            attempt = 1,
            emittedEvent = false,
            backoffMs = 3000L
        )
        assertThat(action).isInstanceOf(StreamRetryAction.Retry::class.java)
        assertThat((action as StreamRetryAction.Retry).waitMs).isEqualTo(3000L)
    }

    @Test
    fun `nextBackoffMs is doubled from current backoff`() {
        val action = CloudStreamRetryPolicy.decide(
            tag = tag,
            classified = TransientException("error"),
            attempt = 1,
            emittedEvent = false,
            backoffMs = 1000L
        )
        assertThat(action).isInstanceOf(StreamRetryAction.Retry::class.java)
        assertThat((action as StreamRetryAction.Retry).nextBackoffMs).isEqualTo(2000L)
    }

    @Test
    fun `nextBackoffMs is capped at MAX_BACKOFF_MS`() {
        val action = CloudStreamRetryPolicy.decide(
            tag = tag,
            classified = TransientException("error"),
            attempt = 1,
            emittedEvent = false,
            backoffMs = 50_000L
        )
        assertThat(action).isInstanceOf(StreamRetryAction.Retry::class.java)
        assertThat((action as StreamRetryAction.Retry).nextBackoffMs)
            .isEqualTo(LLMClient.MAX_BACKOFF_MS)
    }

    // ── Fail-fast after emitted event ─────────────────────────────────────

    @Test
    fun `retryable error after emitted event returns FailAndStop`() {
        val action = CloudStreamRetryPolicy.decide(
            tag = tag,
            classified = TransientException("connection reset"),
            attempt = 1,
            emittedEvent = true,
            backoffMs = 1000L
        )
        assertThat(action).isInstanceOf(StreamRetryAction.FailAndStop::class.java)
    }

    @Test
    fun `RateLimitException after emitted event returns FailAndStop`() {
        val action = CloudStreamRetryPolicy.decide(
            tag = tag,
            classified = RateLimitException("rate limited"),
            attempt = 1,
            emittedEvent = true,
            backoffMs = 1000L
        )
        assertThat(action).isInstanceOf(StreamRetryAction.FailAndStop::class.java)
    }

    @Test
    fun `FailAndStop message mentions partial output`() {
        val action = CloudStreamRetryPolicy.decide(
            tag = tag,
            classified = TransientException("some error"),
            attempt = 1,
            emittedEvent = true,
            backoffMs = 1000L
        )
        assertThat((action as StreamRetryAction.FailAndStop).message)
            .contains("partial output")
    }

    // ── Non-retryable errors ──────────────────────────────────────────────

    @Test
    fun `RuntimeException returns Stop regardless of emitted state`() {
        val action = CloudStreamRetryPolicy.decide(
            tag = tag,
            classified = RuntimeException("unknown error"),
            attempt = 1,
            emittedEvent = false,
            backoffMs = 1000L
        )
        assertThat(action).isEqualTo(StreamRetryAction.Stop)
    }

    @Test
    fun `IllegalStateException returns Stop`() {
        val action = CloudStreamRetryPolicy.decide(
            tag = tag,
            classified = IllegalStateException("bad state"),
            attempt = 1,
            emittedEvent = false,
            backoffMs = 1000L
        )
        assertThat(action).isEqualTo(StreamRetryAction.Stop)
    }

    // ── Max retries exhausted ─────────────────────────────────────────────

    @Test
    fun `retryable error at max attempts returns Stop`() {
        val action = CloudStreamRetryPolicy.decide(
            tag = tag,
            classified = TransientException("timeout"),
            attempt = LLMClient.MAX_RETRIES,
            emittedEvent = false,
            backoffMs = 1000L
        )
        // attempt == MAX_RETRIES means the condition `attempt < MAX_RETRIES` is false
        assertThat(action).isEqualTo(StreamRetryAction.Stop)
    }

    @Test
    fun `retryable error at attempt just below max still retries`() {
        val action = CloudStreamRetryPolicy.decide(
            tag = tag,
            classified = TransientException("timeout"),
            attempt = LLMClient.MAX_RETRIES - 1,
            emittedEvent = false,
            backoffMs = 1000L
        )
        assertThat(action).isInstanceOf(StreamRetryAction.Retry::class.java)
    }
}
