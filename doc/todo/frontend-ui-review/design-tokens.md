# Design Tokens — "Tactile Intelligence"

Drop-in replacements for `ui/theme/Color.kt`, `Type.kt`, `Shape.kt`.
Every value is chosen — none are defaults.

---

## 1. Color — "Ink on bone, claw on paper"

### Light mode (default — "Paper")

```kotlin
// Canvas
val Paper         = Color(0xFFF5F1EA)  // warm bone; replaces pure white
val PaperInset    = Color(0xFFEDE7DC)  // user bubble, inset cards
val PaperDeep     = Color(0xFFE5DED1)  // pressed / hover
val Hairline      = Color(0x1F14110F)  // 12% ink — borders, dividers

// Ink (text + primary)
val Ink           = Color(0xFF14110F)  // deep warm black (not #000)
val InkMuted      = Color(0xFF5C554C)  // secondary text, subtitles
val InkFaint      = Color(0xFF8B8278)  // placeholder, tertiary
val InkGhost      = Color(0x1414110F)  // 8% — watermark paw, micro-dividers

// Claw (THE accent — used sparingly: Running, primary CTA, presence dot)
val Claw          = Color(0xFFC44528)  // burnt sienna
val ClawDeep      = Color(0xFFA83820)  // pressed
val ClawSoft      = Color(0x1FC44528)  // 12% — background tint for running

// Semantic (replace blue/teal/amber/red)
val Moss          = Color(0xFF4A5D3A)  // success (replaces teal)
val MossSoft      = Color(0x1F4A5D3A)
val Amber         = Color(0xFFE8A33D)  // pause / takeover (warmer)
val AmberSoft     = Color(0x1FE8A33D)
val Rust          = Color(0xFF8B2E1F)  // error (deep, not fire-engine)
val RustSoft      = Color(0x1F8B2E1F)

// Shadow — warm, directional, tactile
val ShadowInk     = Color(0x1414110F)  // 8% warm brown, not neutral gray
val ShadowUnder   = Color(0x0F14110F)  // "under-shadow" for folded-paper lift
```

### Dark mode (separate palette — "Lantern", not inverted)

```kotlin
val PaperDark      = Color(0xFF0F0D0B)  // deep walnut, not #000
val PaperInsetDark = Color(0xFF1A1612)
val PaperDeepDark  = Color(0xFF221C17)
val HairlineDark   = Color(0x1FF0EAE0)  // 12% ink-in-dark

val InkDark        = Color(0xFFF0EAE0)  // warm bone (reads as light ink)
val InkMutedDark   = Color(0xFFB9B0A3)
val InkFaintDark   = Color(0xFF7A7268)

val ClawDark       = Color(0xFFE56B4A)  // glows warmer in dark
val ClawDeepDark   = Color(0xFFC44528)
val ClawSoftDark   = Color(0x24E56B4A)

val MossDark       = Color(0xFF7A9466)
val AmberDark      = Color(0xFFF2B960)
val RustDark       = Color(0xFFD55A42)
```

### Semantic → mode mapping (Capsule)

| Capsule mode | Color | Token |
|---|---|---|
| Running | Claw (breathing paw) | `Claw` |
| Takeover / Paused | Amber (frozen paw) | `Amber` |
| WaitingForInput | Ink (no paw, text only) | `Ink` |
| WaitingForApproval | Amber + Rust border | `Amber` / `Rust` |
| Done | Moss (blink-once paw) | `Moss` |
| Error | Rust (shake paw) | `Rust` |

Rule: **Claw is only ever on screen for one of: Running capsule, primary
CTA button, watermark paw-print, new-entry button.** Never more than
two claw-colored elements visible at once.

---

## 2. Typography — three voices

### Fonts to add to `app/src/main/res/font/`

1. **Fraunces** — variable serif (OFL, free). Download from Google Fonts → export as `fraunces_variable.ttf` with axes `wght, opsz, SOFT, WONK`.
2. **Geist** — variable sans (OFL, free). Vercel font — `geist_variable.ttf`.
   - Alternative: **Inter Tight** if Geist feels too recent.
3. **JetBrains Mono** — monospace (OFL, free). `jetbrains_mono_variable.ttf`.

### Compose setup (`Type.kt`)

