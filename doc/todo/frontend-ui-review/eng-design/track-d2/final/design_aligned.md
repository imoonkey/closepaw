# Track D2 — Visual Implementation Eng Design (Aligned Draft 1)

**Authors:** Codex + Claude  
**First mover:** Codex  
**Date:** 2026-04-20  
**Status:** First aligned draft (design-only)

This document is the self-contained source of truth for Track D2. When an open question gets resolved later, update this document first, then remove or revise the corresponding item in `Open Questions`.

## Goal

Implement the D1 visual baseline in Compose with the smallest architecture that can carry:

- D1 palette, typography, spacing, shape, and motion
- Track A chat-row styling (`Thought`, `Action`, `Final`, streaming cursor, row collapse)
- capsule and overlay styling
- later rollout to settings, drawer, and onboarding

The problem is not "we need a design system." The problem is that visual choices are split across `ui/theme`, `ui/chat`, `ui/capsule`, and `ui/overlay` as hardcoded colors, radii, spacing, font sizes, and animation timings.

## Design Rules

1. **Material first.** Use `MaterialTheme` wherever Material already has the right slot.
2. **One extra token surface only.** Add one thin app token accessor for the residue Material cannot express cleanly.
3. **One motion surface only.** Motion lives in one file/object. No motion Locals.
4. **No parallel design-system module.** Reusable styled components stay feature-local unless a second real caller appears.
5. **No visual values in render models.** Non-Compose models carry semantic state, not palette values or alpha knobs.
6. **No long-lived compatibility shims.** Any temporary alias used during migration must be tightly bounded and deleted before Track D2 closes.

## 1. Theme and Token API Surface

### Public API

```kotlin
ClosePawTheme { ... }

MaterialTheme.colorScheme
MaterialTheme.typography
MaterialTheme.shapes
MaterialTheme.closePaw
ClosePawMotion
```

### Decision

Use `MaterialTheme` for standard colors, typography, and shapes, plus exactly one thin extension accessor for the non-Material residue:

```kotlin
val MaterialTheme.closePaw: ClosePawTokens
```

That accessor may be backed by a single `CompositionLocal`, but the caller should see one extension surface, not a second theme system.

Rejected:

- pure Material-only theming
- per-domain Locals (`LocalSpacing`, `LocalTypographyExtras`, `LocalMotion`, etc.)
- per-component style objects (`CapsuleStyle`, `ChatRowStyle`, ...)
- a new shared design-system module

### `ClosePawTokens`

Keep the extra token bag small. It only carries what Material does not model well:

```kotlin
@Immutable
data class ClosePawTokens(
    val inkFaint: Color,
    val bodyItalic: TextStyle,
    val serifItalic: TextStyle,
    val monoBody: TextStyle,
    val monoSmall: TextStyle,
    val spacing: ClosePawSpacing,
)
```

`ClosePawSpacing` is the D1 five-step scale:

- `xs = 4.dp`
- `sm = 8.dp`
- `md = 12.dp`
- `lg = 20.dp`
- `xl = 32.dp`

### Color mapping

Map D1 directly onto Material roles:

| D1 token | Material role |
|---|---|
| `Paper` | `background`, `surface`, `surfaceContainerLowest` |
| `PaperInset` | `surfaceVariant`, `surfaceContainerLow`, `surfaceContainer` |
| `Ink` | `onBackground`, `onSurface` |
| `InkMuted` | `onSurfaceVariant` |
| `Claw` | `primary` |
| `Moss` | `secondary` |
| `Amber` | `tertiary` |
| `Rust` | `error` |
| `Hairline` | `outline` |
| `InkGhost` | `outlineVariant` |

`InkFaint` stays in `ClosePawTokens` because it is a text role, not a standard Material slot.

### Typography

Three font families:

- Geist for operational UI text
- Fraunces for identity-facing text
- JetBrains Mono for machine text

