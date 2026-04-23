# Direction — "Bound Edition"

> A name for this round, not a new theme. The aesthetic stays D1: warm Paper,
> deep Ink, scarce Claw, paw-glyph identity, Geist body, Fraunces italic
> identity, JetBrains Mono ledger. What changes is the **register** —
> from "competent editorial layout" to "object you'd pick up off a shelf."
>
> Reflects code as of `08ae6378` — i.e. after `uxfb-1 → uxfb-followup` landed
> ThoughtGroup, CollapsePill, FinalText, and capsule marquee. §6 was rewritten
> to tune those new components rather than fight them. See `align-with-uxfb-0422.md`
> for the diff history.

## Mental model

Imagine ClosePaw shipping as a small zine, perfect-bound, paper stock 90gsm
warm white. Each screen is a spread: a running head at the top, a fleuron
where sections divide, marginalia where the system speaks beside the user, a
ledger date in mono down by the colophon. The Smart Capsule is the dust
jacket — folded, with a real shadow under it. The agent's running trace is a
column of grouped thoughts, each marked by a thin vertical rule like the
margin rule of a chapbook. The user's turn is a hand-set letter, flush right
against the same kind of rule.

Every ornament that lands on screen has a print-shop reason to be there.
Nothing is decorative-for-decorative — every move below resolves a real
trust signal, attribution question, or rhythm beat that the current Material 3
defaults leave anonymous.

## The eight ornament moves

### 1. Page Masthead (new primitive)

A 32dp running head, opt-in via composable. Composition:

```
[paw 18dp]   ClosePaw                    No 04 · Apr 22
             ────────                    ──────────────
             ↑ Fraunces italic Reg 18sp  ↑ monoSmall, inkFaint
```

Under it: a 1dp Hairline rule across the full width.

Used by: ChatHeader (replacing current Geist title), NavigationDrawer header
(replacing "Sessions"), Settings home (top of page).

The "No 04" is the build/version prefix; "Apr 22" is `Today` localized. Both
read as colophon, not data — the user shouldn't *parse* them, they should
*recognize* them.

### 2. Fleuron separator

A centered Fraunces glyph `❦` (U+2767) at `inkFaint`, 16sp, with 12dp vertical
breathing room. Used in:

- The blank gap above the version footer in SettingsHomePage
- (Optional) the empty space between drawer session list and Settings entry

One glyph, two call sites, single composable in `ui/theme/Ornaments.kt`.

> **Withdrawn call site:** the original spec had a third use — between Trace
> and Final inside `AgentRow`. `uxfb-3` made that boundary structural
> (`AgentRow.kt`: `[trace] [CollapsePill] [FinalAnswer]`), so the pill
> itself *is* the boundary on Complete rows. The fleuron has no remaining
> chat-row job. See `align-with-uxfb-0422.md` (W1).

### 3. Asymmetric EmptyState (new composition)

Layout (portrait):

```
                                       ┌──────────────┐
                                       │              │
                                       │   ╱╲         │ ← Paw, 240dp, 80% Ink,
                                       │  (◯◯)        │   bleeds top-right
                                       │   \◯\        │   (overflow: clipped to 0)
                                       └──────────────┘

  What can I                                          ← Fraunces italic 32sp
  help you with?

  ────────                                            ← Hairline 24dp wide

  → Check unread emails                               ← bodyLarge, onSurface
    a quick triage                                    ← Fraunces italic, inkMuted

  → Turn on Do Not Disturb                            ← (same pattern)
    for the next hour

  → Find nearby restaurants
    open right now
```

