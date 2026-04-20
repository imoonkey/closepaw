# Frontend Visual Baseline — Aligned Draft 2

**Authors:** Codex + Claude
**Draft owner:** Codex (first mover) → Claude (R2 resolutions)
**Date:** 2026-04-20
**Status:** Draft for alignment
**Scope:** Visual baseline for the frontend revamp. This doc aligns the original visual direction with the approved Track A chat-row model and keeps only the parts that survive KISS review.

## 1. Verdict

The baseline direction is good and does not need a rewrite. The aligned draft keeps the identity core: Paper/Ink/Claw, the paw glyph, the calm motion thesis, flat surfaces with one tactile capsule, and a stronger editorial tone than stock Material. The main refinements are: trim the shared token surface, make the motion rules real instead of aspirational, and translate the old chat ideas into Track A's actual row anatomy instead of keeping a parallel "editorial prose + ActionCard" model alive.

## 2. Design rules

1. **KISS first.** Fewer shared tokens, fewer motion primitives, fewer layout knobs.
2. **One chat model only.** The chat surface follows the Track A row model: one agent row per turn, chronological trace inside, optional Final block, one row-level disclosure axis.
3. **One identity system only.** Brand comes from palette, typography discipline, paw glyph, and calm motion. It does not come from a long tail of one-off tokens.
4. **Flat by default.** Only the capsule and modal sheet/drawer lift off the page.
5. **No color-only meaning.** The paw and semantic colors always pair with text or a status label.

## 3. Core direction

ClosePaw should read as a restrained editorial tool, not as a generic chatbot skin. The aligned first draft keeps:

- Warm paper surfaces instead of neutral white.
- Deep warm ink instead of pure black.
- One rare accent color (`Claw`) for moments that matter.
- One semantic success color (`Moss`), one pause/takeover color (`Amber`), and one error color (`Rust`).
- A paw glyph that replaces generic dots and generic loading dots.
- Typography with clear role separation: identity surfaces, operational UI/body text, and machine text.

It drops or defers decorative complexity that does not materially help capsule + chat + first-run surfaces.

## 4. Tokens

### 4.1 Color

Light palette ("Paper"):

- `Paper = #F5F1EA`, `PaperInset = #EDE7DC`
- `Ink = #14110F`, `InkMuted = #5C554C`, `InkFaint = #8B8278`
- `Claw = #C44528`
- `Moss = #4A5D3A`, `Amber = #E8A33D`, `Rust = #8B2E1F`
- `Hairline = 12% Ink` — cross-component dividers (settings rows, drawer entries, card borders)
- `InkGhost = 8% Ink` — in-content separators (Track A Trace ↔ Final rule, watermark paw)

Dark palette ("Lantern"), separate not inverted:

- `PaperDark = #0F0D0B`, `PaperInsetDark = #1A1612`
- `InkDark = #F0EAE0`, `InkMutedDark = #B9B0A3`, `InkFaintDark = #7A7268`
- `ClawDark = #E56B4A`
- `MossDark = #7A9466`, `AmberDark = #F2B960`, `RustDark = #D55A42`
- `HairlineDark = 12% InkDark`
- `InkGhostDark = 8% InkDark`

Aligned rules:

- `Claw` is scarce: Running capsule, primary CTA, paw watermark, drawer new-entry affordance. Not body text. Never more than two claw elements visible at once.
- Two divider levels only: `Hairline` (12%) between components, `InkGhost` (8%) inside a composed surface. Same rule mirrored in dark via `*Dark` counterparts.
- Named `*Soft` and `*Deep` variants are not part of the minimal aligned contract. Add only if implementation proves repeated use.
- Required handoff artifact: a measured contrast matrix for `Ink/InkMuted/Claw/Moss/Amber/Rust on Paper/PaperInset` and the dark counterparts. AA minimum for body, AA-large minimum for status text.

### 4.2 Typography

Three families:

- **Fraunces** (variable serif) — identity surfaces only.
- **Geist** (variable sans) — UI chrome, body text, chat prose including Thought items.
- **JetBrains Mono** — machine text only.

Shared extras (`AgentExtraTypography`):

- `monoBody` — JBMono 13sp, used for Action `tool_name(args)` and shell output.
- `monoSmall` — JBMono 11sp, used for ledger dates and small mono labels.
- `bodyItalic` — Geist italic at body size, used for Track A Thought items.
- `serifItalic` — Fraunces italic, used only on the empty-state question.