**All Material `Typography` slots (`display*`, `headline*`, `title*`, `body*`, `label*`) use Geist.** Fraunces is **not** wired into any Material slot. Identity-facing text reaches Fraunces explicitly through:

- `ClosePawTokens.serifItalic` — used on the empty-state question and any other deliberately identity-tagged serif text surface.
- Local `TextStyle` declarations on the empty state and onboarding watermark, where the surface owns its own identity-tier typography.

Rationale: D1 §4.2 reserves Fraunces for identity surfaces. Routing generic Material slots through Fraunces would silently apply serif to anything calling `MaterialTheme.typography.headlineMedium`, contradicting D1's identity-scarcity rule. Keeping Material slots Geist-only forces every Fraunces use to be a deliberate, named decision.

Track A typography is locked:

- `Thought` uses `ClosePawTokens.bodyItalic` (Geist italic)
- `Action` uses `ClosePawTokens.monoBody`
- `Final` uses `MaterialTheme.typography.bodyLarge` (Geist regular)
- the streaming cursor uses a Fraunces-backed local style — see §2

Font assets are a Phase 1 prerequisite and must be bundled under `app/src/main/res/font/` with the required license attribution (Fraunces SIL OFL, Geist SIL OFL, JetBrains Mono Apache 2.0). Do not introduce downloadable-font machinery.

### Shapes

Use Material `Shapes` only:

- `small = 8.dp`
- `medium = 10.dp`
- `large = 16.dp`

Interpretation:

- `small` for controls and fields
- `medium` for card-like surfaces and the user bubble
- `large` for capsule / pill-like chrome

Legacy shape globals such as `BubbleShapeUser`, `BubbleShapeAgent`, `CapsuleShape`, `CardShape`, and `InputShape` should be removed by the end of migration.

## 2. Motion

Put motion in `ui/theme/Motion.kt` as a plain object:

```kotlin
object ClosePawMotion
```

It owns the D1 contract:

- durations: `120 / 240 / 480 / 900`
- easings: `EaseInOutSine`, `EaseOutCubic`
- no springs
- reduced-motion behavior must be centralized instead of re-decided ad hoc in each feature

### Required motion primitives

The aligned motion layer must cover these reusable cases:

- 120ms status/glyph flip
- 240ms trace-item enter
- 240ms row expand/collapse
- 240ms settings-style page slide
- 240ms surface/content/color transition
- 480ms cursor blink
- 480ms thinking pulse
- 900ms capsule breath in running mode only
- 900ms glow pulse
- 480ms overlay fade-out

No extra motion categories should be added unless a real second caller needs them.

### Surface wiring

**Chat**

- Track A trace items use the 8dp enter motion from the D1 contract
- row collapse/expand uses the shared row transition
- action status changes use the shared 120ms flip
- final-block cursor uses the shared blink cadence (see Streaming cursor below)

**Capsule**

- mode/content/color changes use the shared surface transition
- running state uses the shared breath cadence
- divider/body/control-row appearance uses the shared row transition

**Overlay**

- glow pulse and fade timings move onto `ClosePawMotion`
- island/glow hosts stop owning their own ad-hoc timing numbers

**Settings**

- page transitions move onto the shared page-slide motion instead of local timing constants

### Streaming cursor

The Final block is read-only `Text`, **not** an editable `TextField`, so Compose's text-cursor restyle limitation does not apply. The "cursor" is a glyph rendered inside the text layout via `inlineContent`, with a placeholder anchored at the end of the streaming string:

```kotlin
// Sketch — not part of the design surface, just to lock the approach.
val annotated = buildAnnotatedString {
    append(streamedText)
    appendInlineContent("cursor", "|")
}
val inline = mapOf(
    "cursor" to InlineTextContent(
        placeholder = Placeholder(
            width = 0.5.em,
            height = 1.em,
            placeholderVerticalAlign = /* chosen during implementation verification */,
        ),
        children = { BlinkingPipe(frauncesCursorStyle) },
    ),
)
Text(annotated, inlineContent = inline, style = MaterialTheme.typography.bodyLarge)
```

