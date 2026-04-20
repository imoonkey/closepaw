# Track D1 — Visual Baseline Review (Claude, independent draft)

**Reviewer:** Claude (Opus 4.7), independent of Codex.
**Date:** 2026-04-20
**Scope:** `doc/todo/frontend-ui-review/{README, design-tokens, motion-spec, roadmap}.md`.
**Lens:** KISS. The aligned spec must support Track A's chat row + the existing Smart Capsule modes, and *nothing else*.

---

## 1. Verdict (one paragraph)

The baseline is a strong opinionated direction with a real soul ("editorial field journal for an intelligent animal") and most of it earns its keep. The Paper/Ink/Claw palette, three-voice typography (Fraunces / Geist / JetBrains Mono), and the Paw glyph are the brand and should be locked in. The 4-duration / 2-easing motion rule is unusually disciplined — keep it as a hard contract. **What I would cut:** roughly 25% of the surface area is identity flex aimed at onboarding/permission surfaces (roman-numeral chapter watermarks, telegram-style repair card, an `xxl` spacing token, a `PawStamp` shape, and a per-tool serif italic typography style with no consumer outside Track A). These don't support the chat + capsule loop and should be deferred to Phase 5 or dropped. **What I would tighten:** the `AgentExtraTypography` surface, the spacing scale, and the resolved alignment with Track A's hairline + glyph choices. Net: the aligned spec is the baseline minus ~15 incidental tokens/decisions, plus 4 explicit Track A clarifications.

---

## 2. Tokens

### 2.1 Color — KEEP AS-IS

The palette is the brand. No changes.

- Light "Paper" + dark "Lantern" as separate palettes (not inverted) — keep.
- Claw scarcity rule ("never more than two claw-colored elements visible at once") — keep, this is doing real work.
- Capsule mode → color mapping table — keep verbatim; matches the existing `CapsuleMode` sealed class.
- Contrast: `Claw on Paper = 5.4:1` is documented as AA-large only; **clarify**: Claw is never used for body text, only on filled CTAs and the paw glyph. Make this an explicit invariant (not just a footnote).

**One rename for honesty:** `InkGhost` (8% ink) is currently described as "watermark paw, micro-dividers." Track A also needs an "8% ink" hairline rule between Trace and Final. Rename to `InkHairline` is unnecessary — the existing semantic ("ghost ink") is fine. Just document the dual use. (See §5 Track A check.)

### 2.2 Typography — TRIM

Three-font choice (Fraunces / Geist / JetBrains Mono) is the right answer. The Material `Typography` block is well-judged. Trim the **extras**:

| Extra | Used by | Verdict |
|---|---|---|
| `monoBody` (JBMono 13sp) | ActionCard tool name, shell output | **KEEP** — load-bearing for chat. |
| `monoSmall` (JBMono 11sp) | Ledger dates, tool names | **KEEP** — drawer. |
| `serifItalic` (Fraunces Italic) | Empty state question | **KEEP** — and Track A also needs it for Thought items (§5). |
| `tgmTelegram` (Geist 11sp ALL CAPS, 1.2sp tracking) | PermissionRepairCard | **CUT** — this is `labelSmall` (already 10sp / 1.2sp tracking) bumped one px. Use `labelSmall` directly. |
| `romanNumeral` (Fraunces 120sp) | Onboarding chapter watermarks | **CUT from tokens; defer to Phase 5.** A one-off display style on one screen is not a token. If onboarding wants it, declare it inline. |

Net: `AgentExtraTypography` keeps 3 styles, drops 2.

### 2.3 Shape — TRIM

```
extraSmall = 4dp   chips
small      = 8dp   buttons, text fields
medium     = 10dp  cards, capsule pill
large      = 14dp  sheets
extraLarge = 18dp  drawer, modal
```

Keep the 5-step Material shapes ladder. Keep `BubbleUser = 10dp` and `CapsulePill = 16dp` as named exports (they are intentional overrides of the ladder).

**Cut `PawStamp = RoundedCornerShape(50)`.** A circle for a vector glyph is not a `Shape` — the glyph is already a vector path. This token has no consumer.

### 2.4 Elevation — KEEP AS-IS

Two-layer "folded paper" (warm under-shadow + 1px hairline top edge) is the right call. It replaces Material `shadow(...)` with a token that fits the brand. Keep the rule that **only** Capsule and ModalDrawer lift; everything else sits flat.

### 2.5 Spacing — TRIM

```
xs  = 4dp
sm  = 8dp
md  = 12dp
lg  = 20dp
xl  = 32dp
```

**Cut `xxl = 52dp`.** Only consumer was "chapter spreads, empty state." Empty state can use `xl`. Chapter spreads are deferred (see §4 Onboarding). 5 spacing tokens is plenty; the 6th is decoration.

The "horizontal page padding = `lg` everywhere" rule is golden — keep it as a hard invariant, not a suggestion.

### 2.6 Net token diff vs baseline

