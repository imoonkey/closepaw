package com.moonkey.androidagent.ui.overlay.visualizer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * ScrollIndicatorView - Directional arrow indicator for scroll visualization.
 * 
 * Visual Design:
 * - Shows a vertical line with arrow indicating scroll direction
 * - Start dot at scroll origin
 * - Arrow head pointing in scroll direction
 * - Color: Indigo (#6366F1) at 50% opacity
 * 
 * ```
 * Scroll Down          Scroll Up
 *     ●                    ○
 *     │                    │
 *     │                    │
 *     ▼                    ▲
 *     ○                    ●
 * ```
 */
class ScrollIndicatorView(context: Context) : View(context) {
    
    companion object {
        private const val SCROLL_COLOR = 0xFF6366F1.toInt()  // Indigo
        private const val LINE_WIDTH_DP = 3f
        private const val DOT_RADIUS_DP = 6f
        private const val ARROW_SIZE_DP = 12f
        private const val LINE_ALPHA = 0.5f
        private const val DOT_ALPHA = 0.6f
        private const val ANIMATION_DURATION_MS = 400L
    }
    
    enum class Direction {
        UP, DOWN, LEFT, RIGHT
    }
    
    private var centerX = 0f
    private var centerY = 0f
    private var direction = Direction.DOWN
    private var lineLength = 0f
    
    private var progress = 0f
    
    private val lineWidth = dp(LINE_WIDTH_DP)
    private val dotRadius = dp(DOT_RADIUS_DP)
    private val arrowSize = dp(ARROW_SIZE_DP)
    
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SCROLL_COLOR
        style = Paint.Style.STROKE
        strokeWidth = lineWidth
        strokeCap = Paint.Cap.ROUND
        alpha = (255 * LINE_ALPHA).toInt()
    }
    
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SCROLL_COLOR
        style = Paint.Style.FILL
        alpha = (255 * DOT_ALPHA).toInt()
    }
    
    private val arrowPath = Path()
    
    private var animator: ValueAnimator? = null
    
    /**
     * Set the scroll indicator position and direction.
     * 
     * @param x Center X coordinate
     * @param y Center Y coordinate
     * @param dir Scroll direction
     * @param length Length of the indicator line
     */
    fun setScroll(x: Float, y: Float, dir: Direction, length: Float = dp(80f)) {
        centerX = x
        centerY = y
        direction = dir
        lineLength = length
        progress = 0f
        
        startAnimation()
    }
    
    private fun startAnimation() {
        animator?.cancel()
        
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_DURATION_MS
            interpolator = DecelerateInterpolator()
            
            addUpdateListener { animation ->
                progress = animation.animatedValue as Float
                invalidate()
            }
            
            start()
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val currentLength = lineLength * progress
        
        // Calculate start and end points based on direction
        val (startX, startY, endX, endY) = when (direction) {
            Direction.DOWN -> {
                listOf(centerX, centerY - currentLength / 2, centerX, centerY + currentLength / 2)
            }
            Direction.UP -> {
                listOf(centerX, centerY + currentLength / 2, centerX, centerY - currentLength / 2)
            }
            Direction.LEFT -> {
                listOf(centerX + currentLength / 2, centerY, centerX - currentLength / 2, centerY)
            }
            Direction.RIGHT -> {
                listOf(centerX - currentLength / 2, centerY, centerX + currentLength / 2, centerY)
            }
        }
        
        // Draw start dot
        canvas.drawCircle(startX, startY, dotRadius, dotPaint)
        
        // Draw line
        if (progress > 0.1f) {
            canvas.drawLine(startX, startY, endX, endY, linePaint)
        }
        
        // Draw arrow at end position
        if (progress > 0.3f) {
            drawArrow(canvas, endX, endY)
        }
    }
    
    private fun drawArrow(canvas: Canvas, x: Float, y: Float) {
        arrowPath.reset()
        
        val size = arrowSize * progress
        
        when (direction) {
            Direction.DOWN -> {
                arrowPath.moveTo(x, y + size / 2)
                arrowPath.lineTo(x - size / 2, y - size / 2)
                arrowPath.lineTo(x + size / 2, y - size / 2)
            }
            Direction.UP -> {
                arrowPath.moveTo(x, y - size / 2)
                arrowPath.lineTo(x - size / 2, y + size / 2)
                arrowPath.lineTo(x + size / 2, y + size / 2)
            }
            Direction.LEFT -> {
                arrowPath.moveTo(x - size / 2, y)
                arrowPath.lineTo(x + size / 2, y - size / 2)
                arrowPath.lineTo(x + size / 2, y + size / 2)
            }
            Direction.RIGHT -> {
                arrowPath.moveTo(x + size / 2, y)
                arrowPath.lineTo(x - size / 2, y - size / 2)
                arrowPath.lineTo(x - size / 2, y + size / 2)
            }
        }
        arrowPath.close()
        
        canvas.drawPath(arrowPath, dotPaint)
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
