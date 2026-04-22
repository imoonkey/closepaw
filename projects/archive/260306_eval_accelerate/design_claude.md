# Eval Acceleration Design

Date: 2026-03-05

## Goal

Reduce autotune round wall-clock time from ~100min (20 tasks * 5min sequential) to <30min, enabling faster iteration cycles.

## Current Bottleneck Analysis

Per-task breakdown (~5min):
- Emulator snapshot restore / task setup: ~30-60s
- Agent execution (LLM round-trips): ~3-4min (dominant)
- Task evaluation + teardown: ~15-30s

**Key insight: the bottleneck is LLM latency, not emulator compute.** During agent execution, the emulator is mostly idle waiting for LLM responses. Each turn: send screen state to LLM (~3-5s round-trip), execute one action (~0.5s), repeat. With max_turns=30, that's ~30 * 5s = 150s of pure LLM wait per task.

This means multiple emulators can share CPU/RAM efficiently — they rarely compete for local compute simultaneously.

## Hardware Constraints

Machine: Apple M1 Pro, 8 cores (6P+2E), 16GB RAM.

Per-emulator resource profile:
- RAM: 2-4GB allocated, but actual working set is lower when idle (waiting for LLM)
- CPU: spiky — brief bursts during UI rendering/action execution, idle during LLM calls
- Disk: snapshot images ~2GB each

Realistic local capacity: **2 emulators** comfortably, possibly 3 if aggressive with memory limits. Beyond that, swap pressure will destabilize emulators.

## Strategy Overview

Three independent acceleration axes, ranked by effort/impact:

| Strategy | Speedup | Effort | Cost |
|---|---|---|---|
| A. Local parallel (2 emulators) | ~2x | Low | $0 |
| B. Cloud parallel (4-8 devices) | ~4-8x | Medium | $5-15/round |
| C. Reduce per-task time | ~1.3-2x | Varies | $0 |

These compose multiplicatively. A+C alone could hit ~3-4x (30min target).

---

## Strategy A: Local Parallel (2 Emulators)

### Status

`parallel_runner.py` exists and is well-structured. Never tested. Code looks correct based on review.

### What's Needed

1. **Create a second AVD**: Clone `AndroidWorldAvd` to `AndroidWorldAvd2` (different snapshot).
2. **Boot both emulators** with non-conflicting ports:
   - Emulator 1: serial=emulator-5554, console=5554, grpc=8554
   - Emulator 2: serial=emulator-5556, console=5556, grpc=8556
3. **Smoke test** `parallel_runner.py` with 4 easy tasks.
4. **Monitor resources** during test: `htop` / Activity Monitor for RAM pressure, swap usage.

### Expected Result

- 2x speedup: 20 tasks in ~50min instead of 100min.
- RAM: ~4GB emulator1 + ~4GB emulator2 + ~4GB OS + ~2GB Python/AndroidWorld = ~14GB. Tight but workable because emulators won't peak simultaneously.
- If swap pressure is observed, reduce emulator RAM to 2GB each in AVD config.

### Risk

- ADB server contention with 2 devices (low risk — ADB handles multi-device well).
- AndroidWorld env isolation — each runner creates its own `env` with dedicated ports, so no conflict.
- Emulator instability under memory pressure → mitigate by monitoring and falling back to sequential.

### Integration with Autotune

Update `SKILL.md` Step 3 to optionally use `parallel_runner.py`:

```bash
# Sequential (current)
eval/.venv/bin/python eval/aw_bridge/runner.py --tasks-file eval/config/autotune_round_N.txt

# Parallel (new)
eval/.venv/bin/python eval/aw_bridge/parallel_runner.py \
  --config eval/config/default.yaml \
  --tasks-file eval/config/autotune_round_N.txt \
  --device emulator-5554:5554:8554 \
  --device emulator-5556:5556:8556
```

---

## Strategy B: Cloud Parallel

### Option B1: Genymotion Cloud SaaS

**How it works**: Managed Android VMs accessible via ADB tunnel over the internet.

- Pricing: ~$0.05-0.10/device-minute. 20 tasks * 5min = 100 device-min = **$5-10/round**.
- ADB over network: `adb connect <genymotion-host>:<port>`
- Supports REST API to start/stop devices programmatically.

**Pros**: Zero infra setup, instant scaling to 4-10 devices, stable.
**Cons**: Network latency on ADB commands (~50-100ms per command vs <1ms local), recurring cost.

**Architecture**:
```
Local machine                     Genymotion Cloud
  parallel_runner.py ──adb───────► Android VM 1 (port 5554)
                      ──adb───────► Android VM 2 (port 5556)
                      ──adb───────► Android VM 3 (port 5558)
                      ──adb───────► Android VM 4 (port 5560)
```

**Problem**: AndroidWorld env server needs to run alongside each emulator (it uses gRPC to control the device). Genymotion doesn't run arbitrary code on the VM host. We'd need to either:
- Run AndroidWorld env locally with remote ADB → adds network latency to every gRPC call.
- Or rethink the architecture to bundle everything in cloud.

