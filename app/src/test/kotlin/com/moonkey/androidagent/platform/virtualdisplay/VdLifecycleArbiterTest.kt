package com.moonkey.androidagent.platform.virtualdisplay

import android.media.ImageReader
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        a.withLifecycleTransition { _ ->
            a.transitionTo(mockRunningState())
        }
        assertThat(a.state).isInstanceOf(VdState.Running::class.java)
    }

    @Test
    fun `markBroken transitions Running to Broken and preserves resources`() = runTest {
        val a = arbiter()
        a.withLifecycleTransition { _ -> a.transitionTo(mockRunningState()) }

        a.markBroken("binder died")

        assertThat(a.state).isInstanceOf(VdState.Broken::class.java)
        val broken = a.state as VdState.Broken
        assertThat(broken.reason).isEqualTo("binder died")
        assertThat(broken.displayId).isEqualTo(42)
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
        a.withLifecycleTransition { _ -> a.transitionTo(mockRunningState()) }
        a.markBroken("first")
        a.markBroken("second")
        assertThat((a.state as VdState.Broken).reason).isEqualTo("first")
    }

    // ── Running Lease ────────────────────────────────────────────

    @Test
    fun `withRunningLease succeeds when Running`() = runTest {
        val a = arbiter()
        a.withLifecycleTransition { _ -> a.transitionTo(mockRunningState()) }

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
        a.withLifecycleTransition { _ -> a.transitionTo(mockRunningState()) }
        a.markBroken("dead")
        a.withRunningLease { "should not reach" }
    }

    // ── Pre-drain State ──────────────────────────────────────────

    @Test
    fun `preDrainTransform blocks new ops before drain completes`() = runTest {
        val a = arbiter()
        a.withLifecycleTransition { _ -> a.transitionTo(mockRunningState()) }

        // Stop with preDrainTransform to Draining — new ops should fail fast
        a.withLifecycleTransition(
            preDrainTransform = { current ->
                when (current) {
                    is VdState.Running -> VdState.Draining(current.displayId, current.imageReader)
                    else -> VdState.Stopped
                }
            }
        ) { previous ->
            assertThat(previous).isInstanceOf(VdState.Running::class.java)
            assertThat(a.state).isInstanceOf(VdState.Draining::class.java)
        }
    }

    @Test
    fun `previousState is passed to block`() = runTest {
        val a = arbiter()
        val running = mockRunningState()
        a.withLifecycleTransition { _ -> a.transitionTo(running) }

        a.withLifecycleTransition(
            preDrainTransform = { VdState.Stopped }
        ) { previous ->
            assertThat(previous).isEqualTo(running)
        }
    }

    @Test(expected = PlatformNotRunningException::class)
    fun `withRunningLease throws when Draining`() = runTest {
        val a = arbiter()
        a.withLifecycleTransition { _ -> a.transitionTo(mockRunningState()) }
        a.withLifecycleTransition(
            preDrainTransform = { current ->
                when (current) {
                    is VdState.Running -> VdState.Draining(current.displayId, current.imageReader)
                    else -> current
                }
            }
        ) { _ ->
            // Inside the transition, state is Draining — lease should fail
            a.withRunningLease { "should not reach" }
        }
    }

    // ── Drain Behavior ───────────────────────────────────────────

    @Test
    fun `lifecycle transition waits for in-flight ops to drain`() = runTest {
        val a = arbiter()
        a.withLifecycleTransition { _ -> a.transitionTo(mockRunningState()) }

        var opCompleted = false
        var transitionCompleted = false

        val opJob = launch {
            a.withRunningLease {
                delay(100)
                opCompleted = true
            }
        }

        val transitionJob = launch {
            a.withLifecycleTransition { _ ->
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
            a.withLifecycleTransition { _ ->
                order.add("start-1")
                delay(50)
                a.transitionTo(mockRunningState())
                order.add("end-1")
            }
        }

        val job2 = launch {
            delay(10)
            a.withLifecycleTransition { _ ->
                order.add("start-2")
                a.transitionTo(VdState.Stopped)
                order.add("end-2")
            }
        }

        advanceUntilIdle()

        assertThat(order).containsExactly("start-1", "end-1", "start-2", "end-2").inOrder()
    }
}
