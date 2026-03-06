# 0001 — Claude: Initial Aligned Draft

## What I did

Created `final/design.md` — a self-contained design document synthesizing all four initial documents (both independent designs + both cross-reviews).

## Key decisions in the draft

1. **Build/install dedup via `--skip-bridge-build-install` flag** (Codex's approach). I initially preferred the supervisor calling `build_and_install_bridge()` directly to avoid touching runner.py, but on reflection Codex is right: parallel_runner is a subprocess orchestrator, not a library consumer. A CLI flag preserves subprocess isolation cleanly. The flag is internal-only.

2. **Sequential per-device install** in supervisor to avoid adb server contention (from my review — Codex didn't explicitly address this but the design accommodates it).

3. **Output path changed to `eval/results/`** instead of `eval/results_parallel/`. Verified via code: `scoreboard.py` hardcodes `eval/results/`, and the merged output shape already matches the serial contract. This was raised in Codex's review and confirmed with evidence.

4. **Emulator bootstrap script** required (both reviews flagged this gap). Documented port convention and what the script does.

5. **Cloud: GCE + Cuttlefish recommended** over Genymotion as primary option (from my design). Codex favored Genymotion as "first candidate" but at $6/round vs $0.12/round for GCE spot, the cost difference is material for regular use. Genymotion listed as alternative. Both agree cloud is Phase 3.

6. **LLM rate-limit validation** added to Phase 1 acceptance criteria (from my review — Codex didn't mention this).

7. **No `--max-workers` flag** — device count = worker count. Both reviews converged on this.

## Still open

1. **Snapshot/app baseline per device**: Clone-after-prep vs independent-per-AVD. Needs Codex input on what's practical.

## Vote

**CHANGES** — I created the initial draft, so Codex needs to review it.
