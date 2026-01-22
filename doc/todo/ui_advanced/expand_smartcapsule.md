# SmartCapsule Expand Mode — Design Document

> **Phase**: 5.3 (Advanced)  
> **Status**: Design Complete  
> **Date**: 2026-01-22  
> **Reference**: `ui_impl_plan.md`, `ui_final_design.md` §3.4  
> **Target File**: `ui/overlay/SmartCapsuleManager.kt`

---

## 1. Overview

The SmartCapsule is the agent's **embodied presence** when operating in other apps. Phase 5.1-5.2 implemented a compact floating bar. Phase 5.3 adds **tap-to-expand** functionality, transforming the capsule into a mini-chat preview with full streaming text and action cards.

### Current State (Compact)

```
┌───────────────────────────────────────────────────────┐
│  ●  Opening Gmail...                │  ⏸  │  ⏹  │ ↗  │
└───────────────────────────────────────────────────────┘
Height: 48dp (approx, with padding ~64dp total)
```

### Target State (Expanded)

```
┌───────────────────────────────────────────────────────┐
│  ╭─────────────────────────────────────────────────╮  │
│  │  I'm checking your email. Found 3 unread...█    │  │
│  │                                                  │  │
│  │  ┌──────────────────────────────────────────┐   │  │
│  │  │  📧  Opening Gmail                  ✓    │   │  │
│  │  └──────────────────────────────────────────┘   │  │
│  ╰─────────────────────────────────────────────────╯  │
│                                                       │
│   ┌─────────┐    ┌─────────┐    ┌─────────────────┐  │
│   │ ⏸ Pause │    │ ⏹ Stop  │    │   ↗ Open App   │  │
│   └─────────┘    └─────────┘    └─────────────────┘  │
└───────────────────────────────────────────────────────┘
Height: 280dp
```

---

## 2. Design Goals

| Goal | Description |
|------|-------------|
| **Quick Preview** | Users see agent's thoughts without leaving current app |
| **Transparency** | Action cards show exactly what agent is doing |
| **Minimal Intrusion** | One tap to expand, one tap to collapse |
| **Smooth Animation** | 250ms height transition feels natural |
| **Memory Efficient** | Only render visible action cards (max 3 in expanded view) |

---

## 3. Interaction Design

### 3.1 Gestures

| Gesture | Action | Animation |
|---------|--------|-----------|
| **Tap capsule body** | Toggle expand/collapse | 250ms height tween |
| **Tap buttons** | Execute button action | No mode change |
| **Swipe down (expanded)** | Collapse | 200ms with velocity |
| **Tap outside (expanded)** | No action (keep focus on current app) | — |

### 3.2 State Machine

```
                    ┌─────────────┐
                    │    HIDDEN   │
                    └──────┬──────┘
                           │ show()
                           ▼
┌──────────────────────────────────────────────────────┐
│                                                      │
│    ┌──────────────┐  tap  ┌──────────────┐          │
│    │   COMPACT    │ ───── │   EXPANDED   │          │
│    │   (48dp)     │ ───── │   (280dp)    │          │
│    └──────────────┘  tap  └──────────────┘          │
│                                                      │
└───────────────────────────┬──────────────────────────┘
                            │ hide()
                            ▼
                    ┌─────────────┐
                    │    HIDDEN   │
                    └─────────────┘
```

### 3.3 Auto-Collapse Rules

The expanded capsule should **auto-collapse** in these scenarios:

| Trigger | Delay | Rationale |
|---------|-------|-----------|
| Task completed | 2s | Show "Done" then minimize |
| No activity for 10s | Immediate | User likely focused elsewhere |
| App switch detected | Immediate | Reduce screen obstruction |
| Error displayed | 5s | Give time to read error |

---

## 4. Layout Specification

### 4.1 Compact Mode (Current)

```kotlin
// Compact layout structure
┌─────────────────────────────────────────────────────────┐
│ [●] [Status Text (max 50 chars)    ] [⏸] [⏹] [↗]       │
│ 8dp  flex                            40dp 40dp 40dp    │
└─────────────────────────────────────────────────────────┘

Height: ~48dp content + 12dp top/bottom padding = 72dp total
```

| Element | Size | Spec |
|---------|------|------|
| Status dot | 8dp × 8dp | Circular, color-coded |
| Status text | flex | 14sp, Medium, max 1 line, 50 chars |
| Buttons | 40dp × 40dp | Circular, icon centered |
| Padding | 16dp horizontal, 12dp vertical | Inside card |
| Container padding | 16dp sides, 24dp bottom | Clearance from nav bar |

