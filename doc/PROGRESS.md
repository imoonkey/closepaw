# Changelog

## 2026-03-24: Doc structure alignment with /init-all and /update-doc standards

**What changed:**
- Renamed `AIDEV.md` → `CLAUDE.md` as source of truth; `AGENTS.md`, `GEMINI.md`, `.cursorrules` now symlink to `CLAUDE.md`
- Renamed `.ai-dev/` → `.claude/` as source of truth directory; `.agents/`, `.codex/` now symlink to `.claude/`
- Rewrote `CLAUDE.md` from 106 → 46 lines following pointer convention (≤50 lines, no embedded architecture/patterns)
- Split 5 oversized doc/main/ files (>300 line limit):
  - `app/history.md` (550) → `app/history/{overview,persistence,runtime,models}.md` (316 total)
  - `ui/overlay.md` (443) → `ui/overlay.md` (144) + `ui/capsule/architecture.md` (116)
  - `protocol/protocol.md` (419) → `protocol/{overview,events,config}.md` (297 total)
  - `infra/platform.md` (410) → `infra/platform.md` (152) + `infra/virtual_display.md` (74)
  - `infra/llm.md` (366) → trimmed to 138 lines
- Trimmed `doc/dev/development.md` from 328 → 265 lines
- Fixed archive naming: `diff_review` → `260206_diff_review`, `future_features.md` → `260206_future_features/`
- Updated all `.ai-dev/` → `.claude/` references in skill files, sop files, and PROGRESS.md
- Updated all cross-references from deleted files to new split locations

**Why:**
- Align with `/init-all` (CLAUDE.md as authority, ≤50 lines pointer doc, symlink direction) and `/update-doc` (≤300 line docs, YYYYMMDD archive naming) standards

**Key files:** `CLAUDE.md`, `.claude/`, `doc/main/README.md`, `doc/main/app/history/`, `doc/main/protocol/`, `doc/main/infra/virtual_display.md`, `doc/main/ui/capsule/architecture.md`
**Verification:** All README.md cross-reference links verified
**Commit:** `6ee3fd6`
**Blockers:** None

## 2026-03-13: Memory V2 Implementation

**What changed:**
- Replaced the V1 memory layout with V2 scope-first files: `memory/user.md`, `memory/device.md`, and `memory/apps/<package>.md`.
- Added a shared `MemorySchema` and rewrote `MemoryStore` to write canonical fixed-section markdown with full timestamps.
- Simplified `MemoryRecaller` to deterministic full-file recall for user, device, and current app memory.
- Redesigned `remember_experience` around `scope + section` routing instead of V1 `category + [kind]`.
- Memory writes now propagate explicit success/failure so `remember_experience` and failure auto-retain do not silently report success on failed saves.
- Updated failure auto-retain to write app `Operational Notes`, refreshed prompt guidance, and added regression tests for store, recall, prompt ordering, and tool validation/execution.
- Updated the main memory doc and added an implementation plan for this task.

**Why:**
- Bring runtime behavior in line with the agreed Memory V2 design: KISS scope-first files, deterministic recall, and no extra session-log memory layer.

**Key files:** `app/src/main/kotlin/com/moonkey/androidagent/memory/MemorySchema.kt`, `app/src/main/kotlin/com/moonkey/androidagent/memory/MemoryStore.kt`, `app/src/main/kotlin/com/moonkey/androidagent/memory/MemoryRecaller.kt`, `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/RememberExperienceTool.kt`, `app/src/main/kotlin/com/moonkey/androidagent/agent/Agent.kt`, `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt`, `doc/main/agent/memory.md`, `doc/todo/0.5_memory/memory_v2_implementation_plan.md`
**Verification:** `./gradlew assembleDebug testDebugUnitTest`, `./gradlew assembleDebug lint test`
**Commit:** `56aded7`
**Blockers:** None

## 2026-03-13: Memory V2 Design Note Capture

