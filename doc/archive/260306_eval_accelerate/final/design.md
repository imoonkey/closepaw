# Eval Acceleration - Aligned Design

Date: 2026-03-05
Status: aligned-draft (SEQ=0001, pending Codex review)

## Goal

Reduce autotune eval round wall-clock time from ~100min (20 tasks x 5min sequential) to <30min. Local-first, cost-zero, stability-preserving.

## Problem Analysis

Per-task breakdown (~5min):
- Emulator snapshot restore / task setup: ~30-60s
- Agent execution (LLM round-trips): ~3-4min (dominant, ~70% of wall time)
- Task evaluation + teardown: ~15-30s

**Key insight**: The bottleneck is LLM latency, not emulator compute. During agent execution the emulator is mostly idle waiting for LLM responses. Multiple emulators share CPU/RAM efficiently because they rarely compete for local compute simultaneously.

**Hardware**: Apple M1 Pro, 8 cores (6P+2E), 16GB RAM. Real per-emulator footprint is 1.5-2.5GB on macOS (RSS + QEMU overhead + GPU buffers). Realistic local capacity: **2 emulators** comfortably. 3+ risks swap pressure and emulator instability.

## Scope

### In Scope

1. Validate and harden existing `parallel_runner.py` for 2-device local use.
2. Remove duplicated build/install work that contaminates parallel performance.
3. Provide turnkey emulator lifecycle and autotune integration.
4. Document cloud options for future burst capacity.

### Out of Scope

1. Rewriting eval stack (Appium/Espresso/Firebase).
2. Distributed multi-host scheduling.
3. Maximizing concurrency beyond 2 local devices.
4. In-process concurrency inside `runner.py`.

## Ground Truth From Current Repo

1. `eval/aw_bridge/parallel_runner.py` (581 lines) is a complete implementation with signal forwarding, shard manifests, result merging, and failure isolation. `eval/tests/test_parallel_runner.py` passes (41 tests). Never used end-to-end.
2. `runner_preflight.run_preflight_checks()` unconditionally calls `build_and_install_bridge()`, so N workers = N Gradle builds + N APK installs.
3. AndroidWorld connectivity preflight assumes strict `emulator-<console_port>` serial mapping.
4. Default config: `auto_start_emulator: true`, `emulator_avd_name: AndroidWorldAvd`.
5. `scripts/scoreboard.py` only scans `eval/results/*/per_task.jsonl`.
6. `scripts/prepare_baseline.sh` already exists and is the canonical clean-baseline workflow (kill emulator, boot with `-wipe-data`, run `prepare_baseline.py`).
7. The autotune skill still runs the serial runner only.

**Conclusion**: The codebase has the skeleton for local parallel eval, but it is not yet safe or ergonomic enough to become the default path. The task is validation + hardening, not greenfield.

## Design Principles

1. Keep `runner.py` as the single-device execution engine — zero API surface change.
2. Keep concurrency outside the runner, in the supervisor.
3. Preserve the existing result contract for downstream tools.
4. Prefer explicit device contracts over auto-discovery.
5. Start with the stable capacity of this machine: 2 local emulators.
6. Deduplicate preflight before benchmarking — measurements without this are contaminated.

---

## Design

### 1. Device Bootstrap Contract

Parallel eval runs against **two pre-provisioned emulators** with fixed port mappings:

| Device | Serial | Console Port | gRPC Port |
|--------|--------|-------------|-----------|
| A | emulator-5554 | 5554 | 8554 |
| B | emulator-5556 | 5556 | 8556 |

Rules:
1. Both AVDs must be baseline-prepared (apps/snapshots installed) before the eval run.
2. Baseline prep uses `scripts/prepare_baseline.sh` once per AVD with that device's own `--avd`, `--console-port`, `--grpc-port`, `--adb-serial`. Cloning is allowed as a shortcut but not the correctness contract.
3. Worker configs set `auto_start_emulator=false` — no implicit emulator startup races.
4. Concurrency is controlled solely by the number of `--device` arguments. No separate `--max-workers`. KISS.
5. A convenience script (`scripts/eval_parallel.sh`) handles emulator boot + parallel runner invocation as a turnkey entry point.

