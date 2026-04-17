package ai.closepaw.platform

import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "BoundedCallback"

/**
 * Bounded callback-to-suspend bridge.
 *
 * Converts a callback-driven framework API into a suspend call with a hard deadline.
 * Returns null if the callback does not fire within [timeoutMs], or if the coroutine
 * is cancelled while waiting.
 *
 * On timeout or cancellation, [onCancel] runs to clean up preallocated resources
 * (e.g., Bitmaps allocated before the callback registration).
 *
 * Late callback resumes on an already-cancelled continuation are silently discarded
 * by kotlinx.coroutines 1.7.3+.
 *
 * @param timeoutMs Maximum wait time in milliseconds
 * @param label Descriptive label for timeout warning logs
 * @param onCancel Optional cleanup for preallocated resources on cancellation/timeout
 * @param register Lambda that registers the framework callback. Must call
 *   `cont.resume(value)` on success or `cont.resume(null)` on framework-level failure.
 */
internal suspend fun <T : Any> boundedCallback(
    timeoutMs: Long,
    label: String,
    onCancel: (() -> Unit)? = null,
    register: (CancellableContinuation<T?>) -> Unit
): T? {
    val result = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            if (onCancel != null) {
                cont.invokeOnCancellation { onCancel() }
            }
            register(cont)
        }
    }
    if (result == null) {
        Log.w(TAG, "$label: timed out after ${timeoutMs}ms or cancelled")
    }
    return result
}
