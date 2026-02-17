# Android Agent Evaluation Harness

This folder contains the Tier 0/1 evaluation implementation described in
`doc/todo/0.5_eval/align/design/design.md`.

## What this supports now

- Tier 0: curated task lists under `eval/config/*.txt`.
- Tier 1 MVP: AndroidWorld bridge runner that:
  - reuses AndroidWorld task lifecycle (`initialize_task`, `is_successful`, `tear_down`)
  - launches this app natively through ADB intent extras
  - monitors completion from logcat + timeout
  - parses pulled trace artifacts (`run_summary`, `complete_task.answer`)
  - persists `per_task.jsonl` and `summary.json`

## Quick Start

1. Install Python dependencies for AndroidWorld and this harness.
2. Ensure emulator/device + AndroidWorld runtime are ready.
3. Install latest APK to device (`./scripts/setup.sh`).
4. Run a smoke subset:

```bash
python3 eval/aw_bridge/runner.py --tasks-file eval/config/aw_subset_smoke.txt
```

## Output Layout

Each run writes to:

`eval/results/<timestamp>/`

- `summary.json`: aggregated metrics
- `per_task.jsonl`: one JSON record per attempt
- `artifacts/<run_id>/`: logcat, pulled trace, and parser metadata

## Notes

- Bridge status (operational) is tracked separately from scripted success (benchmark truth).
- By default, only infra failures are retried.