**What changed:**
- Added `doc/todo/0.5_memory/memory_v2_note.md` to preserve the agreed Memory V2 design discussion and final sketch.

**Why:**
- Preserve the current design consensus in-repo so implementation can proceed from one concrete source of truth instead of scattered chat context.

**Key files:** `doc/todo/0.5_memory/memory_v2_note.md`
**Commit:** `79b28a9`
**Blockers:** None

## 2026-03-13: Eval Config Overlay Loading

**What changed:**
- Eval config loading now always starts from `eval/config/default.yaml` and deep-merges any explicitly requested config on top.
- Parallel eval now uses the same merged config path as serial eval, so worker shard configs inherit default settings before device-specific overrides apply.

**Why:**
- Remove duplicated config copies, keep remote config minimal, and make config variants inherit new default settings automatically instead of drifting.

**Key files:** `eval/aw_bridge/runner.py`, `eval/aw_bridge/parallel_runner.py`, `eval/config/remote.yaml`
**Commit:** `2fbfeb2`
**Blockers:** None

## 2026-03-13: Eval Memory Hygiene

**What changed:**
- Eval bridge now clears `files/memory` before each task launch.
- Eval configs now exclude `remember_experience` by default so the memory tool is not exposed during eval.
- Updated eval and memory docs to document the clean-eval contract.
- Added regression tests for config loading and bridge cleanup/launch behavior.

**Why:**
- Prevent `RememberExperience` and persisted memory from contaminating eval runs while keeping the app runtime logic simple.

**Key files:** `eval/aw_bridge/native_agent_bridge.py`, `eval/aw_bridge/runner.py`, `eval/config/default.yaml`, `eval/config/remote.yaml`, `eval/config/gpt54_never_succeeded.yaml`, `eval/tests/test_native_agent_bridge.py`, `eval/tests/test_runner.py`, `doc/main/agent/memory.md`, `doc/main/eval/eval.md`, `eval/README.md`
**Verification:** `./gradlew assembleDebug test`, `./gradlew lint`, `python3 -m pytest eval/tests/test_native_agent_bridge.py eval/tests/test_runner.py eval/tests/test_runner_preflight_policy.py`
**Commit:** `7f18cdb`
**Next:** Keep future eval configs and task overrides aligned with the same clean-memory contract.
**Blockers:** None

## 2026-03-11: Remote Eval Worker Phase 2 & 3

**What changed:**
- Phase 2 (Dual Emulator): `provision.sh` now creates both `AndroidWorldAvd` and `AndroidWorldAvd2`. Runbook extended with dual-emulator baseline prep and parallel eval commands.
- Phase 3 (Operational Hardening): New `eval_tmux.sh` tmux wrapper for SSH-disconnect-safe eval. New `openai-proxy-tunnel.service` systemd unit with autossh auto-reconnect. `proxy_tunnel.sh` rewritten as service manager (install/start/stop/status/logs/manual). `provision.sh` now installs `autossh` and `tmux`.
- Updated `/cog-tune` and `/autotune` skills with remote eval commands and references.
- Added explicit git push/pull sync step to remote eval runbook.
- Remote smoke test: AVD2 created, autossh service verified active, proxy reachable. Required keychain env sourcing fix for passphrase-protected SSH keys.

**Why:**
- Enable parallel eval on remote to cut wall-clock time, and harden operations so long-running evals survive SSH disconnects and tunnel drops.

**Key files:** `scripts/remote/provision.sh`, `scripts/remote/eval_tmux.sh`, `scripts/remote/openai-proxy-tunnel.service`, `scripts/remote/proxy_tunnel.sh`, `doc/dev/remote_eval_worker.md`, `.claude/skills/autotune/SKILL.md`, `.claude/skills/cog-tune/SKILL.md`
**Verification:** `bash -n` on all scripts, remote smoke test (AVD2 created, autossh active, proxy ok)
**Commit:** `afa9713..ced92dc`
**Next:** Run dual-emulator baseline prep and parallel eval end-to-end on `desktop`. Ubuntu 22.04 upgrade deferred.
**Blockers:** None

