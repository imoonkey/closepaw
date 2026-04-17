package ai.closepaw.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * AgentShapes - Shape definitions for the chat UI.
 */
val AgentShapes = Shapes(
    // Chips, small cards
    small = RoundedCornerShape(8.dp),
    
    // Action cards, list items
    medium = RoundedCornerShape(12.dp),
    
    // Bubbles, sheets, dialogs
    large = RoundedCornerShape(20.dp),
    
    // Extra large sheets
    extraLarge = RoundedCornerShape(24.dp)
)

// ============================================
// Chat Bubble Shapes
// Asymmetric corners for natural conversation feel
// ============================================

/**
 * User message bubble shape.
 * Rounded on all corners except bottom-right (pointing right).
 */
val BubbleShapeUser = RoundedCornerShape(
    topStart = 20.dp,
    topEnd = 20.dp,
    bottomStart = 20.dp,
    bottomEnd = 6.dp
)

/**
 * Agent message bubble shape.
 * Rounded on all corners except top-left (pointing left).
 */
val BubbleShapeAgent = RoundedCornerShape(
    topStart = 6.dp,
    topEnd = 20.dp,
    bottomStart = 20.dp,
    bottomEnd = 20.dp
)

// ============================================
// Special Shapes
// ============================================

/**
 * Capsule shape for overlay and pills.
 */
val CapsuleShape = RoundedCornerShape(24.dp)

/**
 * Pill shape (fully rounded ends).
 */
val PillShape = RoundedCornerShape(percent = 50)

/**
 * Card shape for action cards and containers.
 */
val CardShape = RoundedCornerShape(12.dp)

/**
 * Input field shape.
 */
val InputShape = RoundedCornerShape(24.dp)

/**
 * Sheet shape for bottom sheets.
 */
val SheetShape = RoundedCornerShape(
    topStart = 20.dp,
    topEnd = 20.dp,
    bottomStart = 0.dp,
    bottomEnd = 0.dp
)
