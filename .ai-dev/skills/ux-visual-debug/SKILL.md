---
name: ux-visual-debug
description: Run end-to-end Android UX QA from a user perspective through ADB-driven interaction and visual evidence capture. Use when iterating app UX, validating interaction regressions after UI changes, reproducing reported UX bugs, or checking flows in different app states (including Main app and Smart Capsule states such as 接管/补充 and text input paths). Prefer this skill when the goal is UX behavior validation rather than internal agent reasoning/debug logs.
---

# UX Visual Debug

Automate UX regression checks by executing scenario files through ADB, capturing screenshots + UI trees after each step, and producing a structured QA report.

## Scope

Use this skill to validate app UX as an external user:
- Tap buttons and controls.
- Enter text and submit.
- Navigate across states/screens.
- Validate expected UI appears/disappears.
- Capture actionable evidence for each step.

Keep this separate from `visual-debug`:
- `visual-debug`: inspect agent reasoning/perception/tool chain.
- `ux-visual-debug`: inspect app UX behavior and interaction quality.

## Workflow

1. Ensure device is connected and app build is installed.
2. Choose mode:
   - UX-only mode: only user-perspective ADB interactions.
   - Linked mode: run `visual-debug` trajectory capture together with UX scenario.
3. Start from a clean state (`force_stop`, then `start_app`) inside scenario.
4. Execute scenario via ADB runner.
5. Review generated report and step artifacts.
6. Classify findings with UX severity and propose concrete fixes.

## Commands

```bash
# Run scenario file
bash .ai-dev/skills/ux-visual-debug/scripts/run_ux_qa.sh \
  .ai-dev/skills/ux-visual-debug/references/scenario_template.json

# Run on a specific device
bash .ai-dev/skills/ux-visual-debug/scripts/run_ux_qa.sh \
  .ai-dev/skills/ux-visual-debug/references/scenario_main_and_capsule.json \
  --serial emulator-5554

# Direct Python invocation
python3 .ai-dev/skills/ux-visual-debug/scripts/adb_ux_runner.py \
  --scenario .ai-dev/skills/ux-visual-debug/references/scenario_main_and_capsule.json \
  --out-root debug-output/ux-qa

# Linked mode (parallel): run agent goal + UX simulation together
python3 .ai-dev/skills/ux-visual-debug/scripts/adb_ux_runner.py \
  --scenario .ai-dev/skills/ux-visual-debug/references/scenario_main_and_capsule.json \
  --agent-goal "Open Settings and return" \
  --agent-link-mode parallel \
  --agent-join-timeout-sec 45

# Linked mode (serial): capture full agent trajectory first, then run UX scenario
python3 .ai-dev/skills/ux-visual-debug/scripts/adb_ux_runner.py \
  --scenario .ai-dev/skills/ux-visual-debug/references/scenario_main_and_capsule.json \
  --agent-goal "Complete Smart Capsule handoff flow" \
  --agent-link-mode serial \
  --agent-setup \
  --agent-debug-arg=--hybrid \
  --agent-debug-arg=--pro
```

`run_ux_qa.sh` is a thin wrapper and forwards these flags to `adb_ux_runner.py`.

## visual-debug Linkage

When `--agent-goal` is set, this skill reuses the same project scripts as `visual-debug`:
- Optional `scripts/setup.sh` (via `--agent-setup`).
- `scripts/debug-run.sh` for turn screenshots/logcat/trace capture.

Linked execution modes:
- `parallel`: agent trajectory runs while UX scenario performs independent user actions.
- `serial`: agent trajectory completes first, then UX scenario runs.

All linkage metadata is written into UX outputs (`report.md` + `run_summary.json`):
- `agent_debug_run.log`: raw `debug-run.sh` log.
- `agent_link.debug_output_dir`: linked `debug-output/run_*` path from visual-debug.

## Scenario Authoring

Start from:
- `references/scenario_template.json`
- `references/scenario_main_and_capsule.json`
- `references/scenario_agent_parallel_example.json`

Supported actions:
- `force_stop`
- `start_app`
- `wait`
- `tap_text`
- `tap_contains_text`
- `tap_resource_id`
- `tap_desc`
- `tap_xy`
- `type`
- `keyevent`
- `swipe`
- `assert_text`
- `assert_not_text`
- `screenshot`
- `dump_ui`
- `note`

Step options:
- `name`: human-readable step title.
- `continue_on_fail`: continue scenario after failure.
- Action-specific params: `text`, `resource_id`, `x`, `y`, `ms`, etc.

## Output Contract

Runner output goes to `debug-output/ux-qa/run_<timestamp>_<scenario>/`:
- `report.md`: summary + per-step results.
- `run_summary.json`: machine-readable full result.
- `step_XXX_*.png`: screenshot after each step.
- `step_XXX_*.xml`: UI tree after each step.
- `step_XXX_*_visible.txt`: visible text snapshot.
- `agent_debug_run.log`: present in linked mode.
- `agent_setup.log`: present when `--agent-setup` is enabled.

## QA Rubric

When reviewing report artifacts, use `references/ux_checks.md` and classify issues as:
- `P0`: crash/blocker/cannot proceed.
- `P1`: core flow broken, severe confusion.
- `P2`: UX friction, misleading feedback, consistency issue.
- `P3`: minor polish issue.

Always include:
- Exact failed step index/name.
- Screenshot + UI XML path.
- Expected vs actual behavior.
- Repro command.

## Execution Rules

- Prefer ADB interaction over code-level hooks.
- Fail fast on blockers unless `continue_on_fail` is set.
- Keep scenarios deterministic: explicit waits, explicit assertions.
- Keep each scenario focused on one user goal.
- If selectors are unstable, switch from text selector to `resource_id` or coordinate fallback.
- In parallel linked mode, expect occasional ADB contention and use extra waits/assertions to stabilize.
- Keep UX findings independent from agent reasoning findings, then add a combined conclusion only if both datasets point to the same issue.
