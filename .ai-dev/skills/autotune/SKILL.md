---
name: autotune
description: Automated eval-tune loop. Applies approved fixes, selects tasks, runs eval, analyzes results with /cog-tune, and stops for human review. Use to iterate on agent quality without manual orchestration.
---

# AutoTune

Automated fix → eval → analyze loop. Each invocation does one full iteration and stops for human review.

## When to Use

- After reviewing a previous round's `common_problems_<agent>.md` and approving the proposed next steps.
- To kick off round 0 on a fresh task set (skips the fix step).

## Loop

```
1. FIX ──► 2. PREPARE ──► 3. RUN ──► 4. ANALYZE ──► STOP
```

### Step 1 — Fix

Apply changes from the previous round's approved `## Next Steps`.

- **Follow the full `sop/code_work.md` process** (design/plan → phased implementation with /tdd /coding-standards, /code-review → /cog-tune quick debug → code simplification → /update-docs). Do NOT skip any steps in the SOP.
- Commit: `feat(agent): autotune round N — <summary>`.
- Round 0: skip this step.

### Step 2 — Prepare

Select tasks for this round. Full universe: `eval/config/aw_fullset.txt`.

Selection rules:
1. **Directly affected**: Tasks whose failure root cause matches what was just fixed.
2. (Optional) **Regression canaries**: Only include if explicitly requested. Do not add by default.
3. (Optional) **Stuck tasks**: Re-test if you have a new idea.
4. **Budget**: ~5-10 tasks normally, up to 20 for regression sweeps.

Subtract `eval/config/cannot_handle_group.txt`. Write to `eval/config/autotune_round_N.txt`.

### Step 3 — Run

```bash
eval/.venv/bin/python eval/aw_bridge/runner.py --tasks-file eval/config/autotune_round_N.txt
```

Monitor for stalls. If a task hangs (no output for several minutes), check accessibility permission on the device. If needed, stop the runner, remove completed tasks from the config, and re-run the remainder.

**Overlap with Step 4**: You do NOT need to wait for the full run to finish. As soon as a task completes, start its `/cog-tune` analysis (Step 4.1) in parallel while remaining tasks continue running.

### Step 4 — Analyze

For each task in the run:
1. Run `/cog-tune` (eval entry). Write per-task analysis to `doc/autotune/round_N/<run_id>/per_task/<TaskName>_<agent>.md`.
2. Append a short entry to `doc/autotune/meta/per_task/<TaskName>.md` — score, turns, one-line behavior delta vs previous run. Newest on top.
3. Summarize into `doc/autotune/round_N/<run_id>/common_problems_<agent>.md` following `assets/common_problems_template.md`. Must include a `## Next Steps` section.
4. Run `python scripts/scoreboard.py` to regenerate `doc/autotune/meta/scoreboard.json` and `doc/autotune/meta/scoreboard.md`.
5. Append to `doc/autotune/meta/changelog.md`.
6. Update `doc/autotune/meta/issues.md` (new issues, resolved issues, parked tasks).

### STOP

Present to human:
- Scoreboard diff (what improved, regressed, stuck).
- `common_problems_<agent>.md` with proposed next steps.

Wait for approval before the next `/autotune`.

## Templates

- Per-task analysis: `.ai-dev/skills/cog-tune/assets/per_task_analysis_template.md` (owned by /cog-tune)
- Common problems summary: `assets/common_problems_template.md` (owned by /autotune)

## Key Files

- Design: `doc/todo/0.01_autotune/design.md`
- Scoreboard (SOT): `doc/autotune/meta/scoreboard.json`
- Scoreboard (view): `doc/autotune/meta/scoreboard.md`
- Global issues: `doc/autotune/meta/issues.md`
- Changelog: `doc/autotune/meta/changelog.md`
- Per-task changelogs: `doc/autotune/meta/per_task/<TaskName>.md`
- Per-round analysis: `doc/autotune/round_N/<run_id>/`
- Task universe: `eval/config/aw_fullset.txt`
- Exclusions: `eval/config/cannot_handle_group.txt`
- Scoreboard script: `scripts/scoreboard.py`
- Eval runner: `eval/aw_bridge/runner.py`
- Cog-tune skill: `.ai-dev/skills/cog-tune/SKILL.md`
- Code work SOP: `sop/code_work.md`
