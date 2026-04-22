# Non-regressions

> Cross-checked against `projects/archive/20260420_frontend-ui-revamp/aligned/design_aligned.md`
> after writing `direction.md` and `changes.md`. Listed here are the prior
> invariants that bound this round, plus the two places this round
> consciously expands prior scope (with justification).

## Held invariants — this round does not touch them

| Prior invariant (D1 / aligned design) | This round |
|---|---|
| **One chat model only** — Track A: one row per turn, chronological trace, optional Final, single row-level disclosure axis | Held. Re-ornament only; collapse policy and `RowState` machine untouched. |
| **One identity system** — palette + typography + paw glyph + calm motion; not a long tail of one-off tokens | Held. New ornaments (Fleuron, PageMasthead, paperGrain) all reach into existing tokens; no new colors, fonts, durations, or radii. |
| **No color-only meaning** — paw + semantic colors always pair with text/label | Held. Capsule ledger counter pairs with thought text; stop button keeps its label; status dot still has the existing `stateDescription`. |
| **Two divider levels only** — `Hairline` (12% Ink) between components, `InkGhost` (8% Ink) inside a composed surface | Held. UserBubble left rule reuses `Hairline`. The `FinalSeparator` rule is *replaced* by the fleuron — no new divider level introduced. |
| **Mono is reserved for machine-like strings**, not a blanket "executing mode" font swap | Held. New mono call sites are: capsule `[t+12s]` ledger counter (machine timing), `[+]/[−]` collapse brackets (interaction-state ledger), `live` pill (state ledger). Suggestion verbs in EmptyState **stay in `bodyLarge` Geist** — explicitly *not* mono — because they are user-voice prose, not machine output. |
| **Capsule breath in Running only** | Held. Send-button breath is one-shot on submit, not a continuous loop. |
| **Track A row voice locked**: Thought=Geist italic, Action=mono, Final=Geist regular | Held. The thought/action/final styles in `MessageBubble.kt` are unchanged; only `FinalSeparator`, `CollapsedHeader`, `OutcomeFooter` ornaments change. |
| **Reduced-motion contract** read once per call site, no global wrapper | Held. EmptyState stagger and send-button breath each call `ClosePawMotion.reducedMotion()` and skip locally; capsule counter ticks unconditionally (status signal, same class as streaming cursor). |
| **Horizontal page padding is `lg` (20dp), hard invariant** | Held. `smartCapsuleHostPadding` (16dp) and per-screen padding values are unchanged. |
| **Three Material radii only** — small (8) / medium (10) / large (16) | Held. No new shape constants. |
| **Status glyphs paired with text in the accessibility tree** | Held. `qa-thinking-indicator`, `qa-user-bubble`, `qa-agent-bubble` test tags and `stateDescription` labels preserved. |
| **`foldedPaper` chrome scarce** — capsule + modal sheet/drawer only | Held. Not extending `foldedPaper` to chat surfaces. PageMasthead is a flat row + Hairline rule, not a folded card. |

## Conscious expansions of prior scope

Two places this round goes beyond the strict letter of `design_aligned.md`.
Both are deliberate, both fit the spirit of "identity surface" the prior round
defined.

### 1. Fraunces italic Regular extended from "empty-state question only" to PageMasthead and section subheads

**Prior wording:** `serifItalic — Fraunces italic, used only on the empty-state question.`

**This round:** Fraunces italic Regular also appears in:
- `PageMasthead` title (ChatHeader, NavigationDrawer header, Settings home)
- `SectionHeader` in Settings home (small, three call sites)

**Justification:** the prior round itself already used Fraunces (upright,
synthesized SemiBold) on the `OnboardingShell` title and `NavigationDrawer`
header — so the "empty-state question only" wording was already aspirational,
not enforced. This round formalizes those identity surfaces under a single
primitive (`PageMasthead`) and standardizes on the *loaded* italic Regular
weight rather than synthesized SemiBold (a fidelity *improvement* over current
state). The total Fraunces footprint after this round is smaller, not
larger, because we drop the synthesized weights.

If this expansion is contentious during review, the fallback is
**Type Option B** in `changes.md` §Type — retire upright Fraunces from
identity surfaces entirely and use only italic Regular at every identity
call site, including title slots. Either path keeps the "Fraunces is identity"
discipline intact.

### 2. JetBrains Mono extended to `[+]/[−]` collapse brackets

**Prior wording:** `Mono is allowed only for explicitly machine-like strings,
not as a blanket "executing mode" font swap.`

**This round:** brackets used in `CollapsedHeader` and `OutcomeFooter` to
represent collapse state are set in `monoSmall`.

**Justification:** the brackets are a textual rendering of UI control state
(equivalent to a `▸` / `▾` icon), not narrative prose. They sit alongside
existing mono action subtitles, mono ledger dates, and the new ledger counter
— same register, same role. They are *not* a font swap on row body content;
the headline summary, action descriptions, and Final body all stay in their
existing voices.

## Things from the prior round this proposal explicitly does **not** revisit

The archive lists several closed-out concerns. None are reopened:

- Streaming cursor in serif Fraunces ("Phase 3 verify" in archive §Open-questions) — left alone.
- Track B (overlay) work — out of scope; this round touches only main app + capsule.
- Track D (D1 → D2 polish) — completed; PROGRESS notes confirm. We pick up post-D2.
- The QA reports (`qa_report.md`, `r2`, `r3`) and codex reviews already
  shipped fixes — we are not re-litigating them.

## Verification checklist (pre-merge)

After implementation, confirm:

- [ ] Fraunces appears only in: PageMasthead titles, SettingsHomePage section
      headers, EmptyState question, Fleuron ornament. (Italic Regular
      everywhere.)
- [ ] Mono appears only in: ledger dates (drawer, settings footer), action
      subtitles (existing), capsule ledger counter (new), `[+]/[−]` brackets
      (new), `live` pill (new).
- [ ] Geist body voice covers all prose, including suggestion verbs.
- [ ] No new color slot in `Color.kt`. No new shape in `Shape.kt`. No new
      duration in `Motion.kt`.
- [ ] Reduced-motion mode renders EmptyState and send button without
      animation; capsule counter still ticks; capsule breath still paused
      (per existing contract).
- [ ] All existing QA test tags and `stateDescription` labels intact.