`inlineContent` puts the cursor inside the text layout, so it follows reflow and lands on the correct visual line regardless of where the stream ends. Blink is `infiniteTransition` with `tween(480ms, Linear, repeatMode = Reverse)` on alpha — the shared `cursorBlink` cadence from §2.

Verification gate (Phase D2-4 acceptance): on real devices, confirm Fraunces baseline metrics align with Geist body in this `inlineContent` slot and confirm the chosen `placeholderVerticalAlign` actually lands on the correct visual baseline. If the I-beam sits visibly off-baseline and cannot be corrected cleanly, the fallback is a one-line style swap to a Geist `|` at the same blink cadence. The design does not branch on surface shape — only the cursor font choice may fall back.

### Folded-paper primitive

D1 §4.4 requires "subtle warm under-shadow plus a top hairline" on the capsule and on the modal sheet/drawer. Ship this as a single `Modifier`, not as a wrapping composable:

```kotlin
@Composable
fun Modifier.foldedPaper(shape: Shape = MaterialTheme.shapes.large): Modifier {
    val warm = inkDerivedWarmShadowColor           // chosen from theme during implementation
    val hairline = MaterialTheme.colorScheme.outline
    val strokePx = with(LocalDensity.current) { 1.dp.toPx() }
    return this
        .shadow(elevation = 4.dp, shape = shape, ambientColor = warm, spotColor = warm)
        .drawWithContent {
            drawContent()
            drawLine(hairline, Offset.Zero, Offset(size.width, 0f), strokePx)
        }
}
```

Why `Modifier` over a `FoldedPaperSurface` composable:

- The capsule and modal drawer are already `Surface`-bearing composables. A wrapper composable would either nest two Surfaces or replace the existing one — both cost more than chaining `.foldedPaper()` onto the existing call site.
- Compose 1.4+ supports `ambientColor`/`spotColor` on `shadow()`, so the warm tint is native. No custom `Paint`.
- One function, two callers, no content-slot/click-forwarding/accessibility plumbing to reinvent.

This is the only shared chrome primitive Track D2 ships. It lives in `ui/theme/Tokens.kt` alongside `ClosePawTokens`.

### Reduced motion

`ClosePawMotion` exposes a single helper:

```kotlin
@Composable
fun reducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}
```

Each motion call site reads it once and picks: full motion or `EnterTransition.None + fadeIn(120ms)` / skip the `infiniteTransition` entirely. There is no global wrapper that mutates every transition — that hides what each component actually does and makes intentional always-on motion (e.g., the streaming cursor as a liveness signal) hard to opt out of suppression.

D1 §8 reduced-motion contract:

- trace enter → instant + 120ms fade
- collapse/expand → instant
- capsule breath → static paw at full alpha
- decorative looping motion (glow pulse, thinking pulse) → paused at neutral state
- streaming cursor → continues blinking (liveness signal, not decoration)

## 3. File Structure

Keep the existing theme folder. Add only the minimum new files.

### Theme

`app/src/main/kotlin/ai/closepaw/ui/theme/`

- `Theme.kt`
  - `ClosePawTheme(...)`
  - Material light/dark mapping
  - `MaterialTheme.closePaw`
- `Color.kt`
  - D1 palette values only
- `Type.kt`
  - Material typography plus the D1 font families
- `Shape.kt`
  - three Material shapes only
- `Tokens.kt`
  - `ClosePawTokens`
  - `ClosePawSpacing`
- `Motion.kt`
  - `ClosePawMotion`
- `WindowInsets.kt`
  - unchanged

`app/src/main/res/font/`

- Geist
- Fraunces
- JetBrains Mono

### Reusable styled components

Do **not** create a shared `ui/components/` or design-system module up front.

Rules:

- chat-specific styled components stay in `ui/chat/components/`
- capsule/overlay-specific styled components stay in `ui/capsule/` or `ui/overlay/`
- the only shared visual primitive Track D2 ships is `Modifier.foldedPaper()` (defined in `ui/theme/Tokens.kt`, see §2)

