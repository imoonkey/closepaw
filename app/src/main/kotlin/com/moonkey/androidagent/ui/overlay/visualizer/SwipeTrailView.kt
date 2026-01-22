package com.moonkey.androidagent.ui.overlay.visualizer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * SwipeTrailView - Line drawing animation for swipe/scroll visualization.
 * 
 * Visual Design:
 * - Line width: 4dp
 * - Start dot: 8dp radius
 * - End dot: 6dp radius
 * - Color: Light Blue (#3B82F6) at 50% opacity
 * - Duration: Match gesture duration + 200ms fade
 * - Animation: Draw line as gesture progresses
 */
class SwipeTrailView(context: Context) : View(context) {
    
    companion object {
        private const val SWIPE_COLOR = 0xFF3B82F6.toInt()  // Light Blue
        private const val SCROLL_COLOR = 0xFF6366F1.toInt()  // Indigo
        private const val LINE_WIDTH_DP = 4f
        private const val START_DOT_RADIUS_DP = 8f
        private const val END_DOT_RADIUS_DP = 6f
        private const val LINE_ALPHA = 0.5f
        private const val DOT_ALPHA = 0.6f
    }
    
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    
    private var progress = 0f
    
    private var isScroll = false
    
    private val lineWidth = dp(LINE_WIDTH_DP)
    private val startDotRadius = dp(START_DOT_RADIUS_DP)
    private val endDotRadius = dp(END_DOT_RADIUS_DP)
    
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SWIPE_COLOR
        style = Paint.Style.STROKE
        strokeWidth = lineWidth
        strokeCap = Paint.Cap.ROUND
        alpha = (255 * LINE_ALPHA).toInt()
    }
    
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SWIPE_COLOR
        style = Paint.Style.FILL
        alpha = (255 * DOT_ALPHA).toInt()
    }
    
    private var animator: ValueAnimator? = null
    
    /**
     * Set the swipe/scroll path and start the animation.
     * 
     * @param sx Start X coordinate
     * @param sy Start Y coordinate
     * @param ex End X coordinate
     * @param ey End Y coordinate
     * @param durationMs Duration of the actual gesture
     * @param scroll Whether this is a scroll action (uses indigo color)
     */
    fun setPath(
        sx: Float,
        sy: Float,
        ex: Float,
        ey: Float,
        durationMs: Long,
        scroll: Boolean = false
    ) {
        startX = sx
        startY = sy
        endX = ex
        endY = ey
        isScroll = scroll
        progress = 0f
        
        // Update paint colors based on action type
        val color = if (scroll) SCROLL_COLOR else SWIPE_COLOR
        linePaint.color = color
        linePaint.alpha = (255 * LINE_ALPHA).toInt()
        dotPaint.color = color
        dotPaint.alpha = (255 * DOT_ALPHA).toInt()
        
        startAnimation(durationMs)
    }
    
    private fun startAnimation(durationMs: Long) {
        animator?.cancel()
        
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            
            addUpdateListener { animation ->
                progress = animation.animatedValue as Float
                invalidate()
            }
            
            start()
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw start dot
        canvas.drawCircle(startX, startY, startDotRadius, dotPaint)
        
        // Calculate current position based on progress
        val currentX = startX + (endX - startX) * progress
        val currentY = startY + (endY - startY) * progress
        
        // Draw line from start to current position
        if (progress > 0.01f) {
            canvas.drawLine(startX, startY, currentX, currentY, linePaint)
        }
        
        // Draw end dot at current position (once progress is meaningful)
        if (progress > 0.1f) {
            canvas.drawCircle(currentX, currentY, endDotRadius, dotPaint)
        }
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
