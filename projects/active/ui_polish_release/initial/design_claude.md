# Pre-release UI Polish — ClosePaw

> Goal: ship-quality polish across the whole app. Not a redesign. Find bugs,
> drift from the documented D1 / Bound Edition system, and rough edges, and
> fix them with surgical changes. Triaged P0 (blocks release) → P1
> (consistency) → P2 (refinement).

---

## Method

Surveyed: `ui/theme/*`, `ui/chat/**`, `ui/capsule/**`, `ui/overlay/**`,
`ui/settings/**`, `ui/onboarding/**`, `ui/navigation/*`, `ui/viewer/*`,
plus `doc/main/ui/style.md` and `doc/main/ui/user_interaction.md`.

Each finding lists:

- **File\:line** — current code reference
- **Current** — what is on the screen today
- **Fix** — minimum change
- **Effort** — S (≤30 min), M (≤2 h), L (≤½ day)

Token shorthand: D1 = the documented design system in `style.md`.

---

## P0 — Blocks Release

### P0-1 · Touch targets below Material/A11y minimum on the Smart Capsule

The capsule is the primary interaction surface in every mode, including the
overlay where it's the *only* way to control the agent. Several controls are
below the WCAG 2.5.5 / Material 48dp minimum.

| Control | File\:line | Current | Fix |
|---|---|---|---|
| `NavIconButton` (Minimize / Open app / Open viewer) | `ui/capsule/surface/CapsuleControlBar.kt:213-225` | `FilledTonalIconButton(Modifier.size(32.dp))`, icon 18dp | Bump to `size(48.dp)`; if it crowds the row, keep the visual square at 32dp but expand the touch area with `Modifier.minimumInteractiveComponentSize()` (Compose default) or wrap in a 48dp `Box` |
| Capsule input mic + send | `ui/capsule/surface/CapsuleInputBar.kt:231, 247, 265, 309` | `IconButton` / `FilledIconButton(Modifier.size(36.dp))` | Same fix. The TextField's `leadingIcon` / `trailingIcon` slots accept any composable; wrap in 48dp box, keep the painted glyph at 18dp |
| Capsule primary / stop / approval buttons | `ui/capsule/surface/CapsuleControlBar.kt:99-160, 248-262` | `FilledTonalButton(contentPadding = PaddingValues(horizontal=10dp, vertical=6dp))` — rendered height ≈ 32 dp | Add `Modifier.heightIn(min = 40.dp)` to match Material small-button minimum AND increase vertical padding to 8dp. (The compact size is intentional, so 40dp + minimumInteractiveComponentSize() touch overlay is the right balance.) |
| `PermissionRepairCard` Fix button | `ui/onboarding/PermissionRepairCard.kt:119` | `Modifier.height(28.dp)` | Bump to 40dp; the card row is 44dp, raise to 48dp |
| App Access trailing chevron / "+ Memory" | `ui/settings/AppAccessSettingsPage.kt:556, 579` | 32dp Surface buttons | 48dp interactive size via wrapper Box (visual chip can stay 32dp) |
| `CompletionHandoffCtaRow` "Open <App>" | `ui/chat/components/CompletionHandoffCtaRow.kt:63` | `Modifier.height(32.dp)` | Use Material's default 40dp; explicitly set `heightIn(min = 40.dp)` |

**Effort: M (one PR for all six)**

---

### P0-2 · `paperGrain` ships at almost 3× the documented strength

`doc/main/ui/style.md:252` says `Modifier.paperGrain(strength = 0.015f)`.
The code default is `0.04f` (`ui/theme/PaperGrain.kt:41`), and every call site
(`ui/chat/ChatScreen.kt:286`, `ui/settings/SettingsSheet.kt:103`) invokes it
parameterless, so production runs at 0.04f.

The visible effect is a heavier paper texture than the design baseline; on
mid-range OLED screens this reads as ink dust over body copy in Chat and
Settings.

**Fix:** change the default to `0.015f`. One line; documented number wins.
The 0.04f looked right when it was a top-only band; with the matchParentSize()
pass introduced in d23537e8, the same strength stacks across whole surfaces.

**Effort: S**

---

### P0-3 · Grain drawn ON TOP of message content in ChatScreen

`ui/chat/ChatScreen.kt:286`:

```kotlin
Box(modifier = Modifier.matchParentSize().paperGrain())
```

is the LAST child of the outer `Box`, so the noise tile is composited above
the LazyColumn / Scaffold body. Settings does the right thing —
`SettingsSheet.kt:103` chains `.paperGrain()` on the wrapper Column *before*
children, so the grain stays under text. In Chat, every character of agent
output, every chip, every approval button gets a 4 % noise overlay.

Combined with P0-2, the effect is "ink on slightly dirty paper" — the noise
desaturates the ink slightly and lowers AA contrast on `bodyMedium` /
`labelSmall` text.