Everything else stays feature-local.

## 4. Model / Renderer Boundary

The aligned draft adopts the stronger model cleanup from the Codex draft.

These values should not survive in non-Compose render models:

- `CapsuleColors`
- `GlowState.colorHex`
- `CapsuleRenderSpec.DotSpec.color`
- `CapsuleRenderSpec.ThoughtSpec.alpha`

Target rule:

- models express semantic state (`running`, `paused`, `error`, `success`, `dimmed`, etc.)
- Compose renderers resolve actual colors, alpha, and chrome from theme + state

This applies to:

- `ui/overlay/model/*`
- `ui/capsule/surface/*`
- `ui/overlay/compose/*`

## 5. Migration Plan

### Phase D2-1 — Theme foundation

**Scope**

- `ui/theme/*`
- root callers (`MainActivityContent`, overlay compose host entry points)
- `res/font/`
- mechanical sweep of all visual call sites (see below)

**Changes**

- rename `ChatTheme` to `ClosePawTheme`
- rewrite theme files around the D1 palette / shape / typography mapping
- add bundled font assets and license attribution
- add `Tokens.kt` (including `ClosePawTokens`, `ClosePawSpacing`, `Modifier.foldedPaper()`)
- add `Motion.kt` (full population: durations, easings, primitives, `reducedMotion()` helper)
- mechanically replace call-site imports of legacy color/shape symbols (`ChatPrimary`, `ChatSurface`, `BubbleShapeUser`, `CapsuleShape`, `CardShape`, `InputShape`, etc.) with their Material slot or `MaterialTheme.closePaw` equivalents. Commit this sweep separately within the same PR for reviewability.

**Direct sweep is the default.** Do not plan a standing alias layer as part of the design. If implementation proves the Phase D2-1 PR unreadable without a short-lived bridge, a temporary alias window is allowed only as migration scaffolding and must be deleted before D2-1 closes.

**Acceptance**

- project builds and passes lint
- D1 theme values exist in one place (`ui/theme/`)
- no legacy color/shape symbol survives anywhere in `app/src/main/kotlin/` (`grep -rn "ChatPrimary\|BubbleShape\|CapsuleShape\|CardShape\|InputShape" app/src/main/kotlin/` returns zero hits)
- app still renders (visible diff: D1 palette/typography in place; deeper styling lands in later phases)

### Phase D2-2 — Semantic visual model cleanup

**Scope**

- `ui/overlay/model/*`
- `ui/capsule/surface/*`
- `ui/overlay/compose/*`

**Changes**

- remove raw palette values from render models
- replace visual `Int` colors / alpha knobs with semantic state
- make Compose the owner of theme lookup

**Acceptance**

- non-Compose models do not carry raw palette values
- overlay/capsule renderers derive color/alpha from theme + mode

### Phase D2-3 — Capsule and overlay restyle

**Scope**

- `ui/capsule/surface/*`
- `ui/overlay/compose/GlowOverlayHost.kt`
- `ui/overlay/compose/EdgeGlowCompose.kt`
- `ui/overlay/compose/IslandOverlayHost.kt`

**Changes**

- replace hardcoded radii, spacing, typography, divider alpha, and motion timings with theme tokens and `ClosePawMotion` primitives
- apply D1 capsule styling (folded-paper modifier, paw glyph, claw-only-when-running)
- move glow/island styling onto theme + motion (D1 §5.3 action visualizer simplifications)

**Acceptance**

- capsule and overlay use D1 palette, shapes, typography, and motion
- no local ad-hoc timing constants remain in migrated files
- no `Color(0x...)` literals remain in migrated files

### Phase D2-4 — Chat migration onto Track A

**Scope**

- `ui/chat/*`

**Changes**

- replace the old bubble/action-card presentation with Track A’s row model
- retire `ActionCard.kt` as the main action presentation path
- migrate `StreamingText` / `ThinkingIndicator` to Track A-compatible versions
- use D2 theme tokens and motion from day one
- implement the `inlineContent` streaming cursor (see §2)

