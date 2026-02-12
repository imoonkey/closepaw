package com.moonkey.androidagent.ui.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * SmartCapsuleAnimator — window-level animation helpers.
 *
 * Handles overlay height expansion/collapse and exit slide+fade.
 * The renderer handles view-level animations (dot crossfade, content fade).
 */
internal class SmartCapsuleAnimator(
    private val windowManager: WindowManager,
    private val density: Float
) {
    private var heightAnimator: ValueAnimator? = null
    private var exitAnimator: AnimatorSet? = null

    /** Cancel all running animations. Call on hide() or rapid mode changes. */
    fun cancelAll() {
        heightAnimator?.cancel()
        heightAnimator = null
        exitAnimator?.cancel()
        exitAnimator = null
    }

    /**
     * Lock the overlay to its current pixel height, cancelling any in-flight
     * height animation. Call BEFORE rendering new content to prevent size jump.
     */
    fun lockHeight(container: ViewGroup) {
        heightAnimator?.cancel()
        heightAnimator = null
        setOverlayHeight(container, container.height)
    }

    /**
     * Measure the container's desired height after content changes, then animate
     * from [fromHeight] to that target. Restores WRAP_CONTENT on completion.
     * If the height difference is negligible (≤4px), snaps immediately.
     */
    fun animateToMeasuredHeight(container: ViewGroup, fromHeight: Int) {
        container.measure(
            View.MeasureSpec.makeMeasureSpec(container.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val targetHeight = container.measuredHeight
        if (Math.abs(targetHeight - fromHeight) <= 4) {
            setOverlayHeight(container, WindowManager.LayoutParams.WRAP_CONTENT)
            return
        }
        val expanding = targetHeight > fromHeight
        heightAnimator = ValueAnimator.ofInt(fromHeight, targetHeight).apply {
            duration = if (expanding) 250L else 200L
            interpolator = if (expanding) DecelerateInterpolator() else AccelerateInterpolator()
            addUpdateListener { setOverlayHeight(container, it.animatedValue as Int) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    setOverlayHeight(container, WindowManager.LayoutParams.WRAP_CONTENT)
                    heightAnimator = null
                }
            })
            start()
        }
    }

    /**
     * Slide down + fade out for the Done → Hidden exit transition.
     * Resets translationY and alpha on completion before calling [onEnd].
     */
    fun animateExit(container: ViewGroup, onEnd: () -> Unit) {
        exitAnimator?.cancel()
        val slideDown = ObjectAnimator.ofFloat(container, "translationY", 0f, 16f * density)
        val fadeOut = ObjectAnimator.ofFloat(container, "alpha", 1f, 0f)
        exitAnimator = AnimatorSet().apply {
            playTogether(slideDown, fadeOut)
            duration = 300
            interpolator = AccelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                    container.translationY = 0f
                    container.alpha = 1f
                    exitAnimator = null
                }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) {
                        container.translationY = 0f
                        container.alpha = 1f
                        onEnd()
                        exitAnimator = null
                    }
                }
            })
            start()
        }
    }

    private fun setOverlayHeight(container: ViewGroup, height: Int) {
        val params = container.layoutParams as? WindowManager.LayoutParams ?: return
        params.height = height
        try { windowManager.updateViewLayout(container, params) } catch (_: Exception) {}
    }
}
