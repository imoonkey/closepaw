# Parallel Multi-Emulator Eval Design

Date: 2026-02-18  
Scope: `doc/todo/0.5_eval/parallel`

## 1. Problem

Current `eval/aw_bridge/runner.py` executes task instances sequentially in a single process:
- one `adb_serial`
- one AndroidWorld env (`console_port`, `grpc_port`)
- one task loop

This is correct for stability but slow for larger eval sets.

Goal: run multiple eval tasks concurrently by distributing task shards across multiple emulators.

## 2. Goals and Non-Goals

## Goals
- Reduce wall-clock eval time through horizontal scaling.
- Reuse existing `eval/aw_bridge/runner.py` with minimal risk.
- Keep each emulator isolated to avoid cross-device interference.
- Produce one merged report while preserving per-shard artifacts.

## Non-Goals
- No in-process concurrency inside `runner.py`.
- No changes to Android app runtime semantics.
- No distributed multi-host orchestration in v1.

## 3. Constraints

- `runner.py` is designed as a single-device sequential runner.
- AndroidWorld env requires unique `(console_port, grpc_port)` per emulator.
- Existing result schema and analysis scripts assume one run directory per runner invocation.
- Other contributors are actively editing `eval/` Python files; design should minimize merge conflicts.

## 4. Proposed Architecture

Add a new orchestration entrypoint:
- `eval/aw_bridge/parallel_runner.py`

This script is a supervisor that launches N independent `runner.py` subprocesses.

## 4.1 Responsibilities

`parallel_runner.py` will:
1. Resolve full task list (from `--tasks` or `--tasks-file`).
2. Resolve device matrix (serial + ports per emulator).
3. Partition tasks into N shards.
4. For each shard:
   - write temporary `tasks.txt`
   - write temporary worker config YAML (overlayed from base config)
   - launch `runner.py` with worker config
5. Wait for all workers.
6. Collect per-shard outputs.
7. Merge summary into a single run-level `summary.json`.

`runner.py` remains the execution engine for one device.

## 4.2 Device Matrix Contract

Use explicit device specs to avoid ambiguous port mapping.

Recommended CLI shape:

```bash
python3 eval/aw_bridge/parallel_runner.py \
  --config eval/config/default.yaml \
  --tasks-file eval/config/aw_subset_core.txt \
  --device emulator-5554:5554:8554 \
  --device emulator-5556:5556:8556
```

`--device` format:
- `SERIAL:CONSOLE_PORT:GRPC_PORT`

Why explicit ports:
- avoids hidden assumptions
- handles nonstandard emulator mappings
- avoids coupling to serial parsing logic

Optional convenience can be added later (`SERIAL` only with derived ports), but v1 should keep strict explicit input.

## 4.3 Task Sharding Policy

Default policy: deterministic round-robin.

Example with tasks `[t0, t1, t2, t3, t4]` and 2 devices:
- shard0: `t0, t2, t4`
- shard1: `t1, t3`

Rationale:
- simple and deterministic
- naturally balances count when task durations are unknown
- stable between runs for same input ordering

Future policy (optional): duration-aware packing once historical runtime data is available.

## 4.4 Worker Config Overlay (Conflict-Minimizing)

To avoid changing `runner.py` CLI during active parallel development:
- read base config YAML (`--config`)
- create per-worker temp config with only these overrides:
  - `runner.adb_serial`
  - `android_world.console_port`
  - `android_world.grpc_port`
  - `runner.output_root` (worker-specific output root)

Then invoke existing runner:

```bash
python3 eval/aw_bridge/runner.py \
  --config <tmp_worker_config.yaml> \
  --tasks-file <tmp_worker_tasks.txt>
```

This approach avoids edits to `runner.py` argument surface and reduces merge conflicts.

## 5. Output Layout

Proposed top-level directory:
- `eval/results_parallel/<timestamp>/`

Under it:

```text
eval/results_parallel/<timestamp>/
  summary.json                    # merged summary
  shard_manifest.json             # device/task mapping
  shards/
    shard_00_emulator_5554/
      worker_config.yaml
      tasks.txt
      runner_stdout.log
      run/                        # output_root passed to runner
        <runner_timestamp>/
          summary.json
          per_task.jsonl
          artifacts/
    shard_01_emulator_5556/
      ...
```

Notes:
- keep shard-native outputs intact for debugging.
- merged summary should reference shard summary paths.

## 6. Merge Semantics

Two data levels are needed:

1. Attempt-level data
- concatenation of all shard `per_task.jsonl` rows
- useful for infra-flake analysis

2. Final-per-instance data
- for each logical task instance, select the max `attempt`
- feed selected rows into `summarize_results(...)` to compute merged metrics

Merged `summary.json` should include:
- `num_shards`
- `num_devices`
- `num_task_instances`
- `num_attempts`
- merged `metrics`
- per-shard status (exit code, paths, timing)

## 7. Failure Model

- One shard failure must not kill already running siblings.
- Global exit code:
  - `0` when all shards succeed
  - non-zero when any shard fails
- Merged summary still written even if partial failure occurs.
- Partial runs must explicitly mark failed shards and missing metrics.

## 8. Concurrency and Resource Limits

Operational limits to document:
- API rate limits may become the throughput bottleneck.
- Host CPU/RAM and emulator stability can degrade above N devices.
- ADB server contention can increase timeout rates.

Recommended guardrails (v1):
- one worker per device
- no more than physical machine can sustain
- start with 2 emulators, validate flake rate, then scale

## 9. Implementation Plan

## Phase A (Low-risk MVP)
- Add `parallel_runner.py` only.
- Generate per-worker config overlays.
- Deterministic task sharding.
- Parallel subprocess execution.
- Merged summary + shard manifest.

## Phase B (Observability)
- Add shard start/end timestamps.
- Add per-shard throughput stats.
- Add a small `analysis/compare_parallel_runs.py` helper.

## Phase C (Quality)
- Add retry policy for shard boot failures (not task failures).
- Add optional `--max-workers` for large device lists.
- Add duration-aware sharding using historical runtime data.

## 10. Testing Strategy

Unit tests for `parallel_runner.py`:
- parse/validate `--device` specs
- sharding determinism
- merged metric correctness for synthetic `per_task.jsonl`
- partial failure summary generation

Integration smoke:
- 2 emulators, 4-6 tasks
- verify both shard outputs and merged summary
- confirm total wall-clock improves vs sequential baseline

## 11. Open Questions

- Should v1 support `--device SERIAL` auto-deriving ports, or keep strict explicit tuple only?
- Should merged summary include both final-only metrics and first-attempt-only metrics?
- Do we need optional per-shard backend/model overrides for A/B runs?

## 12. Recommendation

Implement Phase A first with zero API surface change to `runner.py`.

This gives immediate speedup, keeps execution semantics unchanged, and minimizes conflict risk while `eval/` is under active concurrent edits.