Removes the suggestion chip card entirely. Each suggestion is two stacked rows:
verb in `bodyLarge` (the user's voice in their natural register — `bodyLarge`
not mono, since suggestions are user-facing prose, not machine output), italic
gloss in serif (the system's interpretation). Tap target: full row width,
generous vertical pad.

Reveal choreography: 4 stagger steps × `TraceEnter` (240ms), 80ms apart.
Total 480ms. Reduced-motion → instant.

### 4. User bubble as "letter to the editor"

Drop the `Surface` wrapping. Right-align the text. Add a 1dp Hairline left
margin rule running the full text height. Timestamp inline at the bottom-right
of the same column in `monoSmall`, no spacer above.

```
                                                          │  Hey, can you check
                                                          │  my calendar for
                                                          │  tomorrow?
                                                          │            14:32
```

The rule is what makes the right-alignment legible as a deliberate column
rather than an unstyled wrap. Cost: zero new tokens; reuse `Hairline` and
`monoSmall`.

### 5. ThinkingIndicator, naked

```
[paw-toe 28dp]  Thinking…
                ↑ closePaw.bodyItalic, inkMuted
```

Drop `Surface(shape = large, color = surfaceVariant)`. Place inline in the
`AgentRow` `Column`. Width auto. The paw-toe animation is already a designed
ornament; it doesn't need a chip around it.

### 6. AgentTrace left rule + CollapsePill summary, in one register

Two one-line moves on code that `uxfb-3/4` just shipped. Both tighten the
ledger voice the new chat shape already gestures at.

**6a. ThoughtGroup left rule weight.** `AgentTrace.kt:109` colors the 2dp
left rule as `outlineVariant` (8% Ink). At 2dp on Paper that computes to
roughly the visual weight of a 1dp Hairline — borderline acceptable, faint
in sustained reading. Since the rule is now the *only* marker that says
"a turn starts here" (the `✱` glyph is gone), it deserves the weight of
an actual structural divider.

```kotlin
// AgentTrace.kt:109
.background(MaterialTheme.colorScheme.outline),  // was outlineVariant
```

**6b. CollapsePill summary register.** `CollapsePill.kt:72` renders the
summary (`✓ N actions · 12s`) as `labelLarge` (Geist medium 14sp). The
chevron next to it is `monoBody` (JetBrains Mono). The summary content is
itself ledger-shaped — unify the register so the whole pill reads as one
machine-ledger object.

```kotlin
// CollapsePill.kt:72
style = MaterialTheme.closePaw.monoSmall,        // was labelLarge
```

> The earlier `[+]/[−]` mono-bracket move on `CollapsedHeader` is withdrawn
> — `uxfb-3` shipped a real `CollapsePill` with rotating chevron that
> supersedes it. See `align-with-uxfb-0422.md` (W2).

### 7. Capsule ledger counter

When `mode is CapsuleMode.Running`, prepend a mono counter to the
`CapsuleStatusLine` thought text:

```
[●]  [t+12s]  reading inbox — three threads landed, the dentist…→ marqueeing
 ↑    ↑       ↑ titleMedium, basicMarquee (uxfb-2), reduced-motion → compactThought()
 paw  monoSmall, inkFaint — fixed
```

Updates every 1s via a `LaunchedEffect(mode)` ticker. Stops at Done/Error.
Reduced-motion: still ticks (it's a status signal, not decoration).

**Layout note:** the ledger counter is its **own `Text`** placed before
the thought `Text`, **not** wrapped inside the marquee row. The paw and
ledger stay fixed while the thought scrolls past. Under reduced-motion,
the ledger continues to tick and the thought falls back to
`compactThought()` (uxfb-2 contract) — both signals are independent.

This is the most product-functional ornament in the round — users have
asked for elapsed time before; the editorial register makes it free to add.

### 8. Send-button breath

`CapsuleInputBar.kt::FilledIconButton` (currently CircleShape, 40dp, primary
Claw). On submit click: scale 1.0 → 1.04 → 1.0 over 240ms (`SurfaceSwap`
duration, `EaseOutCubic`). The Claw color earns its scarcity by *doing
something* at the moment of commit.

Reduced-motion → no scale.

## Two new D1-residue primitives

Both go in `ui/theme/`. No new color slots, no new fonts, no new motion
durations.

### `Modifier.paperGrain(strength: Float = 0.015f)`

Bakes a deterministic noise texture once via `drawWithCache` into a 256×256
`ImageBitmap`, then `drawImageRect` tiles it under the modifier's content.
Skipped (no-op) in dark theme — Lantern keeps a faint warm radial vignette
applied via gradient brush instead.

Apply at the root `Surface` of: ChatScreen Scaffold background,
NavigationDrawer container, OnboardingShell background, SettingsSheet.

### `@Composable Fleuron(modifier: Modifier = Modifier)`

Centered Fraunces `❦` at `inkFaint`, 16sp, 12dp vertical pad above and below.
Single source for all three call sites (Trace→Final, Settings footer, Drawer).

## Light vs. dark

Bound Edition reads identically in both palettes; Lantern (dark) gets the same
ornaments with two adjustments:

- `paperGrain` → `lanternVignette` (warm radial, ~6% Ink-on-PaperDark, baked once).
- Fleuron uses `inkFaintDark` automatically via theme.

## What this is *not*

- Not a new theme. The D1 palette and role mapping in `Theme.kt` are unchanged.
- Not a new font. Fraunces and JetBrains Mono are already shipped; we just stop
  synthesizing weights.
- Not a new motion library. Every animation reuses `ClosePawMotion` durations
  and easings.
- Not a re-skin of the Smart Capsule. The capsule already nailed it; we add
  one ledger counter and let it breathe.
- Not Material-3-replacement work. We stay inside Material slots; D1-residue
  grows by exactly two ornaments.
