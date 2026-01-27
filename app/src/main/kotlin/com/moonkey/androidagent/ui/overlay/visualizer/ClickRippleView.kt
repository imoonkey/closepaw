package com.moonkey.androidagent.ui.overlay.visualizer

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * ClickRippleView - Expanding circle animation for tap/click visualization.
 * 
 * Visual Design:
 * - Initial dot: 8dp radius
 * - Final ripple: 48dp radius
 * - Duration: 300ms
 * - Color: Primary Blue (#2563EB) at 60% opacity
 * - Animation: EaseOut (fast start, slow end)
 */
@SuppressLint("Range")
class ClickRippleView(context: Context) : View(context) {
    
    companion object {
        private const val CLICK_COLOR = 0xFF2563EB.toInt()  // Blue
        private const val LONG_PRESS_COLOR = 0xFF7C3AED.toInt()  // Purple
        private const val INITIAL_RADIUS_DP = 8f
        private const val MAX_RADIUS_DP = 48f
        // Increased from 300ms to 500ms for better visibility
        private const val ANIMATION_DURATION_MS = 500L
        private const val INITIAL_ALPHA = 0.6f
    }
    
    private var centerX = 0f
    private var centerY = 0f
    
    private val initialRadius = dp(INITIAL_RADIUS_DP)
    private val maxRadius = dp(MAX_RADIUS_DP)
    private var currentRadius = initialRadius
    
    private var isLongPress = false
    
    /** Whether animation should start when view is attached */
    private var pendingAnimation = false
    
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CLICK_COLOR
        style = Paint.Style.FILL
        alpha = (255 * INITIAL_ALPHA).toInt()
    }
    
    private var animator: ValueAnimator? = null
    
    /**
     * Set the tap position. Animation will start when view is attached.
     * 
     * @param x Screen X coordinate
     * @param y Screen Y coordinate
     * @param longPress Whether this is a long press (uses purple color)
     */
    fun setPosition(x: Float, y: Float, longPress: Boolean = false) {
        centerX = x
        centerY = y
        isLongPress = longPress
        
        // Update paint color based on action type
        paint.color = if (longPress) LONG_PRESS_COLOR else CLICK_COLOR
        paint.alpha = (255 * INITIAL_ALPHA).toInt()
        
        // If already attached, start animation immediately
        // Otherwise, animation will start in onAttachedToWindow()
        if (isAttachedToWindow) {
            startAnimation()
        } else {
            pendingAnimation = true
        }
    }
    
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Start animation when view is attached (ensures view is in hierarchy for drawing)
        if (pendingAnimation) {
            startAnimation()
            pendingAnimation = false
        }
    }
    
    private fun startAnimation() {
        animator?.cancel()
        
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_DURATION_MS
            interpolator = DecelerateInterpolator()
            
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                
                // Radius expands from initial to max
                currentRadius = initialRadius + (maxRadius - initialRadius) * progress
                
                // Alpha fades out as ripple expands
                val alphaProgress = 1f - progress * 0.7f
                paint.alpha = (alphaProgress * 255 * INITIAL_ALPHA).toInt()
                
                invalidate()
            }
            
            start()
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(centerX, centerY, currentRadius, paint)
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }
    
    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics
        )
    }
}
