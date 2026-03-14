status: draft

# Review of Codex Design — Eval Acceleration

Reviewer: Claude
Date: 2026-03-05

## Overall Assessment

The design is solid and well-grounded. Codex correctly identifies the real bottleneck (wall-clock time per round), confirms the parallel runner already exists and is tested, and proposes a pragmatic local-first acceleration path. The "build/install once" insight is the highest-value change. Cloud analysis is balanced and avoids premature commitment.

Key concern: the design **underestimates existing code maturity** in some areas and **overestimates difficulty** in others, which skews the effort/risk framing.

---

## Correctness

### 1. "parallel_runner has never been used" — Partially correct

Codex states the code "lacks end-to-end verification" and autotune still calls serial `runner.py`. Both true. But the parallel runner (581 lines, well-structured) is a complete implementation, not a prototype. It handles signal forwarding, shard manifests, result merging, and failure isolation. This matters because Phase 1 framing ("implement...") should be closer to "validate and integrate" — lower effort than Codex implies.

### 2. build_and_install_bridge runs per-worker — Confirmed

`runner_preflight.py:94-114` — `run_preflight_checks()` unconditionally calls `build_and_install_bridge()` at line 113. Each `runner.py` subprocess enters `main()` → `run_preflight_checks()`, so N workers = N Gradle builds + N `adb install` calls. This is the correct bottleneck diagnosis.

### 3. Resource estimates — Reasonable but unverified

Codex claims 8 cores / 16 GB RAM / 986 MB per-emulator RSS. The `cpu_cap = floor(8/3)=2` and `mem_cap = floor((16-4)/2.5)=4` formulas are sensible defaults. However, the emulator RSS figure excludes QEMU overhead, GPU rendering buffers, and ADB server memory. Real per-emulator footprint is typically 1.5-2.5 GB on macOS with hardware acceleration. The "2 concurrent is safe" conclusion is correct, but for the wrong reasons — actual headroom is tighter than the formula suggests.

### 4. Round-robin sharding — Adequate for v1

Codex correctly notes this is already implemented (`parallel_runner.py:154-159`). The tail-latency concern is real but second-order: with 2 shards and 20 tasks (10 each), the expected slowest-shard overhead is modest.

---

## Gaps

### 1. Preflight beyond build/install is also duplicated (Critical)

The design focuses on `build_and_install_bridge()`, but the full `run_preflight_checks()` also runs:
- `ensure_adb_device_ready()` — harmless per-device, fine
- `ensure_task_app_snapshots()` — calls AndroidWorld snapshot restore for each app **per worker**
- `attempt_targeted_task_app_install()` — may install apps **per worker**