**Fix:** move the grain Box to render BEFORE the Scaffold inside the outer
Box, or apply `.paperGrain()` directly on the outer Box's background pass
(e.g., on the existing `.background(MaterialTheme.colorScheme.surface)` line
177). Either is one-line.

**Effort: S**

---

### P0-4 · EmptyState hero paw uses brittle offset math

`ui/chat/ChatScreen.kt:182-189` renders the 300dp hero paw with
`.offset(x = 48.dp, y = 88.dp).size(300.dp)` and aligns top-end. The 88dp Y
is presumably "below the status bar + masthead", but on a Pixel 8 (status
bar 48dp), Pixel 7a (status bar 27dp via punch-hole), foldables, and
tablets, this value lands inconsistently — and the paw will clip out the top
of the screen on devices with no status bar inset (e.g., taskbar mode).

The offset also doesn't account for the EmptyState body's lower-left
"What can I help you with?" column, so a smaller screen places the paw body
*on top of* the title text.

**Fix:** lay out the paw with constraints rather than absolute offsets:

1. Inside the same outer Box, place the paw in `Modifier.align(TopEnd)` with
   `.padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp, end = (-48).dp)` — the negative end padding is the existing "bleed off-screen" intent expressed correctly.
2. Or cap the paw size proportionally: `.size(minOf(300.dp, screenWidth * 0.7f))` to prevent the paw from eating the suggestion column on small/foldable screens.

**Effort: M**

---

### P0-5 · Status-bar / inset handling is duplicated and inconsistent

`AppWindowInsets` is the single documented entry point
(`ui/theme/WindowInsets.kt`), but it's used in exactly ONE place
(`ui/navigation/NavigationDrawer.kt:85`). Everywhere else uses raw
`WindowInsets.statusBars` / `statusBarsPadding()` / `systemBarsPadding()`:

- `ui/chat/components/ChatHeader.kt:44` — `windowInsetsPadding(WindowInsets.statusBars)`
- `ui/onboarding/OnboardingShell.kt:63-65` — `.systemBarsPadding().imePadding()`
- `ui/settings/SettingsSheet.kt:104-107` — manual stack of four inset paddings

The current code works but every new page is a chance to drift. More
critically, **the chat screen's hero paw is positioned relative to a magic 88dp
that includes an implicit status-bar assumption** (P0-4) — fixing that means
deciding once where insets are owned.

