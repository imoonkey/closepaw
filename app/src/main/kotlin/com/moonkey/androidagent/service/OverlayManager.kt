package com.moonkey.androidagent.service

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * OverlayManager - Elegant floating control bar for agent.
 * 
 * Positioned at bottom of screen with minimal, professional design
 * matching the app's Notion-inspired aesthetic.
 */
class OverlayManager(
    private val context: AccessibilityService,
    private val onStop: () -> Unit,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var overlayView: ViewGroup? = null
    private var statusText: TextView? = null
    private var pauseButton: View? = null
    private var pauseIcon: ImageView? = null
    private var isPaused = false
    
    // Colors matching the theme
    private val colorBackground = 0xFFFFFFFF.toInt()
    private val colorBorder = 0xFFE9E9E7.toInt()
    private val colorTextPrimary = 0xFF37352F.toInt()
    private val colorTextSecondary = 0xFF6B6B6B.toInt()
    private val colorAccent = 0xFF2F3437.toInt()
    private val colorSuccess = 0xFF0F7B6C.toInt()
    private val colorWarning = 0xFFF2994A.toInt()
    private val colorError = 0xFFEB5757.toInt()

    fun show() {
        if (overlayView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL

        // Main container with shadow effect
        val container = FrameLayout(context).apply {
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        
        // Card layout
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            
            // Rounded rectangle background with border
            background = GradientDrawable().apply {
                setColor(colorBackground)
                cornerRadius = dp(14).toFloat()
                setStroke(1, colorBorder)
            }
            
            // Elevation shadow
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(8).toFloat()
            }
        }

        // Status indicator dot
        val statusDot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(10)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorSuccess)
            }
        }
        card.addView(statusDot)

        // Status text
        statusText = TextView(context).apply {
            text = "Ready"
            setTextColor(colorTextPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        card.addView(statusText)

        // Spacer
        card.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), 0)
        })

        // Pause/Resume button
        pauseButton = createIconButton(
            iconResName = "pause",
            contentDescription = "Pause"
        ) {
            if (isPaused) {
                onResume()
            } else {
                onPause()
            }
        }
        card.addView(pauseButton)

        // Spacer between buttons
        card.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), 0)
        })

        // Stop button
        val stopButton = createIconButton(
            iconResName = "stop",
            contentDescription = "Stop",
            tintColor = colorError
        ) {
            onStop()
        }
        card.addView(stopButton)

        container.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        windowManager.addView(container, params)
        overlayView = container
        
        // Reset state
        isPaused = false
    }
    
    private fun createIconButton(
        iconResName: String,
        contentDescription: String,
        tintColor: Int = colorAccent,
        onClick: () -> Unit
    ): View {
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            
            // Circular background
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFF7F6F3.toInt())
            }
            
            // Icon (using text as fallback since we can't easily load vector icons)
            val iconText = when (iconResName) {
                "pause" -> "⏸"
                "play" -> "▶"
                "stop" -> "⏹"
                else -> "●"
            }
            
            val icon = TextView(context).apply {
                text = iconText
                setTextColor(tintColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
                this.contentDescription = contentDescription
            }
            
            addView(icon, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            
            setOnClickListener { onClick() }
            
            // Store reference for pause button
            if (iconResName == "pause") {
                pauseIcon = null // We're using TextView, not ImageView
            }
        }
    }

    fun hide() {
        if (overlayView != null) {
            windowManager.removeView(overlayView)
            overlayView = null
            statusText = null
            pauseButton = null
            pauseIcon = null
        }
    }

    /**
     * Update the status text displayed in the overlay.
     */
    fun updateStatus(status: String) {
        statusText?.post { 
            // Clean up emoji for cleaner display
            val cleanStatus = status
                .replace("✅", "")
                .replace("❌", "")
                .replace("⚠️", "")
                .replace("🧠", "")
                .replace("🔧", "")
                .replace("💡", "")
                .replace("👀", "")
                .replace("🚀", "")
                .replace("🛑", "")
                .trim()
                .take(40) // Limit length
            
            statusText?.text = cleanStatus.ifEmpty { "Ready" }
            
            // Update status dot color based on status
            val dotView = (overlayView?.getChildAt(0) as? LinearLayout)?.getChildAt(0)
            val dotColor = when {
                status.contains("✅") || status.contains("achieved") -> colorSuccess
                status.contains("❌") || status.contains("Error") -> colorError
                status.contains("⚠️") -> colorWarning
                status.contains("🧠") || status.contains("Thinking") -> colorAccent
                else -> colorSuccess
            }
            (dotView?.background as? GradientDrawable)?.setColor(dotColor)
        }
    }
    
    /**
     * Update the pause/resume button state.
     */
    fun updatePauseState(paused: Boolean) {
        isPaused = paused
        pauseButton?.post {
            // Update the icon text
            val iconView = (pauseButton as? FrameLayout)?.getChildAt(0) as? TextView
            iconView?.text = if (paused) "▶" else "⏸"
        }
    }
    
    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
