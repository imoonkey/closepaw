# Track D2 — Visual Implementation Eng Design (Compose Theme / Tokens / Motion)

**Author:** Codex  
**Date:** 2026-04-20  
**Status:** Initial design  
**Scope:** Design only. No code changes.

## Goal

Implement the D1 visual baseline in Compose with the smallest architecture that can carry:

- D1 palette, typography, spacing, shape, and motion
- Track A chat-row styling (`Thought`, `Action`, `Final`, cursor, row collapse)
- Capsule styling and overlay glow cleanup
- Later rollout to settings / drawer / onboarding without inventing a parallel design system

The real problem is not missing framework. The problem is that visual decisions are split across `ui/theme`, `ui/chat`, `ui/capsule`, and `ui/overlay` as hardcoded colors, radii, font sizes, and animation timings.

## Current State

1. `ChatTheme` is a generic Material shell with a large chat-specific color list, but it does not model D1 semantics (`Paper`, `Ink`, `Claw`, `Hairline`, `InkGhost`, editorial typography roles).
2. Capsule and chat still hardcode style locally:
   - radii like `10.dp`, `12.dp`, `14.dp`, `16.dp`, `24.dp`
   - spacing like `6.dp`, `8.dp`, `12.dp`, `16.dp`
   - font sizes like `14.sp`
   - motion timings like `530ms`, `600ms`, `800ms`, `300/500ms`
3. Visual values leak into non-theme models:
   - `CapsuleColors`
   - `GlowState.colorHex`
   - `CapsuleRenderSpec.DotSpec.color`
   - `CapsuleRenderSpec.ThoughtSpec.alpha`
4. Chat still renders the older bubble/action-card model, while Track A requires a flat chronological row with inline trace items and a streaming final block.

## Decision

Use **MaterialTheme as the main API** and add **one thin app extension** for the tokens Material cannot express cleanly. Do **not** build a custom theme framework. Do **not** pass style objects down the tree.

### Why this wins

- Material already gives us the right primitives for most of D1: color scheme, typography, shapes, buttons, text fields, surfaces.
- D1 only needs a few extra tokens beyond Material: one softer text color, extra text styles, spacing scale, and motion specs.
- A CompositionLocal-heavy system would duplicate what `MaterialTheme` already does.
- Per-feature style objects would create plumbing noise in `ChatScreen`, `SmartCapsuleSurface`, overlay hosts, and settings pages.

## 1. Theme / Token API Surface

### Public surface

```kotlin
ClosePawTheme { ... }

MaterialTheme.colorScheme
MaterialTheme.typography
MaterialTheme.shapes
MaterialTheme.closePaw
ClosePawMotion
```

### Choice

Pick **MaterialTheme extension** as the API shape.

- `ClosePawTheme(...)` becomes the app theme entry point and replaces `ChatTheme`.
- Standard tokens stay on Material:
  - `colorScheme` for the main palette
  - `typography` for default text roles
  - `shapes` for the three D1 radii
- App-specific extras live behind **one** extension accessor:

```kotlin
val MaterialTheme.closePaw: ClosePawTokens
```

Internally this uses a single `CompositionLocal`, but that is an implementation detail. The caller sees one thin extension, not a second theme system.

### `ClosePawTokens`

Keep this small. It only holds what Material cannot carry without abuse:

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

`ClosePawSpacing` is the D1 five-step scale: `xs=4`, `sm=8`, `md=12`, `lg=20`, `xl=32`.

That is enough. No token explosion for per-screen colors, button variants, or component-local paddings.

### Material mapping

Map D1 directly onto Material roles:

| D1 token | Material role |
|---|---|
| `Paper` | `background`, `surface`, `surfaceContainerLowest` |
| `PaperInset` | `surfaceVariant`, `surfaceContainerLow`, `surfaceContainer` |
| `Ink` | `onBackground`, `onSurface` |
| `InkMuted` | `onSurfaceVariant` |
| `Hairline` | `outline` |
| `InkGhost` | `outlineVariant` |
| `Claw` | `primary` |
| `Moss` | `secondary` |
| `Amber` | `tertiary` |
| `Rust` | `error` |