**Fix:** pick one rule and apply it. Recommended: have every page-level
container apply `AppWindowInsets.statusBars` (or full systemBars where it
owns the bottom too), and delete `statusBarsPadding()` / `WindowInsets.statusBars`
direct references in feature code. ChatHeader stops applying status-bar
padding (it's already inside Scaffold + outer Box; padding should come from
the Scaffold's `contentWindowInsets` which is currently `WindowInsets(0,0,0,0)`).

**Effort: M (touches 5-6 files, but each change is local)**

---

## P1 — Consistency Fixes

### P1-1 · `ChatHeader` re-implements `PageMastheadIdentity` instead of using it

`ui/chat/components/ChatHeader.kt:35-85` hand-rolls the masthead row:
status-bar inset → `IconButton(Menu)` → `PageMasthead("ClosePaw")` →
spacer in trailing slot. The other two identity pages
(`Settings`, `Sessions`) use `PageMastheadIdentity` and follow the contract
documented in `style.md:235-241`.

The current asymmetry causes two real problems:

1. The Chat masthead has BOTH a leading `IconButton(Menu)` *and* the
   `PageMasthead` default leading `PawGlyph()` — so the row reads
   `[≡] · paw · ClosePaw · [+]` (paw is doubled with the menu icon).
   `user_interaction.md:18` shows this as the spec, but visually the menu
   icon is the actionable identity, and the paw becomes a passive duplicate.
2. The 56dp Row height + 48dp PageMasthead height clash; padding ends up
   doing the work, making vertical centering inconsistent with the other
   pages (48dp).

**Fix:** keep the masthead row at 48dp; either keep the menu hamburger and
DROP the leading paw (use `leadingSlot = {}`) or keep the paw and turn the
title into a tap target that opens the drawer. Recommended option 1 — the
menu icon is the universal pattern; the paw still appears as Fleuron
dividers, capsule status, thinking, and onboarding progress.

**Effort: S**

---

### P1-2 · Bare `RoundedCornerShape(N.dp)` instead of `MaterialTheme.shapes`

D1 declares three radii (`Shape.kt`). These are bypassed in:

- `ui/onboarding/PermissionRepairCard.kt:50` — `RoundedCornerShape(10.dp)` (same as `shapes.medium`)
- `ui/navigation/NavigationDrawer.kt:238, 241` — `RoundedCornerShape(8.dp)` (same as `shapes.small`)
- `ui/chat/components/MessageBubble.kt:55-60` — `UserBubbleShape` is asymmetric and *correctly* a local constant; KEEP this one.

**Fix:** swap the four occurrences to `MaterialTheme.shapes.medium` / `small`.
Pure rename.

**Effort: S**

---

### P1-3 · `PermissionsAdvanced` debug + trace toggle rows skip `foldedPaper`

`ui/settings/PermissionsAdvancedSettingsPage.kt:90-127, 219-269` build the
Debug Mode and Session Traces rows with a bare `Surface` (no
`.foldedPaper()`). Every OTHER settings card with a similar weight
(`SettingsNavigationRow`, `ToolSettingsCard`, `SettingsRow`) uses
`foldedPaper`. The two rows look distinctly flatter on the page.

**Fix:** refactor these two inline rows to use `ToolSettingsCard` (already
purpose-built for `title + status + switch`). Drops ~40 lines and matches
the rest of the page. The "Trace warning" inline alert inside the Session
Traces card becomes the `expanded` slot of `ToolSettingsCard`, which is
already supported (`ToolSettingsCard.kt:56, 143`).

**Effort: M**

---

### P1-4 · `OnboardingShell` step title uses Fraunces *upright*, not italic

`ui/onboarding/OnboardingShell.kt:113-116`:

```kotlin
style = MaterialTheme.typography.displaySmall.copy(fontFamily = Fraunces)
```

`displaySmall` is `FontWeight.SemiBold` (`Type.kt:36`). Fraunces ships only
Regular + Italic (`Type.kt:22-24`), so SemiBold is **synthesized** — the
glyphs look wonky/heavy. And the D1 contract is "Fraunces italic Regular,
only" on identity surfaces (`style.md:94-96`).

So Step titles like "Let ClosePaw control your phone" render in a
synthesized upright SemiBold serif that doesn't match the masthead voice
anywhere else in the app.

**Fix:** swap to a local TextStyle:

```kotlin
style = TextStyle(
    fontFamily = Fraunces,
    fontStyle = FontStyle.Italic,
    fontWeight = FontWeight.Normal,
    fontSize = 28.sp,
    lineHeight = 36.sp,
)
```

This matches `SerifItalicStyle` in `Tokens.kt` (which is 22sp; onboarding
needs the bigger display size, so a sibling constant in Type.kt is the
cleanest). Effort includes naming the new style.

**Effort: S**

---

### P1-5 · Capsule background + chat user bubble share `surfaceContainerLow`

Both `SmartCapsuleSurface` (`CapsuleSurface.kt:127`) and `UserBubble`
(`MessageBubble.kt:77`) render on `colorScheme.surfaceContainerLow`. In dark
mode this is `PaperInsetDark = #1A1612` versus background `PaperDark =
#0F0D0B`. The capsule sits as a `Surface` with `foldedPaper(shape)` which
adds the warm shadow; the user bubble has neither shadow nor border. So the
floor of the chat (capsule) and the last user bubble look like the SAME
material with the capsule's shadow being the only separator. On scroll, the
visual depth flattens.

**Fix:** keep the capsule on `surfaceContainerLow`; move the user bubble to
`colorScheme.primaryContainer` (Claw-tinted Paper) — `PrimaryContainerLight = #EBCFC3`,
`PrimaryContainerDark = #3A2018` — both warm and clearly distinct from the
capsule chrome. This is also closer to the D1 brand voice
(user = warm tinted paper, agent text = ink on paper, capsule = paper
surface).

Alternative: leave colors alone, add a 1dp `outline` hairline border to the
user bubble (`BorderStroke(1.dp, MaterialTheme.colorScheme.outline)`).
Smaller change, weaker signal.

**Effort: S**

---

### P1-6 · `LocalTabContent` warning rendered as `errorContainer`

`ui/settings/LlmAuthSettingsPage.kt:568-580` paints the "local models are
slow…" notice on `errorContainer` (Rust-tinted Paper). The text is
explicitly a *warning*, not an error — the SettingsAlertCard already models
this with `AlertTone.Warning` mapped to `tertiaryContainer` (Amber-tinted Paper).

**Fix:** replace the bespoke Surface with `SettingsAlertCard(message =
"…", tone = AlertTone.Warning)`. Same component, correct tone, less code.

**Effort: S**

---

### P1-7 · Switch color overrides duplicated across pages

`SwitchDefaults.colors(checkedThumbColor = primary, checkedTrackColor =
primaryContainer)` appears in four call sites:

- `ui/settings/PermissionsAdvancedSettingsPage.kt:119-124, 248-252`
- `ui/settings/ToolSettingsCard.kt:136-140`

A single extension keeps the look consistent and centralizes the policy:

```kotlin
@Composable
fun closePawSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.primary,
    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
)
```

in `ui/theme/Tokens.kt`. Then all four call sites use `colors =
closePawSwitchColors()`. If we later tweak the contract (e.g., dim the
track when disabled), one edit.

**Effort: S**

---

### P1-8 · `IslandOverlayHost` clips long thoughts at 220dp on small phones

`ui/overlay/compose/StatusIslandCompose.kt:66` caps the text at
`widthIn(max = 220.dp)`. On a 360dp-wide phone, that leaves the island
~60% of the viewport. Long thoughts marquee, but on a 320dp-wide narrow
phone (legacy / unfolded landscape strip), the marquee gets squished.

**Fix:** make the max responsive — `min(220.dp, LocalConfiguration.current.screenWidthDp.dp - 120.dp)` (keep 60dp gap each side). Or drop the hardcoded 220 entirely and let `weight(1f)` inside a `Row(modifier = Modifier.widthIn(max = LocalConfiguration.current.screenWidthDp.dp * 0.6f))`.

**Effort: S**

---

### P1-9 · `StatusIslandCompose` and `CapsuleStatusLine` duplicate marquee logic

The reduced-motion fallback + marquee + compactThought call exists in
`StatusIslandCompose.kt:66-88` AND `SmartCapsuleSurface.kt:320-343`,
essentially identical except for `style` and `widthIn`. Pulling these into
a `MarqueeOrCompactText(text, style, color, maxWidth)` composable removes
the divergence risk (today they happen to use the same 30dp/s and 1500ms
delay; nothing prevents that from drifting).

**Fix:** extract to `ui/capsule/surface/MarqueeText.kt`; replace both call sites.

**Effort: S**

---

### P1-10 · Hero paw and Done CTA both spend "Claw scarcity"

D1 reserves the Claw accent for the rare surface (running glow, primary
button, cursor). On the empty-state screen the hero paw is Claw
(`ChatScreen.kt:184`) AND if the user just finished a task, the recently
shown Done capsule also uses Claw-on-primary for "Done". On a fresh chat
the brand mark is loud; mid-task the loudness becomes background noise.

**Fix:** keep the hero paw Claw on first-run, but lower the alpha (e.g.,
`.alpha(0.55f)` on the Icon, or tint with `Claw.copy(alpha = 0.55f)`). The
existing 300dp size keeps it readable; the lowered alpha returns the
"scarce" attribute to surfaces that earn it.

**Effort: S**

---

### P1-11 · CapsuleInputBar disabled hint copy is mode-specific

`ui/capsule/surface/CapsuleInputBar.kt:155-159`:

```kotlin
val hint = when {
    voiceState == VoiceState.Listening -> "Listening…"
    inputEnabled -> spec.hint
    else -> "Take over to type note"
}
```

In `WaitingForInput`, `inputEnabled` is true; in `Takeover`, also true. The
only branch where `else` fires is overlay+ACCESSIBILITY platform+`Running`
or `TakeoverPending`. "Take over to type note" is correct copy for those
states but reads as user-error elsewhere (e.g., if hint logic ever expands).

Not currently broken; just brittle. **Fix:** push the hint copy into
`InputSpec` (it already has `hint` / `submitLabel`) so the `disabled` hint
travels with the spec. Pure refactor.

**Effort: S**

---

### P1-12 · `ChatScreen.MessageList` long-URL overflow

`UserBubble` (`MessageBubble.kt:73-103`) is `widthIn(max = screenWidth *
0.85f)`. A user message containing a single long URL with no spaces won't
break and stretches the bubble (or, in some Compose builds, overflows the
horizontal padding). Agent `FinalAnswer` text wraps freely.

**Fix:** apply `softWrap = true, overflow = TextOverflow.Visible` and add
`Modifier.basicTextField`-style `breakIterator` behavior — simpler, set
the text style with `lineBreak = LineBreak.Paragraph`:

```kotlin
style = MaterialTheme.typography.bodyLarge.copy(
    lineBreak = LineBreak.Paragraph
)
```

That makes long unbroken strings (URLs, hashes) break at any character once
they exceed the bubble width.

**Effort: S**

---

## P2 — Refinements

### P2-1 · `IslandOverlayHost.getStatusBarHeight()` uses deprecated resource lookup

`ui/overlay/compose/IslandOverlayHost.kt:117-120` reads
`status_bar_height` via `Resources.getIdentifier(...)`. This is the
pre-WindowInsets pattern; on edge-to-edge / cutout devices it returns the
default 24dp regardless of the actual inset.

**Fix:** in the AccessibilityService context use `service.windowManager
.currentWindowMetrics.windowInsets.getInsets(Type.statusBars()).top` (API
30+). Below 30, fall back to current behavior.

**Effort: S** (one helper)

---

### P2-2 · `ActionVisualizerCompose` always renders an animation loop while items exist

`ui/overlay/compose/ActionVisualizerCompose.kt:56-61` runs an infinite
`withFrameMillis` loop while `items.isNotEmpty()`. That's correct for the
active animation, but the loop never sleeps when items are mid-life but
their progress is already 1.0 (visually static). Cheap, but on low-end
devices any background-frame work the system can avoid is a win.

**Fix:** in `ActionVisualizerManager` (`ui/overlay/visualizer/`), remove
items as soon as `elapsed >= durationMs`, so the compose loop exits
cleanly. (Without seeing the manager, this may already be the case — if so,
the loop is fine; skip.)

**Effort: S** (audit only)

---

### P2-3 · `Fleuron()` is the same composable in every section; missing semantics

`ui/theme/Ornaments.kt:35-48` renders the divider paw with
`contentDescription = null`. For screen-reader users, the visual rhythm
break is invisible. It's a decorative glyph, so null is technically
correct — but several pages stack `Fleuron + Version text + Spacer` as
their close-out, and the version label IS information. Today the screen
reader jumps from the last card to the version text with no announced
section break.

**Fix:** wrap the `Fleuron + Version` pair in a `Column.semantics {
contentDescription = "End of section, version $name" }` (where used);
keep `Fleuron` itself decorative.

**Effort: S**

---

### P2-4 · `PageMasthead` height is 48dp; trailing slots use 48dp `IconButton`

`ui/theme/Ornaments.kt:60`: `Row.height(48.dp)`, but each trailing
`IconButton` is its own `Modifier.size(48.dp)` (Material default). The
masthead's `Row` therefore depends on the trailing slot being exactly
48dp tall to vertically center; an oversized trailing widget will be
clipped at top/bottom.

Not breaking today (all trailing widgets are 48dp IconButtons or empty
spacers), but a future caller passing a larger composable as the trailing
slot will silently clip. **Fix:** swap `.height(48.dp)` for
`.heightIn(min = 48.dp)`. One-token change.

**Effort: S**

---

### P2-5 · `AppAccessSettingsPage` filter chips use bespoke `Surface`, not Material `FilterChip`

`ui/settings/AppAccessSettingsPage.kt:307-333` defines a custom `FilterChip`
to match the segmented look. Material 3 ships `FilterChip` /
`SingleChoiceSegmentedButtonRow` (the latter is already used in
`LlmAuthSettingsPage:398-415`). Two patterns for the same concept across
adjacent settings pages is friction.

**Fix:** swap `AppAccessSettingsPage` FilterChips for
`SingleChoiceSegmentedButtonRow` + `SegmentedButton`. Same for the per-row
tier selector (`TierSegmentedSelector`, line 649-676) — both are
"pick-one-of-N" patterns the segmented row models natively.

**Effort: M**

---

### P2-6 · `SettingsHomePage` "App Access" subtitle shows `…` during scan

`ui/settings/SettingsHomePage.kt:184-200` returns `…` while the IO scan is
in flight. That's the right pattern but the ellipsis disappears with no
explanation if the IO is fast on a Pixel and slow on a budget device — the
user may see the row flash or, on cold start, sit at `…` for >1s. A short
placeholder ("Counting installed apps…") is more honest.

**Fix:** `return counts?.let { ... } ?: "Counting installed apps…"`. One-line.

**Effort: S**

---

### P2-7 · `SmartCapsuleSurface` `StartupErrorBanner` rule is hardcoded 2dp Rust

`ui/capsule/surface/SmartCapsuleSurface.kt:237-243` draws a 2dp left rule
in `colorScheme.error`. That matches the existing alert language but it's
inline; if D1 ever changes the inline-error rule width or the error color
roles, this won't follow. The existing `SettingsAlertCard` is the standard.

**Fix:** replace `StartupErrorBanner` body with a `SettingsAlertCard` call
plus a trailing dismiss IconButton via the `action` slot. (The
SettingsAlertCard already has an `action` slot — see `SettingsAlertCard.kt:29, 56`.) Drops ~30 lines.

**Effort: S**

---

### P2-8 · `ThinkingIndicator` minimum hit area

`ui/chat/components/ThinkingIndicator.kt:43-62` is purely informational
(it's never tap-targeted). Currently fine, BUT it lives inside an
`AgentRow` that has no other affordance, and a user might tap to expand /
get more info. We have nothing to expand to — but a `clickable {}` no-op
might confuse. **Just notes.** No change needed.

**Effort: 0**

---

### P2-9 · Reduce magic numbers in capsule chrome paddings

`SmartCapsuleSurface.kt:133`: `padding(top = spacing.sm, bottom = 6.dp)`,
and `CapsuleDivider.kt:195-201`: `padding(vertical = 6.dp)`. The 6dp is
"sm − xs / 2" — between `xs=4` and `sm=8`. Either define a `ClosePawSpacing.xxs
= 6.dp` (and own the value), or round to `spacing.sm` (8.dp) and adjust the
visual once. Tiny, but the codebase already names the philosophy ("Five
steps on the 4dp baseline grid" — `style.md:135`); 6.dp violates the grid.

**Fix:** snap all 6dp inside the capsule chrome to `spacing.sm` (8) or
`spacing.xs` (4); pick the one that looks closer in side-by-side.

**Effort: S**

---

### P2-10 · `OnboardingShell` `imePadding()` + `BoxWithConstraints` heuristic is clever but fragile

`ui/onboarding/OnboardingShell.kt:60-79` toggles between scroll and
fill-height based on `maxHeight < 480.dp || imeVisible`. The 480dp
threshold is a guess; on a tablet in split-screen the threshold can fire
incorrectly. The IME-up branch covers most failures, but the doubled
condition reads as "I'm not sure when this is wrong".

**Fix:** unconditionally `verticalScroll(rememberScrollState())`. The
`Spacer(weight(1f))` pattern in step bodies stops pinning CTAs to the
bottom, BUT step bodies are short enough that the CTA is reachable on any
phone — and on landscape phones (where it would scroll) reachability wins
over pinning. Net simpler, fewer edge cases.

**Effort: S**

---

### P2-11 · CompactPill / outcome footer string formatting in `AgentSummary.kt`

`ui/chat/components/AgentSummary.kt:44-47`:

```kotlin
deltaMs < 10_000 -> String.format("%.1fs", deltaMs / 1000.0)
else -> "${deltaMs / 1000}s"
```

`String.format` uses the default locale, so a French user sees `1,5s` in
the pill while every other mono ledger in the app uses `.`. Mono context
should always be locale-independent.

**Fix:** `String.format(Locale.US, ...)`. One-token.

**Effort: S**

---

### P2-12 · `PageMastheadDrillDown` close button is redundant on Settings drilldowns

Every settings sub-page has both `[ ‹ ]` back AND `[ × ]` close
(`Ornaments.kt:122-151`, `SettingsSheet.kt:189-211`). When the user is two
levels deep, both buttons do the same thing visually (return to chat). Back
goes one level; Close exits the sheet. The distinction is real but the icons
are next to each other on every page — many users tap whichever they hit
first.

This is more of a UX call than a bug; D1 is silent. **Recommendation:** keep
both; they're documented. If we want to simplify later, drop the close on
deep pages (>1 level), but the user research is missing. **No code change
recommended for the release.**

**Effort: 0**

---

## Cross-Cutting Recommendations (not numbered findings)

- **One inset policy.** Pick `AppWindowInsets` (already exists, already
  documented in its header), make every page-level container use it,
  ban raw `WindowInsets.statusBars` / `statusBarsPadding()` in feature code
  via a lint rule or PR-check grep.
- **One marquee.** P1-9 above; extract once.
- **One folded-paper card.** `SettingsCard` and `ToolSettingsCard` already
  unify most of settings. The two outliers (P1-3) are the last drift.
- **No bare RoundedCornerShape(N.dp).** P1-2; enforce via lint or grep.
- **Locale-safe number formatting.** P2-11; the codebase has exactly one
  mono ledger pattern; protect it.

---

## What's Already Good (notes for review, not action items)

These came up during the survey and are intentional / correct; calling them
out so the reviewer doesn't waste time re-deciding.

- `foldedPaper` is correctly NOT applied to inline alerts, radio rows
  inside cards, or to user bubbles — those would muddle the hierarchy
  (style.md:189-195).
- `StreamingText` cursor uses serifItalic at `primary` — that's the brand
  cursor; intentional Claw scarcity exception per D1.
- `ThinkingIndicator` lives on `OnSurface` (ink), not Claw, per
  ThinkingIndicatorTintTest. Don't "fix" this to Claw.
- The N1 ledger latch in `SmartCapsuleSurface.kt:113-121` is load-bearing —
  recreating `runningStartedAtMs` on every thought update would reset the
  ledger every tick. The codex review comment block in the file is real;
  preserve.
- `CapsuleStateHolder.recreateMode = true` (referenced from same file) is
  load-bearing for the ledger contract above.
- The `AlertTone.Warning` mapping to `onSurface` (not `tertiary`) in
  `ToolSettingsCard.kt:152-157` is a contrast fix — Amber on PaperInset
  fails AA. Don't revert.
- `LOCAL_TAB_ENABLED = false` in `LlmAuthSettingsPage.kt:72` is intentional
  pre-release; LFM is too slow to be useful but the plumbing stays wired
  for a future smaller model.

---

## Trade-offs

**Why not a single "design system pass" task?** The findings span genuinely
different cost classes: P0-1 is real touch-target work touching 6 files;
P0-2 is one literal. Bundling them hides the small wins behind the big one.
The numbered structure also lets us cut P2 from the release branch with no
loss.

**Why not introduce a `ClosePawButton` / `ClosePawIconButton` wrapper to
solve P0-1 systemically?** Considered, rejected. Material 3 components are
already idiomatic; the violations are concentrated in five files. Wrapping
adds another indirection without proportionate benefit. The fix is "obey
Material's `minimumInteractiveComponentSize()` and stop using
`Modifier.size(N.dp)` on tappable controls".

**Why keep the asymmetric `UserBubbleShape` (P1-2 exception)?** It's the
brand "folded corner" mark and intentionally asymmetric, so it cannot come
from `MaterialTheme.shapes`. Local constant is correct.

**Why P0 vs P1 for paperGrain (P0-2/P0-3) versus capsule touch targets
(P0-1)?** Touch targets fail accessibility audits and are the most
common reason an app fails store review for accessibility. Grain at 3×
strength + drawn-on-top is a *legibility* regression — users won't
articulate it, but contrast drops measurably. Both must ship-block.

---

## Tasks (for `/update-tasks`)

| Slug | Scope | Acceptance |
|---|---|---|
| `polish-touch-targets` | `ui/capsule/surface/Capsule*.kt`, `ui/onboarding/PermissionRepairCard.kt`, `ui/settings/AppAccessSettingsPage.kt`, `ui/chat/components/CompletionHandoffCtaRow.kt` | All tappable controls ≥ 48dp touch area (visual size may remain compact via wrapper Box). Existing snapshot/instrumentation tests still pass. |
| `polish-grain-strength` | `ui/theme/PaperGrain.kt` | Default `strength = 0.015f`. Manual visual check on Chat + Settings in light + dark. |
| `polish-grain-layer-chat` | `ui/chat/ChatScreen.kt` | Grain renders BEHIND Scaffold content. Snapshot test (or visual diff) shows ink characters unaffected by noise. |
| `polish-empty-state-hero` | `ui/chat/ChatScreen.kt` | Hero paw clears the masthead band on Pixel 5/7/8/Fold and tablet emulator; no clipping at status-bar = 0. |
| `polish-window-insets-policy` | `ui/chat/components/ChatHeader.kt`, `ui/onboarding/OnboardingShell.kt`, `ui/settings/SettingsSheet.kt`, `ui/navigation/*` | Every page-level container reaches insets via `AppWindowInsets`. No `WindowInsets.statusBars`/`statusBarsPadding()` in feature code. |
| `polish-chat-header-masthead` | `ui/chat/components/ChatHeader.kt` | ChatHeader uses `PageMastheadIdentity` (or matches its contract); leading paw is not duplicated next to the menu icon. |
| `polish-shape-tokens` | `ui/onboarding/PermissionRepairCard.kt`, `ui/navigation/NavigationDrawer.kt` | No `RoundedCornerShape(N.dp)` literals except `UserBubbleShape`. |
| `polish-permissions-cards` | `ui/settings/PermissionsAdvancedSettingsPage.kt` | Debug + Trace rows use `ToolSettingsCard` with `foldedPaper` chrome. Visual parity with rest of settings. |
| `polish-onboarding-title-style` | `ui/onboarding/OnboardingShell.kt`, `ui/theme/Type.kt` | Step titles render in Fraunces italic Regular at 28sp; no SemiBold synthesis. |
| `polish-user-bubble-color` | `ui/chat/components/MessageBubble.kt` | UserBubble is visually distinct from CapsuleSurface in both themes; user-perceived contrast preserved (AA on text). |
| `polish-local-tab-warning` | `ui/settings/LlmAuthSettingsPage.kt` | LocalTabContent header uses `SettingsAlertCard(tone = Warning)`. |
| `polish-switch-defaults` | `ui/theme/Tokens.kt` + 4 call sites | All `Switch(colors = SwitchDefaults.colors(...))` call sites use `closePawSwitchColors()`. |
| `polish-island-marquee` | `ui/overlay/compose/StatusIslandCompose.kt`, `ui/capsule/surface/SmartCapsuleSurface.kt`, new `MarqueeText.kt` | Marquee + reduced-motion fallback exists in exactly one place; both call sites use it. Existing reduced-motion tests pass. |
| `polish-island-max-width` | `ui/overlay/compose/StatusIslandCompose.kt` | Max text width scales with screen width; no clipping on 320dp viewport. |
| `polish-hero-paw-alpha` | `ui/chat/ChatScreen.kt` | Hero paw at reduced alpha so Claw scarcity is preserved against running-state surfaces. |
| `polish-input-bar-hint-spec` | `ui/capsule/surface/CapsuleInputBar.kt`, `ui/overlay/model/CapsuleRenderSpec.kt` | Disabled hint copy lives in `InputSpec`, not in the bar. |
| `polish-line-break-paragraph` | `ui/chat/components/MessageBubble.kt` | User bubbles wrap long URLs at any character (LineBreak.Paragraph). |
| `polish-locale-elapsed` | `ui/chat/components/AgentSummary.kt` | Elapsed mono ledger uses `Locale.US`. |
| `polish-startup-error-alert` | `ui/capsule/surface/SmartCapsuleSurface.kt` | StartupErrorBanner uses `SettingsAlertCard` with action slot. |
| `polish-island-insets` | `ui/overlay/compose/IslandOverlayHost.kt` | Status-bar height reads from WindowInsets on API 30+; legacy fallback below. |
| `polish-fleuron-version-semantics` | call sites of `Fleuron()` paired with version text | Section-break + version are announced together by screen reader. |
| `polish-app-access-segments` | `ui/settings/AppAccessSettingsPage.kt` | FilterChips + TierSegmentedSelector use Material `SingleChoiceSegmentedButtonRow`. |
| `polish-app-access-subtitle` | `ui/settings/SettingsHomePage.kt` | "App Access" loading-state subtitle reads "Counting installed apps…". |
| `polish-capsule-spacing-grid` | `ui/capsule/surface/SmartCapsuleSurface.kt` | All hardcoded `6.dp` paddings snap to `spacing.xs` or `spacing.sm`. |
| `polish-onboarding-scroll` | `ui/onboarding/OnboardingShell.kt` | Unconditional vertical scroll; remove `BoxWithConstraints` heuristic. |

Dependency notes:

- `polish-window-insets-policy` ⊃ `polish-empty-state-hero`,
  `polish-chat-header-masthead`. Do insets first; the others land cleanly.
- `polish-grain-strength` and `polish-grain-layer-chat` can land in one
  commit (both touch `ChatScreen.kt` / `PaperGrain.kt`).
- `polish-switch-defaults` should land before
  `polish-permissions-cards` so the refactor uses the new helper.
- `polish-touch-targets` is the single biggest patch but every file change
  is local — split into sub-PRs if review fatigue is a concern.

---

## Self-Review

Did the survey cover everything? Verified composables touched:

- Chat: ChatScreen, ChatHeader, EmptyState, MessageBubble (User/Agent),
  AgentRow, AgentTrace, CollapsePill, ThinkingIndicator,
  CompletionHandoffCtaRow, StreamingText, AgentSummary
- Capsule: SmartCapsuleSurface, CapsuleControlBar, CapsuleInputBar,
  SmartCapsuleHostLayout, StatusColors, StatusPawGlyph,
  CapsuleRenderSpec / CapsuleMode / NavSpec
- Overlay: CapsuleOverlayHost, IslandOverlayHost, StatusIslandCompose,
  EdgeGlowCompose, ActionVisualizerCompose, GlowOverlayHost
- Settings: SettingsSheet, SettingsHomePage, SettingsCard, SettingsAlertCard,
  SettingsWidgets, SettingsDropdown, ApiKeyFields, LlmAuthSettingsPage,
  AgentBehaviorSettingsPage, PermissionsAdvancedSettingsPage,
  MemorySettingsPage, AppAccessSettingsPage, AppRow, ToolSettingsCard,
  DisplaySection, ToolsSection
- Onboarding: OnboardingScreen, OnboardingShell, OnboardingSteps,
  PermissionRepairCard
- Navigation: NavigationDrawer
- Theme: Color, Type, Shape, Tokens, Theme, Motion, Ornaments,
  PaperGrain, WindowInsets
- Viewer: VirtualDisplayViewerActivity
- Common: ToolUi

Gaps that I deliberately did not deep-dive (out of scope for a polish pass
or already covered by existing tests / specs):

- `ui/capsule/voice/*` (Recognizer, VoiceInputController,
  VoicePermissionGate) — covered by their own tests; UI surfaces are the
  mic icon (P0-1) and toast plumbing.
- `ui/settings/MemoryFileEditor*` — internal editor; touch-target
  recommendations from P0-1 cover its IconButtons. Color/typography
  follows existing settings.
- `ui/overlay/compose/OverlayComposeHost`, `ServiceLifecycleOwner` —
  framework plumbing, not UI surface.
- `VirtualDisplayViewerActivity` — pure SurfaceView, no Compose chrome to
  polish beyond the overlay capsule which is already in scope.

Findings deliberately not raised:

- The grain bitmap is `remember`-cached per (strength, ink); changing
  `strength` in P0-2 invalidates the cache once, then steady-state, so
  no perf concern.
- The capsule debounce window (300ms in `CapsuleOverlayHost.kt:55`) is an
  interaction concern, not a polish one; outside this pass.
- The `interactionLocked` overlay full-screen scrim
  (`CapsuleOverlayHost.kt:193-214`) is an accessibility-sensitive feature
  but its presence is correct; the implementation is fine.