```kotlin
val Fraunces = FontFamily(Font(R.font.fraunces_variable, variationSettings = …))
val Geist    = FontFamily(Font(R.font.geist_variable, …))
val Mono     = FontFamily(Font(R.font.jetbrains_mono_variable, …))

val AgentTypography = Typography(
    // Display — Fraunces, optical size large, soft axis mid
    displayLarge  = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Bold,
                              fontSize = 48.sp, lineHeight = 52.sp,
                              letterSpacing = (-0.8).sp),
    displayMedium = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold,
                              fontSize = 34.sp, lineHeight = 40.sp,
                              letterSpacing = (-0.4).sp),
    displaySmall  = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold,
                              fontSize = 26.sp, lineHeight = 32.sp),

    // Headlines — Fraunces for identity surfaces (empty state, onboarding, section heads)
    headlineLarge  = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold,
                               fontSize = 22.sp, lineHeight = 28.sp),
    headlineMedium = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium,
                               fontSize = 18.sp, lineHeight = 24.sp),
    headlineSmall  = TextStyle(fontFamily = Geist,    fontWeight = FontWeight.Medium,
                               fontSize = 16.sp, lineHeight = 22.sp),

    // Titles — Geist, UI chrome
    titleLarge  = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp, lineHeight = 22.sp),
    titleMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium,
                            fontSize = 15.sp, lineHeight = 20.sp),
    titleSmall  = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium,
                            fontSize = 13.sp, lineHeight = 18.sp,
                            letterSpacing = 0.2.sp),

    // Body — Geist for user-facing prose
    bodyLarge  = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal,
                           fontSize = 16.sp, lineHeight = 24.sp,
                           letterSpacing = 0.1.sp),
    bodyMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal,
                           fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall  = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal,
                           fontSize = 12.sp, lineHeight = 16.sp),

    // Labels — Geist medium, tracked-out caps for telegrams
    labelLarge  = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium,
                            fontSize = 13.sp, letterSpacing = 0.3.sp),
    labelMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium,
                            fontSize = 11.sp, letterSpacing = 0.8.sp),
    labelSmall  = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium,
                            fontSize = 10.sp, letterSpacing = 1.2.sp),  // ALL CAPS use
)

// Additional non-Material3 styles, exposed via a LocalAgentTypography:
data class AgentExtraTypography(
    val monoBody:       TextStyle,  // JetBrains Mono 13sp — ActionCard, shell output
    val monoSmall:      TextStyle,  // JetBrains Mono 11sp — ledger dates, tool names
    val serifItalic:    TextStyle,  // Fraunces Italic — empty state question
    val tgmTelegram:    TextStyle,  // Geist 11sp, ALL CAPS, 1.2sp tracking — repair card
    val romanNumeral:   TextStyle,  // Fraunces 120sp — onboarding chapter watermark
)
```

### Voice zoning — who speaks where

| Where | Voice |
|---|---|
| App titles, empty state, onboarding chapters, section heads | **Fraunces** (serif) — product identity |
| All UI chrome, chat body, buttons, settings rows | **Geist** (sans) — operational clarity |
| Agent thought-during-execution, ActionCard tool names, shell output, ledger dates, API-key fields | **JetBrains Mono** — "the machine speaking" |

---

## 3. Shape

```kotlin
val AgentShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),   // chips
    small      = RoundedCornerShape(8.dp),   // buttons, text fields
    medium     = RoundedCornerShape(10.dp),  // cards, capsule pill
    large      = RoundedCornerShape(14.dp),  // sheets
    extraLarge = RoundedCornerShape(18.dp),  // drawer, modal
)

// Special shapes
val BubbleUser  = RoundedCornerShape(10.dp)       // symmetric, no "tail"
val CapsulePill = RoundedCornerShape(16.dp)       // flatter than current 20dp
val PawStamp    = RoundedCornerShape(50)          // circle — for toe-pads
```

Note: the current asymmetric `BubbleShapeUser` (6dp/20dp "tail" corner) is
a ChatGPT signature. Drop it — symmetric corners read as **written**, not
**messaged**.

---

## 4. The Paw-print glyph

Hand-draw as a vector drawable at `res/drawable/ic_paw.xml`. Geometry:

```
  ·   ·   ·      ← three toe-pads: r=2.2, centered at (-4,-3), (0,-4.2), (4,-3)
      ▃          ← main pad: rounded trapezoid, width 7, height 5, top radius 3, bottom radius 2
```

Three sizes in use:
- **10dp** — Capsule presence dot (breathing)
- **20dp** — Action Visualizer stamp at swipe destination
- **160dp** — Empty-state watermark (InkGhost, i.e. 8% opacity)

Always single-color (tint via `Color.Claw`, `Moss`, `Amber`, `Rust`, `InkGhost`).
Never gradient-filled. Never stroked — always filled — toe-pads and pad
separate paths so the agent can animate them independently (e.g. toe-pads
fill sequentially for the thinking indicator).

---

## 5. Elevation — "folded paper," not floating cards

Replace Material elevation with a two-layer tactile shadow:

```kotlin
Modifier
    .drawBehind {
        // Under-shadow — warm, directional, 2dp below
        drawRect(ShadowUnder, topLeft = Offset(0f, 2.dp.toPx()),
                 size = size.copy(height = size.height))
        // Hairline top edge — suggests paper thickness
        drawLine(Hairline, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f)
    }
```

No Material `shadow(4.dp)`. The only floating elements in the app become
the Capsule and the ModalDrawer — everything else sits flat on paper.

---

## 6. Spacing — editorial rhythm

Use a **4pt baseline grid** with a golden-ratio scale for large spaces:

```kotlin
object Spacing {
    val xs  = 4.dp
    val sm  = 8.dp
    val md  = 12.dp
    val lg  = 20.dp      // 12 × φ
    val xl  = 32.dp      // 20 × φ
    val xxl = 52.dp      // 32 × φ  — chapter spreads, empty state
}
```

Horizontal page padding: `Spacing.lg` (20dp) everywhere. Consistency is
luxury.
