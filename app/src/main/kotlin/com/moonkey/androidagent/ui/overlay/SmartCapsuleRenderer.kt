package com.moonkey.androidagent.ui.overlay

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.graphics.drawable.GradientDrawable
import com.moonkey.androidagent.ui.overlay.model.CapsuleMode

/**
 * SmartCapsuleRenderer — pure visual rendering for each CapsuleMode.
 *
 * Sets visibility, text, colors, and dot animation on [CapsuleViews].
 * Does NOT handle click listeners, keyboard, or overlay focus state —
 * those are the manager's responsibility after [render] returns.
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

    /**
     * Render the capsule UI for the given mode.
     * Call on the main thread (inside View.post).
     */
    fun render(v: CapsuleViews, mode: CapsuleMode) {
        // All compact modes: ensure expandedBody is hidden
        v.expandedBody?.visibility = View.GONE

        when (mode) {
            is CapsuleMode.Running -> renderRunning(v, mode)
            is CapsuleMode.TakeoverPending -> renderTakeoverPending(v, mode)
            is CapsuleMode.Takeover -> renderTakeover(v, mode)
            is CapsuleMode.SupplementInput -> renderSupplementInput(v, mode)
            is CapsuleMode.Done -> renderDone(v, mode)
            is CapsuleMode.Error -> renderError(v, mode)
            is CapsuleMode.WaitingForInput -> renderWaitingForInput(v, mode)
            is CapsuleMode.WaitingForAction -> renderWaitingForAction(v, mode)
            is CapsuleMode.Hidden -> {} // handled by manager
        }
    }

    // ── Compact mode renders ──

    private fun renderRunning(v: CapsuleViews, mode: CapsuleMode.Running) {
        setDotColor(v, COLOR_BLUE, pulsing = true)
        v.statusDot.visibility = View.VISIBLE
        v.thoughtText.text = mode.thought.ifEmpty { "思考中..." }
        v.thoughtText.alpha = 1f
        v.thoughtText.maxLines = 1

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

    private fun renderTakeoverPending(v: CapsuleViews, mode: CapsuleMode.TakeoverPending) {
        setDotColor(v, COLOR_AMBER, pulsing = false)
        v.statusDot.visibility = View.VISIBLE
        v.thoughtText.text = "正在交接..."
        v.thoughtText.alpha = 1f
        v.thoughtText.maxLines = 1

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

    private fun renderTakeover(v: CapsuleViews, mode: CapsuleMode.Takeover) {
        setDotColor(v, COLOR_AMBER, pulsing = false)
        v.statusDot.visibility = View.VISIBLE
        v.thoughtText.text = mode.lastThought.ifEmpty { "已暂停" }
        v.thoughtText.alpha = 0.6f
        v.thoughtText.maxLines = 1

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

        v.row2.visibility = View.GONE
        v.divider.visibility = View.GONE
    }

    private fun renderError(v: CapsuleViews, mode: CapsuleMode.Error) {
        setDotColor(v, COLOR_RED, pulsing = false)
        v.statusDot.visibility = View.VISIBLE
        v.thoughtText.text = "⚠ ${mode.message}"
        v.thoughtText.alpha = 1f
        v.thoughtText.maxLines = 1

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

    // ── Expanded mode renders (WaitingFor* + SupplementInput) ──

    private fun renderWaitingForInput(v: CapsuleViews, mode: CapsuleMode.WaitingForInput) {
        stopPulse()
        v.statusDot.visibility = View.GONE
        v.thoughtText.text = "💬 等待答复"
        v.thoughtText.alpha = 1f
        v.thoughtText.maxLines = 1

        // Expanded body: show the question
        v.expandedBody?.visibility = View.VISIBLE
        v.expandedBody?.text = mode.question

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

    private fun renderWaitingForAction(v: CapsuleViews, mode: CapsuleMode.WaitingForAction) {
        stopPulse()
        v.statusDot.visibility = View.GONE
        v.thoughtText.text = "✋ 操作手机"
        v.thoughtText.alpha = 1f
        v.thoughtText.maxLines = 1

        // Expanded body: show the instruction
        v.expandedBody?.visibility = View.VISIBLE
        v.expandedBody?.text = mode.instruction

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

    fun setDotColor(v: CapsuleViews, color: Int, pulsing: Boolean) {
        (v.statusDot.background as? GradientDrawable)?.setColor(color)
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

    // ── Helpers ──

    private fun showAllButtons(v: CapsuleViews) {
        v.supplementButton.visibility = View.VISIBLE
        v.primaryButton.visibility = View.VISIBLE
        v.stopButton.visibility = View.VISIBLE
    }
}
