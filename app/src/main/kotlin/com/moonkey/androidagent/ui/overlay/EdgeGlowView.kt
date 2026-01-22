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
        
        /** Base alpha for the glow (0.0 to 1.0). Shared with EdgeGlowManager. */
        const val BASE_ALPHA = 0.7f
    }
    
    // Dimensions in pixels
    private val glowWidth: Float = dp(GLOW_WIDTH_DP)
    
    // Current state
    private var glowColor: Int = GlowState.Active.colorHex
    private var glowAlpha: Float = BASE_ALPHA
    
    // Paint for drawing glow edges - simple gradient, no blur filter
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    
    // Cached gradients to avoid allocation on every frame
    // Recreated when dimensions or color/alpha change
    private var cachedWidth: Float = 0f
    private var cachedHeight: Float = 0f
    private var cachedColorWithAlpha: Int = 0
    private var topGradient: LinearGradient? = null
    private var bottomGradient: LinearGradient? = null
    private var leftGradient: LinearGradient? = null
    private var rightGradient: LinearGradient? = null
    
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
     * Used for pulse animation - typically oscillates between 0.5 and 0.85.
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
     * Get the current glow alpha.
     * Used by EdgeGlowManager to start fade-out from actual current value.
     */
    fun getCurrentAlpha(): Float = glowAlpha
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val w = width.toFloat()
        val h = height.toFloat()
        
        if (w <= 0 || h <= 0) return
        
        // Calculate the color with current alpha
        val colorWithAlpha = applyAlpha(glowColor, glowAlpha)
        
        // Recreate gradients only if dimensions or color changed
        if (w != cachedWidth || h != cachedHeight || colorWithAlpha != cachedColorWithAlpha) {
            updateGradientCache(w, h, colorWithAlpha)
        }
        
        // Draw top edge glow
        paint.shader = topGradient
        canvas.drawRect(0f, 0f, w, glowWidth, paint)
        
        // Draw bottom edge glow
        paint.shader = bottomGradient
        canvas.drawRect(0f, h - glowWidth, w, h, paint)
        
        // Draw left edge glow
        paint.shader = leftGradient
        canvas.drawRect(0f, 0f, glowWidth, h, paint)
        
        // Draw right edge glow
        paint.shader = rightGradient
        canvas.drawRect(w - glowWidth, 0f, w, h, paint)
    }
    
    /**
     * Update cached gradient objects when dimensions or color change.
     * This avoids allocating new LinearGradient objects on every frame.
     */
    private fun updateGradientCache(w: Float, h: Float, colorWithAlpha: Int) {
        val transparent = Color.TRANSPARENT
        
        cachedWidth = w
        cachedHeight = h
        cachedColorWithAlpha = colorWithAlpha
        
        topGradient = LinearGradient(
            0f, 0f,
            0f, glowWidth,
            colorWithAlpha, transparent,
            Shader.TileMode.CLAMP
        )
        
        bottomGradient = LinearGradient(
            0f, h,
            0f, h - glowWidth,
            colorWithAlpha, transparent,
            Shader.TileMode.CLAMP
        )
        
        leftGradient = LinearGradient(
            0f, 0f,
            glowWidth, 0f,
            colorWithAlpha, transparent,
            Shader.TileMode.CLAMP
        )
        
        rightGradient = LinearGradient(
            w, 0f,
            w - glowWidth, 0f,
            colorWithAlpha, transparent,
            Shader.TileMode.CLAMP
        )
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
