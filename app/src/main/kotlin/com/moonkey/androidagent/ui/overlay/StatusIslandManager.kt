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
 * This is the ONLY overlay visible on the real screen when the agent runs on a
 * virtual display. It shows a compact status pill at the top of the screen.
 *
 * - Tap: opens the VirtualDisplayViewerActivity (live preview)
 * - Long-press: expands inline Stop/Pause controls for 3 seconds
 *
 * Touches outside the pill pass through (WRAP_CONTENT layout).
 */
class StatusIslandManager(
    private val service: AccessibilityService,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
    private val onStop: () -> Unit,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit
) {
    companion object {
        private const val TAG = "StatusIslandManager"
        private const val AUTO_HIDE_DELAY_MS = 3000L
        private const val CONTROLS_AUTO_HIDE_MS = 3000L
    }

    private val wm = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var pillView: ViewGroup? = null
    private var statusText: TextView? = null
    private var statusDot: View? = null
    private var controlsContainer: LinearLayout? = null
    private var isPaused = false
    private var pauseIconText: TextView? = null

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
        controlsContainer = null
        pauseIconText = null
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
            pauseIconText?.text = if (paused) "▶" else "⏸"
            val dotColor = if (paused) colorWarning else colorPrimary
            (statusDot?.background as? GradientDrawable)?.setColor(dotColor)
        }
    }

    fun dispose() {
        hide()
    }

    // ── Layout Building ──

    private fun buildPillLayout(): ViewGroup {
        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Main pill row
        val pill = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(colorBackground)
                cornerRadius = dp(20).toFloat()
                setStroke(1, 0xFFE5E5E5.toInt())
            }
            elevation = dp(4).toFloat()

            setOnClickListener { onTap() }
            setOnLongClickListener {
                toggleInlineControls()
                onLongPress()
                true
            }
        }

        // Status dot
        val dot = View(service).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorPrimary)
            }
        }
        pill.addView(dot)
        statusDot = dot

        // Status text
        val text = TextView(service).apply {
            setText("Working...")
            setTextColor(colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
        }
        pill.addView(text)
        statusText = text

        container.addView(pill, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Inline controls (hidden by default)
        val controls = buildInlineControls()
        controls.visibility = View.GONE
        container.addView(controls, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        controlsContainer = controls

        return container
    }

    private fun buildInlineControls(): LinearLayout {
        return LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(colorBackground)
                cornerRadius = dp(16).toFloat()
                setStroke(1, 0xFFE5E5E5.toInt())
            }
            elevation = dp(4).toFloat()

            // Pause/Resume button
            val pauseBtn = TextView(service).apply {
                text = "⏸"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                gravity = Gravity.CENTER
                setOnClickListener {
                    if (isPaused) onResume() else onPause()
                }
            }
            addView(pauseBtn)
            pauseIconText = pauseBtn

            // Spacer
            addView(View(service).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(1))
            })

            // Stop button
            val stopBtn = TextView(service).apply {
                text = "⏹"
                setTextColor(colorError)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                gravity = Gravity.CENTER
                setOnClickListener { onStop() }
            }
            addView(stopBtn)
        }
    }

    private fun toggleInlineControls() {
        val controls = controlsContainer ?: return
        if (controls.visibility == View.VISIBLE) {
            controls.visibility = View.GONE
        } else {
            controls.visibility = View.VISIBLE
            handler.postDelayed({
                controls.visibility = View.GONE
            }, CONTROLS_AUTO_HIDE_MS)
        }
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
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight + dp(8)
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
        ).toInt()
    }
}