## 2026-03-11: Remote Eval Worker Hardening and Validation

**What changed:**
- Hardened remote eval config loading so `android_world.adb_path` and emulator paths are expanded before use.
- Routed eval preflight ADB calls through the configured binary instead of assuming `adb` is on `PATH`.
- Pinned remote provisioning to emulator `32.1.15` from `emulator-linux_x64-10696886.zip` for Ubuntu 18.04 compatibility.
- Updated the remote worker docs to reflect the actual stale-checkout proxy failure mode and the validated rerun outcome.
- Re-ran the five previously failing app tasks on `desktop` after syncing the fixed bridge path; all five passed in `eval/results/20260311_102822`.

**Why:**
- Remove remote-only setup drift around ADB resolution, emulator versioning, and proxy routing so remote eval failures surface as real task behavior instead of infra noise.

**Key files:** `eval/aw_bridge/runner.py`, `eval/aw_bridge/runner_preflight.py`, `eval/tests/test_runner.py`, `scripts/remote/provision.sh`, `doc/dev/remote_eval_worker.md`, `doc/todo/remote_emulator/implementation_summary.md`
**Verification:** `eval/.venv/bin/python -m unittest eval.tests.test_runner eval.tests.test_runner_preflight_policy`, `bash -n scripts/remote/provision.sh`, `./gradlew assembleDebug lint test`, remote rerun `eval/results/20260311_102822` (`5/5` scripted success)
**Commit:** `76ee8ba`
**Next:** Harden remote worker operations (`autossh`/service wrapper, dual-emulator path) and investigate the `ExpenseAddSingle` agent-side verification gap.
**Blockers:** None

## 2026-03-11: Remote Emulator Eval Worker

**What changed:**
- Provisioned `desktop` (Ubuntu 18.04, i9-7900X, 62G RAM) as a remote Android eval worker with headless emulator.
- New `scripts/remote/provision.sh`: one-shot setup (JDK 17, Python 3.11, Android SDK, AVD).
- New `scripts/remote/proxy_tunnel.sh`: SSH tunnel helper for LLM proxy access.
- New `eval/config/remote.yaml`: remote-specific eval config with correct adb path.
- Modified `scripts/prepare_baseline.sh` and `scripts/eval_parallel.sh`: added `--headless` flag, venv Python preference, `~/android-sdk` emulator search path.
- Changed cproxy `proxy.js` bind from `127.0.0.1` to `0.0.0.0` so remote workers can connect directly.
- New `doc/dev/remote_eval_worker.md`: operational runbook.

**Why:**
- Move eval compute off the laptop onto a dedicated machine with more CPU/RAM and KVM support for faster x86_64 emulation.

**Key files:** `scripts/remote/provision.sh`, `scripts/remote/proxy_tunnel.sh`, `eval/config/remote.yaml`, `scripts/prepare_baseline.sh`, `scripts/eval_parallel.sh`, `doc/dev/remote_eval_worker.md`

**Design:** `doc/todo/remote_emulator/remote_emulator_eval_codex.md`

## 2026-03-11: Memory Auto-Retain Fallback Fix (`faf18ab`)

**What changed:**
- Auto-retain pitfall hook now tracks `lastKnownPackage` through the agent turn loop and uses it as fallback when `getCurrentPackageName()` returns null at failure time (e.g. a11y tree has 0 elements).
- Added logging for auto-retain writes.

**Why:**
- When a task fails with 0 accessibility elements, `getCurrentPackageName()` returned null, silently skipping the pitfall memory write. E2E testing on local emulator with gpt-5.4 revealed this gap.

