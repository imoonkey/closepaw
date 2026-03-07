# Changelog

## 2026-03-06: Harden Post-Action Change Detection (`5ee310a`)

**What changed:**
- Excluded `isFocused` from `UiChangeDetector` fingerprint — RecyclerView items gain focus on `ACTION_CLICK` without actually navigating, causing false-positive "Changed" verdicts.
- Extended `PostActionAnalysis` verify window from 800ms (300+500) to 1800ms (300+500+1000) with a third retry round for slow transitions like intent resolution.
- Documented `gesture_tap` false-success pattern on Files RecyclerView items: `dispatchGesture()` accepted but UI unchanged. This is a platform limitation, not a runtime bug.

**Why:**
- The two bugs compounded: the detector reported success on the first channel (node click + isFocused false positive), so the runtime never fell through to retry or fallback. With both fixes, node click now correctly retries and succeeds within the 1800ms window.

**Key files:** `app/.../tool/action/UiChangeDetector.kt`, `app/.../tool/action/PostActionAnalysis.kt`, `doc/main/infra/tool/mobile_action.md`

## 2026-03-06: Click Hotspot Selection Fix (`04618f3`)

**What changed:**
- `refinePointActionTarget()` now searches for the nearest actionable child within a promoted container instead of defaulting to `container.center`.
- Added `findBestActionableChild()` with 80% area threshold and distance-based scoring.
- Added diagnostic logging for target promotion decisions.

**Why:**
- Files app regression: `container.center` landed on a dead zone where `ACTION_CLICK` was accepted but had no effect. The icon hotspot worked. Fix generalizes to any compound row without app-specific workarounds.

**Key files:** `app/src/main/kotlin/com/moonkey/androidagent/tool/action/PointActionExecutorCore.kt`

## 2026-03-06: Local Parallel Eval Workflow (`68d1f88`..`456d3aa`)

**What changed:**
- Hardened `eval/aw_bridge/parallel_runner.py` so the supervisor owns one-time APK build/install, honors `runner.perform_bridge_setup`, and merges results back into `eval/results/<run_id>/`.
- Added `scripts/eval_parallel.sh` as the standard local 2-device entry point for `AndroidWorldAvd` (`emulator-5554`) and `AndroidWorldAvd2` (`emulator-5556`).
- Updated eval docs plus `/autotune` and `/cog-tune` guidance to use the standard result contract and the new local parallel workflow.

**Why:**
- Cut eval wall-clock time with a real local parallel path without creating a second result format or breaking downstream tooling such as `scoreboard.py` and eval analysis flows.

## 2026-03-06: Prompt Ownership Refactor (`02844a5`)

**What changed:**
- Added asset-backed app skills under `app/src/main/assets/app_skills/` and load them per turn from the current foreground package.
- Injected the active app skill into the prompt between Working Memory and Observation.
- Rewrote the standalone and planner system prompts around cross-tool policy instead of app/tool-specific appendices.
- Expanded tool descriptions so `mobile_action`, `open_app`, `shell`, and `complete_task` own their local semantics.

**Why:**
- Separate global behavior, tool semantics, and app-specific knowledge so tuning changes land in one clear owner layer instead of accumulating in one monolithic prompt.

**Key files:** `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/prompt/AppSkillRepository.kt`, `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnPlanningPhaseRunner.kt`, `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt`, `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/PlannerAgentDef.kt`, `app/src/main/assets/app_skills/`
