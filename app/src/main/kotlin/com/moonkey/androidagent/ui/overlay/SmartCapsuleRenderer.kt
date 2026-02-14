package com.moonkey.androidagent.ui.overlay

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.moonkey.androidagent.protocol.PlatformMode
import com.moonkey.androidagent.ui.overlay.model.CapsuleContext
import com.moonkey.androidagent.ui.overlay.model.CapsuleRenderSpec
import com.moonkey.androidagent.ui.overlay.model.NavSpec

/**
 * SmartCapsuleRenderer — applies a [CapsuleRenderSpec] to [CapsuleViews].
 *
 * Pure visual rendering. No business logic, no state tracking, no callbacks.
 * Receives a spec, sets view properties. That's it.
 *
 * View-level animations (dot crossfade, pulse, content fade) live here.
 * Window-level animations (height, exit) live in [SmartCapsuleAnimator].
 */
internal class SmartCapsuleRenderer {

    private var pulseAnimator: AnimatorSet? = null
    private var dotColorAnimator: ValueAnimator? = null
    private var currentDotColor: Int = 0

    /**
     * Apply a [CapsuleRenderSpec] to the views.
     * [animateDot] enables dot color crossfade transition.
     * [fadeInBody] enables fade-in animation for expanded body.
     */
    fun render(
        v: CapsuleViews,
        spec: CapsuleRenderSpec,
        animateDot: Boolean = false,
        fadeInBody: Boolean = false,
    ) {
        dotColorAnimator?.cancel()

        // ── Row 1: dot + thought ──
        renderDot(v, spec.dot, animateDot)
        v.thoughtText.text = spec.thought.text
        v.thoughtText.alpha = spec.thought.alpha
        v.thoughtText.maxLines = 1
        v.row1.visibility = View.VISIBLE

        // ── Expanded body ──
        if (spec.expandedBody != null) {
            v.expandedBody?.text = spec.expandedBody
            if (fadeInBody) fadeIn(v.expandedBody) else v.expandedBody?.visibility = View.VISIBLE
        } else {
            v.expandedBody?.visibility = View.GONE
        }

        // ── Divider 1 ──
        v.divider1.visibility = if (spec.buttons.primary != null || spec.buttons.stop != null) {
            View.VISIBLE
        } else {
            View.GONE
        }

        // ── Row 2: buttons ──
        renderButtons(v, spec.buttons)

        // ── Divider 2 + Row 3 ──
        if (spec.row3 != null) {
            v.divider2.visibility = View.VISIBLE
            v.row3.visibility = View.VISIBLE
            v.inputEditText.hint = spec.row3.hint
            v.inputButtonText.text = spec.row3.buttonText
            if (spec.row3.clearInput) {
                v.inputEditText.text?.clear()
            }
        } else {
            v.divider2.visibility = View.GONE
            v.row3.visibility = View.GONE
        }
    }

    /**
     * Apply navigation button visibility from [NavSpec].
     * Called after render() by the manager.
     */
    fun applyNavSpec(v: CapsuleViews, navSpec: NavSpec) {
        v.navMinimize?.visibility = if (navSpec.showMinimize) View.VISIBLE else View.GONE
        v.navApp?.visibility = if (navSpec.showApp) View.VISIBLE else View.GONE
        v.navWatch?.visibility = if (navSpec.showWatch) View.VISIBLE else View.GONE
    }

    fun cancelAnimations() {
        dotColorAnimator?.cancel()
        dotColorAnimator = null
    }

    fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    // ── Private: dot rendering ──

    private fun renderDot(v: CapsuleViews, dot: CapsuleRenderSpec.DotSpec?, animate: Boolean) {
        if (dot == null) {
            v.statusDot.visibility = View.GONE
            stopPulse()
            return
        }
        v.statusDot.visibility = View.VISIBLE
        setDotColor(v, dot.color, animate)
        if (dot.pulsing) startPulse(v.statusDot) else stopPulse()
    }

    private fun setDotColor(v: CapsuleViews, color: Int, animate: Boolean) {
        if (animate && currentDotColor != 0 && currentDotColor != color) {
            dotColorAnimator = ValueAnimator.ofArgb(currentDotColor, color).apply {
                duration = 200
                addUpdateListener {
                    (v.statusDot.background as? GradientDrawable)?.setColor(it.animatedValue as Int)
                }
                start()
            }
        } else {
            (v.statusDot.background as? GradientDrawable)?.setColor(color)
        }
        currentDotColor = color
    }

    private fun startPulse(dot: View) {
        stopPulse()
        val scaleX = ObjectAnimator.ofFloat(dot, "scaleX", 1f, 1.3f, 1f).apply {
            duration = 1500
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val scaleY = ObjectAnimator.ofFloat(dot, "scaleY", 1f, 1.3f, 1f).apply {
            duration = 1500
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        pulseAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            start()
        }
    }

    // ── Private: button rendering ──

    private fun renderButtons(v: CapsuleViews, buttons: CapsuleRenderSpec.ButtonsSpec) {
        if (buttons.primary == null && buttons.stop == null) {
            v.row2.visibility = View.GONE
            return
        }
        v.row2.visibility = View.VISIBLE

        if (buttons.primary != null) {
            v.primaryButton.visibility = View.VISIBLE
            v.primaryIcon.text = buttons.primary.icon
            v.primaryText.text = buttons.primary.text
            v.primaryButton.contentDescription = buttons.primary.text
            v.primaryButton.isEnabled = buttons.primary.enabled
            v.primaryButton.alpha = if (buttons.primary.enabled) 1f else 0.4f
        } else {
            v.primaryButton.visibility = View.GONE
        }

        if (buttons.stop != null) {
            v.stopButton.visibility = View.VISIBLE
            v.stopIcon.text = buttons.stop.icon
            v.stopText.text = buttons.stop.text
            v.stopButton.contentDescription = buttons.stop.text
            v.stopButton.isEnabled = buttons.stop.enabled
            v.stopButton.alpha = if (buttons.stop.enabled) 1f else 0.4f
        } else {
            v.stopButton.visibility = View.GONE
        }
    }

    // ── Private: animation helpers ──

    private fun fadeIn(view: View?, duration: Long = 150) {
        if (view == null) return
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setDuration(duration).start()
    }
}
