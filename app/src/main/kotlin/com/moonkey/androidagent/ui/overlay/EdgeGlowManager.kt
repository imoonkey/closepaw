package com.moonkey.androidagent.ui.overlay

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import com.moonkey.androidagent.ui.overlay.model.GlowState

/**
 * EdgeGlowManager - Manages the edge glow overlay effect.
 * 
 * Shows a glowing border around the screen edges to indicate the agent
 * is actively controlling the device. Provides ambient visual feedback
 * that's visible but non-intrusive.
 * 
 * ## Features
 * - Full-screen edge glow with gradient fade
 * - State-based colors (Active, Executing, Success, Error, Paused)
 * - Pulse animation when active
 * - Touch pass-through (doesn't block interaction)
 * - Proper display cutout handling
 * 
 * ## Usage
 * ```kotlin
 * val edgeGlow = EdgeGlowManager(accessibilityService)
 * edgeGlow.show(GlowState.Active)  // Show with pulse
 * edgeGlow.updateState(GlowState.Executing)  // Change color
 * edgeGlow.hide()  // Remove
 * ```
 * 
 * ## Z-Order
 * Should be added BEFORE SmartCapsule so it renders below it.
 */
class EdgeGlowManager(
    private val context: AccessibilityService
) {
    companion object {
        private const val TAG = "EdgeGlowManager"
        
        /** Pulse animation duration in ms */
        private const val PULSE_DURATION_MS = 800L
        
        /** Entry fade-in duration in ms */
        private const val FADE_IN_DURATION_MS = 300L
        
        /** Exit fade-out duration in ms */
        private const val FADE_OUT_DURATION_MS = 500L
        
        /** Delay before auto-hide after success (ms) */
        private const val SUCCESS_HIDE_DELAY_MS = 2000L
        
        /** Pulse alpha range - more visible */
        private const val PULSE_ALPHA_MIN = 0.5f
        private const val PULSE_ALPHA_MAX = 0.85f
        
        /** Base alpha when glow is fully visible (not pulsing) */
        private const val BASE_ALPHA = 0.7f
    }
    
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    
    private var glowView: EdgeGlowView? = null
    private var currentState: GlowState = GlowState.Active
    
    // Animators
    private var pulseAnimator: ValueAnimator? = null
    private var fadeAnimator: ValueAnimator? = null
    
    // Animation state tracking (prevents race conditions)
    private var isHiding = false
    
    // Pending hide runnable (for delayed hide after success)
    private var pendingHideRunnable: Runnable? = null
    
    /**
     * Check if the glow overlay is currently visible.
     */
    fun isShowing(): Boolean = glowView != null
    
    /**
     * Show the edge glow with the specified state.
     * If already showing, updates the state instead.
     * 
     * @param state The glow state (determines color)
     */
    fun show(state: GlowState = GlowState.Active) {
        Log.d(TAG, "show() called, state=$state, currently showing=${isShowing()}, isHiding=$isHiding")
        
        // Cancel any pending hide
        cancelPendingHide()
        
        // If currently hiding, cancel the animation and remove immediately
        // This prevents race condition where show() is called during fade-out
        if (isHiding) {
            stopFadeAnimation()
            glowView?.let { view ->
                try { windowManager.removeView(view) } catch (_: Exception) {}
            }
            glowView = null
            isHiding = false
        }
        
        if (glowView != null) {
            updateState(state)
            return
        }
        
        try {
            currentState = state
            
            val params = createLayoutParams()
            
            glowView = EdgeGlowView(context).apply {
                setState(state)
                setGlowAlpha(0f) // Start invisible for fade-in
            }
            
            windowManager.addView(glowView, params)
            Log.i(TAG, "Glow view added successfully")
            
            // Fade in
            animateFadeIn {
                // Start pulse if state warrants it
                if (state == GlowState.Active || state == GlowState.Executing) {
                    startPulseAnimation()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add glow view", e)
        }
    }
    
    /**
     * Hide the edge glow with fade-out animation.
     */
    fun hide() {
        Log.d(TAG, "hide() called, isHiding=$isHiding")
        
        // Prevent double-hide
        if (isHiding) return
        
        cancelPendingHide()
        stopPulseAnimation()
        
        val view = glowView ?: return
        
        isHiding = true
        animateFadeOut {
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing glow view", e)
            }
            glowView = null
            isHiding = false
        }
    }
    
    /**
     * Hide immediately without animation.
     */
    fun hideImmediately() {
        Log.d(TAG, "hideImmediately() called")
        
        cancelPendingHide()
        stopPulseAnimation()
        stopFadeAnimation()
        
        glowView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing glow view", e)
            }
        }
        glowView = null
        isHiding = false
    }
    
    /**
     * Update the glow state (changes color).
     * Does nothing if glow is not currently visible.
     * 
     * @param state The new glow state
     */
    fun updateState(state: GlowState) {
        Log.d(TAG, "updateState: $currentState -> $state, showing=${isShowing()}")
        
        if (!isShowing()) {
            Log.w(TAG, "updateState called while not showing, ignoring")
            return
        }
        
        if (currentState == state) return
        currentState = state
        
        cancelPendingHide()
        
        glowView?.setState(state)
        
        // Manage pulse animation based on state
        when (state) {
            GlowState.Active, GlowState.Executing -> {
                startPulseAnimation()
            }
            GlowState.Success -> {
                stopPulseAnimation()
                scheduleHideAfterDelay(SUCCESS_HIDE_DELAY_MS)
            }
            GlowState.Error, GlowState.Paused -> {
                stopPulseAnimation()
                // Keep visible until explicitly hidden
            }
        }
    }
    
    /**
     * Clean up resources.
     * Call when the service is destroyed.
     */
    fun dispose() {
        Log.d(TAG, "dispose()")
        hideImmediately()
        handler.removeCallbacksAndMessages(null)
    }
    
    // ===== Private Helpers =====
    
    private fun createLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // Critical flags:
            // - NOT_FOCUSABLE: Don't steal focus
            // - NOT_TOUCHABLE: Touch passes through to apps below
            // - LAYOUT_IN_SCREEN: Cover entire screen including status bar
            // - LAYOUT_NO_LIMITS: Extend beyond screen bounds for edge-to-edge
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Handle display cutouts (notch)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
    
    private fun startPulseAnimation() {
        if (pulseAnimator?.isRunning == true) return
        
        pulseAnimator = ValueAnimator.ofFloat(PULSE_ALPHA_MIN, PULSE_ALPHA_MAX).apply {
            duration = PULSE_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                glowView?.setGlowAlpha(animator.animatedValue as Float)
            }
            start()
        }
    }
    
    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        // Reset to base alpha (visible)
        glowView?.setGlowAlpha(BASE_ALPHA)
    }
    
    private fun animateFadeIn(onComplete: () -> Unit = {}) {
        stopFadeAnimation()
        
        val viewRef = glowView ?: return onComplete()
        
        fadeAnimator = ValueAnimator.ofFloat(0f, BASE_ALPHA).apply {
            duration = FADE_IN_DURATION_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                glowView?.setGlowAlpha(animator.animatedValue as Float)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    animation.removeAllListeners()
                    if (glowView === viewRef) {
                        onComplete()
                    }
                }
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    animation.removeAllListeners()
                }
            })
            start()
        }
    }
    
    private fun animateFadeOut(onComplete: () -> Unit) {
        stopFadeAnimation()
        
        val viewRef = glowView ?: return onComplete()
        // Use actual current alpha to avoid visual jump if mid-pulse
        val startAlpha = viewRef.getCurrentAlpha()
        
        fadeAnimator = ValueAnimator.ofFloat(startAlpha, 0f).apply {
            duration = FADE_OUT_DURATION_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                glowView?.setGlowAlpha(animator.animatedValue as Float)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    animation.removeAllListeners()
                    if (glowView === viewRef) {
                        onComplete()
                    }
                }
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    animation.removeAllListeners()
                }
            })
            start()
        }
    }
    
    private fun stopFadeAnimation() {
        fadeAnimator?.cancel()
        fadeAnimator = null
    }
    
    private fun scheduleHideAfterDelay(delayMs: Long) {
        cancelPendingHide()
        pendingHideRunnable = Runnable { hide() }
        handler.postDelayed(pendingHideRunnable!!, delayMs)
    }
    
    private fun cancelPendingHide() {
        pendingHideRunnable?.let { handler.removeCallbacks(it) }
        pendingHideRunnable = null
    }
}
