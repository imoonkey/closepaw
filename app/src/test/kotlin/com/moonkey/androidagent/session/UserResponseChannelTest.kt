package com.moonkey.androidagent.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class UserResponseChannelTest {

    @Test
    fun `deliver completes awaiting coroutine`() = runTest {
        val channel = UserResponseChannel()

        val result = async {
            channel.awaitResponse("call-1")
        }

        // Give the coroutine time to start awaiting
        delay(10)
        assertTrue(channel.hasPending)

        assertTrue(channel.deliver("call-1", "hello"))
        assertEquals("hello", result.await())
        assertFalse(channel.hasPending)
    }

    @Test
    fun `deliver with wrong callId returns false`() = runTest {
        val channel = UserResponseChannel()

        val result = async {
            channel.awaitResponse("call-1")
        }

        delay(10)
        assertFalse(channel.deliver("wrong-id", "hello"))
        assertTrue(channel.hasPending)

        // Clean up
        channel.cancel()
        try { result.await() } catch (_: CancellationException) {}
    }

    @Test
    fun `deliver with no pending returns false`() {
        val channel = UserResponseChannel()
        assertFalse(channel.deliver("call-1", "hello"))
    }

    @Test
    fun `cancel cancels pending request`() = runTest {
        val channel = UserResponseChannel()

        val result = async {
            try {
                channel.awaitResponse("call-1")
            } catch (e: CancellationException) {
                "cancelled"
            }
        }

        delay(10)
        assertTrue(channel.hasPending)
        channel.cancel()
        assertFalse(channel.hasPending)
        assertEquals("cancelled", result.await())
    }

    @Test(expected = IllegalStateException::class)
    fun `double request throws`() = runTest {
        val channel = UserResponseChannel()

        val first = async { channel.awaitResponse("call-1") }
        delay(10)

        // Second request should throw
        try {
            channel.awaitResponse("call-2")
        } finally {
            channel.cancel()
            try { first.await() } catch (_: CancellationException) {}
        }
    }
}