`inkFaint` stays in `ClosePawTokens` because it is a text role, not a standard Material slot.

### Typography mapping

- Material base typography becomes the default UI language.
- `body*`, `label*`, `titleMedium`, `titleSmall` use **Geist**.
- Identity-facing slots (`display*`, `headline*`, `titleLarge`) use **Fraunces**.
- Extra D1 roles live in `MaterialTheme.closePaw`:
  - `bodyItalic` = Geist italic
  - `serifItalic` = Fraunces italic
  - `monoBody` = JetBrains Mono 13sp
  - `monoSmall` = JetBrains Mono 11sp

This gives Track A exactly what it needs without creating a parallel typography tree.

### Shapes

Use Material shapes only:

- `small = 8.dp`
- `medium = 10.dp`
- `large = 16.dp`

Interpretation:

- `small` = controls / fields
- `medium` = cards / user bubble
- `large` = capsule / pill-like chrome

Delete standalone shape globals like `BubbleShapeUser`, `BubbleShapeAgent`, `CapsuleShape`, `CardShape`, `InputShape` once consumers are migrated. Track A does not want an agent bubble at all, so keeping dedicated bubble-shape globals is dead weight.

## 2. Animation Primitives and D1 Motion Wiring

Put motion in one file:

`app/src/main/kotlin/ai/closepaw/ui/theme/Motion.kt`

Expose a plain object:

```kotlin
object ClosePawMotion
```

It owns the D1 contract:

- durations: `120 / 240 / 480 / 900`
- easings: `EaseInOutSine`, `EaseOutCubic`
- no springs

### Motion primitives

`ClosePawMotion` should provide reusable Compose specs, not another state model:

- `statusFlip`: 120ms cross-fade for glyph/status changes
- `traceEnter`: 240ms `slideInVertically(8dp) + fadeIn`
- `rowExpand`: 240ms expand/collapse + fade
- `pageSlide`: 240ms horizontal slide for settings-style page swaps
- `surfaceTransition`: 240ms color/alpha/content transitions for capsule mode changes and settings page swaps
- `cursorBlink`: 480ms reverse alpha loop
- `thinkingPulse`: 480ms alpha loop with 120ms stagger
- `capsuleBreath`: 900ms reverse loop, running mode only
- `glowPulse`: 900ms reverse alpha loop
- `overlayExit`: 480ms fade-out

### Wiring by surface

**Chat**

- Track A trace item entry uses `traceEnter`
- Row collapse/expand uses `rowExpand`
- Action status glyph changes use `statusFlip`
- Streaming final cursor uses `cursorBlink`
- Thinking indicator becomes a paw/toe indicator using `thinkingPulse`

**Capsule**

- Dot/paw color changes and content swaps use `surfaceTransition`
- Running-only breath uses `capsuleBreath`
- Divider/body/control row show/hide uses `rowExpand`

**Overlay glow**

- Replace current `300ms / 500ms / 800ms` timings in `GlowOverlayHost`
- Use 240ms enter fade, `overlayExit` for fade-out, `glowPulse` for active/executing pulse
- Keep success auto-hide delay as behavior; only the fade spec is part of motion

**Settings**

- `SettingsSheet` page transitions switch from ad-hoc slide timing to `pageSlide`

### Streaming cursor verification

D1 already flagged this correctly: the serif streaming cursor is a design goal, not a guaranteed Compose behavior.

Implementation rule:

1. Build cursor blinking through `ClosePawMotion.cursorBlink`
2. Try a Fraunces `|` in the Final block
3. If Compose baseline/alignment is unstable, fall back to a Geist `|` with the same timing

That fallback is acceptable and should be explicit in code review, not treated as a silent downgrade.

## 3. File Structure

Keep file count low. Reuse the current theme folder.

### Theme / tokens

`app/src/main/kotlin/ai/closepaw/ui/theme/`

