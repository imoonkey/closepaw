# Phase 2: Dual Emulator on Remote

## Goal

Run `eval_parallel.sh` on `qiguo-ld1` with two headless emulators to cut eval wall-clock time.

## Current State

- `qiguo-ld1` has one AVD: `AndroidWorldAvd` (Pixel 6, API 33, x86_64)
- `provision.sh` hard-codes `AVD_NAME="AndroidWorldAvd"` — only creates one
- `eval_parallel.sh` already supports two AVDs with `--headless` flag
- `prepare_baseline.sh` already accepts `--avd`, `--console-port`, `--grpc-port`, `--adb-serial`
- `parallel_runner.py` handles per-shard config overlay — no changes needed

## What Needs to Change

### 1. `scripts/remote/provision.sh`

Add second AVD creation after the existing one:

```bash
AVD_NAME_2="AndroidWorldAvd2"

if emulator -list-avds | grep -Fxq "${AVD_NAME_2}"; then
  log "AVD ${AVD_NAME_2} already exists."
else
  log "Creating AVD: ${AVD_NAME_2}..."
  echo "no" | avdmanager create avd \
    --force \
    --name "${AVD_NAME_2}" \
    --device "${AVD_DEVICE}" \
    --package "${SYSTEM_IMAGE}"
fi
```

Idempotent — re-running won't fail if AVD already exists.

### 2. `doc/dev/remote_eval_worker.md`

Add dual-emulator section with:

```bash
# Baseline prep for both AVDs (one-time)
./scripts/prepare_baseline.sh --headless --config eval/config/remote.yaml
./scripts/prepare_baseline.sh --headless --config eval/config/remote.yaml \
  --avd AndroidWorldAvd2 --console-port 5556 --grpc-port 8556 --adb-serial emulator-5556

# Parallel eval
./scripts/eval_parallel.sh --headless --config eval/config/remote.yaml \
  --tasks-file eval/config/<task_file>
```

## What Does NOT Need to Change

- `eval_parallel.sh` — already has `--headless`, dual-AVD defaults, emulator start logic
- `parallel_runner.py` — already shards by `--device` with per-shard config overlay
- `remote.yaml` — parallel runner overrides `adb_serial`/ports per shard
- `prepare_baseline.sh` — fully parameterized

## Risks

- Two headless emulators need ~8-10GB RAM each; `qiguo-ld1` has 62GB — should be fine
- Console/gRPC port conflicts unlikely (5554/8554 and 5556/8556 are standard)
- KVM support already verified in Phase 1

## Verification

1. SSH into `qiguo-ld1`, run updated `provision.sh`
2. Verify `emulator -list-avds` shows both AVDs
3. Prepare baselines for both
4. Run `eval_parallel.sh --headless` with smoke test
5. Compare wall-clock vs single-emulator run