### 2. Build/Install Deduplication

**Problem**: Each worker subprocess triggers a full Gradle build + APK install.

**Solution**: The supervisor owns build/install; workers skip it.

```
parallel_runner.py supervisor:
  1. Build APK once (assembleDebug)
  2. Sequential per-device: adb install (avoids adb server contention)
  3. Launch workers with config: perform_bridge_setup=false
  4. Workers run remaining preflight:
     - adb readiness
     - AndroidWorld connectivity
     - snapshot/package checks
```

Implementation: Add an internal runner config field `runner.perform_bridge_setup` (default `true`). The supervisor sets it to `false` in generated worker configs. The `runner.py` CLI remains unchanged — this field is config-only, not a CLI flag, preventing standalone misuse. The supervisor calls `build_and_install_bridge()` directly from `runner_preflight.py`.

### 3. Result Contract Preservation

Parallel output lands in the standard `eval/results/<run_id>/` path:

```
eval/results/<run_id>/
  per_task.jsonl          # merged, same format as serial
  summary.json            # merged, same format as serial
  parallel/
    shard_manifest.json
    shards/
      shard_00_emulator_5554/
      shard_01_emulator_5556/
```

Rules:
1. Top-level `per_task.jsonl` and `summary.json` are canonical, identical in format to serial output.
2. `scripts/scoreboard.py`, `/cog-tune`, and downstream consumers work with zero changes.
3. Shard-specific debugging artifacts live under `parallel/`.

### 4. Autotune Integration

Autotune gains a clear parallel path with explicit preconditions:

```bash
# Serial (current, unchanged)
eval/.venv/bin/python eval/aw_bridge/runner.py \
  --config eval/config/default.yaml \
  --tasks-file eval/config/autotune_round_N.txt

# Parallel (new, via convenience script)
./scripts/eval_parallel.sh eval/config/autotune_round_N.txt

# Or directly:
eval/.venv/bin/python eval/aw_bridge/parallel_runner.py \
  --config eval/config/default.yaml \
  --tasks-file eval/config/autotune_round_N.txt \
  --device emulator-5554:5554:8554 \
  --device emulator-5556:5556:8556
```

Decision logic: use parallel when 2 prepared devices are available; fall back to serial otherwise. The skill doc must state the precondition: both emulators already started and baseline-prepared.

Task budget: 8-10 tasks for normal rounds, 20 for regression sweeps.

### 5. Sharding

v1: Deterministic round-robin (already implemented in `parallel_runner.py`). With 2 shards and 10-20 tasks, tail imbalance is modest and acceptable.

v2 (Phase 3, optional): Duration-aware sharding using historical `per_task.jsonl` p50 data, only if real imbalance appears in practice.

### 6. LLM Rate-Limit Handling

With 2 workers, LLM API request rate roughly doubles. For providers with per-minute rate limits (OpenAI, OpenRouter), this could cause 429 errors mid-task that look like agent bugs.

**Phase 1 requirement**: The A/B validation must track LLM API error rates. If 429s appear:
- First mitigation: stagger task start times by 30-60s between shards.
- Second mitigation: shared request-rate semaphore in the supervisor (only if staggering is insufficient).

---

## Per-Task Time Reduction (Complementary)

Independent of parallelism, these reduce the 5min/task baseline and compose multiplicatively with parallel speedup:

1. **Smarter task selection**: Enforce 8-10 focused tasks per round (already guided in autotune SKILL.md, enforce strictly).
2. **Per-task max turns cap**: Tasks that historically never succeed after 15 turns should be capped at 15. Partially supported via `task_overrides`; automate using historical `per_task.jsonl` data.
3. **Faster LLM responses**: Use faster models for eval rounds; batch-friendly prompting to reduce turn count.

