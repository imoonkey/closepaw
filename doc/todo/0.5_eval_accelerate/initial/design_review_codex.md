status: draft

# Review of Claude Design - Eval Acceleration

Reviewer: Codex
Date: 2026-03-05

## Overall Assessment

Claude's design is directionally correct: local-first, keep `runner.py` semantics, and remove duplicated bridge build/install work before judging parallelism. The main gaps are not architectural; they are operational details already visible in the current harness. Those details matter because they can make a "parallel" experiment look flaky or slow even when the core approach is sound.

## Strong Points

1. It correctly treats eval acceleration as a throughput problem, not a pure concurrency problem.
2. It identifies the highest-value code change: `parallel_runner.py` currently launches full `runner.py` workers, and each worker's preflight rebuilds and reinstalls the APK.
3. It keeps the first version simple by layering orchestration around the existing single-device runner instead of rewriting the eval stack.
4. It stays skeptical about cloud before proving the local path.

## Main Gaps

## 1. The parallel runner is already implemented, so the task is validation plus hardening, not greenfield MVP

`eval/aw_bridge/parallel_runner.py` and `eval/tests/test_parallel_runner.py` already exist. The real unknown is end-to-end behavior on multiple emulators, not whether the orchestrator structure exists. The design should frame Phase 1 as:

- prove multi-device baseline works on this machine
- remove known duplicated preflight cost
- close the workflow/documentation gaps that keep autotune on the serial path

That keeps the scope honest and avoids spending time redesigning pieces that are already present.

## 2. Device bootstrap needs to be explicit, otherwise workers can fight the default emulator setup path

The default config still sets:

- `android_world.auto_start_emulator: true`
- `android_world.emulator_avd_name: AndroidWorldAvd`

`runner_preflight.run_android_world_connectivity_preflight()` uses `console_port` to derive the expected serial and may auto-start the emulator if it is missing. That means the parallel path cannot just say "pass two devices" and stop there. It also needs a clear bootstrap rule:

- either preboot all emulators and force worker configs to `auto_start_emulator=false`
- or provide distinct AVD names / launch assumptions per device and prove they do not race

Without this, a supposedly parallel run can fail for reasons unrelated to eval logic.

## 3. Build/install deduplication should come before benchmarking parallel speedup

The design already points at duplicated `build_and_install_bridge()` work, but this is more than a nice optimization. In the current code, every worker does:

- `:app:assembleDebug`
- `adb install -r -t ...`

If you benchmark local parallel without fixing that first, the measurement is contaminated by avoidable CPU, disk, and adb contention. The ordering should therefore be:

1. add a "skip bridge build/install" path to `runner.py` / `runner_preflight.py`
2. make `parallel_runner.py` do one global build and one per-device install
3. only then run the local 2-device validation

## 4. Autotune integration is not only a command swap; downstream analysis assumes the serial output contract

The autotune skill still points Step 3 at `eval/aw_bridge/runner.py`, and Step 4 analysis assumes the normal runner output layout under `eval/results/...`. `parallel_runner.py` currently writes `eval/results_parallel/<timestamp>/`, shard manifests, and a merged `summary.json`. Before adopting it in autotune, the design should define one of two paths:

- keep the merged output shape close enough that existing analysis tooling can read it unchanged
- or add a thin adapter that maps parallel output into the exact downstream contract expected by `/cog-tune`, scoreboard regeneration, and per-task analysis scripts

If this is not specified, the parallel runner may work technically but still be unusable in the actual autotune loop.

## 5. Keep scheduling simple in v1

Claude suggests `--max-workers auto`, which is reasonable, but anything more dynamic than a conservative cap of 2 local devices is premature. The current round-robin sharding is deterministic and easy to reason about. Duration-aware sharding or smarter balancing can wait until:

- build/install duplication is removed
- two-device runs are stable
- there is real data showing tail imbalance matters

## Recommended Adjustments

1. Reframe the design around "validate and harden existing parallel infra" rather than "build local parallel MVP."
2. Make preboot strategy part of the core design, not a deployment detail.
3. Require build/install deduplication before any performance evaluation.
4. Define the exact result contract needed for autotune Step 4 consumers.
5. Treat cloud as Phase 3 only after a stable local 2-device path exists.

## Bottom Line

Claude's proposal is the right base. The main improvement is to tighten it around the actual harness constraints that already exist in the repo: emulator startup assumptions, duplicated preflight work, and downstream result-shape compatibility. Once those are explicit, the design becomes implementation-ready instead of just directionally correct.
