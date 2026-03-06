status: in-progress

# Eval Acceleration Implementation Plan

Date: 2026-03-06
Reference: `doc/todo/0.5_eval_accelerate/final/design.md`

## Key Decisions

1. Keep `runner.py` as the single-device engine.
2. Add an internal config-only flag `runner.perform_bridge_setup` so parallel workers can skip duplicated APK build/install work without growing the CLI.
3. Make `parallel_runner.py` write merged outputs into the normal `eval/results/<run_id>/` contract, with shard artifacts under `parallel/`.
4. Force worker overlays to use explicit device tuples and `android_world.auto_start_emulator=false`.
5. Reuse the existing baseline-prep workflow and add a small `scripts/eval_parallel.sh` helper for normal dual-emulator startup + parallel execution.

## Phase 1: Harness Contract

Goal: make the parallel harness produce the right behavior and artifacts.

### Changes

- `eval/aw_bridge/runner.py`
- `eval/aw_bridge/runner_preflight.py`
- `eval/aw_bridge/parallel_runner.py`
- `eval/config/default.yaml`
- `eval/tests/test_runner.py`
- `eval/tests/test_parallel_runner.py`

### Work

1. Add `perform_bridge_setup` to runner config loading with default `true`.
2. Refactor bridge setup into reusable build/install helpers.
3. Make `run_preflight_checks()` honor `perform_bridge_setup`.
4. Make `parallel_runner.py` build once, install once per device, then launch workers.
5. Change parallel output layout to `eval/results/<run_id>/` with `parallel/` subdirectory.
6. Force worker configs to disable emulator auto-start.
7. Add/update tests for the new config field, worker overlay, output layout, and merged summary behavior.

### Verification

1. Targeted unit tests for runner + parallel runner.

## Phase 2: Operator Entry Points And Docs

Goal: make the new path usable and documented.

### Changes

- `scripts/eval_parallel.sh`
- `eval/README.md`
- `.ai-dev/skills/autotune/SKILL.md`
- `.ai-dev/skills/cog-tune/SKILL.md`
- `doc/main/eval/eval.md`
- `doc/changelog.md`

### Work

1. Add `scripts/eval_parallel.sh` for two prepared emulators.
2. Document the new output contract and helper script in eval docs.
3. Update autotune skill Step 3 to show the parallel path and preconditions.
4. Remove stale “parallel runner is WIP” language from cog-tune skill.
5. Update main docs/changelog to reflect the new eval workflow.

### Verification

1. Script help / smoke-level invocation sanity check.
2. Re-run targeted unit tests after doc+script changes.

## Review Plan

After each phase:

1. Run an independent `/code-review` subagent.
2. Fix findings.
3. Commit with a conventional commit message.