- `Theme.kt`
  - `ClosePawTheme(...)`
  - light/dark Material mapping
  - `MaterialTheme.closePaw`
- `Color.kt`
  - D1 palette values only
- `Type.kt`
  - Material typography + extra D1 text styles
- `Shape.kt`
  - D1 three-radius Material `Shapes`
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

No font loader abstraction is needed. `Type.kt` should reference the font resources directly.

### Reusable styled components

Do **not** create a global design-system component package.

Rule:

- If a styled component is only used by chat, keep it in `ui/chat/components/`
- If it is only used by capsule/overlay, keep it in `ui/capsule/` or `ui/overlay/`
- Only create a shared component when at least two surfaces need the same non-trivial chrome

The only shared styled chrome I expect D2 to justify is:

- `ui/common/chrome/FoldedPaperSurface.kt`

That is it. Everything else should stay next to the owning feature.

Concrete placement:

- `ui/chat/components/`
  - new Track A row pieces
  - streaming final text
  - paw thinking indicator
- `ui/capsule/surface/`
  - capsule status, control, and input rows continue to live here
- `ui/overlay/compose/`
  - glow/island hosts stay here, but consume theme + motion instead of raw color ints/timings

## 4. Migration Plan

### Phase 1 — Replace the theme shell

Scope:

- `ui/theme/*`
- root callers (`MainActivityContent`, `OverlayComposeHost`)

Changes:

- Rename `ChatTheme` to `ClosePawTheme`
- Rewrite `Color.kt`, `Type.kt`, `Shape.kt` to match D1
- Add `Tokens.kt` and `Motion.kt`
- Keep callers compiling by preserving Material usage first

Acceptance:

- App still renders with Material components
- D1 palette/typography/shape values exist in one place
- No feature code depends on old `Chat*` color constants for new work

### Phase 2 — Remove visual values from models

Scope:

- `ui/overlay/model/*`
- `ui/capsule/surface/*`
- `ui/overlay/compose/*`

Changes:

- Delete `CapsuleColors`
- Remove `GlowState.colorHex`
- Replace `CapsuleRenderSpec.DotSpec.color` with semantic state (`status tone`) or derive tone directly from `CapsuleMode`
- Stop passing raw alpha through `ThoughtSpec`; dimming is applied in Compose based on mode

Acceptance:

- Non-Compose models express behavior/state, not palette values
- Compose renderers own all theme lookup

### Phase 3 — Migrate capsule styling and motion

Scope:

- `ui/capsule/surface/*`
- `ui/overlay/compose/GlowOverlayHost.kt`
- `ui/overlay/compose/EdgeGlowCompose.kt`
- `ui/overlay/compose/IslandOverlayHost.kt`

Changes:

- Replace hardcoded radii, spacing, `fontSize`, divider alpha, and shadow values with theme tokens
- Introduce folded-paper chrome for the capsule
- Replace local timing constants with `ClosePawMotion`
- Remove emoji-bearing capsule copy from render specs per D1

Acceptance:

- Capsule uses D1 palette, shapes, typography, and calm motion
- Overlay glow uses the same motion contract as in-app Compose

### Phase 4 — Migrate chat to Track A using the new tokens

Scope:

- `ui/chat/*`

Changes:

- Replace `ActionCard`-driven bubble composition with Track A’s flat chronological row
- Retire `ActionCard.kt` as the primary action presentation
- Replace `StreamingText` and `ThinkingIndicator` with Track A-compatible versions that consume `ClosePawMotion` and D1 text styles
- Make user bubble use the new `PaperInset` token and symmetric `medium` shape

Acceptance:

- Chat row styling matches Track A + D1
- No local chat file owns its own palette/timing constants

### Phase 5 — Roll tokens outward

Scope:

- settings
- drawer
- onboarding

Changes:

- Replace surviving `ChatSuccess`, `ChatWarning`, and similar raw theme constants
- Move settings transitions onto `ClosePawMotion`

Acceptance:

- Theme naming is app-wide, not chat-specific
- D1 identity is consistent outside chat/capsule

