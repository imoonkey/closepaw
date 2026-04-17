package ai.closepaw.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================
// Clean Light Theme - Clear, Inviting, Modern
// Inspired by ChatGPT, Claude, modern AI apps
// High clarity, warm neutrals, good contrast
// ============================================

// Background & Surface - Clean whites
val Background = Color(0xFFFFFFFF)          // Pure white
val Surface = Color(0xFFFFFFFF)             // Same as background
val SurfaceVariant = Color(0xFFF7F7F8)      // Light warm gray (visible!)
val SurfaceElevated = Color(0xFFFFFFFF)

// Primary - For send button & user messages (warm, not too dark)
val Primary = Color(0xFF3B3B3B)             // Soft black (readable, not harsh)
val PrimaryVariant = Color(0xFF2D2D2D)      // Slightly darker
val OnPrimary = Color(0xFFFFFFFF)

// Accent - Teal/green for active states (fresh, not muted)
val Accent = Color(0xFF10A37F)              // ChatGPT green (recognizable)
val AccentMuted = Color(0xFF5D5D5D)         // Neutral gray
val AccentSoft = Color(0xFFF0F0F0)          // Light background

// Secondary - For success states
val Secondary = Color(0xFF10A37F)           // Same teal
val SecondaryLight = Color(0xFFE6F4F1)      // Light teal bg

// Text hierarchy - High contrast, readable
val TextPrimary = Color(0xFF0D0D0D)         // Near black (clear!)
val TextSecondary = Color(0xFF5D5D5D)       // Medium gray (visible!)
val TextMuted = Color(0xFF8E8E8E)           // Light gray (still visible)
val TextPlaceholder = Color(0xFFB4B4B4)     // Placeholder gray

// Borders & Dividers - Visible but subtle
val Border = Color(0xFFE5E5E5)              // Clear border
val BorderFocused = Color(0xFFCCCCCC)       // Darker when focused
val Divider = Color(0xFFEEEEEE)             // Subtle divider

// Status colors - Clear, not muted
val StatusSuccess = Color(0xFF10A37F)       // Teal green
val StatusSuccessBg = Color(0xFFE6F4F1)     // Light teal
val StatusWarning = Color(0xFFF5A623)       // Warm amber
val StatusWarningBg = Color(0xFFFFF8E6)     // Light amber
val StatusError = Color(0xFFEF4146)         // Clear red
val StatusErrorBg = Color(0xFFFEEEEF)       // Light red
val StatusInfo = Color(0xFF2563EB)          // Blue
val StatusInfoBg = Color(0xFFEFF6FF)        // Light blue

// Interactive states
val Hover = Color(0xFFF7F7F8)               // Light hover
val Pressed = Color(0xFFEEEEEE)             // Pressed state
val Disabled = Color(0xFFE5E5E5)            // Disabled bg
val DisabledText = Color(0xFFB4B4B4)        // Disabled text

// Shadows
val ShadowColor = Color(0x0A000000)         // Subtle shadow

// ============================================
// Chat UI Colors - Clean, Clear Theme
// High contrast, warm tones, inviting
// ============================================

// Primary - For send button (dark for contrast)
val ChatPrimary = Color(0xFF3B3B3B)         // Soft black for send button
val ChatOnPrimary = Color.White
val ChatPrimaryContainer = Color(0xFFF0F0F0) // Light gray for chips
val ChatOnPrimaryContainer = Color(0xFF3B3B3B) // Dark text on light

// User bubble - Light, fresh (like modern chat apps)
val UserBubble = Color(0xFFEFEFEF)          // Light gray - clean, modern
val UserBubbleText = Color(0xFF1A1A1A)      // Dark text on light bubble

// Secondary - Fresh teal for accents
val ChatSecondary = Color(0xFF10A37F)       // ChatGPT teal
val ChatOnSecondary = Color.White
val ChatSecondaryContainer = Color(0xFFE6F4F1) // Light teal
val ChatOnSecondaryContainer = Color(0xFF0D7355) // Dark teal

