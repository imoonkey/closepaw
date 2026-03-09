# Implementation Plan

## Phase 1 — Skill Contract Restructure

### Goal
Align the repo's autotune workflow with the final design by splitting responsibilities cleanly across `autotune`, `autotune-loop`, `prompt-tune`, and `ralph-loop`.

### Affected files
- `.ai-dev/skills/autotune/SKILL.md`
- `.ai-dev/skills/autotune/references/tuning_principles.md`
- `.ai-dev/skills/prompt-tune/SKILL.md`
- `.ai-dev/skills/fullyautotune/SKILL.md` → `.ai-dev/skills/autotune-loop/SKILL.md`
- `doc/autotune/meta/loop_state.json`
- `doc/autotune/meta/changelog.md`
- `AIDEV.md`

### Key decisions
- Treat this as one committable phase because the change is a documentation and skill-contract migration, not a multi-phase code rollout.
- Seed `doc/autotune/meta/loop_state.json` from the latest real manual state: Round 14 completed, next round is 15, and human review is still pending.
- Make `autotune` manual by default, with orchestrated behavior only when explicitly invoked from `autotune-loop`.
- Keep `autotune-loop` thin: one orchestrated round per Ralph iteration, then decide stop/continue from `loop_state.json`.
- Centralize anti-overfit and token-minimalism guidance in a shared tuning principles reference instead of duplicating it across skills.