Removed from the shared token surface:

- `tgmTelegram` — repair card uses `labelSmall` (already 10sp / 1.2sp tracked caps) for the header.
- `romanNumeral` — onboarding watermark, if kept, is a local style on that screen.

Track A voice mapping (locked):

- Thought = `bodyItalic` (Geist italic)
- Action = `monoBody`
- Final = `bodyLarge` (Geist regular)

Rationale for Geist italic over Fraunces italic on Thought: a Track A row already mixes body sans (Final), italic (Thought), and mono (Action). Adding a serif face into that dense local mix is noise; serif is reserved for identity surfaces (empty state, onboarding, streaming cursor) where it lands on its own. Geist italic keeps the row coherent on a single body family with two style axes.

Other rules:

- Mono is reserved for explicitly machine-like text. It is not a mode-based swap for all "executing" prose. The capsule follows the same rule.
- The streaming cursor on the Final block is a serif I-beam (`|`) in Fraunces — the one identity exception inside the chat row. **Engineering verification required** during Phase 3 implementation: confirm Compose's text cursor can be restyled per-stream; if not, fall back to a sans I-beam in Geist at the same cadence.

### 4.3 Shape

The aligned system does not need the full Material radius ladder.

Use three named radii:

- `small = 8dp` for controls and fields
- `card = 10dp` for user bubble / inset surfaces
- `pill = 16dp` for capsule and pill-like chrome

Aligned rules:

- User bubble is symmetric.
- Capsule uses the pill radius.
- `PawStamp` is removed. Paw geometry belongs in the drawable, not in the shape system.

### 4.4 Elevation

Keep one tactile elevation treatment:

- A folded-paper modifier: subtle warm under-shadow plus a top hairline.

Aligned rules:

- Capsule uses it.
- Modal sheet/drawer may use it.
- Everything else stays flat.

### 4.5 Spacing

4pt baseline grid. Five named tiers, no more:

- `xs = 4dp`
- `sm = 8dp`
- `md = 12dp`
- `lg = 20dp`
- `xl = 32dp`

Aligned rules:

- Horizontal page padding is `lg` across primary screens. Hard invariant.
- `xxl = 52dp` is removed.
- Track A trace-item vertical spacing is `sm` (8dp). Originally drafted as `6dp` in the Track A spec; aligned to the 4pt grid here. The Track A doc should be updated to match — trace items already have glyph differentiation (`✱` Thought / `→` Action), so 8dp gives air without losing grouping.

## 5. Motion

### 5.1 Motion contract

The aligned contract is:

- Durations: `120 / 240 / 480 / 900 ms`
- Easings: `EaseInOutSine` and `EaseOutCubic`
- No springs, no overshoot

This is a real constraint, not a slogan. Any motion that does not fit this contract is out of the aligned first draft.

### 5.2 Keep

- Capsule breath in Running only
- Simple capsule mode transitions
- Thinking indicator based on paw toes
- 120ms glyph/status flips
- Reduced-motion fallbacks that replace movement with short fades or instant state changes

### 5.3 Simplify or defer

- Edge glow keeps the softer radial falloff direction. Ambient drift (the original 8s wobble) is **cut**, not deferred — it invents a third motion period that pretends to be subtle and breaks the four-duration contract.
- Action visualizer for v1:
  - tap = single ink-drop ring (one ring + filled center, no satellite ring)
  - long-press = ring holds at max radius with a 900ms inner-fill pulse
  - swipe = tapered straight stroke (4dp → 2dp) + paw stamp at destination
- Satellite ring on tap, perlin-wobble on swipe, and other flourish are deferred until the base motion language is shipping.
- Cold-start orchestration is Phase 5 polish, not part of the baseline contract.

## 6. Surface guidance

### 6.1 Capsule

The capsule is the signature surface.

Keep:

- paw glyph instead of a generic status dot
- folded-paper pill
- semantic color by mode
- breath only in Running
- freeze/blink/error behavior by mode
- emoji removal from capsule chrome

Aligned rules:

- Capsule copy stays simple and readable.
- Mono is allowed only for explicitly machine-like strings, not as a blanket "executing mode" font swap.

### 6.2 Chat

The chat surface follows the Track A row model and does not keep a second component model alive.