### Option B2: Self-Hosted Cuttlefish on GCE

**How it works**: Rent a beefy GCE VM, run multiple Cuttlefish (Google's cloud-optimized Android emulator) instances + AndroidWorld + runner all on the same VM.

- GCE `n2-standard-16` (16 vCPU, 64GB): ~$0.76/hr. Can run 4-8 Cuttlefish instances.
- A 30min eval round: **~$0.40/round**. Very cheap.
- Use spot/preemptible for ~$0.23/hr → **~$0.12/round**.

**Architecture**:
```
GCE VM (n2-standard-16, 64GB, nested virt enabled)
  ├── Cuttlefish 1 (console=5554, grpc=8554)
  ├── Cuttlefish 2 (console=5556, grpc=8556)
  ├── Cuttlefish 3 (console=5558, grpc=8558)
  ├── Cuttlefish 4 (console=5560, grpc=8560)
  ├── AndroidWorld env (shared or per-device)
  ├── parallel_runner.py
  └── Agent APK installed on each device
```

**Pros**: Cheapest per-round, full control, no network latency (everything local to VM), scales to 8+ devices.
**Cons**: Setup effort (Cuttlefish + AndroidWorld + APK deploy automation), need to maintain a VM image/script.

### Option B3: docker-android on Any Cloud

Run `budtmo/docker-android` containers on any cloud VM. Simpler than Cuttlefish but uses standard emulator (heavier).

- Similar cost to B2 but needs more RAM per instance.
- Easier Docker-based setup.

### Recommendation: B2 for Best ROI

Cuttlefish on GCE is the best option if investing in cloud:
- $0.12-0.40/round is negligible.
- 4-8x parallelism → 20 tasks in 12-25min.
- One-time setup effort: create a GCE image with Cuttlefish + AndroidWorld pre-installed, write a `scripts/cloud_eval.sh` launcher.

**However**: Start with Strategy A first. Only invest in B2 if local parallel is insufficient or you need >2x speedup regularly.

---

## Strategy C: Reduce Per-Task Time

Independent of parallelism, these reduce the 5min/task baseline:

### C1: Smarter Task Selection (high impact, zero cost)

Current autotune selects 5-20 tasks per round. Reducing to 5-8 focused tasks cuts wall time proportionally. Already in SKILL.md as guidance — enforce it more strictly.

### C2: Faster LLM Responses

Since LLM latency is ~70% of task time:
- Use a faster model for autotune eval rounds (e.g., qwen3.5 is already fast).
- If using OpenRouter/cloud LLM, check if response streaming reduces perceived latency.
- Batch-friendly prompting (fewer but larger tool calls per turn) could reduce turn count.

### C3: Reduce Max Turns for Known-Fast Tasks

Some tasks consistently complete in <10 turns. Setting per-task `max_turns` lower avoids wasting time on failed attempts that spin for 30 turns.

Already partially implemented via `task_overrides` in config. Could be automated: if historical data shows a task never succeeds after 15 turns, cap at 15.

### C4: Snapshot Warm Cache

Ensure emulator snapshot is pre-warmed and close to the task start state. The `snapshot_policy: auto_repair` already does this. No further optimization needed.

---

## Implementation Plan

### Phase 1: Local Parallel MVP (1-2 hours)

1. Create second AVD (`AndroidWorldAvd2`).
2. Boot both emulators, verify they coexist (memory, ports).
3. Run `parallel_runner.py` with 4 tasks as smoke test.
4. Monitor RAM/CPU during the run.
5. If stable: run a full 10-task autotune round with parallel.
6. Compare wall-clock time vs sequential baseline.

### Phase 2: Integrate into Autotune (30 min)

If Phase 1 succeeds:
1. Add `parallel_runner.py` as an option in autotune SKILL.md.
2. Add a convenience script `scripts/eval_parallel.sh` that boots emulators + runs parallel.

### Phase 3: Cloud Setup (if needed, 4-8 hours)

Only if local parallel is insufficient:
1. Create GCE VM image with Cuttlefish + AndroidWorld + agent APK.
2. Write `scripts/cloud_eval.sh` (start VM, deploy APK, run parallel_runner, pull results, stop VM).
3. Test with 4 devices, 20 tasks.

---

## Trade-offs

| Approach | Pros | Cons |
|---|---|---|
| Local parallel only | Free, simple, uses existing code | Max 2x speedup, hardware-limited |
| Genymotion Cloud | Zero setup, scales well | $5-10/round, network latency issues with AndroidWorld |
| GCE + Cuttlefish | Cheapest at scale ($0.12/round), no latency | One-time setup effort, infra maintenance |
| Task selection alone | Free, no infra change | Reduces coverage per round |

## Decision

**Start with Phase 1 (local parallel).** It's free, uses existing code, and the LLM-latency-dominant workload means 2 emulators won't fight for resources as much as you'd expect. A 2x speedup (50min for 20 tasks) combined with tighter task selection (10 tasks → 25min) likely meets the <30min target without any cloud spend.

Revisit cloud (Phase 3) only if you routinely need 20+ task rounds or want <15min cycle times.
