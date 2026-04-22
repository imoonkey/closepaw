# UI Refresh — 2026-04-22 ("Bound Edition")

> Post-D2 polish round. Not a rebuild. The D1 token system, Track A row model,
> motion vocabulary, and folded-paper chrome are all keepers — this round
> tightens the **page personality** that the system earned but hasn't yet shown.

## One-line direction

> **From "tasteful editorial baseline" to "a piece of bound editorial print you carry in your pocket."**

The system already has the materials (Paper, Ink, Claw, Fraunces italic, paw glyph,
mono ledger). What it's missing is *evidence of the hand that set it*: marginalia,
running heads, fleurons between sections, paper grain, asymmetric composition,
running counters. Those are the ornaments that turn a Material 3 surface dressed
in warm tokens into something a reader recognizes from across the room.

## Scope

In:

- **ChatHeader** — give the brand a body. Currently `[Menu] ClosePaw [+]` 56dp Material standard.
- **EmptyState** — push from centered grid to asymmetric editorial first-page.
- **MessageBubble (User)** — drop the chip-shape box, adopt a "letter to the editor" treatment.
- **AgentRow ornaments** — replace the 8%-Ink hairline `FinalSeparator` with a centered fleuron; replace `▸` collapse glyph with bracketed mono `[+]/[−]`.
- **ThinkingIndicator** — lose the surfaceVariant chip, render the paw-toe canvas inline with a serif-italic "Thinking…".
- **OnboardingShell** — five-paw progress contrast bump (current `outlineVariant` is invisible in light mode); add running date in monoSmall.
- **NavigationDrawer header** — "Sessions" in actually-loaded Fraunces italic Regular (current SemiBold is synthesized, since only Regular + Italic ship).
- **SettingsHomePage** — add section grouping with serif-italic subheads; centered fleuron version footer.
- **CapsuleControlBar** — Stop button gets full Rust treatment (border, text, icon — currently mixed).
- **CapsuleStatusLine (Running)** — prepend mono `[t+12s]` ledger counter.
- **Page chrome primitive** — small running-head composable (`PageMasthead`) that any top-level surface can opt into.
- **Paper grain primitive** — opt-in Modifier that bakes a 1.5%-alpha noise texture once.
- **First-paint choreography** — staggered EmptyState reveal using existing `TraceEnter`.

Out:

- New color slots, new radii, new font families, new motion durations.
- Touching the D1 token surface (`ClosePawTokens`) signature beyond adding two ornament helpers (`fleuron` glyph, `grain` modifier).
- Reworking Track A row state machine (`RowState`, collapse policy).
- Changing the Smart Capsule state-machine contract or the overlay rendering path.
- Reduced-motion contract changes — every new motion respects `ClosePawMotion.reducedMotion()`.

## Files in this folder

| File | Purpose |
|---|---|
| `README.md` | this file |
| `critique.md` | current-state review with file:line references, severity-tagged |
| `direction.md` | "Bound Edition" aesthetic vision and the 8 ornament moves |
| `changes.md` | per-file concrete change list with code sketches |
| `motion.md` | choreography deltas (all reuse existing `ClosePawMotion` durations) |
| `plan.md` | phased implementation order, verify gates |
| `non-regressions.md` | what we're explicitly *not* undoing from D1/D2 |

## Success criteria

A user opening the app for the first time should be able to describe what they
saw without saying "an AI app" or "a chat app":

- [ ] First screen reads as **a printed page**, not a chat empty state.
- [ ] Brand identity is present **after** the first message is sent (not only on EmptyState).
- [ ] The Smart Capsule running state shows **elapsed time** as a ledger counter.
- [ ] No Material slot is using a synthesized weight (Fraunces SemiBold currently is).
- [ ] No primitive ornament (fleuron, masthead, grain) lives outside `ui/theme/` —
      ornaments are tokenized, not call-site one-offs.
- [ ] All new motion respects `ClosePawMotion.reducedMotion()`.
- [ ] `./gradlew lint test` clean. No new test failures, no string changes that
      break QA test tags.