### 4.2 Expanded Mode (New)

```
┌───────────────────────────────────────────────────────────────┐
│                                                               │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │  Streaming Text Area (max 200 chars, multi-line)        │ │
│  │  16sp, Regular, with blinking cursor when streaming     │ │
│  │  Height: ~80dp (3-4 lines)                              │ │
│  └─────────────────────────────────────────────────────────┘ │
│                                                               │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │  ActionCard 1 (most recent)                              │ │
│  └─────────────────────────────────────────────────────────┘ │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │  ActionCard 2                                            │ │
│  └─────────────────────────────────────────────────────────┘ │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │  ActionCard 3 (oldest visible)                           │ │
│  └─────────────────────────────────────────────────────────┘ │
│                                                               │
│  ┌───────────────┐  ┌───────────────┐  ┌─────────────────┐  │
│  │   ⏸ Pause     │  │   ⏹ Stop      │  │    ↗ Open App   │  │
│  └───────────────┘  └───────────────┘  └─────────────────┘  │
│                                                               │
└───────────────────────────────────────────────────────────────┘

Total Height: 280dp
```

| Section | Height | Content |
|---------|--------|---------|
| Streaming text | ~80dp | Up to 200 chars, multi-line, with cursor |
| Action cards | ~120dp | Up to 3 cards × 40dp each |
| Buttons row | ~48dp | Larger labeled buttons |
| Spacing + Padding | ~32dp | Internal margins |

### 4.3 Dimension Constants

```kotlin
object CapsuleDimensions {
    // Compact mode
    const val COMPACT_HEIGHT_DP = 48
    const val COMPACT_TEXT_MAX_CHARS = 50
    const val COMPACT_TEXT_SIZE_SP = 14f
    
    // Expanded mode
    const val EXPANDED_HEIGHT_DP = 280
    const val EXPANDED_TEXT_MAX_CHARS = 200
    const val EXPANDED_TEXT_SIZE_SP = 16f
    const val EXPANDED_TEXT_AREA_HEIGHT_DP = 80
    const val EXPANDED_ACTION_CARD_HEIGHT_DP = 40
    const val EXPANDED_MAX_ACTION_CARDS = 3
    
    // Common
    const val CAPSULE_CORNER_RADIUS_DP = 24
    const val BUTTON_SIZE_COMPACT_DP = 40
    const val BUTTON_SIZE_EXPANDED_DP = 48
    const val CARD_HORIZONTAL_PADDING_DP = 16
    const val CARD_VERTICAL_PADDING_DP = 12
    
    // Animation
    const val EXPAND_COLLAPSE_DURATION_MS = 250L
}
```

---

## 5. Visual Design

### 5.1 Expanded Container

```kotlin
// Background
background = GradientDrawable().apply {
    setColor(colorBackground)  // 0xFFFFFFFF
    cornerRadius = dp(24).toFloat()
    setStroke(1, colorBorder)  // 0xFFE5E5E5
}

// Elevation (shadow)
elevation = dp(12).toFloat()  // Slightly higher than compact
```

### 5.2 Streaming Text Area

```kotlin
// Text styling
textSize = 16sp
typeface = Typeface.DEFAULT
textColor = colorText  // 0xFF171717
lineSpacing = 1.3f  // 130% line height
maxLines = 4
ellipsize = TextUtils.TruncateAt.END

// Container
background = GradientDrawable().apply {
    setColor(0xFFF5F5F5.toInt())  // surfaceVariant
    cornerRadius = dp(12).toFloat()
}
padding = dp(12)
```

### 5.3 Action Card (Mini Version)

```kotlin
// Simplified action card for overlay (non-interactive)
┌────────────────────────────────────────────────────────┐
│  [Icon]  Tool Name                            [Status] │
│  20dp    flex                                   20dp   │
└────────────────────────────────────────────────────────┘

Height: 40dp
Corner radius: 8dp
Background: Based on status (Success=0x1A0D9488, Error=0x1ADC2626, Executing=0x1A2563EB)
Border: 1dp solid, color matches status
```

### 5.4 Expanded Buttons

```kotlin
// Larger buttons with labels
┌───────────────────┐
│   [Icon]  Label   │
│   18sp    12sp    │
└───────────────────┘

Width: flexible (weight-based)
Height: 48dp
Corner radius: 12dp
Background: surfaceVariant (0xFFF5F5F5)
```

### 5.5 Blinking Cursor

