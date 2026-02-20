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

1. Create/use eval virtualenv and install Python dependencies for AndroidWorld and this harness.
2. Ensure emulator/device + AndroidWorld runtime are ready.
3. Install latest APK to device (`./scripts/setup.sh`).
4. Run a smoke subset:

```bash
eval/.venv/bin/python eval/aw_bridge/runner.py --tasks-file eval/config/aw_subset_smoke.txt
```

Use `eval/.venv/bin/python` for eval-related commands to avoid dependency/version drift.

## Output Layout

Each run writes to:

`eval/results/<timestamp>/`

- `summary.json`: aggregated metrics
- `per_task.jsonl`: one JSON record per attempt
- `artifacts/<run_id>/`: logcat, pulled trace, and parser metadata

## Running in Virtual-Display Mode

By default evals run in **accessibility** mode (agent operates on the real
emulator screen).  To run on a **Shizuku virtual display** instead:

### One-time device setup

1. Install Shizuku on the emulator (the runner can do this automatically if
   `bridge.shizuku_apk_path` is set — see below).
2. Open the Shizuku app on the emulator and start the server via the
   "Start via ADB" flow.
3. Launch the Android Agent app, which will trigger the Shizuku permission
   dialog.  Tap **Allow**.

This grant persists across emulator reboots (as long as you don't wipe data).
You only need to do it once per emulator image.

### Running

```bash
# CLI override (no config change needed):
eval/.venv/bin/python eval/aw_bridge/runner.py \
  --platform-mode virtual_display \
  --tasks-file eval/config/aw_subset_smoke.txt

# Or set it in eval/config/default.yaml:
#   bridge:
#     platform_mode: virtual_display
```

The runner will automatically start the Shizuku server if it isn't already
running.  If Shizuku is not installed on the device and you want auto-install,
set `shizuku_apk_path` in your config:

```yaml
bridge:
  platform_mode: virtual_display
  shizuku_apk_path: eval/tools/shizuku.apk   # bundled v13.6.0
```

### Troubleshooting

If the agent silently falls back to accessibility mode, check logcat for:

- `Shizuku is not available` — server not running; the runner should
  auto-start it, but verify with `adb shell pidof shizuku_server`.
- `Shizuku permission not granted` — re-do the one-time grant step above.

## Notes

- Bridge status (operational) is tracked separately from scripted success (benchmark truth).
- By default, only infra failures are retried.