Keep:

- user bubble on `PaperInset` with symmetric corners
- no agent bubble/card around the full row
- inline action styling that borrows receipt primitives
- paw-toe thinking indicator
- large paw watermark on the empty state
- italic question on the empty state

Aligned rules:

- The agent row is `Trace* + optional Final + optional Footer`.
- Action rows are inline trace rows, not standalone cards.
- There is one disclosure axis only: the row.
- Thought/Action/Final styling must support scanability before flourish.
- **No claw-red left tick anywhere in the chat row** — neither on Trace items nor on Final. Identity in chat comes from typography (Geist italic Thought, mono Action, body Final, serif streaming cursor) and the `InkGhost` hairline above Final. The claw accent stays scarce: capsule, primary CTA, watermark, drawer new-entry.
- Trace markers: Thought uses the existing `ic_paw` rendered at 10dp tinted `Ink`; Action uses the Geist text glyph `U+2192`. No new vector assets.
- Glyph column: 16dp wide, baseline-aligned to first text line of the trace item.
- Action status glyphs (`⏳ ✓ ✕ ⊘`) right-aligned, paired with text in the accessibility tree (never color alone).

### 6.3 Settings

Keep:

- serif section heads
- restrained hairline dividers
- mono `→` glyph instead of decorative arrows
- mono API-key fields with a clearer focus treatment

Drop:

- extra ornament that hurts scanability

### 6.4 Onboarding

Keep:

- five-paw progress treatment
- strong chapter title treatment

Aligned rules:

- Large roman-numeral watermarks are not shared tokens.
- If onboarding keeps the watermark, treat it as a local Phase 4 flourish and validate it on small screens first.

### 6.5 Permission repair

Keep the urgent system tone, but do it with existing styles:

- tracked-caps header via existing label style
- mono body

Do not keep a dedicated telegram typography token.

### 6.6 Drawer

Keep:

- ledger treatment
- mono dates
- one claw-accented new-entry affordance
- restrained settings link treatment

## 7. Track A compatibility

This aligned visual baseline is explicitly shaped around the approved Track A row model.

Required compatibilities:

- Thought items render in `bodyItalic` (Geist italic).
- Action items render in `monoBody`.
- Final renders in `bodyLarge` (Geist regular) at the bottom of the row.
- The rule between Trace and Final is `InkGhost` (8% Ink) in light, `InkGhostDark` in dark.
- Trace-item vertical spacing is `sm` (8dp), aligned to the 4pt grid.
- The row stays flat on the page rather than becoming a nested card system.
- The old "ActionCard with its own expandable output" model does not survive as a separate interaction model.
- No claw left accent in chat; identity is typographic.

Spec sync required:

- Track A's `track-a/final/design_aligned.md` §4.6 currently says `6dp` trace spacing. That value is superseded here; Track A doc should be updated to `8dp`. The change is purely visual — no reducer/state-machine impact.

Implementation consequence:

- Roadmap chat work must be rewritten against the Track A row anatomy.
- Track A is an input to Phase 3 chat work, not an optional add-on after it.

## 8. Accessibility and contrast

The aligned baseline keeps the original accessibility intent and tightens it:

- `Claw` is not used for body text.
- Status is never conveyed by color alone — paw glyph and semantic colors always pair with a text label or accessibility description.
- Dark mode requires the same measured contrast matrix as light mode (see §4.1 handoff artifact).
- Reduced-motion: replace slide-in with instant + 120ms fade; collapse/expand becomes instant; breath pauses to a static paw at full alpha.

## 9. Phase shape

The first aligned draft implies this phase order:

1. **Identity foundation:** palette, fonts, paw glyph, theme wiring
2. **Capsule:** paw, motion cleanup, folded-paper treatment, emoji removal
3. **Chat:** Track A row styling, inline action rows, empty state, thinking indicator, cursor treatment
4. **Settings / Onboarding / Drawer:** apply the lighter editorial language to lower-frequency surfaces
5. **Polish:** only motion/details that still earn their keep after the core surfaces are working

## Open Questions

1. **Streaming-cursor feasibility.** The Final block uses a serif I-beam in Fraunces while streaming. Compose's text cursor is not trivially restyle-able mid-stream. Phase 3 implementation must verify; if blocked, fall back to a Geist sans I-beam at the same blink cadence. Reflect outcome here once known.