**Acceptance**

- chat styling matches Track A + D1
- no local chat file owns its own palette/timing constants
- streaming cursor verified on real devices (see §2 verification gate); fallback to Geist `|` recorded in this doc if Fraunces baseline cannot be aligned

### Phase D2-5 — Settings, drawer, and onboarding rollout

**Scope**

- `ui/settings/*`
- `ui/navigation/*`
- `ui/onboarding/*`

**Changes**

- apply D1 editorial baseline to lower-frequency surfaces
- move settings page transitions to the shared motion layer

**Acceptance**

- lower-frequency surfaces do not remain on the old token vocabulary
- settings page transitions use `ClosePawMotion` page-slide

### Phase D2-6 — Contrast handoff

**Scope**

- `doc/todo/frontend-ui-review/aligned/contrast-matrix.md`

**Changes**

- measure contrast for `Ink`, `InkMuted`, `Claw`, `Moss`, `Amber`, `Rust` on `Paper` and `PaperInset`
- cover light and dark theme variants

**Acceptance**

- AA minimum for body text
- AA-large minimum for status text
- contrast artifact exists before the track is considered complete

## 6. Tasks

| Slug | Scope | Acceptance | Depends on |
|---|---|---|---|
| `d2-1-theme-foundation` | `ui/theme/**`, root theme callers, `res/font/`, mechanical sweep across `app/src/main/kotlin/**` | `ClosePawTheme` exists; tokens + motion fully populated; fonts wired with attribution; zero legacy color/shape symbols remain | none |
| `d2-2-semantic-visual-model-cleanup` | `ui/overlay/model/**`, `ui/capsule/surface/**`, `ui/overlay/compose/**` | no raw palette values in non-Compose render models | `d2-1-theme-foundation` |
| `d2-3-capsule-overlay-restyle` | capsule surface files, glow/island hosts | capsule and overlay consume theme + motion; no `Color(0x..)` literals; no local timing constants | `d2-2-semantic-visual-model-cleanup` |
| `d2-4-chat-track-a-restyle` | `ui/chat/**` | Track A row styling lands on D2 theme/motion; `inlineContent` streaming cursor implemented and verified on device; legacy action-card path retired | `d2-1-theme-foundation`, Track A implementation |
| `d2-5-settings-drawer-onboarding-rollout` | `ui/settings/**`, `ui/navigation/**`, `ui/onboarding/**` | lower-frequency surfaces migrate to D1 editorial baseline; settings transitions use `ClosePawMotion` page-slide | `d2-1-theme-foundation` |
| `d2-6-contrast-handoff` | `doc/todo/frontend-ui-review/aligned/contrast-matrix.md` | measured contrast artifact exists for light + dark pairs | `d2-1-theme-foundation` |

## 7. Trade-offs

### Rejected: pure Material-only theming

Too lossy. D1 needs extra text roles, spacing tokens, and one extra text color role that do not fit cleanly into standard Material slots.

### Rejected: multi-Local token domains

Too much ceremony for too little data. One thin extra token surface is enough.

### Rejected: per-component style objects

They add plumbing between theme values and call sites without solving a real problem in this codebase.

### Rejected: shared component module up front

It creates framework weight before the second caller exists. Feature-local placement is simpler.

### Rejected: temporary migration aliases

The aligned draft does **not** plan alias layers as part of the target architecture. Direct sweep is cheaper. If implementation needs a brief bridge for reviewability, that bridge is temporary scaffolding only and must be deleted before D2-1 closes. See Phase D2-1 for the sweep contract.

## Open Questions

1. **Streaming cursor verification:** The aligned design has settled the cursor surface (`inlineContent` inside the text layout), but two implementation details remain unresolved until Phase D2-4: the exact `placeholderVerticalAlign` that best matches the real font metrics, and whether Fraunces aligns cleanly enough on device or must fall back to Geist.
