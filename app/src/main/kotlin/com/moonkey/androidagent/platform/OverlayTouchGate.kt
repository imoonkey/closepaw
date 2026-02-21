package com.moonkey.androidagent.platform

/**
 * Gate that temporarily makes the capsule overlay non-touchable during gesture dispatch.
 *
 * Prevents `dispatchGesture` events from being silently consumed by the overlay window.
 * The overlay restores its baseline touchability after the returned token is closed.
 *
 * Thread safety: Must be called on the Main thread (same thread as `dispatchGesture`).
 */
interface OverlayTouchGate {
    /**
     * Enter gesture pass-through mode. The overlay becomes [FLAG_NOT_TOUCHABLE].
     * Call [AutoCloseable.close] when the gesture completes (or in a `finally` block).
     */
    fun beginGesturePassThrough(): AutoCloseable
}