**E2E verification:** Memory recall confirmed working — seeded `com.android.settings.md` was injected as `## Recalled Memory` into LLM prompt and visible to model.

**Key files:** `agent/Agent.kt`
**Commit:** `faf18ab`

## 2026-03-11: Cross-Session Memory System V1

**What changed:**
- New `memory/` package: `MemoryStore` (file I/O, entry caps, path traversal protection, atomic writes) and `MemoryRecaller` (elastic-budget recall per turn).
- New `RememberExperienceTool`: LLM-callable tool with `[workflow]`/`[pitfall]`/`[verification]` kind tags. Auto-allowed, cognitive tool (non-screen-changing).
- Recall injected into prompt between working memory and app skill via new `recalledMemory` param in `PromptBuilder.buildInputItems()`.
- Elastic budget: device 1KB + user_prefs 1.5KB + app gets remainder up to 3.5KB, total ≤6KB. Newest entries kept on truncation.
- Failure auto-retain hook in `Agent.kt`: when task fails and LLM never called `remember_experience`, auto-saves a `[pitfall]` entry.
- Storage: `<filesDir>/memory/apps/<package>.md`, `user_prefs.md`, `device.md`. Entry caps: 30/app, 20/user_prefs, 10/device.
- Added `ToolName.RememberExperience` variant, `StandaloneAgentDef` allowedTools + Long-Term Memory system prompt section.

**Why:**
- Let the agent learn from experience across sessions. App-specific quirks, pitfalls, and verification strategies persist as markdown and are recalled when the same app is foregrounded.

**Key files:** `memory/MemoryStore.kt`, `memory/MemoryRecaller.kt`, `tool/impl/RememberExperienceTool.kt`, `agent/Agent.kt`, `agent/TurnPlanningPhaseRunner.kt`, `agent/cognition/prompt/PromptBuilder.kt`, `agent/definition/StandaloneAgentDef.kt`, `session/SessionServices.kt`, `tool/ToolName.kt`

**Design:** `doc/todo/0.5_memory/final/design.md`

## 2026-03-10: OpenClaw Family Common Capability Analysis

**What changed:**
- Added a new comparative analysis doc at `doc/todo/0.5_openclaw/common/common_capabilities_analysis_cn_codex.md`.
- Distinguished core OpenClaw-family runtimes from adjacent ecosystem projects in `.reference/claws/`.
- Summarized the shared capability stack across OpenClaw variants: ingress surfaces, sessioned runtime, tools/execution, memory/workspace, autonomy/scheduling, ops, and security boundaries.
- Mapped platform boundaries into three buckets: desktop/cloud-specific strengths, Android-portable capabilities, and mobile-native advantages that desktop/cloud agents do not naturally own.

**Why:**
- Provide a clearer product and architecture frame for deciding what Android Agent should absorb from OpenClaw-family systems versus what should be reinterpreted natively for a phone-first agent.

**Key files:** `doc/todo/0.5_openclaw/common/common_capabilities_analysis_cn_codex.md`, `doc/changelog.md`

## 2026-03-09: Perception High-Fidelity Capture and Text Targeting Alignment (`f23287d`)

**What changed:**
- `Perceptor` capture now keeps raw `text`, `description`, and `hintText` without capture-time whitespace normalization.
- Prompt `text` now reflects visible/accessibility text semantics only (`text -> description -> hintText`) and no longer falls back to `resourceId` suffix.
- Added downstream-only `normalizeForMatching()` and used it for `text_index` / `desc_index` grouping and `TargetResolver` text lookup.
- `TargetResolver` now prioritizes prompt-text semantics first, then falls back to `description` / `hintText` when needed.
- Added dedicated perception/targeting tests, a new `doc/main/infra/perception.md` SOTA doc, and a perception-specific design note at `doc/autotune/round_14/percetion_fidelity_codex.md`.

**Why:**
- Preserve accessibility observations as source-of-truth and avoid irreversible capture-time rewriting.
- Fix drift between what the model sees in prompt JSON and what `text` targeting can actually resolve.