| Cut | Add | Renamed |
|---|---|---|
| `AgentExtraTypography.tgmTelegram` | — | — |
| `AgentExtraTypography.romanNumeral` | — | — |
| `Spacing.xxl` | — | — |
| `Shape.PawStamp` | — | — |

Four cuts, zero adds, zero renames. The baseline's bones are right.

---

## 3. Motion

### 3.1 Hard rules — KEEP, PROMOTE TO CONTRACT

- 4 durations only: `120 / 240 / 480 / 900 ms`.
- 2 easings only: `EaseInOutSine` (loop) / `EaseOutCubic` (entry).
- 1 orchestrated entrance per screen (cold-start only).

Promote these from "guidelines" to **invariants enforced in code review**: any `tween(...)` outside the duration set or any `spring`/`overshoot` reference fails review. The discipline is the entire value of the motion spec.

### 3.2 The Breath — KEEP

Capsule paw breath (1.0 ↔ 1.04, 900ms inhale / 900ms exhale, alpha 0.85 ↔ 1.0). Pause when `lifecycle.state < RESUMED`. This is the signature motion. Keep.

### 3.3 Mode transitions — KEEP

`AnimatedContent` between Capsule modes with `slideInVertically + fadeIn` 240ms. Special-case overrides (Done blink, Error shake, Takeover freeze, cold-Hidden→Running stamp) earn their keep — each maps to a real semantic distinction.

### 3.4 Action Visualizer — KEEP TAP / SIMPLIFY SWIPE

- Tap (ink-drop + satellite ring) — keep.
- Long-press (ring freezes + pulsing inner fill) — keep.
- Swipe (Perlin-noise wobble + paw stamp at destination) — **simplify for v1**: ship a straight tapered line (4dp → 2dp) + paw stamp. **DEFER** Perlin wobble to Phase 5 polish. The wobble is delightful but adds per-frame noise generation; the tapered line + stamp already reads as "deliberate hand-drawn intent" relative to the current uniform line.

### 3.5 Thinking indicator — KEEP

Three paw-toes filling sequentially over 900ms. Tempo-locks with Breath. Keep verbatim.

### 3.6 Cold-start orchestrated entrance — KEEP

The 1.32s staggered choreography is the *one* delight moment. Deletion would not save complexity worth caring about, and it is a real first-impression payoff. Keep.

### 3.7 Edge glow drift — KEEP

8s drift is offbeat-by-design vs the 1.8s breath. Adds one infinite transition. Worth it — the edge glow is otherwise a static gradient.

### 3.8 What NOT to animate — KEEP, ADD ONE

Keep all six "no" rules (no button press scale, no ripple, no list enter anims, no skeletons, no parallax, etc.).

**Add:** "No animation for trace-item arrival in collapsed rows." Track A's row collapse implies that when a `Complete` row is collapsed, new trace events don't arrive (the row is sealed). This is a no-op clarification but worth pinning so we never get tempted to animate a count badge.

---

## 4. Layout & component-level decisions

### 4.1 Capsule (signature surface) — VALIDATE

All baseline decisions ratified:
- Paw glyph replaces 8dp dot.
- Folded-paper pill replaces `shadow(4.dp)`.
- Mono switch when agent is *executing* vs *narrating* — keep the rule but note it must align with Track A's distinction (Track A renders Thought as serif italic in chat; the capsule may still use mono for live execution status — different surfaces, different voices is OK).
- Emoji removal from `CapsuleRenderSpec` — non-negotiable, KEEP.

### 4.2 Chat (largest surface) — VALIDATE + ALIGN WITH TRACK A

Baseline decisions all stand:
- Remove agent bubble; render as paper-prose with 3px claw left tick.
- User bubble = `PaperInset`, symmetric 10dp corners (not the asymmetric "tail").
- ActionCard = typeset receipt, no fill, hairline rules.
- Three-toe thinking indicator.
- Serif I-beam streaming cursor.
- Empty state: 160dp paw watermark + Fraunces italic question.

**Alignment with Track A (§5 below has details):** the chat row spec from Track A consumes these primitives directly. No conflicts; small clarifications only.

### 4.3 Settings — VALIDATE WITH ONE TRIM

- Section heads: Fraunces SemiBold + hanging numerals — KEEP.
- Hairline row dividers (`Hairline` 12% ink) — KEEP.
- Mono `→` glyph instead of arrow icons — KEEP (saves an icon set).
- `ApiKeyFields` mono with claw focus border — KEEP.

No changes.

### 4.4 Onboarding — DEFER FLOURISHES

**Cut from v1:** 120sp Fraunces roman numeral watermarks per chapter. **DEFER to Phase 5.**

**Keep for v1:** five paw-prints progress indicator (replaces progress bar), Fraunces chapter titles at `headlineLarge`. That alone gives onboarding identity without a bespoke 120sp display style.

Rationale: onboarding runs **once** per install. Roman-numeral watermarks are the highest-ratio of identity-payoff to maintenance-cost on a screen the user sees once. Defer.

### 4.5 Permission Repair Card — DEFER TELEGRAM TREATMENT

