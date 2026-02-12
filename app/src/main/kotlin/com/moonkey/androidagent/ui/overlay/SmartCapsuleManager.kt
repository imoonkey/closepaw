package com.moonkey.androidagent.ui.overlay

import android.accessibilityservice.AccessibilityService
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import com.moonkey.androidagent.app.MainActivity
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
    var onSupplement: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onOpenApp: (() -> Unit)? = null
    var onDismissError: (() -> Unit)? = null

    // ── State ──

    private var mode: CapsuleMode = CapsuleMode.Hidden
    private var views: CapsuleViews? = null
    private var overlayView: ViewGroup? = null
    private var pulseAnimator: AnimatorSet? = null
    private var delayedHideRunnable: Runnable? = null
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
                onSupplement = { debounced { onSupplement?.invoke() } },
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
                is CapsuleMode.Done -> renderDone(v, mode)
                is CapsuleMode.Error -> renderError(v, mode)
                is CapsuleMode.Hidden -> {} // handled in updateMode
                // Stage 2/3 modes - placeholder rendering falls back to Running
                is CapsuleMode.SupplementInput -> renderRunning(v, CapsuleMode.Running(
                    mode.previousMode.let {
                        when (it) {
                            is CapsuleMode.Running -> it.thought
                            is CapsuleMode.Takeover -> it.lastThought
                            else -> "思考中..."
                        }
                    }
                ))
                is CapsuleMode.WaitingForInput -> renderRunning(v, CapsuleMode.Running(mode.question))
                is CapsuleMode.WaitingForAction -> renderRunning(v, CapsuleMode.Running(mode.instruction))
            }
        }
    }

    private fun renderRunning(v: CapsuleViews, mode: CapsuleMode.Running) {
        // Row 1: blue pulsing dot + thought
        setDotColor(v, colorBlue, pulsing = true)
        v.thoughtText.text = mode.thought.ifEmpty { "思考中..." }
        v.thoughtText.alpha = 1f

        // Row 2: [补充] [接管] [停止], all visible and enabled
        v.row2.visibility = View.VISIBLE
        v.divider.visibility = View.VISIBLE

        v.supplementButton.visibility = View.VISIBLE
        v.supplementButton.isEnabled = true
        v.supplementButton.alpha = 1f

        v.primaryIcon.text = "✋"
        v.primaryText.text = "接管"
        v.primaryButton.isEnabled = true
        v.primaryButton.alpha = 1f

        v.stopButton.visibility = View.VISIBLE
        v.stopButton.isEnabled = true
        v.stopButton.alpha = 1f
    }

    private fun renderTakeoverPending(v: CapsuleViews, mode: CapsuleMode.TakeoverPending) {
        // Row 1: amber static dot + "正在交接..."
        setDotColor(v, colorAmber, pulsing = false)
        v.thoughtText.text = "正在交接..."
        v.thoughtText.alpha = 1f

        // Row 2: supplement disabled, primary disabled, stop enabled
        v.row2.visibility = View.VISIBLE
        v.divider.visibility = View.VISIBLE

        v.supplementButton.visibility = View.VISIBLE
        v.supplementButton.isEnabled = false
        v.supplementButton.alpha = 0.4f

        v.primaryIcon.text = "✋"
        v.primaryText.text = "交接中"
        v.primaryButton.isEnabled = false
        v.primaryButton.alpha = 0.4f

        v.stopButton.visibility = View.VISIBLE
        v.stopButton.isEnabled = true
        v.stopButton.alpha = 1f
    }

    private fun renderTakeover(v: CapsuleViews, mode: CapsuleMode.Takeover) {
        // Row 1: amber static dot + dimmed last thought
        setDotColor(v, colorAmber, pulsing = false)
        v.thoughtText.text = mode.lastThought.ifEmpty { "已暂停" }
        v.thoughtText.alpha = 0.6f

        // Row 2: [补充] [▶ 继续] [停止]
        v.row2.visibility = View.VISIBLE
        v.divider.visibility = View.VISIBLE

        v.supplementButton.visibility = View.VISIBLE
        v.supplementButton.isEnabled = true
        v.supplementButton.alpha = 1f

        v.primaryIcon.text = "▶"
        v.primaryText.text = "继续"
        v.primaryButton.isEnabled = true
        v.primaryButton.alpha = 1f

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
        v.thoughtText.text = "⚠ ${mode.message}"
        v.thoughtText.alpha = 1f

        // Row 2: only dismiss button
        v.row2.visibility = View.VISIBLE
        v.divider.visibility = View.VISIBLE

        v.supplementButton.visibility = View.GONE
        v.primaryButton.visibility = View.GONE

        v.stopText.text = "关闭"
        v.stopButton.visibility = View.VISIBLE
        v.stopButton.isEnabled = true
        v.stopButton.alpha = 1f
    }

    // ── Button logic ──

    private fun handlePrimaryClick() {
        when (mode) {
            is CapsuleMode.Running -> onTakeover?.invoke()
            is CapsuleMode.Takeover -> onResume?.invoke()
            else -> {} // Other modes handle primary differently (Stage 2/3)
        }
    }

    private fun handleStopClick() {
        when (mode) {
            is CapsuleMode.Error -> {
                // In error mode, stop button shows "关闭" (dismiss)
                onDismissError?.invoke() ?: hide()
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

    // ── Legacy compatibility (used by ServiceOverlayController during migration) ──

    /**
     * Legacy method for backward compatibility with ServiceOverlayController.
     * Maps old event-based calls to CapsuleMode updates.
     */
    fun onTaskStarted(taskId: String, userInput: String) {
        updateMode(CapsuleMode.Running("${userInput.take(30)}..."))
    }

    fun onMessageDelta(turnId: String, delta: String) {
        // MessageDelta is now secondary to ThoughtUpdate.
        // Only update if we're in Running and thought is still the default.
        val current = mode
        if (current is CapsuleMode.Running && current.thought == "思考中...") {
            val text = delta.replace("\n", " ").trim().take(40)
            if (text.isNotEmpty()) {
                updateMode(CapsuleMode.Running(text))
            }
        }
    }

    fun onActionExecuted(toolName: String, success: Boolean) {
        // Thought stays from ThoughtUpdate; don't override with tool name
    }

    fun onTaskCompleted() {
        updateMode(CapsuleMode.Done("已完成"))
    }

    fun onError(message: String) {
        updateMode(CapsuleMode.Error(message.take(40)))
    }

    fun updateStatus(status: String) {
        // Legacy: only update if still in default thinking state
        val current = mode
        if (current is CapsuleMode.Running && current.thought == "思考中...") {
            val clean = status.replace(Regex("[🚀👀🧠💡✅⏸️❌⚠️✓]"), "").trim()
            if (clean.isNotEmpty()) {
                updateMode(CapsuleMode.Running(clean.take(40)))
            }
        }
    }

    fun updatePauseState(paused: Boolean) {
        if (paused) {
            val lastThought = (mode as? CapsuleMode.Running)?.thought ?: "已暂停"
            updateMode(CapsuleMode.Takeover(lastThought))
        } else {
            updateMode(CapsuleMode.Running("思考中..."))
        }
    }
}
