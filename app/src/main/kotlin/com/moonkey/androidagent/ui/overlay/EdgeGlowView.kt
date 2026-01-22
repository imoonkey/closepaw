package com.moonkey.androidagent.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.TypedValue
import android.view.View
import com.moonkey.androidagent.ui.overlay.model.GlowState

/**
 * EdgeGlowView - Custom view that renders a glowing edge effect around the screen.
 * 
 * Draws gradient overlays on all four edges to create a visible glow effect
 * that indicates the agent is actively controlling the device.
 * 
 * ## Visual Design
 * - 40dp glow width for clear visibility
 * - Gradient fades from glow color (70% opacity) to transparent
 * - Supports different colors based on [GlowState]
 * - Pulse animation controlled externally via [setGlowAlpha]
 * 
 * ## Performance
 * - Uses hardware layer for efficient rendering
 * - Single draw pass for all four edges
 */
class EdgeGlowView(context: Context) : View(context) {
    
    companion object {
        /** Width of the glow in dp - increased for visibility */
        private const val GLOW_WIDTH_DP = 40f
        
        /** Base alpha for the glow (0.0 to 1.0) */
        private const val BASE_ALPHA = 0.7f
    }
    
    // Dimensions in pixels
    private val glowWidth: Float = dp(GLOW_WIDTH_DP)
    
    // Current state
    private var glowColor: Int = GlowState.Active.colorHex
    private var glowAlpha: Float = BASE_ALPHA
    
    // Paint for drawing glow edges - simple gradient, no blur filter
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    
    init {
        // Hardware layer for better performance
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }
    
    /**
     * Set the current glow state, which determines the color.
     */
    fun setState(state: GlowState) {
        if (glowColor != state.colorHex) {
            glowColor = state.colorHex
            invalidate()
        }
    }
    
    /**
     * Set the glow alpha (opacity).
     * Used for pulse animation - typically oscillates between 0.3 and 0.5.
     * 
     * @param alpha Value between 0.0 and 1.0
     */
    fun setGlowAlpha(alpha: Float) {
        val clampedAlpha = alpha.coerceIn(0f, 1f)
        if (glowAlpha != clampedAlpha) {
            glowAlpha = clampedAlpha
            invalidate()
        }
    }
    
    /**
     * Animate color transition to new state.
     * Color changes are instant - for smooth transitions, use animator externally.
     */
    fun animateToState(state: GlowState) {
        // For now, instant change. Could add ValueAnimator for smooth color transitions.
        setState(state)
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val w = width.toFloat()
        val h = height.toFloat()
        
        if (w <= 0 || h <= 0) return
        
        // Calculate the color with current alpha
        val colorWithAlpha = applyAlpha(glowColor, glowAlpha)
        val transparent = Color.TRANSPARENT
        
        // Draw top edge glow
        paint.shader = LinearGradient(
            0f, 0f,
            0f, glowWidth,
            colorWithAlpha, transparent,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, glowWidth, paint)
        
        // Draw bottom edge glow
        paint.shader = LinearGradient(
            0f, h,
            0f, h - glowWidth,
            colorWithAlpha, transparent,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, h - glowWidth, w, h, paint)
        
        // Draw left edge glow
        paint.shader = LinearGradient(
            0f, 0f,
            glowWidth, 0f,
            colorWithAlpha, transparent,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, glowWidth, h, paint)
        
        // Draw right edge glow
        paint.shader = LinearGradient(
            w, 0f,
            w - glowWidth, 0f,
            colorWithAlpha, transparent,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(w - glowWidth, 0f, w, h, paint)
    }
    
    /**
     * Apply alpha to a color.
     * 
     * @param color The base color (0xAARRGGBB format)
     * @param alpha The alpha value (0.0 to 1.0)
     * @return Color with modified alpha
     */
    private fun applyAlpha(color: Int, alpha: Float): Int {
        // Use alpha directly (0-255 range)
        val newAlpha = (255 * alpha).toInt().coerceIn(0, 255)
        return Color.argb(
            newAlpha,
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }
    
    /**
     * Convert dp to pixels.
     */
    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics
        )
    }
}