```kotlin
// Show cursor when streaming (expanded mode only)
val cursorChar = "█"
val cursorAlpha = animateFloat(1f to 0f, duration = 530ms, linear, repeat = Reverse)
textColor = colorPrimary.copy(alpha = cursorAlpha)
```

---

## 6. Animation Specification

### 6.1 Expand Animation

```kotlin
fun expandCapsule() {
    isExpanded = true
    
    // 1. Height animation
    val heightAnimator = ValueAnimator.ofInt(compactHeight, expandedHeight).apply {
        duration = 250
        interpolator = FastOutSlowInInterpolator()
        addUpdateListener { animation ->
            val params = capsuleCard.layoutParams
            params.height = animation.animatedValue as Int
            capsuleCard.layoutParams = params
        }
    }
    
    // 2. Content fade-in (staggered)
    val contentFadeIn = AnimatorSet().apply {
        playSequentially(
            fadeIn(streamingTextArea, duration = 150, startDelay = 50),
            fadeIn(actionCardsContainer, duration = 150, startDelay = 0),
            fadeIn(expandedButtonsRow, duration = 100, startDelay = 0)
        )
    }
    
    // 3. Compact content fade-out
    val compactFadeOut = fadeOut(compactContentRow, duration = 100)
    
    AnimatorSet().apply {
        play(heightAnimator)
        play(compactFadeOut).with(heightAnimator)
        play(contentFadeIn).after(compactFadeOut)
        start()
    }
}
```

### 6.2 Collapse Animation

```kotlin
fun collapseCapsule() {
    // 1. Content fade-out
    val expandedFadeOut = AnimatorSet().apply {
        playTogether(
            fadeOut(streamingTextArea, duration = 100),
            fadeOut(actionCardsContainer, duration = 100),
            fadeOut(expandedButtonsRow, duration = 100)
        )
    }
    
    // 2. Height animation
    val heightAnimator = ValueAnimator.ofInt(expandedHeight, compactHeight).apply {
        duration = 200  // Slightly faster collapse
        interpolator = FastOutSlowInInterpolator()
        addUpdateListener { animation ->
            val params = capsuleCard.layoutParams
            params.height = animation.animatedValue as Int
            capsuleCard.layoutParams = params
        }
    }
    
    // 3. Compact content fade-in
    val compactFadeIn = fadeIn(compactContentRow, duration = 150)
    
    AnimatorSet().apply {
        play(expandedFadeOut)
        play(heightAnimator).after(50)  // Start height change before fade completes
        play(compactFadeIn).after(heightAnimator)
        doOnEnd { isExpanded = false }
        start()
    }
}
```

### 6.3 Animation Easing

| Animation | Duration | Easing | Notes |
|-----------|----------|--------|-------|
| Expand height | 250ms | FastOutSlowIn | Feels like unfolding |
| Collapse height | 200ms | FastOutSlowIn | Slightly snappier |
| Content fade in | 150ms | LinearOutSlowIn | Smooth appearance |
| Content fade out | 100ms | FastOutLinearIn | Quick disappear |
| Cursor blink | 530ms | Linear | Match chat screen cursor |

---

## 7. Data Flow

### 7.1 Action Cards Storage

```kotlin
class SmartCapsuleManager {
    // Store recent actions for expanded view
    private val recentActions = ArrayDeque<ActionCardData>(MAX_VISIBLE_ACTIONS)
    
    fun onActionExecuted(toolName: String, success: Boolean) {
        val action = ActionCardData(
            id = UUID.randomUUID().toString(),
            toolName = toolName,
            state = if (success) ActionState.Success else ActionState.Failed,
            timestamp = System.currentTimeMillis()
        )
        
        // Keep only recent N actions
        if (recentActions.size >= EXPANDED_MAX_ACTION_CARDS) {
            recentActions.removeFirst()
        }
        recentActions.addLast(action)
        
        // Update UI
        if (isExpanded) {
            updateActionCardsUI()
        }
        
        // Also update compact status
        setStatusDot(if (success) colorSuccess else colorError, pulsing = false)
        setStatusText("$toolName ${if (success) "✓" else "✗"}")
    }
}

data class ActionCardData(
    val id: String,
    val toolName: String,
    val state: ActionState,
    val timestamp: Long
)

enum class ActionState {
    Executing, Success, Failed
}
```

### 7.2 Streaming Text Handling

