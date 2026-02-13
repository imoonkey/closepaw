package com.moonkey.androidagent.ui.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * CapsuleViews — handles to the key views inside the capsule.
 * Used by SmartCapsuleManager and SmartCapsuleRenderer to update content and visibility.
 */
internal data class CapsuleViews(
    val container: ViewGroup,
    // Row 1: status dot + thought
    val row1: ViewGroup,
    val statusDot: View,
    val thoughtText: TextView,
    // Divider 1 (between Row 1 and expanded body / Row 2)
    val divider1: View,
    // Expanded body — shown in WaitingFor* states (question/instruction text)
    val expandedBody: TextView? = null,
    // Row 2: control buttons (left) + nav icons (right)
    val row2: ViewGroup,
    val primaryButton: ViewGroup,
    val primaryIcon: TextView,
    val primaryText: TextView,
    val stopButton: ViewGroup,
    val stopIcon: TextView,
    val stopText: TextView,
    // Navigation icons (right side of Row 2)
    val navMinimize: View? = null,   // [1] ⊖
    val navApp: View? = null,        // [2] 📱
    val navWatch: View? = null,      // [3] 👁
    // Divider 2 (between Row 2 and Row 3)
    val divider2: View,
    // Row 3: input + action button
    val row3: ViewGroup,
    val inputEditText: EditText,
    val inputButton: ViewGroup,
    val inputButtonText: TextView,
)

/**
 * SmartCapsuleLayoutBuilder — builds the three-row capsule view hierarchy.
 *
 * Row 1: [StatusDot] [ThoughtText]
 * Row 2: [接管/继续] [停止]  ...  [⊖] [📱] [👁]
 * Row 3: [EditText] [发送/补充]
 *
 * All views are built programmatically (no XML layouts in overlay context).
 * The builder is stateless — call build() to get a fresh CapsuleViews.
 */