Combined projection: 10 tasks x 5min / 2 devices = 25min, meeting the <30min target.

---

## Cloud Strategy (Future, Phase 4)

Only invest after local parallel is proven insufficient. Decision threshold: local estimated wall time >75 minutes.

### Recommended if needed: GCE + Cuttlefish

- `n2-standard-16` (16 vCPU, 64GB): ~$0.76/hr, supports 4-8 Cuttlefish instances.
- Spot: ~$0.23/hr -> ~$0.12/round for 30min. Everything on one VM, no network latency.
- One-time setup: GCE image with Cuttlefish + AndroidWorld + agent APK, `scripts/cloud_eval.sh` launcher.
- Caveat: GCP nested virtualization has ~10%+ performance penalty.

### Alternative: Genymotion SaaS

- $0.06/min -> ~$6/round (100 device-min). Steady-state at 2 rounds/day: ~$360/month.
- Closest to "managed emulator + ADB" model.
- Limitation: AndroidWorld env server can't run on Genymotion VM host — must run locally with remote ADB, adding network latency to gRPC calls.

### Not viable

- **Firebase Test Lab**: Robo/Instrumentation only — can't run arbitrary `runner.py` without fundamental rewrite.
- **AWS Device Farm**: $0.17/device-minute ($17/round) — too expensive.

---

## Implementation Plan

### Phase 1: Harden Local Parallel (1-2 days)

**Prerequisite**: Build/install deduplication lands before any performance measurement.

1. Refactor bridge setup: supervisor builds once, installs sequentially per device.
2. Add `runner.perform_bridge_setup` config field; workers skip when `false`.
3. Parallel output -> standard `eval/results/<run_id>/` contract with `parallel/` subdirectory.
4. Worker configs: fixed device specs, `auto_start_emulator=false`.
5. Create second AVD (`AndroidWorldAvd2`), document provisioning via `prepare_baseline.sh`.
6. Write `scripts/eval_parallel.sh`.

### Phase 2: Validate (0.5-1 day)

A/B comparison on 8-10 tasks (serial vs 2-parallel):

| Metric | Target |
|--------|--------|
| Wall-clock reduction | >= 40% |
| Success rate drop | <= 5pp |
| Infra failure rate | no increase |
| LLM 429 / throttling | no increase |

### Phase 3: Polish (optional, 1 day)

1. Periodic shard progress logging (every 60s: shard status, elapsed time).
2. Duration-aware sharding if tail imbalance is observed.
3. Shard-level health metrics.

### Phase 4: Cloud Burst (optional, 2-4 days)

1. GCE VM image with Cuttlefish + AndroidWorld + agent APK.
2. `scripts/cloud_eval.sh` launcher.
3. Small-sample consistency check (same tasks + seed, local vs cloud scores).

---

## Risks and Mitigations

| Risk | Cause | Mitigation |
|------|-------|------------|
| Parallel runs still look slow | Duplicated build/install not fully removed | Finish dedup before measuring |
| Emulator race / wrong-device | Implicit auto-start, non-explicit ports | Fixed 2-device contract, explicit `--device`, `auto_start_emulator=false` |
| Downstream tooling breaks | Parallel results in separate root | Preserve `eval/results/<run_id>/` contract |
| LLM API throttling | 2x request rate | Monitor 429s in A/B; stagger task starts if needed |
| ADB server contention | Parallel adb install | Sequential install in supervisor |
| Memory pressure | 16GB total, ~3-5GB per emulator | Monitor during A/B; fall back to sequential if swap detected |

## Decision

Start with strict local-first: 2 prepared emulators, hardened `parallel_runner.py`, build once / install once per device, standard result contract, autotune integration after local path is proven stable. This is the shortest path to a real speedup without introducing a second eval system or over-designing cloud infrastructure.
