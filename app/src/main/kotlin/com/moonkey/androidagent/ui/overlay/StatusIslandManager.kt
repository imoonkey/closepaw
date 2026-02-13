package com.moonkey.androidagent.ui.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * StatusIslandManager — Floating pill overlay on the real screen during VD mode.
 *
 * This is the ONLY overlay visible on the real screen when the agent runs on a virtual display
 * and the user is NOT viewing the VD viewer or the main app. It shows a compact status pill
 * at the top of the screen.
 *
 * - Tap: expands the Smart Capsule overlay (provides full controls)
 *
 * All controls (stop, takeover, resume, navigation) are in the expanded Smart Capsule.
 * The island is purely a compact status display.
 *
 * Touches outside the pill pass through (WRAP_CONTENT layout).
 */
class StatusIslandManager(
        private val service: AccessibilityService,
        private val onExpandCapsule: () -> Unit
) {
    companion object {
        private const val TAG = "StatusIslandManager"
        private const val AUTO_HIDE_DELAY_MS = 3000L
    }

    private val wm = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var pillView: ViewGroup? = null
    private var statusText: TextView? = null
    private var statusDot: View? = null
    private var isPaused = false

    // Colors
    private val colorBackground = 0xFFFFFFFF.toInt()
    private val colorText = 0xFF171717.toInt()
    private val colorPrimary = 0xFF2563EB.toInt()
    private val colorSuccess = 0xFF0D9488.toInt()
    private val colorError = 0xFFDC2626.toInt()
    private val colorWarning = 0xFFF59E0B.toInt()

    fun show() {
        if (pillView != null) return
        try {
            val view = buildPillLayout()
            val params = createLayoutParams()
            wm.addView(view, params)
            pillView = view
            Log.i(TAG, "Status island shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show status island", e)
        }
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        pillView?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove pill view", e)
            }
        }
        pillView = null
        statusText = null
        statusDot = null
    }

    fun isShowing(): Boolean = pillView != null

    fun updateStatus(statusStr: String, dotColor: Int) {
        statusText?.post {
            if (pillView == null) return@post
            val display = if (statusStr.length > 24) statusStr.take(21) + "..." else statusStr
            statusText?.text = display
            (statusDot?.background as? GradientDrawable)?.setColor(dotColor)
        }
    }

    fun showSuccess(message: String) {
        updateStatus(message, colorSuccess)
        handler.postDelayed({ hide() }, AUTO_HIDE_DELAY_MS)
    }

    fun showError(message: String) {
        updateStatus(message, colorError)
        handler.postDelayed({ hide() }, AUTO_HIDE_DELAY_MS)
    }

    fun updatePauseState(paused: Boolean) {
        isPaused = paused
        handler.post {
            if (pillView == null) return@post
            val dotColor = if (paused) colorWarning else colorPrimary
            (statusDot?.background as? GradientDrawable)?.setColor(dotColor)
        }
    }

    fun dispose() {
        hide()
    }

    // ── Layout Building ──

    private fun buildPillLayout(): LinearLayout {
        // Simple pill: tap to expand Smart Capsule overlay
        val pill =
                LinearLayout(service).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    background =
                            GradientDrawable().apply {
                                setColor(colorBackground)
                                cornerRadius = dp(20).toFloat()
                                setStroke(1, 0xFFE5E5E5.toInt())
                            }
                    elevation = dp(4).toFloat()

                    setOnClickListener { onExpandCapsule() }
                }

        // Status dot
        val dot =
                View(service).apply {
                    layoutParams =
                            LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) }
                    background =
                            GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(colorPrimary)
                            }
                }
        pill.addView(dot)
        statusDot = dot

        // Status text
        val text =
                TextView(service).apply {
                    setText("Working...")
                    setTextColor(colorText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    maxLines = 1
                }
        pill.addView(text)
        statusText = text

        return pill
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val statusBarHeight = getStatusBarHeight()
        return WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                )
                .apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = statusBarHeight + dp(4)
                }
    }

    private fun getStatusBarHeight(): Int {
        val resId = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) service.resources.getDimensionPixelSize(resId) else dp(24)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        value.toFloat(),
                        service.resources.displayMetrics
                )
                .toInt()
    }
}
