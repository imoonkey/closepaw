package com.moonkey.androidagent.ui.overlay

import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.TextView
import com.moonkey.androidagent.app.MainActivity
import com.moonkey.androidagent.util.StatusUtils

/**
 * SmartCapsuleManager - Enhanced floating control bar with streaming support.
 * 
 * Features:
 * - Streaming text display from MessageDelta events
 * - Pulsing status dot animation
 * - "Open App" button to return to main activity
 * - Task status updates (started, executing, completed)
 * 
 * Positioned at bottom of screen with modern capsule design.
 */
class SmartCapsuleManager(
    private val context: AccessibilityService,
    private val onStop: () -> Unit,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val onOpenApp: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "SmartCapsuleManager"
    }
    
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    
    private var overlayView: ViewGroup? = null
    private var statusText: TextView? = null
    private var statusDot: View? = null
    private var pauseButton: View? = null
    private var pauseIconText: TextView? = null
    
    private var isPaused = false
    
    // Streaming state
    private val streamingText = StringBuilder()
    private var currentTurnId: String? = null
    private var pulseAnimator: ObjectAnimator? = null
    
    // Colors matching the new chat theme
    private val colorBackground = 0xFFFFFFFF.toInt()
    private val colorBorder = 0xFFE5E5E5.toInt()
    private val colorPrimary = 0xFF2563EB.toInt()     // Blue - working
    private val colorSuccess = 0xFF0D9488.toInt()     // Teal - success
    private val colorError = 0xFFDC2626.toInt()       // Red - error
    private val colorWarning = 0xFFF59E0B.toInt()     // Amber - paused
    private val colorText = 0xFF171717.toInt()
    private val colorTextMuted = 0xFF525252.toInt()

    /**
     * Check if the capsule overlay is currently visible.
     */
    fun isShowing(): Boolean = overlayView != null
    
    fun show() {
        Log.d(TAG, "show() called, overlayView=${overlayView != null}")
        if (overlayView != null) {
            Log.d(TAG, "show() - already showing, returning early")
            return
        }

        try {
            val builder = SmartCapsuleLayoutBuilder(
                context = context,
                colors = CapsuleColors(
                    background = colorBackground,
                    border = colorBorder,
                    primary = colorPrimary,
                    error = colorError,
                    text = colorText
                )
            )
            val views = builder.build(
                onPauseToggle = {
                    if (isPaused) {
                        onResume()
                    } else {
                        onPause()
                    }
                },
                onStop = onStop,
                onOpenApp = { openAgentApp() }
            )
            windowManager.addView(views.container, builder.createLayoutParams())
            overlayView = views.container
            statusText = views.statusText.apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            statusDot = views.statusDot
            pauseButton = views.pauseButton
            pauseIconText = views.pauseIconText
            Log.i(TAG, "show() - overlay view added successfully")
            
            // Reset state
            isPaused = false
            streamingText.clear()
            currentTurnId = null
        } catch (e: Exception) {
            Log.e(TAG, "show() - failed to add overlay view", e)
        }
    }
    
    /**
     * Open the main Agent app activity.
     */
    private fun openAgentApp() {
        onOpenApp?.invoke() ?: run {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(intent)
        }
    }

    fun hide() {
        stopPulsingAnimation()
        if (overlayView != null) {
            windowManager.removeView(overlayView)
            overlayView = null
            statusText = null
            statusDot = null
            pauseButton = null
            pauseIconText = null
        }
        streamingText.clear()
        currentTurnId = null
    }
    
    // ===== Streaming Support =====
    
    /**
     * Called when a new task starts.
     */
    @Suppress("UNUSED_PARAMETER") // taskId reserved for future tracking
    fun onTaskStarted(taskId: String, userInput: String) {
        Log.d(TAG, "onTaskStarted: taskId=$taskId, input=${userInput.take(30)}")
        streamingText.clear()
        currentTurnId = null
        show()
        setStatusDot(colorPrimary, pulsing = true)
        setStatusText("Working on: ${userInput.take(30)}...")
    }
    
    /**
     * Called when streaming text delta is received.
     */
    fun onMessageDelta(turnId: String, delta: String) {
        if (turnId != currentTurnId) {
            streamingText.clear()
            currentTurnId = turnId
        }
        streamingText.append(delta)
        updateStatusText(streamingText.toString())
    }
    
    /**
     * Called when an action is executed.
     */
    fun onActionExecuted(toolName: String, success: Boolean) {
        setStatusDot(if (success) colorSuccess else colorError, pulsing = false)
        setStatusText("$toolName ${if (success) "✓" else "✗"}")
    }
    
    /**
     * Called when task completes.
     */
    fun onTaskCompleted() {
        setStatusDot(colorSuccess, pulsing = false)
        setStatusText("✓ Done")
        handler.postDelayed({ hide() }, 3000)
    }
    
    /**
     * Called on error.
     */
    fun onError(message: String) {
        setStatusDot(colorError, pulsing = false)
        setStatusText("⚠ $message")
    }
    
    // ===== Internal Helpers =====
    
    private fun updateStatusText(text: String) {
        val displayText = text.take(50).replace("\n", " ")
        setStatusText(displayText.ifEmpty { "Thinking..." })
    }
    
    private fun setStatusText(text: String) {
        statusText?.post {
            if (overlayView == null) return@post
            statusText?.text = text
        }
    }
    
    private fun setStatusDot(color: Int, pulsing: Boolean) {
        statusDot?.post {
            if (overlayView == null) return@post
            (statusDot?.background as? GradientDrawable)?.setColor(color)
            
            if (pulsing) {
                startPulsingAnimation()
            } else {
                stopPulsingAnimation()
            }
        }
    }
    
    /**
     * Update the status text displayed in the overlay.
     * (Legacy method for compatibility with existing code)
     */
    fun updateStatus(status: String) {
        val currentStatusText = statusText ?: return
        currentStatusText.post { 
            if (overlayView == null) return@post
            
            // Clean up emoji for cleaner display
            val cleanStatus = StatusUtils.cleanStatusText(status)
            val displayText = if (cleanStatus.length > 40) {
                cleanStatus.take(37) + "..."
            } else {
                cleanStatus
            }
            statusText?.text = displayText.ifEmpty { "Ready" }
            
            // Update status dot color based on status type
            val dotColor = when (StatusUtils.getStatusType(status)) {
                StatusUtils.StatusType.SUCCESS -> colorSuccess
                StatusUtils.StatusType.ERROR -> colorError
                StatusUtils.StatusType.WARNING -> colorWarning
                StatusUtils.StatusType.THINKING -> colorPrimary
                else -> colorPrimary
            }
            (statusDot?.background as? GradientDrawable)?.setColor(dotColor)
        }
    }
    
    /**
     * Update the pause/resume button state.
     */
    fun updatePauseState(paused: Boolean) {
        isPaused = paused
        pauseButton?.post {
            if (overlayView == null) return@post
            pauseIconText?.text = if (paused) "▶" else "⏸"
            
            if (paused) {
                setStatusDot(colorWarning, pulsing = false)
            }
        }
    }
    
    // ===== Animations =====
    
    private fun startPulsingAnimation() {
        stopPulsingAnimation()
        statusDot?.let { dot ->
            pulseAnimator = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.4f, 1f).apply {
                duration = 1000
                repeatCount = ObjectAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
    }
    
    private fun stopPulsingAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        statusDot?.alpha = 1f
    }
    
}
