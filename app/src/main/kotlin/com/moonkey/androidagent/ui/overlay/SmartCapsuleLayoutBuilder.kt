package com.moonkey.androidagent.ui.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.util.TypedValue

internal data class CapsuleColors(
    val background: Int,
    val border: Int,
    val primary: Int,
    val error: Int,
    val text: Int
)

internal data class CapsuleViews(
    val container: ViewGroup,
    val statusText: TextView,
    val statusDot: View,
    val pauseButton: View,
    val pauseIconText: TextView
)

internal class SmartCapsuleLayoutBuilder(
    private val context: AccessibilityService,
    private val colors: CapsuleColors
) {
    fun build(
        onPauseToggle: () -> Unit,
        onStop: () -> Unit,
        onOpenApp: () -> Unit
    ): CapsuleViews {
        val container = FrameLayout(context).apply {
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(this@SmartCapsuleLayoutBuilder.colors.background)
                cornerRadius = dp(24).toFloat()
                setStroke(1, this@SmartCapsuleLayoutBuilder.colors.border)
            }
        }

        val statusDot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(10)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(this@SmartCapsuleLayoutBuilder.colors.primary)
            }
        }
        card.addView(statusDot)

        val statusText = TextView(context).apply {
            text = "Ready"
            setTextColor(colors.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        card.addView(statusText)

        card.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(12), 0)
        })

        val pauseButton = createIconButton(
            iconText = "⏸",
            contentDescription = "Pause",
            tintColor = colors.text,
            onClick = onPauseToggle
        )
        card.addView(pauseButton.container)

        card.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(6), 0)
        })

        val stopButton = createIconButton(
            iconText = "⏹",
            contentDescription = "Stop",
            tintColor = colors.error,
            onClick = onStop
        )
        card.addView(stopButton.container)

        card.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(6), 0)
        })

        val openAppButton = createIconButton(
            iconText = "↗",
            contentDescription = "Open App",
            tintColor = colors.primary,
            onClick = onOpenApp
        )
        card.addView(openAppButton.container)

        container.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        return CapsuleViews(
            container = container,
            statusText = statusText,
            statusDot = statusDot,
            pauseButton = pauseButton.container,
            pauseIconText = pauseButton.icon
        )
    }

    fun createLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
    }

    private data class IconButton(val container: View, val icon: TextView)

    private fun createIconButton(
        iconText: String,
        contentDescription: String,
        tintColor: Int,
        onClick: () -> Unit
    ): IconButton {
        val container = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFF5F5F5.toInt())
            }
            setOnClickListener { onClick() }
        }

        val icon = TextView(context).apply {
            text = iconText
            setTextColor(tintColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            this.contentDescription = contentDescription
        }

        container.addView(icon, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        return IconButton(container, icon)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
