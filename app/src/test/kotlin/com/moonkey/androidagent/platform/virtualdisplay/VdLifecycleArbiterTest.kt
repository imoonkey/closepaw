package com.moonkey.androidagent.platform.virtualdisplay

import android.media.ImageReader
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VdLifecycleArbiterTest {

    private fun arbiter() = VdLifecycleArbiter()
    private fun mockRunningState(): VdState.Running {
        val reader = mockk<ImageReader>(relaxed = true)
        return VdState.Running(displayId = 42, imageReader = reader)
    }

    // ── State Machine ────────────────────────────────────────────

    @Test
    fun `initial state is Stopped`() {
        assertThat(arbiter().state).isEqualTo(VdState.Stopped)
    }

    @Test
    fun `transitionTo changes state within lifecycle transition`() = runTest {
        val a = arbiter()
        a.withLifecycleTransition {
            a.transitionTo(mockRunningState())
        }
        assertThat(a.state).isInstanceOf(VdState.Running::class.java)
    }

    @Test
    fun `markBroken transitions Running to Broken`() = runTest {
        val a = arbiter()
        a.withLifecycleTransition { a.transitionTo(mockRunningState()) }

        a.markBroken("binder died")

        assertThat(a.state).isInstanceOf(VdState.Broken::class.java)
        assertThat((a.state as VdState.Broken).reason).isEqualTo("binder died")
    }

    @Test
    fun `markBroken is no-op when Stopped`() {
        val a = arbiter()
        a.markBroken("test")
        assertThat(a.state).isEqualTo(VdState.Stopped)
    }

    @Test
    fun `markBroken is no-op when already Broken`() = runTest {
        val a = arbiter()
        a.withLifecycleTransition { a.transitionTo(mockRunningState()) }
        a.markBroken("first")
        a.markBroken("second")
        assertThat((a.state as VdState.Broken).reason).isEqualTo("first")
    }

    // ── Running Lease ────────────────────────────────────────────

    @Test
    fun `withRunningLease succeeds when Running`() = runTest {
        val a = arbiter()
        a.withLifecycleTransition { a.transitionTo(mockRunningState()) }

        val result = a.withRunningLease { running ->
            assertThat(running.displayId).isEqualTo(42)
            "ok"
        }
        assertThat(result).isEqualTo("ok")
    }

    @Test(expected = PlatformNotRunningException::class)
    fun `withRunningLease throws when Stopped`() = runTest {
        arbiter().withRunningLease { "should not reach" }
    }

    @Test(expected = PlatformNotRunningException::class)
    fun `withRunningLease throws when Broken`() = runTest {
        val a = arbiter()
        a.withLifecycleTransition { a.transitionTo(mockRunningState()) }
        a.markBroken("dead")
        a.withRunningLease { "should not reach" }
    }

    // ── Drain Behavior ───────────────────────────────────────────

    @Test
    fun `lifecycle transition waits for in-flight ops to drain`() = runTest {
        val a = arbiter()
        a.withLifecycleTransition { a.transitionTo(mockRunningState()) }

        var opCompleted = false
        var transitionCompleted = false

        // Start an operational call that takes time
        val opJob = launch {
            a.withRunningLease {
                delay(100)
                opCompleted = true
            }
        }

        // Start a lifecycle transition that should wait for the op
        val transitionJob = launch {
            a.withLifecycleTransition {
                // By the time we get here, the op should have completed
                transitionCompleted = true
                assertThat(opCompleted).isTrue()
                a.transitionTo(VdState.Stopped)
            }
        }

        advanceUntilIdle()

        assertThat(opCompleted).isTrue()
        assertThat(transitionCompleted).isTrue()
    }

    // ── Lifecycle Serialization ──────────────────────────────────

    @Test
    fun `concurrent lifecycle transitions are serialized`() = runTest {
        val a = arbiter()
        val order = mutableListOf<String>()

        val job1 = launch {
            a.withLifecycleTransition {
                order.add("start-1")
                delay(50)
                a.transitionTo(mockRunningState())
                order.add("end-1")
            }
        }

        val job2 = launch {
            delay(10) // Ensure job2 starts slightly after job1
            a.withLifecycleTransition {
                order.add("start-2")
                a.transitionTo(VdState.Stopped)
                order.add("end-2")
            }
        }

        advanceUntilIdle()

        // Transitions should not interleave
        assertThat(order).containsExactly("start-1", "end-1", "start-2", "end-2").inOrder()
    }
}
