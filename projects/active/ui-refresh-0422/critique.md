# Critique — current state, post-D2

> Reviewed: 2026-04-22. Files inspected: theme/*, chat/*, capsule/surface/*,
> onboarding/{OnboardingScreen,OnboardingShell}, settings/SettingsHomePage,
> navigation/NavigationDrawer.
>
> Severity legend: **[H]** = breaks brand identity or trust signal,
> **[M]** = competent but generic, **[L]** = polish.

## What's working (keep these)

- **D1 token system in `ui/theme/`** is mature: warm Paper/Ink/Claw palette, three
  named radii, three font families, named motion primitives, reduced-motion
  contract, `foldedPaper` chrome modifier. The seam between Material 3 slots
  and `ClosePawTokens` is clean — feature code reaches for Material first and
  D1-residue only for what Material doesn't model. Don't perturb the contract.
- **Smart Capsule surface** (`SmartCapsuleSurface.kt:93`) — `foldedPaper(shape)`
  with subtle warm under-shadow + top hairline is the strongest *physical* moment
  in the UI. Use it as the reference for what the rest of the app should aspire
  to feel like.
- **Track A `AgentRow`** (`MessageBubble.kt:115`) — the chronological
  Trace + Final model with row-level disclosure is conceptually right and
  semantically labelled (`stateDescription`). Don't restructure; only re-ornament.
- **`StatusPawGlyph`** (`StatusPawGlyph.kt:25`) — paw-as-status-dot is the
  identity move that pays for itself everywhere it appears.

## Findings

### [H] The brand collapses the moment you send a message
- `ChatHeader.kt:72` — title is `"ClosePaw"` in `Geist Medium`. No paw glyph,
  no Fraunces, no ornament. Once the EmptyState (the only place the brand
  actually lives) scrolls away, the chrome reads as any-app-anywhere.
- The header has 56dp of vertical real estate and uses ~0% of it for identity.
- **Move:** small paw glyph + `ClosePaw` set in Fraunces italic Regular, with
  `Today · Apr 22` running date in `monoSmall` underneath, OR a centered paw
  + a mono "running number" that ticks across surfaces (sessions list, settings).
  See `direction.md` §1 (Page Masthead).

### [H] `Fraunces SemiBold` is being synthesized
- `Type.kt:18-20` ships only `fraunces_regular` and `fraunces_italic`.
- `EmptyState.kt:58` uses `headlineMedium.copy(fontFamily = Fraunces)` then
  forces `fontWeight = FontWeight.SemiBold` → Compose synthesizes the weight,
  blurring the glyph edges on a serif at display size.
- `NavigationDrawer.kt:175` does the same on `titleLarge.copy(fontFamily = Fraunces)`
  with `FontWeight.SemiBold`.
- `OnboardingShell.kt:104` uses `displaySmall.copy(fontFamily = Fraunces)` —
  `displaySmall` is `FontWeight.SemiBold` in the typography table. Same
  synthesis problem at the largest possible Fraunces moment.
- **Move:** standardize on Fraunces *italic Regular* for every identity-tier
  string, or ship a Fraunces medium/semibold TTF if the upright weight is
  visually load-bearing. See `changes.md` §Type.

### [H] Stop button is a Rust costume on a default-outline body
- `CapsuleControlBar.kt:140-167` — `OutlinedButton` with `MaterialTheme.colorScheme.error`
  text + icon, but `border` is `ButtonDefaults` default → resolves to `outline`
  (12% Ink). The destructive verb reads as "muted text + warm icon," not "stop."
- **Move:** explicit `BorderStroke(1.dp, MaterialTheme.colorScheme.error)`.

### [H] `FinalSeparator` between Trace and Final is a barely-visible JPEG line
- `MessageBubble.kt:262-272` — 1dp `outlineVariant` (8% Ink). On a Paper
  surface the rule is below the just-noticeable-difference threshold for many
  displays at typical brightness. The "switch from agent reasoning to its
  answer" beat — one of the most important rhythm moments in the chat — has
  no perceptual signal.
- **Move:** centered Fraunces fleuron (`❦` U+2767) at `inkFaint`, no rule.
  Restores the editorial-print metaphor and is unambiguously visible.

### [M] EmptyState is grid-centered and predictable
- `EmptyState.kt:32-94` — paw → title → subtitle → 3 vertically stacked chips,
  all centered. Functional, but reads as the default "AI app empty state"
  layout. Nothing about its composition is unforgettable.
- The chips quote the suggestion text in actual `"…"` ASCII quotes
  (`EmptyState.kt:112`) — the quotation marks are a tell of "this is a
  suggestion the AI is voicing," which is exactly the wrong attribution
  (the *user* is saying it).
- **Move:** asymmetric composition. Paw bleeds top-right ~240dp at 80% alpha;
  headline left-aligned, lower-third; suggestions as marginalia rows
  (`→ verb in mono` + italic gloss in serif). Stagger reveal on first paint.
  See `direction.md` §3.

### [M] User bubble is a tan box
- `MessageBubble.kt:81-93` — `Surface(shape = medium, color = surfaceVariant)`.
  PaperInset on Paper. Reads as "tagged content," not "you said this."
  Editorial paper would let the user's voice *sit on the page*, not in a card.
- **Move:** drop the Surface; right-align the text, give it a 1dp Hairline
  *left margin rule* (the one piece of chrome) running 100% of the bubble's
  height. Letter-to-the-editor aesthetic. Timestamp inline at the bottom-right
  in `monoSmall`.

### [M] Thinking indicator is trapped in a chip
- `ThinkingIndicator.kt:38-52` — the carefully crafted paw-toe animation lives
  inside a `Surface(shape = large, color = surfaceVariant)` 28dp tall. It
  reads as "another bubble," not as "the agent is thinking."
- **Move:** render the paw-toe canvas naked, 28dp on the left, with the word
  `Thinking…` set in `bodyItalic` (`MaterialTheme.closePaw.bodyItalic`) to its
  right at `inkMuted`. No chrome.

### [M] Five-paw onboarding progress is invisible in light mode
- `OnboardingShell.kt:128-135` — unfilled paws use `outlineVariant` (8% Ink).
  On Paper, that's ~`#DCD7CD` — basically invisible at 20dp.
- **Move:** unfilled = `inkFaint` (`#8B8278`, ~36% effective Ink); filled = `primary` (Claw).

### [M] Suggestion chips, ledger dates, and timestamps share a mono voice that doesn't extend to the rest of the app
- The drawer shows `JetBrains Mono` for relative dates (`NavigationDrawer.kt:300-304`)
  and the settings entry version (`NavigationDrawer.kt:386-390`). Good — that's
  the "machine ledger" register working. But it stops at the drawer.
- **Move:** extend mono to (a) capsule running counter `[t+12s]`,
  (b) settings home subtitles' numeric chips (`60 turns`, `v0.5.x`), (c) a
  small running header on every top-level page (Page Masthead, see direction).

### [M] CapsuleControlBar primary button uses Amber (tertiaryContainer); send button uses Claw (primary)
- This is *correct* per D1 (Claw scarce, Amber for "do it now") but the visual
  difference between the two CTAs is inconsistent — the most-pressed button on
  the most-visible surface is *not* the brand color.
- **Move:** keep Amber for capsule-mode primary actions (Takeover/Resume/Done/Allow),
  but make the Claw send-button stand-out moment count: add a one-frame breath
  on submit (240ms scale 1.0 → 1.04 → 1.0) so the brand color *does something*
  at the moment the user commits.

### [L] StartupErrorBanner is anonymous Material errorContainer
- `SmartCapsuleSurface.kt:185-220` — generic colored card with a Close. No sigil,
  no Rust hairline. Doesn't feel like part of the same designed system as the
  capsule above it.
- **Move:** add a 2dp Rust left rule + leading `⚠` glyph in `monoBody` at Rust.

### [L] "live" pill is a Material rounded chip
- `ChatScreen.kt:301-333` — `Surface(shape = extraLarge)` with arrow + "live".
  Generic.
- **Move:** small mono pill `↓ live · N new` (when N>0) in `monoSmall` at
  `onSurfaceVariant`, with an explicit border at `outline`. Same rhythm as
  the Capsule running counter.

### [L] SettingsHomePage is three rows in a column with no grouping
- `SettingsHomePage.kt:39-78` — flat list. Each row's subtitle is well-composed
  (`agentBehaviorSubtitle`, `permissionsSubtitle`) but the page has no shape
  beyond its rows.
- **Move:** wrap in three section blocks with serif-italic subheads:
  *Voice* (LLM), *Behavior* (Agent), *System* (Permissions). Centered fleuron
  ornament above the version footer.

### [L] No paper grain
- The whole metaphor is "Paper." Solid `#F5F1EA` is *beige*, not paper. A
  1.5%-alpha noise overlay (baked once via `drawWithCache` to an ImageBitmap,
  reused for every surface) lands the metaphor without runtime cost.
- Dark mode ("Lantern"): skip noise; use a faint warm radial vignette instead.

## Anti-findings (do *not* "fix" these)

- The `foldedPaper` modifier is correctly limited to capsule + modal chrome
  (`Tokens.kt:71`). Don't apply it to chat bubbles or onboarding cards — the
  scarcity is the point.
- `ClosePawShapes` (`Shape.kt:8`) ships exactly three radii. Don't add a
  "bubble shape" or "input shape." The chip-vs-bubble vocabulary is in the
  call sites, not the theme.
- The Track A row collapse policy (`MessageBubble.kt:122-131`) — tri-state
  user-override-wins-over-default — is subtle and correct. Re-ornament freely;
  do not touch the policy.
- Reduced-motion handling is read once per call site by design
  (`Motion.kt:46-56`). Don't introduce a global animation wrapper.
