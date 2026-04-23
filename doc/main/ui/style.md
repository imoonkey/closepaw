# UI Style Guide

> Design system: D1 visual baseline wired through Material 3 + one thin token surface.
> Last updated: 2026-04-22 (Bound Edition: ornaments + paper grain + ledger counter)

## Design System

ClosePaw uses Material 3 with the **D1 visual baseline** (warm paper surfaces, deep
warm ink, scarce Claw accent, paw glyph identity). The full token set, role mapping,
motion vocabulary, and folded-paper chrome are documented below — this file is the
authoritative reference.

### Theme Files

> See: `ui/theme/`

| File | Purpose |
|------|---------|
| `Color.kt` | D1 palette (Paper / Ink / Claw / Moss / Amber / Rust + light + dark) |
| `Shape.kt` | `ClosePawShapes` — three Material radii (8 / 10 / 16dp) |
| `Theme.kt` | `ClosePawTheme` composable + D1 → Material role mapping |
| `Tokens.kt` | `ClosePawTokens`, `ClosePawSpacing`, `Modifier.foldedPaper`, `MaterialTheme.closePaw` accessor |
| `Motion.kt` | `ClosePawMotion` (durations, easings, named primitives, `reducedMotion()`) |
| `Type.kt` | `ClosePawTypography` — Geist on every Material slot; identity / mono extras carried in `ClosePawTokens` |
| `Ornaments.kt` | `Fleuron`, `PageMasthead`, `SectionHeader`, `todayLabel` — the Bound Edition paper-zine register |
| `PaperGrain.kt` | `Modifier.paperGrain` (light) and `Modifier.lanternVignette` (dark) background passes |
| `WindowInsets.kt` | `AppWindowInsets` singleton |

### ClosePawTheme

> See: `ui/theme/Theme.kt`

```kotlin
@Composable
fun ClosePawTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)
```

- Selects light/dark color scheme from D1 palette
- Provides `LocalClosePawTokens` for the `MaterialTheme.closePaw` accessor
- Configures status-bar icon appearance and bar colors on API < 35
- Applies `ClosePawTypography` and `ClosePawShapes`

Call sites use Material slots first (`MaterialTheme.colorScheme.*`, `MaterialTheme.typography.*`,
`MaterialTheme.shapes.*`); only the D1 residue (extra text roles, identity / mono styles,
spacing tiers, folded-paper chrome) goes through `MaterialTheme.closePaw`.

---

## D1 → Material Role Mapping

> See: `ui/theme/Theme.kt`

| D1 token | Material slot |
|---|---|
| `Paper` | `background`, `surface`, `surfaceContainerLowest` |
| `PaperInset` | `surfaceVariant`, `surfaceContainerLow…High` |
| `Ink` | `onBackground`, `onSurface` |
| `InkMuted` | `onSurfaceVariant` |
| `Claw` | `primary` |
| `Moss` | `secondary` |
| `Amber` | `tertiary` |
| `Rust` | `error` |
| `Hairline` (12% Ink) | `outline` |
| `InkGhost` (8% Ink) | `outlineVariant` |

`InkFaint` is a text role and lives in `ClosePawTokens.inkFaint`, not in any Material slot.

### Light Palette ("Paper")

| Role | Hex |
|---|---|
| `Paper` | `#F5F1EA` |
| `PaperInset` | `#EDE7DC` |
| `Ink` | `#14110F` |
| `InkMuted` | `#5C554C` |
| `InkFaint` | `#8B8278` |
| `Claw` | `#C44528` |
| `Moss` | `#4A5D3A` |
| `Amber` | `#E8A33D` |
| `Rust` | `#8B2E1F` |

### Dark Palette ("Lantern")

Separate from light, not inverted. `*Dark` counterparts in `ui/theme/Color.kt`.

---

## Typography

> See: `ui/theme/Type.kt`

Three families:

- **Geist** (sans) — every Material slot, including `bodyLarge`, all `title*`, `label*`, etc.
- **Fraunces** (serif) — identity surfaces only. Reached via `ClosePawTokens.serifItalic` or local TextStyle. Never auto-applied through a Material slot.
- **JetBrains Mono** — machine text. Reached via `ClosePawTokens.monoBody` / `monoSmall`.

Track A row voice (UXFB-4 ThoughtGroup hierarchy):

