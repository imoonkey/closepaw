package com.moonkey.androidagent.ui.overlay

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.graphics.drawable.GradientDrawable
import com.moonkey.androidagent.ui.overlay.model.CapsuleMode

/**
 * SmartCapsuleRenderer — pure visual rendering for each CapsuleMode.
 *
 * Sets visibility, text, colors, dot animation, and content transitions on [CapsuleViews].
 * Does NOT handle click listeners, keyboard, or overlay focus state —
 * those are the manager's responsibility after [render] returns.
 *
 * View-level animations (dot crossfade, content fade) live here.
 * Window-level animations (height, exit) live in [SmartCapsuleAnimator].
 */
internal class SmartCapsuleRenderer {

    companion object {
        // Dot colors
        internal const val COLOR_BLUE = 0xFF2563EB.toInt()
        internal const val COLOR_AMBER = 0xFFF59E0B.toInt()
        internal const val COLOR_TEAL = 0xFF0D9488.toInt()
        internal const val COLOR_RED = 0xFFEF4444.toInt()
    }

    private var pulseAnimator: AnimatorSet? = null
    private var dotColorAnimator: ValueAnimator? = null
    private var currentDotColor: Int = 0

    /**
     * Render the capsule UI for the given mode.
     * [previousMode] enables transition animations (dot crossfade, content fade).
     * Call on the main thread (inside View.post).
     */
    fun render(v: CapsuleViews, mode: CapsuleMode, previousMode: CapsuleMode? = null) {
        dotColorAnimator?.cancel()
        when (mode) {
            is CapsuleMode.Running -> renderRunning(v, mode, previousMode)
            is CapsuleMode.TakeoverPending -> renderTakeoverPending(v, mode, previousMode)
            is CapsuleMode.Takeover -> renderTakeover(v, mode, previousMode)
            is CapsuleMode.SupplementInput -> renderSupplementInput(v, mode)
            is CapsuleMode.Done -> renderDone(v, mode)
            is CapsuleMode.Error -> renderError(v, mode)
            is CapsuleMode.WaitingForInput -> renderWaitingForInput(v, mode, previousMode)
            is CapsuleMode.WaitingForAction -> renderWaitingForAction(v, mode, previousMode)
            is CapsuleMode.Hidden -> {} // handled by manager
        }
    }

    /** Cancel view-level animations (dot crossfade). */
    fun cancelAnimations() {
        dotColorAnimator?.cancel()
        dotColorAnimator = null
    }

    // ── Compact mode renders ──

    private fun renderRunning(v: CapsuleViews, mode: CapsuleMode.Running, previousMode: CapsuleMode?) {
        val animateDot = previousMode is CapsuleMode.TakeoverPending || previousMode is CapsuleMode.Takeover
        setDotColor(v, COLOR_BLUE, pulsing = true, animate = animateDot)
        v.statusDot.visibility = View.VISIBLE
        v.thoughtText.text = mode.thought.ifEmpty { "思考中..." }
        v.thoughtText.alpha = 1f
        v.thoughtText.maxLines = 1
        v.expandedBody?.visibility = View.GONE

        v.row2.visibility = View.VISIBLE
        v.divider.visibility = View.VISIBLE
        v.supplementInputArea?.visibility = View.GONE

        showAllButtons(v)
        v.supplementButton.isEnabled = true
        v.supplementButton.alpha = 1f

        v.primaryIcon.text = "✋"
        v.primaryText.text = "接管"
        v.primaryButton.contentDescription = "接管"
        v.primaryButton.isEnabled = true
        v.primaryButton.alpha = 1f

        v.stopIcon.text = "⏹"
        v.stopText.text = "停止"
        v.stopButton.contentDescription = "停止"
        v.stopButton.isEnabled = true
        v.stopButton.alpha = 1f
    }