With 2 workers sharing the same emulator (which isn't the case here — but if tasks on different emulators need the same app snapshots), the snapshot/install work is legitimately per-device. However, if using a single emulator image cloned to multiple AVDs, the baseline prep should happen **once before cloning**, not per-worker. The design doesn't address baseline preparation for multi-emulator setups.

**Recommendation:** Phase 1 should document the assumed device preparation flow (each emulator is pre-provisioned with all required apps/snapshots before parallel run).

### 2. No emulator lifecycle management

`parallel_runner.py` requires pre-started emulators (`--device` specs). The serial `runner.py` has `auto_start_emulator` support (`runner_preflight.py:284-287`). The design doesn't address:
- Who starts/stops the second emulator?
- Does `auto_start_emulator` work across different console/grpc port pairs?
- Is there a script or documented procedure for spinning up N emulators?

This is the biggest **usability gap** for adoption. The autotune skill operator (human or AI) needs a turnkey way to go from "1 emulator" to "2 emulators ready".

**Recommendation:** Add a `scripts/start_emulators.sh` or equivalent to Phase 1, or at minimum document the manual steps.

### 3. Autotune skill integration is hand-waved

The design says "autotune skill Step 3 add parallel example command" but doesn't specify the decision logic. Key questions:
- Should autotune **always** use parallel when 2+ devices are available?
- How does the autotune operator know which devices are up?
- Should the skill auto-detect available emulators?

This is important because the user's pain point is autotune round time, and the skill is the actual entry point.

### 4. adb server contention under parallel install

Even with build-once, parallel `adb install` to different devices goes through a single `adb server` process. On macOS, this can cause timeouts when the server is busy. The design mentions "adb/server contention" in risks but proposes no concrete mitigation.

**Recommendation:** Sequential install in the supervisor (build once → install to device A → install to device B → launch workers). This is trivially serializable and eliminates the contention window.

### 5. No LLM API rate-limit consideration

With 2 workers running tasks simultaneously, LLM API request rate roughly doubles. For providers with per-minute rate limits (OpenAI, OpenRouter), this could cause 429 errors mid-task, leading to task failures that look like agent bugs.

**Recommendation:** Mention provider rate limits as a Phase 1 validation item. The A/B test should check for increased API error rates.

---

## Trade-offs & Alternatives

### Build-once via CLI flag vs. supervisor-managed preflight

Codex proposes adding `skip_bridge_build_install` to `runner.py` and having the supervisor set it. This is a clean approach but creates a "partial preflight" mode that's easy to misuse (someone runs `runner.py --skip-bridge-build-install` standalone without building first).

**Alternative:** Instead of a skip flag, have the supervisor invoke `build_and_install_bridge()` directly (it's a standalone function in `runner_preflight.py:705-731`) and then launch workers. Workers still run the rest of preflight (adb ready, snapshots, packages). This avoids touching `runner.py` at all — consistent with the original parallel design's principle (`doc/todo/0.5_eval/parallel/design.md` section 12: "zero API surface change to runner.py").

This is a better trade-off: simpler, no new flags, no risk of partial-preflight misuse.

### Resource auto-detection vs. explicit `--max-workers`

Codex proposes both `auto` and `N` modes for `--max-workers`. But `parallel_runner.py` already determines worker count from `--device` count — if you pass 2 devices, you get 2 workers. Adding a separate `--max-workers` that might **discard** provided devices is confusing.

**Recommendation:** Keep `--device` as the sole concurrency control (pass fewer devices = fewer workers). Resource gate should be a **warning**, not a cap. KISS.

### Cloud cost analysis

The Firebase / AWS / Genymotion comparison is useful but contains assumptions that should be flagged:
- Firebase Test Lab pricing is for **test execution**, not raw emulator access. You can't run arbitrary `runner.py` on FTL — it's Robo/Instrumentation only. Codex correctly flags "low adaptability" but the cost estimate is misleading because the harness can't run there at all without a fundamental rewrite.
- Genymotion SaaS at $0.06/min for 100 device-minutes = $6/round. At 2 rounds/day, that's ~$360/month — worth flagging as the steady-state cost.

---

## Specific Code Observations

1. **`parallel_runner.py` doesn't pass `--snapshot-policy` or `--platform-mode`** to workers. `runner.py` (`_parse_args`, line 278-288) accepts these, but `_launch_workers` (line 288-293) only passes `--config` and `--tasks-file`. Since the worker config YAML is generated by the supervisor, this is fine for YAML-sourced settings — but CLI-only flags won't propagate. Worth verifying all settings the user might override are represented in the YAML path.

2. **`_wait_for_workers` polls at 2-second intervals** (line 332). For runs taking 5+ minutes per task, this is fine. But stdout/stderr is redirected to files, so there's no live progress feedback. Consider adding periodic shard status prints (e.g., every 60 seconds: "shard 0: still running, X minutes elapsed").

---

## Summary of Recommendations

| # | Item | Priority | Effort |
|---|------|----------|--------|
| 1 | Supervisor calls `build_and_install_bridge()` directly instead of adding skip flag to runner.py | High | Small |
| 2 | Sequential per-device install in supervisor to avoid adb contention | High | Small |
| 3 | Document/script multi-emulator startup procedure | High | Small |
| 4 | Address baseline prep (snapshots/apps) for multi-emulator | Medium | Medium |
| 5 | Add LLM rate-limit validation to A/B test plan | Medium | Tiny |
| 6 | Drop `--max-workers` in favor of `--device` count as sole concurrency control | Low | Zero |
| 7 | Add periodic progress prints to `_wait_for_workers` | Low | Tiny |

## Verdict

Approve with changes. The core direction (local 2-parallel with build dedup) is correct and achievable in 1-2 days. The main risk is usability — without emulator lifecycle tooling and autotune skill integration, the parallel runner stays unused just like it is today.
