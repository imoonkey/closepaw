package ai.closepaw.llm

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CloudStreamRetryRunnerTest {

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
    fun `Created event does NOT block retry -- only TextDelta and ToolCallDone do`() = runTest {
        // Fixed: Created is metadata-only and does not set emittedEvent.
        // A retryable error after Created but before semantic output retries.
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
            if (attempts < 3) throw SocketTimeoutException("connection reset")
        }

        // Fixed: retries through Created events, succeeds on attempt 3
        assertThat(attempts).isEqualTo(3)
        assertThat(result.completed).isTrue()
        assertThat(result.failureEmitted).isFalse()
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
    fun `RateLimitException retryAfterMs is preserved through retry`() = runTest {
        // Fixed: domain exceptions are preserved without reclassification.
        // The original retryAfterMs=5000 is now used for the delay.
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

        assertThat(result.completed).isTrue()
        // Fixed: delay uses preserved retryAfterMs (5000ms), not backoff (10ms)
        assertThat(testScheduler.currentTime).isEqualTo(5000L)
    }

    @Test
    fun `TransientException is preserved and retried without reclassification`() = runTest {
        // Fixed: domain exceptions are preserved. TransientException is retryable
        // and no longer gets reclassified to RuntimeException.
        var attempts = 0

        val result = streamWithRetry(
            tag = "test",
            emitToFlow = {},
            maxRetries = 3,
            initialBackoffMs = 10L
        ) { _, _ ->
            attempts++
            if (attempts < 3) throw TransientException("Stream ended without completion event")
        }

        // Fixed: TransientException is preserved → retryable → retries succeed
        assertThat(attempts).isEqualTo(3)
        assertThat(result.completed).isTrue()
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

    @Test
    fun `response_incomplete emits exactly one Failed and does not retry`() = runTest {
        // Simulates what CodexResponseClient does when parser yields Failed from
        // response.incomplete: emit Failed, return normally (no throw).
        // streamWithRetry should see completed=true, not trigger any retry.
        val events = mutableListOf<LLMStreamEvent>()
        var attempts = 0

        val result = streamWithRetry(
            tag = "test",
            emitToFlow = { events += it },
            maxRetries = 3,
            initialBackoffMs = 10L
        ) { _, emitter ->
            attempts++
            emitter.emit(LLMStreamEvent.Created("resp-1"))
            emitter.emit(LLMStreamEvent.TextDelta("partial text"))
            // Parser maps response.incomplete → Failed; attempt emits it and returns
            emitter.emit(LLMStreamEvent.Failed("Response incomplete: max_output_tokens"))
        }

        assertThat(attempts).isEqualTo(1)
        assertThat(result.completed).isTrue()
        assertThat(result.failureEmitted).isTrue()
        assertThat(result.lastError).isNull()
        // Exactly one Failed event in the stream
        val failedEvents = events.filterIsInstance<LLMStreamEvent.Failed>()
        assertThat(failedEvents).hasSize(1)
        assertThat(failedEvents[0].error).contains("incomplete")
    }

    // ── Failed blocks retry (HIGH-1 regression) ──────────────────────────

    @Test
    fun `Failed event blocks retry -- emit Failed then retryable throw does NOT retry`() = runTest {
        var attempts = 0
        val events = mutableListOf<LLMStreamEvent>()

        val result = streamWithRetry(
            tag = "test",
            emitToFlow = { events += it },
            maxRetries = 5,
            initialBackoffMs = 10L
        ) { _, emitter ->
            attempts++
            emitter.emit(LLMStreamEvent.Failed("upstream failure"))
            throw SocketTimeoutException("retryable error after Failed")
        }

        // Must NOT retry — caller already saw Failed
        assertThat(attempts).isEqualTo(1)
        assertThat(result.completed).isFalse()
        assertThat(result.failureEmitted).isTrue()
        assertThat(events.filterIsInstance<LLMStreamEvent.Failed>()).hasSize(1)
    }

    // ── Max retries exhausted ─────────────────────────────────────────────

    @Test
    fun `exhausting all retries returns completed=false with correct cumulative backoff`() = runTest {
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
        // 3 attempts, each gets Retry (attempt < MAX_RETRIES=5):
        // attempt 1 → delay 10ms, attempt 2 → delay 20ms, attempt 3 → delay 40ms
        assertThat(testScheduler.currentTime).isEqualTo(70L)
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
    fun `backoff increases between retries with correct exponential timing`() = runTest {
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
        // 3 retries before success: delay 10ms + 20ms + 40ms = 70ms (exponential)
        // A fixed 10ms delay would yield 30ms — this proves backoff growth.
        assertThat(testScheduler.currentTime).isEqualTo(70L)
    }
}
