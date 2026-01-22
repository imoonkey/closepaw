package com.moonkey.androidagent.ui.overlay.visualizer

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * ActionVisualizerManager - Orchestrates visual feedback for touch actions.
 * 
 * When the agent performs touch actions (click, swipe, scroll), this manager
 * displays visual feedback so users can see where and how the agent interacts
 * with the screen.
 * 
 * Features:
 * - Ripple effect for taps/clicks
 * - Trail animation for swipes/scrolls
 * - Non-intrusive, passes touch events through
 * - Automatically removes visualizations after animation
 * 
 * Integration Point:
 * This manager is meant to be called from AccessibilityPlatform right before
 * dispatching gestures, providing visual feedback before the action executes.
 * 
 * Usage:
 * ```kotlin
 * // In AccessibilityPlatform
 * class AccessibilityPlatform(
 *     private val service: AccessibilityService,
 *     private val visualizer: ActionVisualizerManager? = null
 * ) {
 *     private suspend fun performTap(x: Float, y: Float): ActionResult {
 *         visualizer?.showClick(x, y)
 *         // ... dispatch gesture
 *     }
 * }
 * ```
 */
class ActionVisualizerManager(
    private val context: AccessibilityService
) {
    companion object {
        private const val TAG = "ActionVisualizerManager"
        
        // Animation durations (should match view animations)
        // Click: 500ms animation + 300ms fade = ~800ms total visible time
        private const val CLICK_ANIMATION_DURATION_MS = 500L
        // Swipe: gesture duration + 400ms extra = gesture + visible tail
        private const val SWIPE_EXTRA_DURATION_MS = 400L
        // Fade out duration for smooth disappearance
        private const val FADE_OUT_DURATION_MS = 300L
        // Padding from screen edges for coordinate clamping
        private const val EDGE_PADDING = 10f
    }
    
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    
    /** Cached screen dimensions for coordinate clamping */
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    
    /**
     * Reusable overlay container - stays added to WindowManager,
     * child views come and go.
     */
    private var overlayContainer: FrameLayout? = null
    
    /** 
     * Flag to prevent operations after dispose() is called.
     * Guards against race conditions between show/dispose calls.
     */
    @Volatile
    private var isDisposed = false
    
    /** Whether visualization is enabled (can be toggled in settings) */
    @Volatile
    var enabled: Boolean = true
    
    /**
     * Show a click/tap ripple effect at the given coordinates.
     * Coordinates are clamped to screen bounds to ensure visibility.
     * 
     * @param x Screen X coordinate
     * @param y Screen Y coordinate
     * @param longPress Whether this is a long press (uses different color)
     */
    fun showClick(x: Float, y: Float, longPress: Boolean = false) {
        if (!enabled || isDisposed) return
        
        handler.post {
            if (isDisposed) return@post
            ensureOverlay()
            
            // Clamp coordinates to screen bounds
            val clampedX = clampX(x)
            val clampedY = clampY(y)
            Log.d(TAG, "showClick at ($x, $y) -> clamped ($clampedX, $clampedY), longPress=$longPress")
            
            val ripple = ClickRippleView(context).apply {
                setPosition(clampedX, clampedY, longPress)
            }
            
            addAndAnimate(ripple, CLICK_ANIMATION_DURATION_MS)
        }
    }
    
    /**
     * Show a swipe trail from start to end coordinates.
     * Coordinates are clamped to screen bounds to ensure visibility.
     * 
     * @param startX Start X coordinate
     * @param startY Start Y coordinate
     * @param endX End X coordinate
     * @param endY End Y coordinate
     * @param durationMs Duration of the gesture (animation will match)
     */
    fun showSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ) {
        if (!enabled || isDisposed) return
        
        handler.post {
            if (isDisposed) return@post
            ensureOverlay()
            
            // Clamp coordinates to screen bounds
            val clampedStartX = clampX(startX)
            val clampedStartY = clampY(startY)
            val clampedEndX = clampX(endX)
            val clampedEndY = clampY(endY)
            Log.d(TAG, "showSwipe from ($startX, $startY) to ($endX, $endY) -> clamped ($clampedStartX, $clampedStartY) to ($clampedEndX, $clampedEndY), duration=$durationMs")
            
            val trail = SwipeTrailView(context).apply {
                setPath(clampedStartX, clampedStartY, clampedEndX, clampedEndY, durationMs, scroll = false)
            }
            
            addAndAnimate(trail, durationMs + SWIPE_EXTRA_DURATION_MS)
        }
    }
    
    /**
     * Show a scroll visualization using swipe trail (alternative to arrow).
     * Coordinates are clamped to screen bounds to ensure visibility.
     * 
     * @param startX Start X coordinate
     * @param startY Start Y coordinate
     * @param endX End X coordinate
     * @param endY End Y coordinate
     * @param durationMs Duration of the gesture
     */
    fun showScrollAsSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ) {
        if (!enabled || isDisposed) return
        
        handler.post {
            if (isDisposed) return@post
            ensureOverlay()
            
            // Clamp coordinates to screen bounds
            val clampedStartX = clampX(startX)
            val clampedStartY = clampY(startY)
            val clampedEndX = clampX(endX)
            val clampedEndY = clampY(endY)
            Log.d(TAG, "showScrollAsSwipe from ($startX, $startY) to ($endX, $endY) -> clamped ($clampedStartX, $clampedStartY) to ($clampedEndX, $clampedEndY)")
            
            val trail = SwipeTrailView(context).apply {
                setPath(clampedStartX, clampedStartY, clampedEndX, clampedEndY, durationMs, scroll = true)
            }
            
            addAndAnimate(trail, durationMs + SWIPE_EXTRA_DURATION_MS)
        }
    }
    
    /**
     * Check if we have permission to draw overlays.
     * Required on Android M+ (API 23+).
     */
    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }
    
    /**
     * Ensure the overlay container is added to WindowManager.
     * Must be called on main thread.
     */
    private fun ensureOverlay() {
        if (isDisposed || overlayContainer != null) return
        
        if (!canDrawOverlays()) {
            Log.w(TAG, "Overlay permission not granted, skipping visualization")
            return
        }
        
        try {
            // Update screen dimensions for coordinate clamping
            updateScreenDimensions()
            
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                // Critical flags:
                // - NOT_FOCUSABLE: Don't steal focus
                // - NOT_TOUCHABLE: Pass all touches through to underlying apps
                // - LAYOUT_IN_SCREEN: Cover full screen including status bar
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            
            overlayContainer = FrameLayout(context).apply {
                // Enable hardware acceleration for smooth animations
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }
            
            windowManager.addView(overlayContainer, params)
            Log.d(TAG, "Overlay container created")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create overlay container", e)
            overlayContainer = null
        }
    }
    
    /**
     * Update cached screen dimensions.
     */
    private fun updateScreenDimensions() {
        val displayMetrics = context.resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        Log.d(TAG, "Screen dimensions: ${screenWidth}x${screenHeight}")
    }
    
    /**
     * Clamp a coordinate to stay within screen bounds with padding.
     */
    private fun clampX(x: Float): Float {
        if (screenWidth <= 0) return x
        return x.coerceIn(EDGE_PADDING, screenWidth - EDGE_PADDING)
    }
    
    private fun clampY(y: Float): Float {
        if (screenHeight <= 0) return y
        return y.coerceIn(EDGE_PADDING, screenHeight - EDGE_PADDING)
    }
    
    /**
     * Add a view to the overlay and schedule its removal after animation.
     * 
     * @param view The visualization view to add
     * @param duration Total duration before removal (animation + fade)
     */
    private fun addAndAnimate(view: View, duration: Long) {
        val container = overlayContainer ?: return
        
        // Add view with full-screen layout params
        container.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        
        // Schedule fade-out and removal
        view.animate()
            .alpha(0f)
            .setDuration(FADE_OUT_DURATION_MS)
            .setStartDelay(duration - FADE_OUT_DURATION_MS)
            .withEndAction {
                // Guard against removal after dispose() - check view is still attached
                if (view.parent != null) {
                    container.removeView(view)
                }
            }
            .start()
    }
    
    /**
     * Remove the overlay container completely.
     * Call this when the service is being destroyed or visualization is no longer needed.
     */
    fun dispose() {
        isDisposed = true  // Set immediately to prevent new operations
        handler.post {
            overlayContainer?.let { container ->
                try {
                    // Remove all children first
                    container.removeAllViews()
                    windowManager.removeView(container)
                    Log.d(TAG, "Overlay container disposed")
                } catch (e: Exception) {
                    Log.w(TAG, "Error disposing overlay container", e)
                }
            }
            overlayContainer = null
        }
    }
    
    /**
     * Clear all active visualizations without disposing the container.
     */
    fun clearAll() {
        handler.post {
            overlayContainer?.removeAllViews()
        }
    }
}