| Item | Style |
|---|---|
| Thought header | `MaterialTheme.typography.bodyLarge` (Geist regular, `onSurface`) — group marker; left rule replaces the prior `✱` glyph |
| Action | `MaterialTheme.closePaw.monoSmall`, `onSurfaceVariant`, indented `spacing.lg` inside the group |
| Final | `MaterialTheme.typography.bodyLarge` (Geist regular, `onSurface`) |

Each `ContentBlock.Thought` opens a ThoughtGroup; subsequent Actions belong to
it until the next Thought. Groups render as `Row` with a 2dp left rule
(`outlineVariant`) plus a `Column` (`spacing.md` start padding) — see
`ui/chat/components/AgentTrace.kt`. Italic + `onSurfaceVariant` thought styling
was retired in UXFB-4 (inverted hierarchy made actions read as more prominent
than the reasoning that produced them).

Font binaries ship in `app/src/main/res/font/` (`geist_{regular,medium}.ttf`,
`fraunces_{regular,italic}.ttf`, `jetbrains_mono_{regular,medium}.ttf`) with
attribution in `app/src/main/assets/FONT_ATTRIBUTION.md`.

---

## Shapes

> See: `ui/theme/Shape.kt`

`ClosePawShapes` ships exactly three Material radii. No bubble / capsule / card / input shape globals.

| Slot | Radius | Usage |
|---|---|---|
| `small` | 8dp | controls, fields |
| `medium` | 10dp | card-like surfaces, user bubble |
| `large` | 16dp | capsule / pill chrome |

---

## Spacing

> See: `ui/theme/Tokens.kt` — `ClosePawSpacing`

Five steps on the 4dp baseline grid: `xs=4` · `sm=8` · `md=12` · `lg=20` · `xl=32`.
Reached via `MaterialTheme.closePaw.spacing`. No `xxl`; horizontal page padding is `lg`.

---

## Motion

> See: `ui/theme/Motion.kt` — `ClosePawMotion`

Durations: `120 / 240 / 480 / 900 ms`. Easings: `EaseInOutSine`, `EaseOutCubic`. No springs.

Named primitives map onto real surface needs:

| Primitive | Duration | Where |
|---|---|---|
| `StatusFlip` | 120 | status / glyph flip |
| `TraceEnter`, `RowExpand`, `PageSlide`, `SurfaceSwap` | 240 | trace items, row expand, settings page slide, surface/content/color swap |
| `CursorBlink`, `ThinkingPulse`, `OverlayFadeOut` | 480 | streaming cursor, thinking pulse, overlay fade |
| `CapsuleBreath`, `GlowPulse` | 900 | running-mode breath, glow pulse |

`ClosePawMotion.reducedMotion()` is read once per call site; each surface picks instant-or-fade
itself rather than wrapping every transition globally. The contract:

- trace enter → instant + 120ms fade
- collapse / expand → instant
- capsule breath → static paw at full alpha
- decorative loops (glow, thinking) → paused
- streaming cursor → keeps blinking (liveness signal, never suppressed)

---

## Folded Paper

> See: `ui/theme/Tokens.kt` — `Modifier.foldedPaper(shape)`

The only shared chrome primitive. Subtle warm under-shadow + a top hairline.
Capsule and modal sheet/drawer use it; everything else stays flat. Chained onto
the existing `Surface`, not wrapped around one.

---

## Bound Edition Ornaments

> See: `ui/theme/Ornaments.kt`

Three ornaments give the chat shell its paper-zine register. Call sites use
these primitives directly — never reinvent the glyph or layout.

| Primitive | Purpose | Where |
|---|---|---|
| `Fleuron()` | Centered Fraunces italic `❦` (16sp, `inkFaint`), 12dp vertical padding. The single shared section divider. | Settings home (above Version footer); end-of-conversation seal candidates |
| `PageMasthead(title, rightSlot, leadingPaw)` | Running-head row: optional paw glyph + Fraunces italic title (18sp `onSurface`) + optional `monoSmall` right slot, followed by a 1dp `outline` hairline. | `ChatHeader`, `NavigationDrawer` header, `SettingsHomePage` |
| `SectionHeader(text)` | Fraunces italic 18sp `inkFaint`, 16dp top / 4dp bottom padding. Section subhead inside identity surfaces. | Settings home (`Voice`, `Behavior`, `System`) |
| `todayLabel()` | Locale-formatted current day from `DateFormat.getMediumDateFormat`. The canonical right-slot ledger string for `PageMasthead`. | All masthead surfaces |