    private fun renderTakeoverPending(v: CapsuleViews, mode: CapsuleMode.TakeoverPending, previousMode: CapsuleMode?) {
        val animateDot = previousMode is CapsuleMode.Running
        setDotColor(v, COLOR_AMBER, pulsing = false, animate = animateDot)
        v.statusDot.visibility = View.VISIBLE
        v.thoughtText.text = "正在交接..."
        v.thoughtText.alpha = 1f
        v.thoughtText.maxLines = 1
        v.expandedBody?.visibility = View.GONE

        v.row2.visibility = View.VISIBLE
        v.divider.visibility = View.VISIBLE
        v.supplementInputArea?.visibility = View.GONE

        showAllButtons(v)
        v.supplementButton.isEnabled = false
        v.supplementButton.alpha = 0.4f

        v.primaryIcon.text = "✋"
        v.primaryText.text = "交接中"
        v.primaryButton.isEnabled = false
        v.primaryButton.alpha = 0.4f

        v.stopIcon.text = "⏹"
        v.stopText.text = "停止"
        v.stopButton.isEnabled = true
        v.stopButton.alpha = 1f
    }

    private fun renderTakeover(v: CapsuleViews, mode: CapsuleMode.Takeover, previousMode: CapsuleMode?) {
        val animateDot = previousMode is CapsuleMode.Running
        setDotColor(v, COLOR_AMBER, pulsing = false, animate = animateDot)
        v.statusDot.visibility = View.VISIBLE
        v.thoughtText.text = mode.lastThought.ifEmpty { "已暂停" }
        v.thoughtText.alpha = 0.6f
        v.thoughtText.maxLines = 1
        v.expandedBody?.visibility = View.GONE

        v.row2.visibility = View.VISIBLE
        v.divider.visibility = View.VISIBLE
        v.supplementInputArea?.visibility = View.GONE

        showAllButtons(v)
        v.supplementButton.isEnabled = true
        v.supplementButton.alpha = 1f

        v.primaryIcon.text = "▶"
        v.primaryText.text = "继续"
        v.primaryButton.contentDescription = "继续"
        v.primaryButton.isEnabled = true
        v.primaryButton.alpha = 1f

        v.stopIcon.text = "⏹"
        v.stopText.text = "停止"
        v.stopButton.contentDescription = "停止"
        v.stopButton.isEnabled = true
        v.stopButton.alpha = 1f
    }

    private fun renderDone(v: CapsuleViews, mode: CapsuleMode.Done) {
        setDotColor(v, COLOR_TEAL, pulsing = false)
        v.statusDot.visibility = View.VISIBLE
        v.thoughtText.text = "✓ ${mode.message}"
        v.thoughtText.alpha = 1f
        v.thoughtText.maxLines = 1
        v.expandedBody?.visibility = View.GONE

        v.row2.visibility = View.GONE
        v.divider.visibility = View.GONE
    }

    private fun renderError(v: CapsuleViews, mode: CapsuleMode.Error) {
        setDotColor(v, COLOR_RED, pulsing = false)
        v.statusDot.visibility = View.VISIBLE
        v.thoughtText.text = "⚠ ${mode.message}"
        v.thoughtText.alpha = 1f
        v.thoughtText.maxLines = 1
        v.expandedBody?.visibility = View.GONE

        v.row2.visibility = View.VISIBLE
        v.divider.visibility = View.VISIBLE
        v.supplementInputArea?.visibility = View.GONE

        v.supplementButton.visibility = View.GONE
        v.primaryButton.visibility = View.GONE

        v.stopIcon.text = "✕"
        v.stopText.text = "关闭"
        v.stopButton.contentDescription = "关闭"
        v.stopButton.visibility = View.VISIBLE
        v.stopButton.isEnabled = true
        v.stopButton.alpha = 1f
    }

    // ── Expanded mode renders ──