**Key files:** `Perceptor.kt`, `PerceptorInternals.kt`, `TargetResolver.kt`, `PerceptorTest.kt`, `TargetResolverTest.kt`, `doc/main/infra/perception.md`, `doc/autotune/round_14/percetion_fidelity_codex.md`

## 2026-03-09: Autotune Rounds 10-14 — qwen3.5 Targeted Tuning (0→20/22)

**What changed:**
- System prompt (`StandaloneAgentDef.kt`): added cross-app destination rule (#10), strengthened Information mode anti-hallucination, expanded Completion section with file-ops verification + date verification + scratchpad cross-checking. Later softened anti-hallucination to defer to app skill guidance.
- Perception (`PerceptorInternals.kt:236`): changed `MULTI_NEWLINE` replacement from `"\n"` to `"\n\n"` to preserve paragraph breaks in a11y tree.
- Debug logging (`TurnPlanningPhaseRunner.kt:158`): added `Log.d` for app skill lookup results.
- New/updated app skills:
  - `com.simplemobiletools.calendar.pro` — NumberPicker scroll, 24h format, date verification
  - `code.name.monkey.retromusic` — Songs tab add flow
  - `org.tasks` — diff-based completion detection, overflow menu location, priority turn budget, date reasoning example, partial-answer guidance
  - `de.dennisguse.opentracks` — Edit-based activity type checking with smart name filtering
- Eval config (`default.yaml`): added `max_turns: 50` for SportsTracker tasks + TasksHighPriorityTasks.

**Why:**
- Targeted tuning of 22 tasks that failed in R8/R9 (qwen3.5 model). Improved from 0/22 to 20/22 (90.9%) across 5 rounds.

**Key files:** `StandaloneAgentDef.kt`, `PerceptorInternals.kt`, `TurnPlanningPhaseRunner.kt`, `app/src/main/assets/app_skills/`, `eval/config/default.yaml`

## 2026-03-07: Add `/prompt-tune` Skill

**What changed:**
- New skill at `.claude/skills/prompt-tune/` with `SKILL.md` and `references/ownership_model.md`.
- Encodes the three-layer ownership model from the Round 4 prompt refactor design: core system prompt → tool descriptions → app skills.
- Provides a 5-step workflow: classify ownership → read target → apply change → anti-pattern check → validate.
- Includes a decision tree for ownership classification and an anti-pattern table.
- Absorbed `llm_best_practices.md` content (was a dead reference under cog-tune).
- Updated `/cog-tune` SKILL.md: replaced "External best practices" section with "Related skills" pointing to `/prompt-tune`.

**Why:**
- Separates diagnosis (`/cog-tune`) from treatment (`/prompt-tune`). Cog-tune analyzes traces and classifies root causes; prompt-tune applies the actual prompt/tool-desc/app-skill changes with ownership guardrails.

**Key files:** `.claude/skills/prompt-tune/SKILL.md`, `.claude/skills/prompt-tune/references/ownership_model.md`, `.claude/skills/cog-tune/SKILL.md`

## 2026-03-07: Fix Unchanged-Fallback Double-Click (`2042beb`)

**What changed:**
- `PointActionExecutorCore.buildPointActionOutcome()` no longer treats `Unchanged` as channel failure. Returns `ActionOutcome.Success` with `verified=false` and warning instead.
- No automatic fallback to next channel (e.g. `gesture_tap`) when `node_action_click` succeeds but screen content stays the same.
- Updated `mobile_action.md` pipeline docs and "Accepted but unchanged" semantics.

**Why:**
- When a click succeeds but the screen content happens to stay the same (e.g. random number repeats), the old logic fell through to `gesture_tap` causing a spurious second click. Observed in BrowserMultiply A11Y T16: 4th button click repeated the same number → extra click overwrote the 5th number.

**Key files:** `PointActionExecutorCore.kt`, `ClickExecutorTest.kt`, `doc/main/infra/tool/mobile_action.md`

## 2026-03-06: Provider Base URL Override (Local Proxy Support)

**What changed:**
- Added `OPENAI_BASE_URL` support in `.env` to route OPENAI-provider models through a local proxy without modifying `llm_models.json`
- New `ModelCatalog.withBaseUrlOverrides()` applies provider-level base URL overrides at session bootstrap via `__BASE_URL_<PROVIDER>` convention in the `apiKeys` map
- Full intent chain: `.env` → `debug-run.sh` / eval runner → intent extra → `AppSettingsState` → `SessionLlmBootstrapper` → `ModelCatalog` → client creation
- Eval runner (`native_agent_bridge.py`) now forwards `openai_base_url` intent extra
- Enabled `android:usesCleartextTraffic="true"` in manifest for HTTP proxy connections

**Why:**
- Route gpt-5.4/gpt-5.2 through a local OpenAI-compatible proxy (e.g. for quota management) as a script-level config, not baked into the model catalog

**Key files:** `session/SessionLlmBootstrapper.kt`, `llm/ModelCatalog.kt`, `app/AppSettingsState.kt`, `app/MainActivityIntentPayload.kt`, `eval/aw_bridge/native_agent_bridge.py`, `eval/aw_bridge/runner.py`, `scripts/debug-run.sh`, `AndroidManifest.xml`

## 2026-03-06: BrowserMultiply Eval — Two New Click Issues

**What changed:**
- Documented two new click failure patterns from BrowserMultiply eval runs (A11Y: `20260306_230038`, VD: `20260306_232810`):
  1. **First-click-after-launch**: `node_action_click` on Files RecyclerView item returns `true` but UI unchanged after 1800ms. Same click succeeds after other interactions (long_press → context menu). Reproducible in both A11Y and VD modes. Root cause unknown.
  2. **Unchanged-fallback double-click**: When a click succeeds but screen content stays the same (e.g. random number repeats), `UiChangeDetector` sees `Unchanged` → executor falls through to `gesture_tap` → extra click. Caused BrowserMultiply to lose the 5th number.
- Planned fix for #2: treat `Unchanged` as warning only, not channel failure.

**Why:**
- Both issues cause BrowserMultiply to fail (MaxTurnsReached at 30). Issue #2 is actionable — removing the fallback-on-unchanged behavior avoids the double-click. Issue #1 is a deeper Files RecyclerView quirk that needs further investigation.

**Key files:** `doc/main/infra/tool/mobile_action.md`, eval results `eval/results/20260306_230038/`, `eval/results/20260306_232810/`

## 2026-03-06: VD Click Transport Experiment (`edb4acd`, `55976dd`)

**What changed:**
- Added Shizuku injection and display-id targeting to `DebugActionExecutor` and `action-test.sh` for isolated transport testing.
- Ran 2x2 agent-loop matrix (A11Y/VD × node_click-first/gesture_tap-first) plus 8 isolated action-debug tests and secondary display smoke test on Files RecyclerView.
- Updated `mobile_action.md` with corrected transport matrix — previous claim that VD `injectInputEvent` "works like real touch" was not supported by test data on this surface.

**Why:**
- Files RecyclerView is a known difficult surface where `dispatchGesture` false-succeeds. Needed to determine if Shizuku `injectInputEvent` was a viable alternative. Result: `node_action_click` is the only reliable channel for this surface (8/8). Shizuku false-succeeded (0/3), but this may be a test setup issue — most other app surfaces work fine with all transports. Current priority order `node_click → gesture_tap` confirmed correct.

**Key files:** `app/.../debug/DebugActionExecutor.kt`, `scripts/action-test.sh`, `doc/main/infra/tool/click_transport_experiment.md`, `doc/main/infra/tool/mobile_action.md`

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