### Mastheaded Surfaces

The `PageMasthead` running-head appears on every "page-level" identity surface:

- `ChatHeader` — `[≡] · paw · ClosePaw · todayLabel · [+]`
- `NavigationDrawer` `DrawerHeader` — `paw · Sessions · todayLabel · [×]`
- `SettingsHomePage` — `paw · Settings · todayLabel`

The leading paw + Fraunces italic title + monoSmall ledger date is the
identity contract — the right slot is text-only and read-only (no tap target).

---

## Paper Grain & Lantern Vignette

> See: `ui/theme/PaperGrain.kt`

Two complementary background modifiers, theme-selected:

| Modifier | Theme | Behavior |
|---|---|---|
| `Modifier.paperGrain(strength = 0.015f)` | Light only (no-op in dark) | Tiles a 256×256 deterministic-noise bitmap (seed = `42`) tinted at `onSurface`, alpha = `strength`, repeated across the surface |
| `Modifier.lanternVignette()` | Dark only (no-op in light) | Radial gradient from transparent → `primary @ 6% alpha`, centered, radius = `0.7 × max(width, height)` |

The grain bitmap is `remember`-cached per (strength, ink) so the bake runs
once. Detection is luminance-based on `colorScheme.background` — calling
either modifier in the wrong theme is safe and free.

Apply at the outermost `Box` / `Scaffold` of any identity surface (chat shell,
empty state, settings home). Do not stack — one grain pass per surface.

---

## Ledger Counter (Capsule Running Mode)

> See: `ui/capsule/surface/SmartCapsuleSurface.kt` — `produceElapsedLabel(...)`

While the capsule is in `Running` mode, the status line carries a monospaced
elapsed-time chip (`[t+12s]`, `monoSmall`, `inkFaint`) immediately to the
right of the paw glyph and immediately to the left of the (potentially
marqueed) thought text.

**Critical layout invariant:** the ledger `Text` is a *sibling* of the
thought `Text` inside the same `Row`, never nested inside the thought's
`basicMarquee` modifier. Paw + ledger stay fixed; only the thought scrolls.

**Latch contract (N1 correctness gate, 2026-04-22 codex review):**
`CapsuleStateHolder` recreates `CapsuleMode.Running(...)` on every
`onThoughtUpdate`, so the ticker MUST NOT key off the full `mode` value.
`SmartCapsuleSurface` latches a `runningStartedAtMs: Long?` when the surface
transitions *into* `Running` from a non-`Running` mode and clears it on
transition out. Elapsed = `System.currentTimeMillis() - runningStartedAtMs`,
ticking at 1Hz. Verified: counter advances `[t+40s] → [t+55s]` across
multiple thought updates within a single Running session.

The ledger is a status, not motion — `reducedMotion()` has no effect on it.
The thought's marquee independently honors reduced motion via the existing
`compactThought()` fallback.

---

## File Structure

```
ui/theme/
├── Color.kt          # D1 palette (light + dark)
├── Shape.kt          # ClosePawShapes (small / medium / large)
├── Theme.kt          # ClosePawTheme + D1 → Material role mapping
├── Tokens.kt         # ClosePawTokens, ClosePawSpacing, MaterialTheme.closePaw, foldedPaper
├── Motion.kt         # ClosePawMotion (durations, easings, primitives, reducedMotion)
├── Type.kt           # ClosePawTypography (Geist) + identity / mono extras
├── Ornaments.kt      # Fleuron, PageMasthead, SectionHeader, todayLabel
├── PaperGrain.kt     # Modifier.paperGrain (light) / Modifier.lanternVignette (dark)
└── WindowInsets.kt   # AppWindowInsets singleton
```

---

## Edge-to-Edge

- Status / nav bar: transparent, icon color adapts to theme
- API < 35: bar colors set explicitly to `colorScheme.background`
- Insets via `AppWindowInsets`; prefer Material 3 component `windowInsets` parameters over manual `windowInsetsPadding()`

> See: `ui/theme/WindowInsets.kt`

---

## Related Docs

- [User Interaction](user_interaction.md) — pages, components, behaviors
- [Tech Design](tech_design.md) — implementation details
- [Overlay](overlay.md) — overlay visual specifications and capsule colors