internal class SmartCapsuleLayoutBuilder(
    private val context: AccessibilityService
) {
    // ── Colors ──

    private val bgWhite = 0xFFFFFFFF.toInt()
    private val dividerGray = 0xFFF3F4F6.toInt()
    private val textPrimary = 0xFF111827.toInt()
    private val textSecondary = 0xFF6B7280.toInt()
    private val textWhite = 0xFFFFFFFF.toInt()

    private val colorBlue = 0xFF2563EB.toInt()
    private val colorRed = 0xFFEF4444.toInt()
    private val colorRedLight = 0xFFFEE2E2.toInt()
    private val colorGrayBg = 0xFFF9FAFB.toInt()
    private val colorGrayBorder = 0xFFE5E7EB.toInt()
    private val colorNavGray = 0xFF9CA3AF.toInt()

    fun build(
        onPrimary: () -> Unit,
        onStop: () -> Unit,
        onRow1Tap: (() -> Unit)? = null,
        onRow3Submit: () -> Unit,
        onMinimize: (() -> Unit)? = null,
        onNavApp: (() -> Unit)? = null,
        onNavWatch: (() -> Unit)? = null,
        onInputFocused: () -> Unit = {},
    ): CapsuleViews {
        // Outer container with side margins
        val container = FrameLayout(context).apply {
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }

        // Card: vertical layout with rounded corners
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(bgWhite)
                cornerRadius = dp(24).toFloat()
                setStroke(1, colorGrayBorder)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(4).toFloat()
            }
        }

        // ── Row 1: Status dot + Thought text ──
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            onRow1Tap?.let { handler ->
                setOnClickListener { handler() }
                isClickable = true
                isFocusable = true
                contentDescription = "打开主应用"
            }
        }

        val statusDot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(10)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorBlue)
            }
        }
        row1.addView(statusDot)

        val thoughtText = TextView(context).apply {
            text = "思考中..."
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row1.addView(thoughtText)

        card.addView(row1, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // ── Divider 1 ──
        val divider1 = buildDivider()
        card.addView(divider1)

        // ── Expanded body (hidden by default, shown in WaitingFor* states) ──
        val expandedBody = TextView(context).apply {
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            maxLines = 3
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        card.addView(expandedBody)

        // ── Row 2: Control buttons (left) + nav icons (right) ──
        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }

        // Primary button (filled, blue — 接管/继续)
        val primaryResult = buildPillButton(
            icon = "✋", label = "接管",
            bgColor = colorBlue, borderColor = colorBlue, textColor = textWhite,
            onClick = onPrimary
        )
        row2.addView(primaryResult.container, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        row2.addView(spacer(dp(8)))

        // Stop button (outlined red)
        val stopResult = buildPillButton(
            icon = "⏹", label = "停止",
            bgColor = colorRedLight, borderColor = colorRed, textColor = colorRed,
            onClick = onStop
        )
        row2.addView(stopResult.container, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Flexible spacer pushes nav icons to the right
        row2.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })

        // Navigation icons [1] [2] [3]
        val navMinimize = buildNavIcon("⊖", "最小化") { onMinimize?.invoke() }
        row2.addView(navMinimize)
        row2.addView(spacer(dp(4)))

        val navApp = buildNavIcon("📱", "打开应用") { onNavApp?.invoke() }
        row2.addView(navApp)
        row2.addView(spacer(dp(4)))

        val navWatch = buildNavIcon("👁", "查看屏幕") { onNavWatch?.invoke() }
        row2.addView(navWatch)

        card.addView(row2, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // ── Divider 2 ──
        val divider2 = buildDivider()
        card.addView(divider2)

        // ── Row 3: Input + action button ──
        val row3 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }

        val inputEditText = EditText(context).apply {
            hint = "有想法? 补充一下..."
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            background = GradientDrawable().apply {
                setColor(colorGrayBg)
                cornerRadius = dp(16).toFloat()
                setStroke(1, colorGrayBorder)
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onInputFocused()
            }
        }
        row3.addView(inputEditText)

        row3.addView(spacer(dp(8)))

        // Action button (pill-shaped: "发送 →" or "💬 补充")
        val inputButtonResult = buildPillButton(
            icon = "💬", label = "补充",
            bgColor = colorBlue, borderColor = colorBlue, textColor = textWhite,
            onClick = onRow3Submit
        )
        row3.addView(inputButtonResult.container, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        card.addView(row3, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        container.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        return CapsuleViews(
            container = container,
            row1 = row1,
            statusDot = statusDot,
            thoughtText = thoughtText,
            divider1 = divider1,
            expandedBody = expandedBody,
            row2 = row2,
            primaryButton = primaryResult.container,
            primaryIcon = primaryResult.icon,
            primaryText = primaryResult.label,
            stopButton = stopResult.container,
            stopIcon = stopResult.icon,
            stopText = stopResult.label,
            navMinimize = navMinimize,
            navApp = navApp,
            navWatch = navWatch,
            divider2 = divider2,
            row3 = row3,
            inputEditText = inputEditText,
            inputButton = inputButtonResult.container,
            inputButtonText = inputButtonResult.label,
        )
    }

    fun createLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1)
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
    }

    // ── Divider builder ──

    private fun buildDivider(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
        }
        setBackgroundColor(dividerGray)
    }

    // ── Navigation icon builder ──

    private fun buildNavIcon(icon: String, contentDesc: String, onClick: () -> Unit): View {
        return TextView(context).apply {
            text = icon
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            val size = dp(28)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener { onClick() }
            isClickable = true
            isFocusable = true
            contentDescription = contentDesc
            setTextColor(colorNavGray)
        }
    }

    // ── Pill button builder ──

    private data class PillButtonViews(
        val container: ViewGroup,
        val icon: TextView,
        val label: TextView,
    )

    private fun buildPillButton(
        icon: String,
        label: String,
        bgColor: Int,
        borderColor: Int,
        textColor: Int,
        onClick: () -> Unit,
    ): PillButtonViews {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dp(20).toFloat()
                setStroke(1, borderColor)
            }
            setOnClickListener { onClick() }
            isClickable = true
            isFocusable = true
            contentDescription = label
        }

        val iconView = TextView(context).apply {
            text = icon
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }
        container.addView(iconView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(4) })

        val labelView = TextView(context).apply {
            text = label
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        container.addView(labelView)

        return PillButtonViews(container as ViewGroup, iconView, labelView)
    }

    private fun spacer(width: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(width, 0)
    }

    // ── Dp conversion ──

    internal fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
