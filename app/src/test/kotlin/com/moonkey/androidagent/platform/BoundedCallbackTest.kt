package com.moonkey.androidagent.platform

import com.google.common.truth.Truth.assertThat
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BoundedCallbackTest {

    @Test
    fun `returns value when callback fires before timeout`() = runTest {
        val result = boundedCallback(
            timeoutMs = 5000,
            label = "test"
        ) { cont ->
            cont.resume("hello")
        }
        assertThat(result).isEqualTo("hello")
    }

    @Test
    fun `returns null when callback never fires`() = runTest {
        val result = boundedCallback<String>(
            timeoutMs = 100,
            label = "test"
        ) { /* never resume */ }

        assertThat(result).isNull()
    }

    @Test
    fun `returns null when callback resumes with null`() = runTest {
        val result = boundedCallback<String>(
            timeoutMs = 5000,
            label = "test"
        ) { cont ->
            cont.resume(null)
        }
        assertThat(result).isNull()
    }

    @Test
    fun `onCancel is called on timeout`() = runTest {
        var cleanupCalled = false
        val result = boundedCallback<String>(
            timeoutMs = 100,
            label = "test",
            onCancel = { cleanupCalled = true }
        ) { /* never resume */ }

        assertThat(result).isNull()
        assertThat(cleanupCalled).isTrue()
    }

    @Test
    fun `onCancel is called on coroutine cancellation`() = runTest {
        var cleanupCalled = false

        val job = launch {
            boundedCallback<String>(
                timeoutMs = 10_000,
                label = "test",
                onCancel = { cleanupCalled = true }
            ) { /* never resume */ }
        }

        // Give the coroutine time to start
        advanceTimeBy(10)

        job.cancel()
        advanceUntilIdle()

        assertThat(cleanupCalled).isTrue()
    }
}