```kotlin
// Expanded mode shows more text with formatting
private fun updateStatusText(text: String) {
    val displayText = if (isExpanded) {
        // Multi-line, more chars, with cursor
        text.take(EXPANDED_TEXT_MAX_CHARS)
    } else {
        // Single line, truncated
        text.take(COMPACT_TEXT_MAX_CHARS).replace("\n", " ")
    }
    
    setStatusText(displayText.ifEmpty { "Thinking..." })
    
    if (isExpanded) {
        updateStreamingTextArea(displayText)
    }
}
```

---

## 8. Implementation Plan

### 8.1 File Changes

| File | Change Type | Description |
|------|-------------|-------------|
| `SmartCapsuleManager.kt` | Major update | Add expand/collapse logic, new layouts |
| `StatusUtils.kt` | No change | Already sufficient |

### 8.2 Implementation Steps

#### Step 1: Add State Management

```kotlin
// Add to SmartCapsuleManager
private var isExpanded = false
private val recentActions = ArrayDeque<ActionCardData>(3)

// View references for expanded mode
private var streamingTextArea: TextView? = null
private var actionCardsContainer: LinearLayout? = null
private var expandedButtonsRow: LinearLayout? = null
private var compactContentRow: LinearLayout? = null
```

#### Step 2: Create Expanded Layout

```kotlin
private fun createExpandedLayout(): ViewGroup {
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE  // Hidden initially
        
        // Streaming text area
        streamingTextArea = TextView(context).apply {
            // ... styling from §5.2
        }
        addView(streamingTextArea)
        
        // Action cards container
        actionCardsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        addView(actionCardsContainer)
        
        // Expanded buttons row
        expandedButtonsRow = createExpandedButtonsRow()
        addView(expandedButtonsRow)
    }
}
```

#### Step 3: Add Tap Handler

```kotlin
// In show() method, add to capsule card:
card.setOnClickListener {
    if (isExpanded) {
        collapseCapsule()
    } else {
        expandCapsule()
    }
}

// Prevent button clicks from triggering expand
pauseButton.setOnClickListener { /* ... existing */ }
// Add: it.setOnTouchListener that consumes touch
```

#### Step 4: Implement Animations

```kotlin
private fun expandCapsule() {
    if (isExpanded) return
    isExpanded = true
    
    // Show expanded content
    expandedContentLayout?.visibility = View.VISIBLE
    
    // Animate height
    val animator = ValueAnimator.ofInt(compactHeightPx, expandedHeightPx).apply {
        duration = EXPAND_COLLAPSE_DURATION_MS
        interpolator = FastOutSlowInInterpolator()
        addUpdateListener { /* update layout params */ }
    }
    
    // Fade animations
    fadeOut(compactContentRow, 100)
    fadeIn(expandedContentLayout, 150, startDelay = 50)
    
    animator.start()
    
    // Update text to show full version
    updateStreamingTextArea(streamingText.toString())
    updateActionCardsUI()
}

private fun collapseCapsule() {
    if (!isExpanded) return
    
    // Animate first, then update state
    val animator = ValueAnimator.ofInt(expandedHeightPx, compactHeightPx).apply {
        duration = 200
        interpolator = FastOutSlowInInterpolator()
        addUpdateListener { /* update layout params */ }
        doOnEnd {
            isExpanded = false
            expandedContentLayout?.visibility = View.GONE
        }
    }
    
    fadeOut(expandedContentLayout, 100)
    fadeIn(compactContentRow, 150, startDelay = 100)
    
    animator.start()
}
```

#### Step 5: Add Action Card Rendering

```kotlin
private fun updateActionCardsUI() {
    actionCardsContainer?.removeAllViews()
    
    recentActions.reversed().take(EXPANDED_MAX_ACTION_CARDS).forEach { action ->
        val cardView = createMiniActionCard(action)
        actionCardsContainer?.addView(cardView)
    }
}

private fun createMiniActionCard(action: ActionCardData): View {
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(40)
        ).apply {
            topMargin = dp(4)
        }
        
        // Background based on state
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(when (action.state) {
                ActionState.Success -> 0x1A0D9488.toInt()
                ActionState.Failed -> 0x1ADC2626.toInt()
                ActionState.Executing -> 0x1A2563EB.toInt()
            })
            setStroke(1, when (action.state) {
                ActionState.Success -> colorSuccess
                ActionState.Failed -> colorError
                ActionState.Executing -> colorPrimary
            })
        }
        setPadding(dp(12), dp(8), dp(12), dp(8))
        
        // Tool icon
        addView(TextView(context).apply {
            text = getToolEmoji(action.toolName)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        
        // Tool name
        addView(TextView(context).apply {
            text = action.toolName
            setTextColor(colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        })
        
        // Status indicator
        addView(TextView(context).apply {
            text = when (action.state) {
                ActionState.Success -> "✓"
                ActionState.Failed -> "✗"
                ActionState.Executing -> "⋯"
            }
            setTextColor(when (action.state) {
                ActionState.Success -> colorSuccess
                ActionState.Failed -> colorError
                ActionState.Executing -> colorPrimary
            })
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
    }
}

private fun getToolEmoji(toolName: String): String = when (toolName.lowercase()) {
    "click", "tap" -> "👆"
    "type", "input" -> "⌨️"
    "scroll" -> "📜"
    "swipe" -> "👈"
    "back" -> "⬅️"
    "home" -> "🏠"
    "wait" -> "⏳"
    "complete_task" -> "✅"
    "launch_app", "open" -> "📱"
    else -> "🔧"
}
```

