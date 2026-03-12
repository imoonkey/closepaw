# R39 Starting Point

## Baseline Token Counts (pre-optimization)

Measure before R39 fix step. Run:
```bash
# System prompt: extract prompt string length from StandaloneAgentDef.kt
# Tool descriptions: sum description properties from tool/impl/*Tool.kt
# App skills: wc -c app/src/main/assets/app_skills/*/SKILL.md
```

Record in `doc/autotune/round_39/<run_id>/token_baseline.md`.

## Current State

- 16 app skills, largest: Tasks.org (41 lines), OsmAnd (31), OpenTracks (31)
- R36-R38 already generalized 7 skills (removed ~66 lines of eval-specific content)
- System prompt and tool descriptions have NOT been audited for verbosity/generalization

## R39 Focus: Cut Phase

Start with the highest-token targets:

1. **System prompt** (`StandaloneAgentDef.kt`) — audit for redundancy, over-prescription, content that belongs in tool descriptions or app skills
2. **Tool descriptions** — audit for cross-tool policy leakage, verbose examples, content duplicated with system prompt
3. **App skills** — second pass: any remaining verbose sections, redundant guidance across skills

For each cut, note what was removed and why. Commit, run full eval, analyze.

## Task List

Use `eval/config/aw_fullset.txt` minus `eval/config/cannot_handle_group.txt`. This is a regression sweep — we need to see what breaks.

## Eval Config

- Model: gpt-5.4
- Run: `--remote` (see `/autotune` Step 3)
