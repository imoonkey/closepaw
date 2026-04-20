package ai.closepaw.ui.theme

import androidx.compose.ui.graphics.Color

// D1 palette — see doc/todo/frontend-ui-review/aligned/design_aligned.md §4.1.

// Light ("Paper")
val Paper = Color(0xFFF5F1EA)
val PaperInset = Color(0xFFEDE7DC)
val Ink = Color(0xFF14110F)
val InkMuted = Color(0xFF5C554C)
val InkFaint = Color(0xFF8B8278)
val Claw = Color(0xFFC44528)
val Moss = Color(0xFF4A5D3A)
val Amber = Color(0xFFE8A33D)
val Rust = Color(0xFF8B2E1F)
val Hairline = Ink.copy(alpha = 0.12f)
val InkGhost = Ink.copy(alpha = 0.08f)

// Material *Container slots — Paper warm-tinted to keep editorial palette
// consistent across FilledTonalButton / FilterChip(selected) / error cards.
// Avoids Material 3's default lavender leak. See QA report I-1.
val PrimaryContainerLight = Color(0xFFEBCFC3)   // Claw-tinted Paper
val TertiaryContainerLight = Color(0xFFF2E2C7)  // Amber-tinted Paper
val ErrorContainerLight = Color(0xFFE0CAC1)     // Rust-tinted Paper

// Dark ("Lantern") — separate, not inverted.
val PaperDark = Color(0xFF0F0D0B)
val PaperInsetDark = Color(0xFF1A1612)
val InkDark = Color(0xFFF0EAE0)
val InkMutedDark = Color(0xFFB9B0A3)
val InkFaintDark = Color(0xFF7A7268)
val ClawDark = Color(0xFFE56B4A)
val MossDark = Color(0xFF7A9466)
val AmberDark = Color(0xFFF2B960)
val RustDark = Color(0xFFD55A42)
val HairlineDark = InkDark.copy(alpha = 0.12f)
val InkGhostDark = InkDark.copy(alpha = 0.08f)

val PrimaryContainerDark = Color(0xFF3A2018)
val TertiaryContainerDark = Color(0xFF3C2F1C)
val ErrorContainerDark = Color(0xFF361C16)
