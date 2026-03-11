---
name: autotune
description: One eval-tune round. Applies approved fixes, selects tasks, runs eval, analyzes results, and updates loop state. Standalone use stops for human review; `/autotune-loop` can orchestrate repeated rounds.
---

# AutoTune

One full fix → eval → analyze round.

## When to Use

- After reviewing a previous round's `common_problems_<agent>.md` and approving the proposed next steps.
- To kick off round 0 on a fresh task set (skips the fix step).
- When `/autotune-loop` needs one orchestrated round worker.

## Modes

`/autotune` has two modes:

- **Manual mode**: default for standalone `/autotune`. Do one round, update `loop_state.json` with `status: "waiting_review"` and `last_round.recommended_action: "wait_human"`, then stop for human review.
- **Orchestrated mode**: only when invoked from `/autotune-loop`. Do the same round work, update `loop_state.json` with the round verdict, and return control to the loop controller.

Invocation context decides the mode. Do not infer orchestrated behavior from a stale `doc/autotune/meta/loop_state.json` alone.

## Loop

```
1. FIX ──► 2. PREPARE ──► 3. RUN ──► 4. ANALYZE ──► STOP
```

### Step 1 — Fix

Apply changes from the previous round's approved `## Next Steps`.

- Every proposed change must pass `.ai-dev/skills/autotune/references/tuning_principles.md`.
- For prompt, tool description, or app skill changes, use `/prompt-tune` to determine the correct ownership layer before editing.
- **Use `/implement` skill** for all code changes. Do NOT skip any steps in the implementation workflow.
- Commit: `feat(agent): autotune round N — <summary>`.
- Round 0: skip this step.

### Step 2 — Prepare

Select tasks for this round. Full universe: `eval/config/aw_fullset.txt`.

Selection rules:
1. **Directly affected**: Tasks whose failure root cause matches what was just fixed.
2. (Optional) **Regression canaries**: Only include if explicitly requested. Do not add by default.
3. (Optional) **Stuck tasks**: Re-test only if you have a new idea and the change still passes the shared tuning principles.
4. **Budget**: ~5-10 tasks normally, up to 20 for regression sweeps.

Use `doc/autotune/meta/scoreboard.json` to judge whether a targeted retry is still productive.

Subtract `eval/config/cannot_handle_group.txt`. Write the selected tasks to `eval/config/autotune_round_N.txt`.

### Step 3 — Run

```bash
# Preferred, set up both baseline-prepared emulators first:
./scripts/eval_parallel.sh eval/config/autotune_round_N.txt

# Fallback: single-device serial run
eval/.venv/bin/python eval/aw_bridge/runner.py --tasks-file eval/config/autotune_round_N.txt

# Remote eval on qiguo-ld1 (sync code first: git push → git pull + assembleDebug):
# Requires SSH reverse tunnel for gpt-* models; OpenRouter models work without it.
# See doc/dev/remote_eval_worker.md for full setup.
eval/.venv/bin/python eval/aw_bridge/runner.py \
  --config eval/config/remote.yaml \
  --tasks-file eval/config/autotune_round_N.txt
```

Parallel preconditions:
- `AndroidWorldAvd` is baseline-prepared on `emulator-5554` / gRPC `8554`
- `AndroidWorldAvd2` is baseline-prepared on `emulator-5556` / gRPC `8556`
- Use `./scripts/prepare_parallel_baselines.sh` for the one-time dual-AVD prep path
- `./scripts/eval_parallel.sh` does not create `AndroidWorldAvd2`; that AVD must already exist
- If you only see one emulator window, treat it as a startup failure on the second device, not a normal parallel state
- If either device is unavailable, use the serial fallback instead of improvising

Monitor for stalls. If a task hangs (no output for several minutes), check accessibility permission on the device. If needed, stop the runner, remove completed tasks from the config, and re-run the remainder.

**Overlap with Step 4**: You do NOT need to wait for the full run to finish. As soon as a task completes, start its `/cog-tune` analysis (Step 4.1) in parallel while remaining tasks continue running.

### Step 4 — Analyze

For each task in the run (**MUST use a separate subagent per task** for cleaner context — do NOT analyze multiple tasks in one agent):
1. Run `/cog-tune` (eval entry). Write per-task analysis to `doc/autotune/round_N/<run_id>/per_task/<TaskName>_<agent>.md`.
2. Append a short entry to `doc/autotune/meta/per_task/<TaskName>_<agent>.md` — score, turns, one-line behavior delta vs previous run. Newest on top.

Once done with all tasks:
3. Summarize into `doc/autotune/round_N/<run_id>/common_problems_<agent>.md` following `assets/common_problems_template.md`. Must include a `## Next Steps` section.
4. Run `python3.12 scripts/scoreboard.py` to regenerate `doc/autotune/meta/scoreboard.json` and `doc/autotune/meta/scoreboard.md`.
5. Append to `doc/autotune/meta/changelog.md`.
6. Update `doc/autotune/meta/issues.md` (new issues, resolved issues, parked tasks).
7. Update `doc/autotune/meta/loop_state.json` as the final control-plane handoff for this round.

Escalate to `/double-design` only when at least one of these is true:
- The same task cluster has failed for 2+ rounds with no progress.
- The proposed fix touches the core prompt or major tool semantics.
- A capability-gap candidate needs confirmation before being parked.

Note: <agent> = your name, e.g., claude, codex (do your analysis independently, don't look at other agents' analyses even if they exist).

## Loop State Contract

`doc/autotune/meta/loop_state.json` is the only control-plane file for the current loop.

- In **manual mode**, set `status` to `waiting_review`, keep `mode` as `manual`, and set `last_round.recommended_action` to `wait_human`.
- In **orchestrated mode**, write the round verdict into `last_round` and return. `/autotune-loop` decides whether to continue or stop.
- Do not create a separate `round_verdict.json`.

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
- Loop state: `doc/autotune/meta/loop_state.json`
- Global issues: `doc/autotune/meta/issues.md`
- Changelog: `doc/autotune/meta/changelog.md`
- Per-task changelogs: `doc/autotune/meta/per_task/<TaskName>.md`
- Per-round analysis: `doc/autotune/round_N/<run_id>/`
- Task universe: `eval/config/aw_fullset.txt`
- Exclusions: `eval/config/cannot_handle_group.txt`
- Scoreboard script: `scripts/scoreboard.py`
- Eval runner: `eval/aw_bridge/runner.py`
- Eval remote config: `eval/config/remote.yaml`
- Remote eval worker runbook: `doc/dev/remote_eval_worker.md`
- Cog-tune skill: `.ai-dev/skills/cog-tune/SKILL.md`
- Implement skill: `.ai-dev/skills/implement/SKILL.md`
- Shared tuning principles: `.ai-dev/skills/autotune/references/tuning_principles.md`
- Loop controller: `.ai-dev/skills/autotune-loop/SKILL.md`