// Success
val ChatSuccess = Color(0xFF10A37F)         // Teal
val ChatSuccessBg = Color(0xFFE6F4F1)       // Light teal

// Error
val ChatError = Color(0xFFEF4146)           // Clear red
val ChatErrorBg = Color(0xFFFEEEEF)         // Light red

// Warning
val ChatWarning = Color(0xFFF5A623)         // Warm amber
val ChatWarningBg = Color(0xFFFFF8E6)       // Light amber

// Surface - Clean whites
val ChatSurface = Color(0xFFFFFFFF)         // Pure white
val ChatSurfaceVariant = Color(0xFFF7F7F8)  // Visible light gray
val ChatOnSurface = Color(0xFF0D0D0D)       // Near black - readable!
val ChatOnSurfaceVariant = Color(0xFF5D5D5D) // Medium gray - visible!

// Background - Pure white
val ChatBackground = Color(0xFFFFFFFF)      // Pure white
val ChatOnBackground = Color(0xFF0D0D0D)    // Near black

// Outline - Visible borders
val ChatOutline = Color(0xFFE5E5E5)         // Clear border
val ChatOutlineVariant = Color(0xFFEEEEEE)  // Subtle variant

// Icon tints - Visible, not washed out
// Note: ChatIconPrimary/Secondary are part of the design system for icon tints
// even if not currently used - reserved for future consistency
val ChatIconPrimary = Color(0xFF5D5D5D)     // Medium gray - visible!
val ChatIconSecondary = Color(0xFF8E8E8E)   // Lighter but still visible

// Send button active state (ChatGPT-style: pure black/white for high contrast)
// These are intentionally high-contrast for the send button when text is entered
val ChatSendButtonActive = Color(0xFF000000)     // Pure black when has text
val ChatSendButtonOnActive = Color(0xFFFFFFFF)   // Pure white icon on black

// ============================================
// Chat UI Dark Theme Colors - Clean Dark
// ============================================

// Primary - Light for dark mode
val ChatPrimaryDark = Color(0xFFEEEEEE)     // Light gray
val ChatOnPrimaryDark = Color(0xFF1A1A1A)   // Dark text
val ChatPrimaryContainerDark = Color(0xFF2D2D2D) // Dark container
val ChatOnPrimaryContainerDark = Color(0xFFEEEEEE) // Light text

// Secondary - Teal in dark mode
val ChatSecondaryDark = Color(0xFF4ADE9E)   // Lighter teal
val ChatOnSecondaryDark = Color(0xFF052E20) // Dark teal text
val ChatSecondaryContainerDark = Color(0xFF0D7355) // Medium teal
val ChatOnSecondaryContainerDark = Color(0xFFE6F4F1) // Light teal text

// Surface - Dark grays
val ChatSurfaceDark = Color(0xFF1A1A1A)     // Dark surface
val ChatSurfaceVariantDark = Color(0xFF2D2D2D) // Slightly lighter
val ChatOnSurfaceDark = Color(0xFFEEEEEE)   // Light text
val ChatOnSurfaceVariantDark = Color(0xFFB4B4B4) // Medium text

// Background
val ChatBackgroundDark = Color(0xFF0D0D0D)  // Near black
val ChatOnBackgroundDark = Color(0xFFEEEEEE) // Light text

// Error
val ChatErrorDark = Color(0xFFFF6B6B)       // Light red
val ChatErrorContainerDark = Color(0xFF5C1E1E) // Dark red

// Outline
val ChatOutlineDark = Color(0xFF3D3D3D)     // Dark border
val ChatOutlineVariantDark = Color(0xFF2D2D2D) // Subtle border

// Icon tints - Dark mode
val ChatIconPrimaryDark = Color(0xFFB4B4B4) // Visible gray
val ChatIconSecondaryDark = Color(0xFF8E8E8E) // Slightly darker
