package com.moonkey.androidagent.ui.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.moonkey.androidagent.ui.overlay.model.CapsuleMode
import com.moonkey.androidagent.ui.overlay.model.isExpanded

/**
 * SmartCapsuleManager — pure View renderer for the Smart Capsule overlay.
 *
 * Does NOT compute state. State is managed by [CapsuleStateHolder].
 * Call [renderMode] to push a new mode for display. The manager handles
 * show/hide, rendering, animations, keyboard, and button callbacks.
 *
 * Visual rendering is delegated to [SmartCapsuleRenderer].
 * Window-level animations are delegated to [SmartCapsuleAnimator].
 */
class SmartCapsuleManager(
    private val service: AccessibilityService
) {
    companion object {
        private const val TAG = "SmartCapsuleManager"
        private const val DEBOUNCE_MS = 300L
        private const val NUDGE_DELAY_MS = 4 * 60 * 1000L // 4 minutes
    }

    // ── Callbacks (set by ServiceOverlayController) ──

    var onTakeover: (() -> Unit)? = null
    var onResume: (() -> Unit)? = null
    var onSupplement: ((String) -> Unit)? = null
    var onUserResponse: ((String, String) -> Unit)? = null // (callId, response)
    var onStop: (() -> Unit)? = null
    var onOpenApp: (() -> Unit)? = null
    var onDismissError: (() -> Unit)? = null
    var onDoneAutoHide: (() -> Unit)? = null

    // ── Rendering state (cache, not source of truth) ──
    // INVARIANT: these always match the last renderMode() call.
    // Read by handlePrimaryClick/handleStopClick to determine current button behavior.

    private var mode: CapsuleMode = CapsuleMode.Hidden
    private var previousMode: CapsuleMode = CapsuleMode.Hidden
    private var views: CapsuleViews? = null
    private var overlayView: ViewGroup? = null

    // ── Timers & runnables ──

    private var delayedHideRunnable: Runnable? = null
    private var supplementConfirmedRunnable: Runnable? = null
    private var keyboardShowRunnable: Runnable? = null
    private var nudgeRunnable: Runnable? = null
    private var lastButtonClickTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val layoutBuilder = SmartCapsuleLayoutBuilder(service)
    private val renderer = SmartCapsuleRenderer()
    private val animator = SmartCapsuleAnimator(
        windowManager, service.resources.displayMetrics.density
    )

    // ── Public API ──

    fun isShowing(): Boolean = overlayView != null

    /**
     * Render the capsule for a new mode. This is the single entry point for visual updates.
     * State transitions happen in [CapsuleStateHolder]; this method only displays.
     */
    fun renderMode(newMode: CapsuleMode, prevMode: CapsuleMode) {
        previousMode = prevMode
        mode = newMode
        Log.d(TAG, "Render: ${prevMode::class.simpleName} → ${newMode::class.simpleName}")

        // Cancel pending delayed actions and exit animation
        cancelAllRunnables()
        animator.cancelAll()

        // If leaving an input mode, hide keyboard
        if (prevMode is CapsuleMode.WaitingForInput) {
            hideKeyboard()
        }

        when (newMode) {
            is CapsuleMode.Hidden -> hide()
            else -> {
                if (overlayView == null) show()
                renderAndSetup(newMode)
            }
        }
    }

    fun show() {
        if (overlayView != null) return
        try {
            val params = layoutBuilder.createLayoutParams()
            val capsuleViews = layoutBuilder.build(
                onSupplement = { /* Supplement via Row 3 in Stage 7+ */ },
                onPrimary = { debounced { handlePrimaryClick() } },
                onStop = { debounced { handleStopClick() } },
                onRow1Tap = { debounced { onOpenApp?.invoke() } },
            )
            windowManager.addView(capsuleViews.container, params)
            overlayView = capsuleViews.container
            // Reset transform state from any previous exit animation
            capsuleViews.container.translationY = 0f
            capsuleViews.container.alpha = 1f
            views = capsuleViews
            Log.i(TAG, "Capsule shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show capsule", e)
        }
    }

    fun hide() {
        animator.cancelAll()
        renderer.stopPulse()
        renderer.cancelAnimations()
        cancelAllRunnables()
        hideKeyboard()
        overlayView?.let {
            it.translationY = 0f
            it.alpha = 1f
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

    /**
     * Flash a supplement confirmation on the thought line.
     * Called by ServiceOverlayController when SupplementReceived event arrives.
     */
    fun flashSupplementConfirmation(isAgentMidTurn: Boolean) {
        val v = views ?: return
        val originalText = v.thoughtText.text.toString()
        val confirmText = if (isAgentMidTurn) "✓ 已收到，下一步生效" else "✓ 已收到"
        val confirmDuration = if (isAgentMidTurn) 2000L else 1500L
        v.thoughtText.text = confirmText
        supplementConfirmedRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            views?.thoughtText?.text = originalText
            supplementConfirmedRunnable = null
        }
        supplementConfirmedRunnable = runnable
        handler.postDelayed(runnable, confirmDuration)
    }

    // ── Rendering ──

    private fun renderAndSetup(mode: CapsuleMode) {
        val v = views ?: return
        val container = overlayView ?: return
        val prev = previousMode

        container.post {
            if (overlayView == null) return@post

            val currentHeight = container.height
            val needsHeightAnim = currentHeight > 0 && isHeightTransition(prev, mode)

            if (needsHeightAnim) {
                animator.lockHeight(container)
                renderer.render(v, mode, prev)
                animator.animateToMeasuredHeight(container, currentHeight)
            } else {
                renderer.render(v, mode, prev)
            }

            setupInteractivity(v, mode)
        }
    }

    /**
     * Set up interactive parts after renderer has configured visuals.
     * Click listeners, keyboard, focus state.
     */
    private fun setupInteractivity(v: CapsuleViews, mode: CapsuleMode) {
        when (mode) {
            is CapsuleMode.WaitingForInput -> {
                setOverlayFocusable(true)
                setupAnswerInput(v, mode.callId)
                startNudgeTimer(v)
            }
            is CapsuleMode.WaitingForAction -> {
                setOverlayFocusable(false)
                startNudgeTimer(v)
            }
            is CapsuleMode.Done -> {
                scheduleAutoHide()
            }
            else -> {}
        }
    }

    // ── Input setup ──

    private fun setupInputWithSend(v: CapsuleViews, onSend: (String) -> Unit) {
        val editText = v.supplementEditText ?: return
        val sendButton = v.supplementSendButton ?: return

        editText.requestFocus()
        scheduleKeyboardShow(editText)

        sendButton.setOnClickListener {
            val text = editText.text.toString().trim()
            if (text.isNotEmpty()) onSend(text)
        }
    }

    private fun setupAnswerInput(v: CapsuleViews, callId: String) {
        setupInputWithSend(v) { text ->
            onUserResponse?.invoke(callId, text)
            // State transition handled by CapsuleStateHolder via onUserResponseSent
        }
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

    private fun scheduleKeyboardShow(editText: EditText) {
        keyboardShowRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            val imm = service.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }
        keyboardShowRunnable = runnable
        handler.postDelayed(runnable, 200)
    }

    private fun hideKeyboard() {
        val editText = views?.supplementEditText ?: return
        val imm = service.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(editText.windowToken, 0)
    }

    // ── Button logic ──

    private fun handlePrimaryClick() {
        when (val m = mode) {
            is CapsuleMode.Running -> onTakeover?.invoke()
            is CapsuleMode.Takeover -> onResume?.invoke()
            is CapsuleMode.WaitingForAction -> {
                onUserResponse?.invoke(m.callId, "done")
            }
            else -> {}
        }
    }

    private fun handleStopClick() {
        when (mode) {
            is CapsuleMode.Error -> onDismissError?.invoke() ?: hide()
            else -> onStop?.invoke()
        }
    }

    // ── Timers ──

    private fun scheduleAutoHide() {
        delayedHideRunnable = Runnable {
            val container = overlayView ?: run { hide(); return@Runnable }
            animator.animateExit(container) { onDoneAutoHide?.invoke() ?: hide() }
        }.also { handler.postDelayed(it, 3000) }
    }

    private fun startNudgeTimer(v: CapsuleViews) {
        cancelNudgeTimer()
        nudgeRunnable = Runnable {
            val body = v.expandedBody ?: return@Runnable
            if (body.windowToken == null) return@Runnable // view detached
            val currentText = body.text?.toString() ?: ""
            body.text = "$currentText\n还在等待您的回复..."
        }.also { handler.postDelayed(it, NUDGE_DELAY_MS) }
    }

    private fun cancelNudgeTimer() {
        nudgeRunnable?.let { handler.removeCallbacks(it) }
        nudgeRunnable = null
    }

    private fun cancelAllRunnables() {
        delayedHideRunnable?.let { handler.removeCallbacks(it) }
        delayedHideRunnable = null
        supplementConfirmedRunnable?.let { handler.removeCallbacks(it) }
        supplementConfirmedRunnable = null
        keyboardShowRunnable?.let { handler.removeCallbacks(it) }
        keyboardShowRunnable = null
        cancelNudgeTimer()
    }

    // ── Animation helpers ──

    private fun isHeightTransition(from: CapsuleMode, to: CapsuleMode): Boolean =
        from.isExpanded() != to.isExpanded()

    // ── Debounce ──

    private fun debounced(action: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastButtonClickTime < DEBOUNCE_MS) return
        lastButtonClickTime = now
        action()
    }
}