#### Step 6: Auto-Collapse Logic

```kotlin
private val autoCollapseRunnable = Runnable { collapseCapsule() }

fun onTaskCompleted() {
    setStatusDot(colorSuccess, pulsing = false)
    setStatusText("✓ Done")
    
    // Auto-collapse after 2s if expanded
    if (isExpanded) {
        handler.postDelayed(autoCollapseRunnable, 2000)
    }
    
    handler.postDelayed({ hide() }, 3000)
}

// Cancel auto-collapse if user interacts
private fun onUserInteraction() {
    handler.removeCallbacks(autoCollapseRunnable)
}
```

### 8.3 Testing Checklist

| Test | Description |
|------|-------------|
| Tap to expand | Single tap expands from compact to 280dp |
| Tap to collapse | Single tap collapses back to compact |
| Button isolation | Button taps don't trigger expand |
| Streaming text (expanded) | Shows up to 200 chars with cursor |
| Streaming text (compact) | Shows up to 50 chars, single line |
| Action cards | Shows up to 3 recent actions |
| Action card states | Correct colors for Success/Failed/Executing |
| Auto-collapse on complete | Collapses after 2s when task completes |
| Animation smoothness | 60fps during expand/collapse |
| Memory | No leaks after repeated expand/collapse |

---

## 9. Accessibility

### 9.1 Content Descriptions

```kotlin
// Compact mode
capsuleCard.contentDescription = "Agent status: $statusText. Tap to expand for details."

// Expanded mode
capsuleCard.contentDescription = "Agent details expanded. $streamingText. ${recentActions.size} actions shown. Tap to collapse."

// Action cards
actionCard.contentDescription = "$toolName action ${state.name.lowercase()}"
```

### 9.2 Touch Target Sizes

| Element | Minimum Size |
|---------|--------------|
| Capsule body (tap area) | Full width × 48dp (compact) |
| Buttons (compact) | 40dp × 40dp |
| Buttons (expanded) | Full width × 48dp |

---

## 10. Edge Cases

| Scenario | Behavior |
|----------|----------|
| Expand while streaming | Continue streaming in expanded view |
| Collapse while streaming | Continue streaming in compact view |
| Task completes while expanded | Show "Done", auto-collapse after 2s |
| Error while expanded | Show error, keep expanded for 5s |
| No actions yet | Show only streaming text area |
| Very long tool name | Truncate with ellipsis |
| Rapid tap/collapse | Debounce, ignore if animation in progress |

---

## 11. Performance Considerations

1. **View Recycling**: Don't recreate action card views on every update; reuse/update existing
2. **Animation Efficiency**: Use `ViewPropertyAnimator` where possible for hardware acceleration
3. **Memory**: Clear action cards when capsule is hidden
4. **Layout Calculations**: Cache `dp()` conversions, avoid repeated calculations

---

## 12. Future Enhancements (Out of Scope)

- [ ] Drag to reposition capsule
- [ ] Swipe to dismiss
- [ ] Mini-input field in expanded mode
- [ ] Voice feedback toggle
- [ ] Custom themes/colors

---

## 13. Summary

The SmartCapsule expand mode transforms a simple status bar into a **mini-chat preview**, giving users transparency into agent operations without leaving their current app. The implementation prioritizes:

1. **Smooth animation** (250ms expand/collapse)
2. **Minimal intrusion** (single tap interaction)
3. **Information density** (streaming text + action cards)
4. **Accessibility** (proper content descriptions, touch targets)

Implementation estimate: ~300 lines of new code, primarily layout construction and animation logic.
