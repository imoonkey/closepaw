package com.moonkey.androidagent.ui.overlay

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.graphics.drawable.GradientDrawable
import com.moonkey.androidagent.protocol.PlatformMode
import com.moonkey.androidagent.ui.overlay.model.CapsuleContext
import com.moonkey.androidagent.ui.overlay.model.CapsuleMode
import com.moonkey.androidagent.ui.overlay.model.isExpanded

/**
 * SmartCapsuleRenderer — pure visual rendering for each CapsuleMode.
 *
 * Sets visibility, text, colors, dot animation, and content transitions on [CapsuleViews].
 * Does NOT handle click listeners, keyboard, or overlay focus state —
 * those are the manager's responsibility after [render] returns.
 *
 * Three-row layout:
 *   Row 1: thought line (dot + text)
 *   Row 2: control buttons + nav icons
 *   Row 3: input field + action button
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
            is CapsuleMode.Done -> renderDone(v, mode)
            is CapsuleMode.Error -> renderError(v, mode)
            is CapsuleMode.WaitingForInput -> renderWaitingForInput(v, mode, previousMode)
            is CapsuleMode.WaitingForAction -> renderWaitingForAction(v, mode, previousMode)
            is CapsuleMode.Hidden -> {} // handled by manager
        }
    }

    /**
     * Configure navigation button visibility based on context and platform mode.
     * Called after render() by the manager.
     */
    fun configureNavButtons(
        v: CapsuleViews,
        context: CapsuleContext,
        platformMode: PlatformMode,
        hasIsland: Boolean = true
    ) {
        // [1] ⊖ Minimize: only in VD mode AND only when island exists to return to
        val showMinimize = platformMode == PlatformMode.VIRTUAL_DISPLAY && hasIsland
        // [2] 📱 App: never when already in the app
        val showApp = context != CapsuleContext.MAIN_APP
        // [3] 👁 Watch: never in A11y mode; never when already viewing
        val showWatch = when {
            platformMode == PlatformMode.ACCESSIBILITY -> false
            context == CapsuleContext.SCREEN_VIEWING -> false
            else -> true
        }
        v.navMinimize?.visibility = if (showMinimize) View.VISIBLE else View.GONE
        v.navApp?.visibility = if (showApp) View.VISIBLE else View.GONE
        v.navWatch?.visibility = if (showWatch) View.VISIBLE else View.GONE
    }

    /** Cancel view-level animations (dot crossfade). */
    fun cancelAnimations() {
        dotColorAnimator?.cancel()
        dotColorAnimator = null
    }

    // ── Running ──

    private fun renderRunning(v: CapsuleViews, mode: CapsuleMode.Running, previousMode: CapsuleMode?) {
        val animateDot = previousMode is CapsuleMode.TakeoverPending || previousMode is CapsuleMode.Takeover
        setDotColor(v, COLOR_BLUE, pulsing = true, animate = animateDot)
        v.statusDot.visibility = View.VISIBLE
        v.thoughtText.text = mode.thought.ifEmpty { "思考中..." }
        v.thoughtText.alpha = 1f
        v.thoughtText.maxLines = 1
        v.expandedBody?.visibility = View.GONE

        // Row 1 + divider1 visible
        v.row1.visibility = View.VISIBLE
        v.divider1.visibility = View.VISIBLE

        // Row 2: [接管] [停止] + nav
        v.row2.visibility = View.VISIBLE
        v.primaryButton.visibility = View.VISIBLE
        v.primaryIcon.text = "✋"
        v.primaryText.text = "接管"
        v.primaryButton.contentDescription = "接管"
        v.primaryButton.isEnabled = true
        v.primaryButton.alpha = 1f
        v.stopButton.visibility = View.VISIBLE
        v.stopIcon.text = "⏹"
        v.stopText.text = "停止"
        v.stopButton.contentDescription = "停止"
        v.stopButton.isEnabled = true
        v.stopButton.alpha = 1f

        // Row 3: supplement mode
        v.divider2.visibility = View.VISIBLE
        v.row3.visibility = View.VISIBLE
        configureRow3Supplement(v)
    }

    // ── TakeoverPending ──

    private fun renderTakeoverPending(v: CapsuleViews, mode: CapsuleMode.TakeoverPending, previousMode: CapsuleMode?) {
        val animateDot = previousMode is CapsuleMode.Running
        setDotColor(v, COLOR_AMBER, pulsing = false, animate = animateDot)
        v.statusDot.visibility = View.VISIBLE
        v.thoughtText.text = "正在交接..."
        v.thoughtText.alpha = 1f
        v.thoughtText.maxLines = 1
        v.expandedBody?.visibility = View.GONE

        v.row1.visibility = View.VISIBLE
        v.divider1.visibility = View.VISIBLE

        // Row 2: [接管 disabled] [停止]
        v.row2.visibility = View.VISIBLE
        v.primaryButton.visibility = View.VISIBLE
        v.primaryIcon.text = "✋"
        v.primaryText.text = "交接中"
        v.primaryButton.isEnabled = false
        v.primaryButton.alpha = 0.4f
        v.stopButton.visibility = View.VISIBLE
        v.stopIcon.text = "⏹"
        v.stopText.text = "停止"
        v.stopButton.isEnabled = true
        v.stopButton.alpha = 1f

        // Row 3: supplement mode
        v.divider2.visibility = View.VISIBLE
        v.row3.visibility = View.VISIBLE
        configureRow3Supplement(v)
    }

    // ── Takeover ──

    private fun renderTakeover(v: CapsuleViews, mode: CapsuleMode.Takeover, previousMode: CapsuleMode?) {
        val animateDot = previousMode is CapsuleMode.Running
        setDotColor(v, COLOR_AMBER, pulsing = false, animate = animateDot)
        v.statusDot.visibility = View.VISIBLE
        v.thoughtText.text = mode.lastThought.ifEmpty { "已暂停" }
        v.thoughtText.alpha = 0.6f
        v.thoughtText.maxLines = 1
        v.expandedBody?.visibility = View.GONE

        v.row1.visibility = View.VISIBLE
        v.divider1.visibility = View.VISIBLE

        // Row 2: [继续] [停止]
        v.row2.visibility = View.VISIBLE
        v.primaryButton.visibility = View.VISIBLE
        v.primaryIcon.text = "▶"
        v.primaryText.text = "继续"
        v.primaryButton.contentDescription = "继续"
        v.primaryButton.isEnabled = true
        v.primaryButton.alpha = 1f
        v.stopButton.visibility = View.VISIBLE
        v.stopIcon.text = "⏹"
        v.stopText.text = "停止"
        v.stopButton.contentDescription = "停止"
        v.stopButton.isEnabled = true
        v.stopButton.alpha = 1f

        // Row 3: supplement mode
        v.divider2.visibility = View.VISIBLE
        v.row3.visibility = View.VISIBLE
        configureRow3Supplement(v)
    }

    // ── Done ──

    private fun renderDone(v: CapsuleViews, mode: CapsuleMode.Done) {
        setDotColor(v, COLOR_TEAL, pulsing = false)
        v.statusDot.visibility = View.VISIBLE
        v.thoughtText.text = "✓ ${mode.message}"
        v.thoughtText.alpha = 1f
        v.thoughtText.maxLines = 1
        v.expandedBody?.visibility = View.GONE

        v.row1.visibility = View.VISIBLE
        v.divider1.visibility = View.GONE
        v.row2.visibility = View.GONE
        v.divider2.visibility = View.GONE
        v.row3.visibility = View.GONE
    }

    // ── Error ──

    private fun renderError(v: CapsuleViews, mode: CapsuleMode.Error) {
        setDotColor(v, COLOR_RED, pulsing = false)
        v.statusDot.visibility = View.VISIBLE
        v.thoughtText.text = "⚠ ${mode.message}"
        v.thoughtText.alpha = 1f
        v.thoughtText.maxLines = 1
        v.expandedBody?.visibility = View.GONE

        v.row1.visibility = View.VISIBLE
        v.divider1.visibility = View.VISIBLE

        // Row 2: [关闭] only
        v.row2.visibility = View.VISIBLE
        v.primaryButton.visibility = View.GONE
        v.stopIcon.text = "✕"
        v.stopText.text = "关闭"
        v.stopButton.contentDescription = "关闭"
        v.stopButton.visibility = View.VISIBLE
        v.stopButton.isEnabled = true
        v.stopButton.alpha = 1f

        // Row 3: hidden
        v.divider2.visibility = View.GONE
        v.row3.visibility = View.GONE
    }

    // ── WaitingForInput ──

    private fun renderWaitingForInput(v: CapsuleViews, mode: CapsuleMode.WaitingForInput, previousMode: CapsuleMode?) {
        stopPulse()
        v.statusDot.visibility = View.GONE
        v.thoughtText.text = "💬 等待答复"
        v.thoughtText.alpha = 1f
        v.thoughtText.maxLines = 1

        // Expanded body: show the question (fade in from compact modes)
        v.expandedBody?.text = mode.question
        showExpandedBody(v.expandedBody, previousMode)

        v.row1.visibility = View.VISIBLE
        v.divider1.visibility = View.VISIBLE

        // Row 2: [停止] only
        v.row2.visibility = View.VISIBLE
        v.primaryButton.visibility = View.GONE
        v.stopIcon.text = "⏹"
        v.stopText.text = "停止"
        v.stopButton.contentDescription = "停止"
        v.stopButton.visibility = View.VISIBLE
        v.stopButton.isEnabled = true
        v.stopButton.alpha = 1f

        // Row 3: answer mode — only clear input when entering from a different mode
        v.divider2.visibility = View.VISIBLE
        v.row3.visibility = View.VISIBLE
        configureRow3Answer(v, previousMode)
    }

    // ── WaitingForAction ──

    private fun renderWaitingForAction(v: CapsuleViews, mode: CapsuleMode.WaitingForAction, previousMode: CapsuleMode?) {
        stopPulse()
        v.statusDot.visibility = View.GONE
        v.thoughtText.text = "✋ 操作手机"
        v.thoughtText.alpha = 1f
        v.thoughtText.maxLines = 1

        // Expanded body: show the instruction (fade in from compact modes)
        v.expandedBody?.text = mode.instruction
        showExpandedBody(v.expandedBody, previousMode)

        v.row1.visibility = View.VISIBLE
        v.divider1.visibility = View.VISIBLE

        // Row 2: [完成] [停止]
        v.row2.visibility = View.VISIBLE
        v.primaryButton.visibility = View.VISIBLE
        v.primaryIcon.text = "✅"
        v.primaryText.text = "完成"
        v.primaryButton.contentDescription = "完成"
        v.primaryButton.isEnabled = true
        v.primaryButton.alpha = 1f
        v.stopButton.visibility = View.VISIBLE
        v.stopIcon.text = "⏹"
        v.stopText.text = "停止"
        v.stopButton.contentDescription = "停止"
        v.stopButton.isEnabled = true
        v.stopButton.alpha = 1f

        // Row 3: hidden (user operates phone)
        v.divider2.visibility = View.GONE
        v.row3.visibility = View.GONE
    }

    // ── Row 3 configuration helpers ──

    private fun configureRow3Supplement(v: CapsuleViews) {
        v.inputEditText.hint = "有想法? 补充一下..."
        v.inputButtonText.text = "补充"
    }

    private fun configureRow3Answer(v: CapsuleViews, previousMode: CapsuleMode?) {
        // Only clear input when transitioning *into* WaitingForInput from another mode,
        // so re-renders (e.g. thought updates) don't erase what the user is typing.
        if (previousMode !is CapsuleMode.WaitingForInput) {
            v.inputEditText.text?.clear()
        }
        v.inputEditText.hint = "输入你的答复..."
        v.inputButtonText.text = "发送 →"
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

    private fun showExpandedBody(body: View?, previousMode: CapsuleMode?) {
        if (body == null) return
        val fromCompact = previousMode != null && !previousMode.isExpanded()
        if (fromCompact) fadeIn(body) else body.visibility = View.VISIBLE
    }
}