    private fun renderWaitingForInput(v: CapsuleViews, mode: CapsuleMode.WaitingForInput, previousMode: CapsuleMode?) {
        stopPulse()
        v.statusDot.visibility = View.GONE
        v.thoughtText.text = "💬 等待答复"
        v.thoughtText.alpha = 1f
        v.thoughtText.maxLines = 1

        // Expanded body: show the question (fade in from compact modes)
        v.expandedBody?.text = mode.question
        val fromCompact = previousMode != null && !isExpandedMode(previousMode)
        if (fromCompact && v.expandedBody != null) {
            fadeIn(v.expandedBody!!)
        } else {
            v.expandedBody?.visibility = View.VISIBLE
        }

        // Input area for the answer
        v.supplementInputArea?.visibility = View.VISIBLE
        v.supplementEditText?.text?.clear()
        v.supplementEditText?.hint = "输入你的答复..."

        // Row 2: only stop button
        v.row2.visibility = View.VISIBLE
        v.divider.visibility = View.VISIBLE
        v.supplementButton.visibility = View.GONE
        v.primaryButton.visibility = View.GONE

        v.stopIcon.text = "⏹"
        v.stopText.text = "停止"
        v.stopButton.contentDescription = "停止"
        v.stopButton.visibility = View.VISIBLE
        v.stopButton.isEnabled = true
        v.stopButton.alpha = 1f
    }

    private fun renderWaitingForAction(v: CapsuleViews, mode: CapsuleMode.WaitingForAction, previousMode: CapsuleMode?) {
        stopPulse()
        v.statusDot.visibility = View.GONE
        v.thoughtText.text = "✋ 操作手机"
        v.thoughtText.alpha = 1f
        v.thoughtText.maxLines = 1

        // Expanded body: show the instruction (fade in from compact modes)
        v.expandedBody?.text = mode.instruction
        val fromCompact = previousMode != null && !isExpandedMode(previousMode)
        if (fromCompact && v.expandedBody != null) {
            fadeIn(v.expandedBody!!)
        } else {
            v.expandedBody?.visibility = View.VISIBLE
        }

        v.supplementInputArea?.visibility = View.GONE

        // Row 2: [完成] [停止]
        v.row2.visibility = View.VISIBLE
        v.divider.visibility = View.VISIBLE
        v.supplementButton.visibility = View.GONE

        v.primaryIcon.text = "✅"
        v.primaryText.text = "完成"
        v.primaryButton.contentDescription = "完成"
        v.primaryButton.visibility = View.VISIBLE
        v.primaryButton.isEnabled = true
        v.primaryButton.alpha = 1f

        v.stopIcon.text = "⏹"
        v.stopText.text = "停止"
        v.stopButton.contentDescription = "停止"
        v.stopButton.visibility = View.VISIBLE
        v.stopButton.isEnabled = true
        v.stopButton.alpha = 1f
    }

    private fun renderSupplementInput(v: CapsuleViews, mode: CapsuleMode.SupplementInput) {
        stopPulse()
        v.statusDot.visibility = View.GONE
        v.thoughtText.text = "补充你的想法"
        v.thoughtText.alpha = 1f
        v.expandedBody?.visibility = View.GONE

        v.supplementInputArea?.visibility = View.VISIBLE
        v.supplementEditText?.text?.clear()
        v.supplementEditText?.hint = "输入补充信息..."

        // Row 2: only close button
        v.row2.visibility = View.VISIBLE
        v.divider.visibility = View.VISIBLE
        v.supplementButton.visibility = View.GONE
        v.primaryButton.visibility = View.GONE

        v.stopIcon.text = "✕"
        v.stopText.text = "取消"
        v.stopButton.contentDescription = "取消"
        v.stopButton.visibility = View.VISIBLE
        v.stopButton.isEnabled = true
        v.stopButton.alpha = 1f
    }

    // ── Dot helpers ──

    fun setDotColor(v: CapsuleViews, color: Int, pulsing: Boolean, animate: Boolean = false) {
        dotColorAnimator?.cancel()
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
        if (pulsing) startPulse(v.statusDot) else stopPulse()
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

    fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    // ── Animation helpers ──

    private fun fadeIn(view: View, duration: Long = 150) {
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setDuration(duration).start()
    }

    private fun isExpandedMode(mode: CapsuleMode): Boolean = when (mode) {
        is CapsuleMode.WaitingForInput,
        is CapsuleMode.WaitingForAction,
        is CapsuleMode.SupplementInput -> true
        else -> false
    }

    // ── Helpers ──

    private fun showAllButtons(v: CapsuleViews) {
        v.supplementButton.visibility = View.VISIBLE
        v.primaryButton.visibility = View.VISIBLE
        v.stopButton.visibility = View.VISIBLE
    }
}
