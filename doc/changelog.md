# Changelog

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