Use `labelSmall` (already 10sp / 1.2sp tracking ALL CAPS in the Material slot) for the header instead of a custom `tgmTelegram` style. Card body in `bodyMedium`. The "telegram" feel comes from caps + tracking, not from a new token.

### 4.6 Drawer — VALIDATE

Ledger treatment, mono dates (`monoSmall`), claw-red "New entry" at top, mono `// preferences` at bottom. All valid. No changes.

### 4.7 Track A row chrome — RATIFY

Track A §4.6: "No surrounding bubble or card. The trace items sit on the page background, separated by 6dp vertical spacing." 6dp is between `xs` (4) and `sm` (8) — **clarify**: Track A's 6dp is intra-row spacing for trace-item lines, not a new spacing token. It is a local layout constant, not promoted.

---

## 5. Track A compatibility check

I compared the baseline tokens against every visual decision in `track-a/final/design_aligned.md`. Findings:

| Track A need | Baseline support | Status |
|---|---|---|
| Italic Thought trace items (§4.1) | `AgentExtraTypography.serifItalic` (Fraunces Italic) | ✅ Use it. Confirms `serifItalic` belongs in the kept-extras set. |
| Mono `tool_name(args)` for Action items (§4.2) | `AgentExtraTypography.monoBody` | ✅ Direct fit. |
| Hairline rule between Final and Trace (§4) — "ink @ 8%" | Baseline `InkGhost = 0x14/14110F` (8%); also `Hairline = 0x1F/14110F` (12%) | ⚠️ **Resolve:** Track A uses 8%; baseline reserves 8% (`InkGhost`) for watermarks and 12% (`Hairline`) for borders. **Aligned decision:** in-row separators inside the chat row use `InkGhost` (8%); cross-component dividers (settings rows, drawer entries) use `Hairline` (12%). Two semantic levels, one for "inside a piece of content," one for "between pieces of content." |
| Trace glyphs `✱` (Thought) and `→` (Action) | Baseline paw vector exists (`ic_paw.xml`); no glyph spec for these | ⚠️ **Resolve:** `✱` = the existing 10dp `ic_paw` (single toe-pad variant or the full glyph at 10dp tinted `Ink`). `→` = Geist text glyph (`U+2192`), not a custom asset. Avoids new vector assets. |
| Action status glyphs `⏳ ✓ ✕ ⊘` | Material symbols + colors (Moss / Rust / InkMuted) | ✅ Use existing semantic colors. `⏳` while running = `InkMuted`; `✓` = `Moss`; `✕` = `Rust`; `⊘` = `InkMuted`. |
| Streaming cursor on Final (§4.4) | Baseline serif I-beam `\|` in Fraunces | ✅ Direct fit. |
| Outcome footer single line (§4.5) | `labelMedium` (Geist 11sp / 0.8sp tracking) | ✅ Direct fit; no new token. |
| 240ms slide-in for trace items / 120ms cross-fade for status flips (§8) | Baseline duration set `{120, 240, 480, 900}` | ✅ All within set. |

**No conflicts. Two clarifications resolved (8%-vs-12% hairline + glyph asset choice).**

---

## 6. Open questions for cross-review

1. **Three fonts vs two?** The most aggressive KISS cut would be to drop one of {Fraunces, JetBrains Mono} and use weight/style alone for distinction. I considered this and rejected it: Fraunces carries identity (the "field journal" feel), Mono carries truth ("the machine speaking"). Cutting either collapses a meaningful semantic axis. But it is the one thing where the second reviewer might disagree.
2. **Italic Fraunces availability.** Variable Fraunces ships with an italic variant — confirm we are loading it. If not, the Track A italic Thought item would need an inline `Font(R.font.fraunces_italic_variable)` declaration.
3. **Capsule mono-when-executing rule.** Baseline says capsule thought switches to JBMono "when agent is executing (not narrating)." Track A keeps Thought as serif italic in the *chat* row. Are these consistent or contradictory? My read: capsule = live ambient, executing = mono is fine; chat row = chronicle, italic serif throughout. Different surfaces, different voices. But worth a second eye.
4. **Is the cold-start orchestrated entrance worth Phase 1?** It currently sits in Phase 5. I'd actually move it to Phase 1: it ships with `Theme.kt` setup and is the first impression. Low cost, high payoff.
5. **Should the 5-step shape ladder be 4?** `extraSmall` (4dp) is used only for chips. If we have no chip surface in v1 (suggestion chips in empty state are "underlined serif links," not Material chips), we could drop `extraSmall`. Keep for now since chip might re-emerge.

---

## 7. Bottom line for the aligner

The aligned spec should be:
- Tokens, motion, layout direction: **adopt baseline verbatim**, with the four cuts listed in §2.6 + §3.4 swipe simplification + Phase 5 deferrals listed in §4.4 / §4.5.
- Track A: ratified; two clarifications (hairline level + glyph assets) belong in the aligned spec.
- The result is a thinner, more enforceable baseline, not a different one.