## Components

### What stays

- `MaterialTheme`
- feature-local composables
- `CapsuleRenderSpec` as a behavioral render contract
- `GlowState` as a semantic visual state

### What changes

- `ChatTheme` becomes `ClosePawTheme`
- theme tokens become D1-native instead of generic chat colors
- chat/capsule read tokens instead of hardcoding shape/spacing/motion
- overlay compose hosts read semantic state and resolve colors in Compose

### What gets removed

- `CapsuleColors`
- raw color ints in render specs
- dedicated old bubble shape globals
- chat-specific theme naming (`ChatPrimary`, `ChatSuccess`, etc.) once migrated
- ad-hoc motion numbers in chat/capsule/overlay

## Tasks

1. `track-d2-theme-foundation`
   - Scope: `app/src/main/kotlin/ai/closepaw/ui/theme/*`, `app/src/main/kotlin/ai/closepaw/app/MainActivityContent.kt`, `app/src/main/kotlin/ai/closepaw/ui/overlay/compose/OverlayComposeHost.kt`
   - Acceptance: `ClosePawTheme` exists; D1 palette/type/shape mapping exists; one `MaterialTheme.closePaw` accessor exists.
   - Dependencies: none

2. `track-d2-semantic-visual-models`
   - Scope: `app/src/main/kotlin/ai/closepaw/ui/overlay/model/*`, `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/*`
   - Acceptance: no raw palette values in non-Compose render models; capsule styling still fully derivable from mode + theme.
   - Dependencies: `track-d2-theme-foundation`

3. `track-d2-motion-primitives`
   - Scope: `app/src/main/kotlin/ai/closepaw/ui/theme/Motion.kt`, `app/src/main/kotlin/ai/closepaw/ui/overlay/compose/*`, `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsSheet.kt`
   - Acceptance: D1 motion contract is centralized and reused; no remaining local timing constants in migrated files.
   - Dependencies: `track-d2-theme-foundation`

4. `track-d2-capsule-restyle`
   - Scope: `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/*`, `app/src/main/kotlin/ai/closepaw/ui/overlay/compose/IslandOverlayHost.kt`
   - Acceptance: capsule matches D1 token/motion baseline and no longer depends on ad-hoc shape/font/timing constants.
   - Dependencies: `track-d2-semantic-visual-models`, `track-d2-motion-primitives`

5. `track-d2-chat-restyle`
   - Scope: `app/src/main/kotlin/ai/closepaw/ui/chat/*`
   - Acceptance: Track A row styling lands on top of the D2 theme/motion system; legacy `ActionCard` presentation path is removed.
   - Dependencies: `track-d2-theme-foundation`, `track-d2-motion-primitives`, Track A implementation

6. `track-d2-settings-drawer-onboarding-pass`
   - Scope: `app/src/main/kotlin/ai/closepaw/ui/settings/*`, `app/src/main/kotlin/ai/closepaw/ui/navigation/*`, `app/src/main/kotlin/ai/closepaw/ui/onboarding/*`
   - Acceptance: old chat-specific theme constants are gone from these surfaces; D1 editorial baseline is consistently applied.
   - Dependencies: `track-d2-theme-foundation`

## Trade-offs

### Rejected: custom all-app CompositionLocal theme

Too much indirection. It would recreate Material in parallel and make simple components harder to read.

### Rejected: per-feature style objects

Too much plumbing. Chat and capsule would need style threading through every leaf while still using `MaterialTheme` underneath.

### Rejected: pure Material with no extension tokens

Too lossy. D1 needs explicit extra text styles and one extra text color role that do not map cleanly onto standard Material slots.

## Self-Review

- Covers the required API decision, animation wiring, file structure, and migration plan.
- Keeps abstraction count low: one theme wrapper, one extension token accessor, one motion object.
- Pushes palette and timing back into Compose where they belong, instead of stuffing more visual data into render models.
- Leaves Track A free to build the new chat row without inheriting the old action-card styling model.
