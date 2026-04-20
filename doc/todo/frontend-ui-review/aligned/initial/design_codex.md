# Codex Design Review

## 1. Verdict

Refine. The baseline has the right backbone: a distinct palette, a calm motion thesis, a strong capsule, and a viable editorial direction. It also adds too much costume in places: too many token variants, too many type voices, and motion/details that break the stated KISS rule. Keep the identity system, trim the vocabulary hard, and let Track A be the forcing function for what stays.

## 2. Tokens

- Color keep: `Paper`, `Ink`, `Claw`, `Moss`, `Amber`, `Rust`, one `Hairline`, and a dark palette with the same structure.
- Color cut: named `*Soft`, `*Deep`, `InkGhost`, `PaperDeep*` variants unless they are reused enough to justify a token. Most of these can be derived from alpha or pressed-state logic.
- Color add: one explicit focus/border rule token. DEFER any extra decorative color.
- Type keep: one sans for UI/body/final, one mono for actions/output. Serif can stay, but only on true identity surfaces.
- Type cut: the “three equal voices” model. Cut execution-dependent font swapping in chat. Cut tracked caps and special-case display styling as defaults.
- Type add: one explicit Track A map: Thought = body italic, Action = mono, Final = body regular. This matters more than extra font styles.
- Shape keep: symmetric corners and a pill capsule.
- Shape cut: the five-step radius ladder. `PawStamp` is asset geometry, not a shape token.
- Shape add: cap the system at 3 radii max: small / card / pill.
- Elevation keep: flat by default, with one subtle paper lift for capsule and maybe drawer/sheet.
- Elevation cut: bespoke folded-paper shadow treatments on many components and any nested framed surfaces.
- Elevation add: one stronger modal/sheet step. DEFER anything beyond that.
- Spacing keep: 4pt base grid.
- Spacing cut: the golden-ratio story and `52dp`. It adds narrative, not control.
- Spacing add: normalize to `4/8/12/16/24/32`. Track A row gaps should snap to that set.

## 3. Motion

- Keep: the 4-duration rule, the 2-easing rule, breath on Running only, simple slide/fade for row entry, and 120ms status glyph changes.
- Cut: the written exceptions that already violate the rule: `160`, `180`, `280`, `360`, `760`, `8000`. Cut perlin wobble, satellite rings, and long ambient drift until the base language is stable.
- Add: one hard audit rule: every animation must map to `120/240/480/900`; if it cannot, DEFER it.
- Validation: the rule is correct; the current motion doc does not fully obey it. This is a refine, not a pass-as-written.

## 4. Layout & Component-Level Decisions

- Capsule: keep it as the signature surface. Paw + semantic state + restrained motion is enough. Avoid extra paper theater beyond one hairline and one lift.
- Chat row: keep it flat and unbubbled. One row per turn, chronological inside. Editorial styling should not obscure row structure.
- Action card: treat it as a trace line or receipt line inside the agent row, not as its own standalone card.
- Settings: keep restrained editorial typography and hairlines. Cut journal cosplay that hurts scanability.
- Drawer: keep ledger density and mono dates. Cut novelty labels if they slow navigation.
- Onboarding: keep paw-step progress and one strong display moment. DEFER chapter-spread theatrics unless they survive small screens cleanly.

## 5. Track A Compatibility Check

- Trace items (`Thought` / `Action`): partial support. The baseline already has prose vs action styling, and mono action language fits well. It still needs an explicit `Thought` item model.
- Final block: partial support. The baseline wants agent prose on the page, which is compatible. It still needs a hard rule that Final is a distinct bottom block after the trace.
- Hairline divider: yes. Strong fit. The baseline already uses hairlines; one divider between trace and Final is consistent.
- Italic Thought voice: no, not yet. The baseline talks about serif identity and mono execution, but Track A needs a stable italic body style for Thought.
- Conflict: the roadmap’s `ActionCard` with receipt chrome and expandable output can drift into a nested-card model. Track A wants one disclosure axis at the row level.
- Conflict: the README’s claw-red left-margin tick for agent prose may become too loud once Thought, Action, and Final all coexist in one row.
- Conflict: Track A’s `6dp` trace spacing is off-grid against the baseline 4pt spacing system. Prefer `8dp` over inventing a one-off.
- Bottom line: no baseline blocker, but Track A needs the baseline simplified before implementation.

## 6. Open Questions for Cross-Review

- Do we want two in-chat type families only (`sans + mono`) and reserve serif for non-chat identity surfaces?
- Should Track A snap row spacing to `8dp`, or is there a real reason to keep `6dp`?
- Should claw red appear in chat at all beyond live/running state, or should Thought/Final stay ink-only?
- Is the receipt treatment for Action rows still too heavy once the row itself is expandable?
- Do we lock the motion system to `120/240/480/900` now and DEFER edge-glow drift and action-visualizer flourish?
