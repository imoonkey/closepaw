package com.moonkey.androidagent.ui.overlay

import android.accessibilityservice.AccessibilityService
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import com.moonkey.androidagent.ui.overlay.model.CapsuleMode

/**
 * SmartCapsuleManager — CapsuleMode-driven floating collaboration surface.
 *
 * One CapsuleMode value drives the entire UI.
 * Call [updateMode] to change state. Call [updateThought] as a convenience
 * to update the thought text while staying in Running mode.
 *
 * The manager owns the overlay lifecycle (show/hide) and renders based on mode.
 */
class SmartCapsuleManager(
    private val service: AccessibilityService
) {
    companion object {
        private const val TAG = "SmartCapsuleManager"
        private const val DEBOUNCE_MS = 300L
    }

    // ── Callbacks ──

    var onTakeover: (() -> Unit)? = null
    var onResume: (() -> Unit)? = null
    var onSupplement: ((String) -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onOpenApp: (() -> Unit)? = null
    var onDismissError: (() -> Unit)? = null

    // ── State ──

    private var mode: CapsuleMode = CapsuleMode.Hidden
    private var views: CapsuleViews? = null
    private var overlayView: ViewGroup? = null
    private var pulseAnimator: AnimatorSet? = null
    private var delayedHideRunnable: Runnable? = null
    private var supplementConfirmedRunnable: Runnable? = null
    private var lastButtonClickTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val layoutBuilder = SmartCapsuleLayoutBuilder(service)

    // ── Colors ──

    private val colorBlue = 0xFF2563EB.toInt()
    private val colorAmber = 0xFFF59E0B.toInt()
    private val colorTeal = 0xFF0D9488.toInt()
    private val colorRed = 0xFFEF4444.toInt()

    // ── Public API ──

    fun isShowing(): Boolean = overlayView != null

    /**
     * Update the capsule to a new mode. This is the primary API.
     * Handles show/hide automatically.
     */
    fun updateMode(newMode: CapsuleMode) {
        val oldMode = mode
        mode = newMode
        Log.d(TAG, "Mode: ${oldMode::class.simpleName} → ${newMode::class.simpleName}")

        // Cancel pending delayed hide (e.g., from Done state) to prevent race
        clearDelayedHide()

        when (newMode) {
            is CapsuleMode.Hidden -> hide()
            else -> {
                if (overlayView == null) show()
                render(newMode)
            }
        }
    }

    /**
     * Convenience: update thought text while in Running mode.
     * If not currently Running, transitions to Running(thought).
     */
    fun updateThought(thought: String) {
        updateMode(CapsuleMode.Running(thought))
    }

    fun show() {
        if (overlayView != null) return
        try {
            val params = layoutBuilder.createLayoutParams()
            val capsuleViews = layoutBuilder.build(
                onSupplement = { debounced { enterSupplementInput() } },
                onPrimary = { debounced { handlePrimaryClick() } },
                onStop = { debounced { handleStopClick() } },
            )
            windowManager.addView(capsuleViews.container, params)
            overlayView = capsuleViews.container
            views = capsuleViews
            Log.i(TAG, "Capsule shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show capsule", e)
        }
    }

    fun hide() {
        stopPulse()
        clearDelayedHide()
        supplementConfirmedRunnable?.let { handler.removeCallbacks(it) }
        supplementConfirmedRunnable = null
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove capsule view", e)
            }
        }
        overlayView = null
        views = null
        mode = CapsuleMode.Hidden
    }

    fun dispose() {
        hide()
    }

    // ── Rendering ──

    private fun render(mode: CapsuleMode) {
        val v = views ?: return
        v.container.post {
            if (overlayView == null) return@post
            when (mode) {
                is CapsuleMode.Running -> renderRunning(v, mode)
                is CapsuleMode.TakeoverPending -> renderTakeoverPending(v, mode)
                is CapsuleMode.Takeover -> renderTakeover(v, mode)
                is CapsuleMode.SupplementInput -> renderSupplementInput(v, mode)
                is CapsuleMode.Done -> renderDone(v, mode)
                is CapsuleMode.Error -> renderError(v, mode)
                is CapsuleMode.Hidden -> {} // handled in updateMode
                // Stage 3 modes — placeholder rendering falls back to Running
                is CapsuleMode.WaitingForInput -> renderRunning(v, CapsuleMode.Running(mode.question))
                is CapsuleMode.WaitingForAction -> renderRunning(v, CapsuleMode.Running(mode.instruction))
            }
        }
    }

    private fun renderRunning(v: CapsuleViews, mode: CapsuleMode.Running) {
        // Row 1: blue pulsing dot + thought
        setDotColor(v, colorBlue, pulsing = true)
        v.statusDot.visibility = View.VISIBLE
        v.thoughtText.text = mode.thought.ifEmpty { "思考中..." }
        v.thoughtText.alpha = 1f

        // Row 2: [补充] [接管] [停止], all visible and enabled
        v.row2.visibility = View.VISIBLE
        v.divider.visibility = View.VISIBLE
        v.supplementInputArea?.visibility = View.GONE

        v.supplementButton.visibility = View.VISIBLE
        v.supplementButton.isEnabled = true
        v.supplementButton.alpha = 1f

        v.primaryIcon.text = "✋"
        v.primaryText.text = "接管"
        v.primaryButton.visibility = View.VISIBLE
        v.primaryButton.isEnabled = true
        v.primaryButton.alpha = 1f

        v.stopIcon.text = "⏹"
        v.stopText.text = "停止"
        v.stopButton.visibility = View.VISIBLE
        v.stopButton.isEnabled = true
        v.stopButton.alpha = 1f
    }

    private fun renderTakeoverPending(v: CapsuleViews, mode: CapsuleMode.TakeoverPending) {
        // Row 1: amber static dot + "正在交接..."
        setDotColor(v, colorAmber, pulsing = false)
        v.statusDot.visibility = View.VISIBLE
        v.thoughtText.text = "正在交接..."
        v.thoughtText.alpha = 1f

        // Row 2: supplement disabled, primary disabled, stop enabled
        v.row2.visibility = View.VISIBLE
        v.divider.visibility = View.VISIBLE
        v.supplementInputArea?.visibility = View.GONE

        v.supplementButton.visibility = View.VISIBLE
        v.supplementButton.isEnabled = false
        v.supplementButton.alpha = 0.4f

        v.primaryIcon.text = "✋"
        v.primaryText.text = "交接中"
        v.primaryButton.visibility = View.VISIBLE
        v.primaryButton.isEnabled = false
        v.primaryButton.alpha = 0.4f

        v.stopIcon.text = "⏹"
        v.stopText.text = "停止"
        v.stopButton.visibility = View.VISIBLE
        v.stopButton.isEnabled = true
        v.stopButton.alpha = 1f
    }

    private fun renderTakeover(v: CapsuleViews, mode: CapsuleMode.Takeover) {
        // Row 1: amber static dot + dimmed last thought
        setDotColor(v, colorAmber, pulsing = false)
        v.statusDot.visibility = View.VISIBLE
        v.thoughtText.text = mode.lastThought.ifEmpty { "已暂停" }
        v.thoughtText.alpha = 0.6f

        // Row 2: [补充] [▶ 继续] [停止]
        v.row2.visibility = View.VISIBLE
        v.divider.visibility = View.VISIBLE
        v.supplementInputArea?.visibility = View.GONE

        v.supplementButton.visibility = View.VISIBLE
        v.supplementButton.isEnabled = true
        v.supplementButton.alpha = 1f

        v.primaryIcon.text = "▶"
        v.primaryText.text = "继续"
        v.primaryButton.visibility = View.VISIBLE
        v.primaryButton.isEnabled = true
        v.primaryButton.alpha = 1f

        v.stopIcon.text = "⏹"
        v.stopText.text = "停止"
        v.stopButton.visibility = View.VISIBLE
        v.stopButton.isEnabled = true
        v.stopButton.alpha = 1f
    }

    private fun renderDone(v: CapsuleViews, mode: CapsuleMode.Done) {
        // Row 1: teal static dot + message
        setDotColor(v, colorTeal, pulsing = false)
        v.thoughtText.text = "✓ ${mode.message}"
        v.thoughtText.alpha = 1f

        // Row 2: hidden
        v.row2.visibility = View.GONE
        v.divider.visibility = View.GONE

        // Auto-hide after 3 seconds (tracked so it can be cancelled)
        clearDelayedHide()
        delayedHideRunnable = Runnable { hide() }.also {
            handler.postDelayed(it, 3000)
        }
    }

    private fun renderError(v: CapsuleViews, mode: CapsuleMode.Error) {
        // Row 1: red static dot + error message
        setDotColor(v, colorRed, pulsing = false)
        v.statusDot.visibility = View.VISIBLE
        v.thoughtText.text = "⚠ ${mode.message}"
        v.thoughtText.alpha = 1f

        // Row 2: only dismiss button
        v.row2.visibility = View.VISIBLE
        v.divider.visibility = View.VISIBLE
        v.supplementInputArea?.visibility = View.GONE

        v.supplementButton.visibility = View.GONE
        v.primaryButton.visibility = View.GONE

        v.stopIcon.text = "✕"
        v.stopText.text = "关闭"
        v.stopButton.visibility = View.VISIBLE
        v.stopButton.isEnabled = true
        v.stopButton.alpha = 1f
    }

    private fun renderSupplementInput(v: CapsuleViews, mode: CapsuleMode.SupplementInput) {
        // Row 1: "补充你的想法" + close button (repurpose dot area)
        stopPulse()
        v.statusDot.visibility = View.GONE
        v.thoughtText.text = "补充你的想法"
        v.thoughtText.alpha = 1f

        // Row 2: show EditText + send button; hide normal buttons
        v.row2.visibility = View.VISIBLE
        v.divider.visibility = View.VISIBLE

        v.supplementButton.visibility = View.GONE
        v.primaryButton.visibility = View.GONE

        // Repurpose stop button as close (✕)
        v.stopIcon.text = "✕"
        v.stopText.text = "取消"
        v.stopButton.visibility = View.VISIBLE
        v.stopButton.isEnabled = true
        v.stopButton.alpha = 1f

        // Show supplement input area
        showSupplementInputArea(v)
    }

    private fun enterSupplementInput() {
        updateMode(CapsuleMode.SupplementInput(previousMode = mode))
    }

    private fun showSupplementInputArea(v: CapsuleViews) {
        val inputArea = v.supplementInputArea ?: return
        val editText = v.supplementEditText ?: return
        val sendButton = v.supplementSendButton ?: return

        inputArea.visibility = View.VISIBLE
        editText.text.clear()

        // Make overlay focusable to allow keyboard input
        setOverlayFocusable(true)

        // Request focus and show keyboard
        editText.requestFocus()
        handler.postDelayed({
            val imm = service.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }, 200)

        sendButton.setOnClickListener {
            val text = editText.text.toString().trim()
            if (text.isNotEmpty()) {
                onSupplement?.invoke(text)
                hideSupplementInputArea(v)
            }
        }
    }

    private fun hideSupplementInputArea(v: CapsuleViews) {
        val inputArea = v.supplementInputArea ?: return
        val editText = v.supplementEditText ?: return

        // Hide keyboard
        val imm = service.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(editText.windowToken, 0)

        editText.text.clear()
        inputArea.visibility = View.GONE

        // Restore overlay non-focusable
        setOverlayFocusable(false)

        // Restore dot visibility
        v.statusDot.visibility = View.VISIBLE
    }

    private fun setOverlayFocusable(focusable: Boolean) {
        val container = overlayView ?: return
        val params = container.layoutParams as? WindowManager.LayoutParams ?: return
        if (focusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        try {
            windowManager.updateViewLayout(container, params)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update focusable state", e)
        }
    }

    // ── Takeover / Supplement transitions ──

    /**
     * Called when user taps 接管 — immediately show TakeoverPending,
     * then the session confirms with [onTakeoverConfirmed].
     */
    private fun requestTakeover() {
        val lastThought = (mode as? CapsuleMode.Running)?.thought ?: ""
        updateMode(CapsuleMode.TakeoverPending(lastThought))
        onTakeover?.invoke()
    }

    /**
     * Called by ServiceOverlayController when SessionTakeover event arrives.
     * Transitions from TakeoverPending → Takeover.
     */
    fun onTakeoverConfirmed() {
        val lastThought = when (val m = mode) {
            is CapsuleMode.TakeoverPending -> m.lastThought
            is CapsuleMode.Running -> m.thought
            else -> ""
        }
        updateMode(CapsuleMode.Takeover(lastThought))
    }

    /**
     * Called by ServiceOverlayController when SupplementReceived event arrives.
     * Exits SupplementInput mode and shows a brief "已收到" confirmation.
     */
    fun onSupplementConfirmed() {
        val previousMode = (mode as? CapsuleMode.SupplementInput)?.previousMode
        if (previousMode != null) {
            updateMode(previousMode)
        }
        // Brief flash "已收到" on the thought line (cancellable)
        val v = views ?: return
        val originalText = v.thoughtText.text.toString()
        v.thoughtText.text = "✓ 已收到"
        supplementConfirmedRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            views?.thoughtText?.text = originalText
            supplementConfirmedRunnable = null
        }
        supplementConfirmedRunnable = runnable
        handler.postDelayed(runnable, 1500)
    }

    // ── Button logic ──

    private fun handlePrimaryClick() {
        when (mode) {
            is CapsuleMode.Running -> requestTakeover()
            is CapsuleMode.Takeover -> onResume?.invoke()
            else -> {} // Other modes handle primary differently (Stage 3)
        }
    }

    private fun handleStopClick() {
        when (val m = mode) {
            is CapsuleMode.Error -> {
                // In error mode, stop button shows "关闭" (dismiss)
                onDismissError?.invoke() ?: hide()
            }
            is CapsuleMode.SupplementInput -> {
                // Cancel supplement input, return to previous mode
                views?.let { hideSupplementInputArea(it) }
                updateMode(m.previousMode)
            }
            else -> onStop?.invoke()
        }
    }

    // ── Status dot ──

    private fun setDotColor(v: CapsuleViews, color: Int, pulsing: Boolean) {
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

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    private fun clearDelayedHide() {
        delayedHideRunnable?.let { handler.removeCallbacks(it) }
        delayedHideRunnable = null
    }

    // ── Debounce ──

    private fun debounced(action: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastButtonClickTime < DEBOUNCE_MS) return
        lastButtonClickTime = now
        action()
    }

    // ── Legacy compatibility (used by ServiceOverlayController) ──

    fun onTaskStarted(taskId: String, userInput: String) {
        updateMode(CapsuleMode.Running("${userInput.take(30)}..."))
    }

    fun onMessageDelta(turnId: String, delta: String) {
        val current = mode
        if (current is CapsuleMode.Running && current.thought == "思考中...") {
            val text = delta.replace("\n", " ").trim().take(40)
            if (text.isNotEmpty()) {
                updateMode(CapsuleMode.Running(text))
            }
        }
    }

    fun onActionExecuted(toolName: String, success: Boolean) {
        // Thought stays from ThoughtUpdate; no-op
    }

    fun onTaskCompleted() {
        updateMode(CapsuleMode.Done("已完成"))
    }

    fun onError(message: String) {
        updateMode(CapsuleMode.Error(message.take(40)))
    }

    fun updateStatus(status: String) {
        val current = mode
        if (current is CapsuleMode.Running && current.thought == "思考中...") {
            val clean = status.replace(Regex("[🚀👀🧠💡✅⏸️❌⚠️✓]"), "").trim()
            if (clean.isNotEmpty()) {
                updateMode(CapsuleMode.Running(clean.take(40)))
            }
        }
    }
}
