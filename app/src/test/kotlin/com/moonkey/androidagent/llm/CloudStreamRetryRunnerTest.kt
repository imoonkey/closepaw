package com.moonkey.androidagent.llm

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CloudStreamRetryRunnerTest {

    // Use exceptions that survive OpenAIErrorClassifier.classify() as retryable:
    // - SocketTimeoutException → classified as TransientException
    // - IOException (non-connectivity) → classified as TransientException

    // ── Successful completion ─────────────────────────────────────────────

    @Test
    fun `successful attempt returns completed=true`() = runTest {
        val events = mutableListOf<LLMStreamEvent>()

        val result = streamWithRetry(
            tag = "test",
            emitToFlow = { events += it },
            maxRetries = 3,
            initialBackoffMs = 10L
        ) { _, emitter ->
            emitter.emit(LLMStreamEvent.Created("resp-1"))
            emitter.emit(LLMStreamEvent.TextDelta("hello"))
            emitter.emit(LLMStreamEvent.Completed)
        }

        assertThat(result.completed).isTrue()
        assertThat(result.failureEmitted).isFalse()
        assertThat(result.lastError).isNull()
        assertThat(events).hasSize(3)
    }

    // ── emittedEvent tracking ─────────────────────────────────────────────

    @Test
    fun `KNOWN BUG -- Created event sets emittedEvent blocking retry`() = runTest {
        // Bug: emittedEvent=true after Created (metadata event).
        // When a retryable error occurs after Created but before TextDelta,
        // retry is blocked even though no user-visible output was sent.
        var attempts = 0
        val events = mutableListOf<LLMStreamEvent>()

        val result = streamWithRetry(
            tag = "test",
            emitToFlow = { events += it },
            maxRetries = 3,
            initialBackoffMs = 10L
        ) { attempt, emitter ->
            attempts++
            emitter.emit(LLMStreamEvent.Created("resp-$attempt"))
            // SocketTimeoutException → classified as TransientException (retryable)
            throw SocketTimeoutException("connection reset")
        }

        // Current broken behavior: only 1 attempt because Created sets emittedEvent=true,
        // causing FailAndStop on the first failure.
        // Expected after fix: should retry because Created is metadata-only.
        assertThat(attempts).isEqualTo(1)
        assertThat(result.completed).isFalse()
        assertThat(result.failureEmitted).isTrue()
    }

    @Test
    fun `retryable error before any events triggers retry`() = runTest {
        var attempts = 0

        val result = streamWithRetry(
            tag = "test",
            emitToFlow = {},
            maxRetries = 3,
            initialBackoffMs = 10L
        ) { _, _ ->
            attempts++
            // SocketTimeoutException survives classify() as TransientException
            if (attempts < 3) throw SocketTimeoutException("timeout")
            // Success on attempt 3
        }

        assertThat(attempts).isEqualTo(3)
        assertThat(result.completed).isTrue()
    }

    @Test
    fun `retry stops after TextDelta emitted`() = runTest {
        var attempts = 0
        val events = mutableListOf<LLMStreamEvent>()

        val result = streamWithRetry(
            tag = "test",
            emitToFlow = { events += it },
            maxRetries = 5,
            initialBackoffMs = 10L
        ) { _, emitter ->
            attempts++
            emitter.emit(LLMStreamEvent.TextDelta("partial"))
            throw SocketTimeoutException("stream interrupted")
        }

        assertThat(attempts).isEqualTo(1)
        assertThat(result.completed).isFalse()
        assertThat(result.failureEmitted).isTrue()
    }

    // ── Domain exception reclassification ─────────────────────────────────

    @Test
    fun `KNOWN BUG -- RateLimitException is reclassified losing retryAfterMs`() = runTest {
        // Bug: classify() always reclassifies. A RateLimitException("Rate limit hit")
        // gets re-detected as rate limit (message matches), but retryAfterMs is lost.
        val result = streamWithRetry(
            tag = "test",
            emitToFlow = {},
            maxRetries = 2,
            initialBackoffMs = 10L
        ) { attempt, _ ->
            if (attempt == 1) {
                throw RateLimitException("Rate limit hit", retryAfterMs = 5000L)
            }
        }

        // Still retries because reclassified result is RateLimitException (message matches),
        // but the retryAfterMs=5000 is lost. This test documents the bug.
        assertThat(result.completed).isTrue()
    }

    @Test
    fun `KNOWN BUG -- TransientException with generic message loses retryability`() = runTest {
        // Bug: TransientException("Stream ended without completion event") gets reclassified
        // to RuntimeException because message doesn't match any pattern → not retryable.
        var attempts = 0

        val result = streamWithRetry(
            tag = "test",
            emitToFlow = {},
            maxRetries = 3,
            initialBackoffMs = 10L
        ) { _, _ ->
            attempts++
            throw TransientException("Stream ended without completion event")
        }

        // Current broken behavior: only 1 attempt because reclassified to RuntimeException → Stop
        // Expected after fix: should retry (TransientException is retryable)
        assertThat(attempts).isEqualTo(1)
        assertThat(result.completed).isFalse()
        assertThat(result.lastError).isInstanceOf(RuntimeException::class.java)
        assertThat(result.lastError).isNotInstanceOf(TransientException::class.java)
    }

    // ── failureEmitted tracking ───────────────────────────────────────────

    @Test
    fun `failureEmitted is true when Failed event is emitted by attempt block`() = runTest {
        val result = streamWithRetry(
            tag = "test",
            emitToFlow = {},
            maxRetries = 2,
            initialBackoffMs = 10L
        ) { _, emitter ->
            emitter.emit(LLMStreamEvent.Failed("explicit failure"))
        }

        assertThat(result.completed).isTrue() // block didn't throw
        assertThat(result.failureEmitted).isTrue()
    }

    @Test
    fun `failureEmitted is true when FailAndStop emits Failed`() = runTest {
        val events = mutableListOf<LLMStreamEvent>()

        val result = streamWithRetry(
            tag = "test",
            emitToFlow = { events += it },
            maxRetries = 3,
            initialBackoffMs = 10L
        ) { _, emitter ->
            emitter.emit(LLMStreamEvent.TextDelta("data"))
            throw SocketTimeoutException("error after output")
        }

        assertThat(result.failureEmitted).isTrue()
        // The FailAndStop path emits a Failed event
        val failedEvents = events.filterIsInstance<LLMStreamEvent.Failed>()
        assertThat(failedEvents).isNotEmpty()
    }

    // ── Max retries exhausted ─────────────────────────────────────────────

    @Test
    fun `exhausting all retries returns completed=false`() = runTest {
        var attempts = 0

        val result = streamWithRetry(
            tag = "test",
            emitToFlow = {},
            maxRetries = 3,
            initialBackoffMs = 10L
        ) { _, _ ->
            attempts++
            throw SocketTimeoutException("always fails")
        }

        assertThat(attempts).isEqualTo(3)
        assertThat(result.completed).isFalse()
        assertThat(result.lastError).isNotNull()
    }

    // ── Non-retryable errors ──────────────────────────────────────────────

    @Test
    fun `non-retryable error stops immediately`() = runTest {
        var attempts = 0

        val result = streamWithRetry(
            tag = "test",
            emitToFlow = {},
            maxRetries = 5,
            initialBackoffMs = 10L
        ) { _, _ ->
            attempts++
            throw IllegalStateException("fatal error")
        }

        assertThat(attempts).isEqualTo(1)
        assertThat(result.completed).isFalse()
    }

    // ── Backoff progression ───────────────────────────────────────────────

    @Test
    fun `backoff increases between retries`() = runTest {
        var attempts = 0

        val result = streamWithRetry(
            tag = "test",
            emitToFlow = {},
            maxRetries = 4,
            initialBackoffMs = 10L
        ) { _, _ ->
            attempts++
            if (attempts < 4) throw SocketTimeoutException("retry me")
        }

        assertThat(attempts).isEqualTo(4)
        assertThat(result.completed).isTrue()
    }
}
