# Audio Recorder App Skill Rewrite Design

## Goal

Rewrite `app/src/main/assets/app_skills/com.dimowner.audiorecorder/SKILL.md` to match the Unified App Skill Rewrite Framework.

Success means:

- keep only stable app-local truth,
- remove solver logic and generic task procedure,
- fit Tier 1 with the smallest useful skill body,
- preserve the one fact most likely to prevent wrong interaction.

## Constraints

- Follow the framework in `doc/todo/appskill_topdown/final/framework.md`.
- Do not keep YAML frontmatter or decorative headings in the rewritten skill.
- Keep this as a Tier 1 rewrite: target 2 to 5 lines, but smaller is better if nothing else earns its place.
- No `CRITICAL` block: the framework example for this app already shows the intended reduction.
- Favor anti-overfit and token minimalism from `tuning_principles.md`.

## Current Skill Audit

Current content mixes one app fact with mostly generic procedure:

- "Tap record, wait, tap stop" is obvious workflow, not app-local truth.
- "Recording appears in list with auto-generated name" is descriptive but not a decisive mechanic.
- "Rename from 3-dot menu or long-press" is the only non-obvious interaction detail.
- "Type the exact filename verbatim" is generic agent discipline, not app knowledge.
- "Confirm and verify" is generic verification, not app knowledge.

## Design Decision

Rewrite the skill to a single bullet that keeps only the rename entry points:

```md
- Rename a recording from its 3-dot menu or by long-pressing it.
```

This matches the framework's Example C exactly and keeps the only surviving app-local mechanic.

## Rationale

- The rename entry point is the only line that changes what the agent would try inside this app.
- Recording itself is obvious from the UI and should be handled by the core agent behavior, not the app skill.
- Exact filename handling belongs in generic grounding rules, not this app skill.
- Verification after rename is generic and already covered elsewhere.
- Adding more lines would spend tokens on behavior that does not appear unique to this app.

## Answers To Key Questions

### 1. Is there any app truth beyond the rename mechanic worth keeping?

No, based on the current skill and the framework guidance. Nothing else shown is both app-local and non-obvious enough to justify prompt budget.

### 2. Should auto-generated names be mentioned?

No. They do not change the interaction model, and the framework's own Audio Recorder example removes that line. Mentioning them would add description, not leverage.

### 3. What is the minimal set of genuine app facts?

One fact:

- recordings can be renamed either from the item's 3-dot menu or by long-pressing the item.

## Trade-offs

- This rewrite may remove some local reassurance for the record-then-rename eval flow, but that reassurance is procedural rather than app-specific.
- Keeping only one line increases dependence on the core prompt for generic recording, typing, and verification behavior.
- That trade is correct for Phase 1 because the scoreboard already shows the recording task fixed and the remaining useful guidance is the rename mechanic.

## Self-Review

- The design solves the real problem: remove overfit and keep only app truth.
- The result is simpler than the current skill and aligned with the framework's Tier 1 example.
- No extra line currently clears the "repeated use" bar.
- The design leaves no ambiguous ownership: rename mechanic stays in the app skill; everything else falls back to core behavior.
