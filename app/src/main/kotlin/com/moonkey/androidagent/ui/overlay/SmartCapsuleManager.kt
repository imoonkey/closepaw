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
import com.moonkey.androidagent.protocol.PlatformMode
import com.moonkey.androidagent.ui.overlay.model.CapsuleContext
import com.moonkey.androidagent.ui.overlay.model.CapsuleMode
import com.moonkey.androidagent.ui.overlay.model.isExpanded

/**
 * SmartCapsuleManager — pure View renderer for the Smart Capsule overlay.
 *
 * Does NOT compute state. State is managed by [CapsuleStateHolder].
 * Call [renderMode] to push a new mode for display. The manager handles
 * show/hide, rendering, animations, keyboard, and button callbacks.
 *
 * Three-row layout:
 *   Row 1: thought line
 *   Row 2: controls + nav
 *   Row 3: input + action button
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
    // Navigation callbacks
    var onMinimize: (() -> Unit)? = null
    var onOpenViewer: (() -> Unit)? = null
    // Send new task (when no task active, Row 3 acts as InputDock).
    // Not wired in Stage 7 — overlay hides on Hidden mode. Wired in Stage 8+ for
    // Compose main-app capsule or future Row-3-only idle overlay (e.g. VD viewer with no task).
    var onSend: ((String) -> Unit)? = null

    // ── Rendering state (cache, not source of truth) ──
    // INVARIANT: these always match the last renderMode() call.
    // Read by handlePrimaryClick/handleStopClick/handleRow3Submit to determine behavior.

    private var mode: CapsuleMode = CapsuleMode.Hidden
    private var previousMode: CapsuleMode = CapsuleMode.Hidden
    private var views: CapsuleViews? = null
    private var overlayView: ViewGroup? = null

    // ── Context for nav button rendering ──
    private var capsuleContext: CapsuleContext = CapsuleContext.SCREEN_VIEWING
    private var platformMode: PlatformMode = PlatformMode.ACCESSIBILITY
    private var hasIsland: Boolean = true

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

    /** Update context and platform mode for nav button rendering. */
    fun updateNavContext(context: CapsuleContext, platform: PlatformMode, hasIsland: Boolean = true) {
        capsuleContext = context
        platformMode = platform
        this.hasIsland = hasIsland
        // Re-render nav buttons if currently showing
        val v = views ?: return
        renderer.configureNavButtons(v, capsuleContext, platformMode, hasIsland)
    }

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
                onPrimary = { debounced { handlePrimaryClick() } },
                onStop = { debounced { handleStopClick() } },
                onRow1Tap = { debounced { onOpenApp?.invoke() } },
                onRow3Submit = { debounced { handleRow3Submit() } },
                onMinimize = { debounced { onMinimize?.invoke() } },
                onNavApp = { debounced { onOpenApp?.invoke() } },
                onNavWatch = { debounced { onOpenViewer?.invoke() } },
                onInputFocused = { handleInputFocused() },
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
                renderer.configureNavButtons(v, capsuleContext, platformMode, hasIsland)
                animator.animateToMeasuredHeight(container, currentHeight)
            } else {
                renderer.render(v, mode, prev)
                renderer.configureNavButtons(v, capsuleContext, platformMode, hasIsland)
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
                focusInputAndShowKeyboard(v.inputEditText)
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

    // ── Row 3 input handling ──

    private fun handleRow3Submit() {
        val v = views ?: return
        val text = v.inputEditText.text.toString().trim()
        if (text.isEmpty()) return

        when (val m = mode) {
            is CapsuleMode.WaitingForInput -> {
                onUserResponse?.invoke(m.callId, text)
            }
            is CapsuleMode.Hidden -> {
                onSend?.invoke(text)
            }
            else -> {
                // Running, TakeoverPending, Takeover → supplement
                onSupplement?.invoke(text)
            }
        }

        v.inputEditText.text?.clear()
        hideKeyboard()
        setOverlayFocusable(false)
    }

    private fun handleInputFocused() {
        setOverlayFocusable(true)
        val v = views ?: return
        scheduleKeyboardShow(v.inputEditText)
    }

    // ── Input helpers ──

    private fun focusInputAndShowKeyboard(editText: EditText) {
        editText.requestFocus()
        scheduleKeyboardShow(editText)
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
        val editText = views?.inputEditText ?: return
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
