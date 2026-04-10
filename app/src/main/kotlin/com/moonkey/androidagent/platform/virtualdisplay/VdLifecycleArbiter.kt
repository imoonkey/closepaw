package com.moonkey.androidagent.platform.virtualdisplay

import android.media.ImageReader
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Virtual-display lifecycle states.
 *
 * Only [Running] allows operational calls (captureScreen, performAction).
 * [Draining] is a transient state during stop: rejects new leases but keeps resources
 * accessible so in-flight ops can complete.
 * [Broken] is the terminal error state after binder death or unrecoverable platform loss.
 */
sealed interface VdState {
    data object Stopped : VdState
    data class Running(val displayId: Int, val imageReader: ImageReader) : VdState
    data class Draining(val displayId: Int, val imageReader: ImageReader) : VdState
    data class Broken(
        val reason: String,
        val displayId: Int,
        val imageReader: ImageReader
    ) : VdState
}

/**
 * Serializes virtual-display lifecycle transitions and protects in-flight operational calls.
 *
 * Lifecycle transitions (start, stop, binder death) take exclusive access via [withLifecycleTransition].
 * They wait for in-flight operational calls to drain before proceeding.
 *
 * Operational calls (captureScreen, performAction) run under [withRunningLease], which increments
 * an active-ops counter and checks the state is [VdState.Running]. A lifecycle transition cannot
 * proceed while any operational call is in flight.
 *
 * Thread-safety relies on [state] being `@Volatile` and [activeOps] being atomic, providing
 * the necessary memory barriers between the lifecycle and operational paths.
 */
internal class VdLifecycleArbiter {
    @Volatile var state: VdState = VdState.Stopped
        private set

    private val lifecycleMutex = Mutex()
    private val activeOps = AtomicInteger(0)

    companion object {
        private const val TAG = "VdLifecycleArbiter"
        private const val DRAIN_TIMEOUT_MS = 5_000L
        private const val DRAIN_POLL_MS = 5L
    }

    /**
     * Execute a lifecycle transition under exclusive access.
     *
     * Acquires the lifecycle mutex, optionally transforms the state via [preDrainTransform]
     * to reject new operational calls during the drain window, then waits for in-flight ops
     * to complete (up to [DRAIN_TIMEOUT_MS]). The caller should finalize [state] via
     * [transitionTo] inside the block.
     *
     * For `stop()`, pass a transform that moves Running -> Draining so new [withRunningLease]
     * calls fail fast while in-flight ops can still access resources via providers.
     *
     * @return the result of [block], which receives the state that was active before the transform
     */
    suspend fun <T> withLifecycleTransition(
        preDrainTransform: ((VdState) -> VdState)? = null,
        block: suspend (previousState: VdState) -> T
    ): T =
        lifecycleMutex.withLock {
            val previous = state
            preDrainTransform?.let { state = it(previous) }
            drainActiveOps()
            block(previous)
        }

    /**
     * Execute an operational call under a Running lease.
     *
     * Increments the active-ops counter (preventing lifecycle transitions from proceeding),
     * checks the state is [VdState.Running], and runs the block with the running state.
     *
     * Note: [markBroken] can change state outside the lifecycle mutex. An in-flight lease
     * may briefly observe stale Running state after binder death. The dead binder will
     * reject the call; subsequent calls will fail fast.
     *
     * @throws PlatformNotRunningException if the platform is not [VdState.Running]
     */
    suspend fun <T> withRunningLease(block: suspend (VdState.Running) -> T): T {
        activeOps.incrementAndGet()
        try {
            val s = state
            if (s !is VdState.Running) throw PlatformNotRunningException(s)
            return block(s)
        } finally {
            activeOps.decrementAndGet()
        }
    }

    /** Update state. Call only from within [withLifecycleTransition]. */
    fun transitionTo(newState: VdState) {
        state = newState
    }

    /**
     * Emergency transition to [VdState.Broken].
     *
     * Can be called outside a lifecycle transition (e.g., from binder death callback).
     * In-flight operational calls will complete with whatever error the dead binder produces;
     * subsequent calls will fail fast.
     */
    fun markBroken(reason: String) {
        val current = state
        if (current == VdState.Stopped || current is VdState.Broken) return
        if (current is VdState.Running) {
            state = VdState.Broken(reason, current.displayId, current.imageReader)
        } else if (current is VdState.Draining) {
            state = VdState.Broken(reason, current.displayId, current.imageReader)
        } else {
            // Should not happen, but guard against unexpected states
            state = VdState.Stopped
        }
    }

    private suspend fun drainActiveOps() {
        val deadline = System.nanoTime() + DRAIN_TIMEOUT_MS * 1_000_000
        while (activeOps.get() > 0) {
            if (System.nanoTime() > deadline) {
                Log.w(TAG, "Drain timeout — ${activeOps.get()} ops still in flight, proceeding")
                break
            }
            delay(DRAIN_POLL_MS)
        }
    }
}

/** Thrown when an operational call is attempted on a non-Running platform. */
class PlatformNotRunningException(state: VdState) :
    IllegalStateException("Platform not running (${state.description})")

private val VdState.description: String
    get() = when (this) {
        VdState.Stopped -> "Stopped"
        is VdState.Running -> "Running(displayId=$displayId)"
        is VdState.Draining -> "Draining(displayId=$displayId)"
        is VdState.Broken -> "Broken: $reason"
    }
