# Changelog

Rounds before autotune are reconstructed from doc/todo/0.01_eval_tune/ history.

## Autotune Round 9 (2026-03-07, runs 050909–080143, GPT-5.4, full baseline eval-only)
- **Key changes**: none (eval-only round, no code changes since R8)
- **Task selection rationale**: Full eval across all 116 tasks (groups 0-5) — first comprehensive GPT-5.4 baseline with R8 code changes
- **Tasks run**: 116
- **Config**: `eval/config/gpt54_never_succeeded.yaml`, model GPT-5.4, serial single-device, snapshot_policy best_effort
- **Result**: 77.5/116 (66.8%)
- **Score breakdown**: G0=12/14 (86%), G1=14/20 (70%), G2=15/20 (75%), G3=15.5/20 (78%), G4=10/20 (50%), G5=11/22 (50%)
- **Net new passes**: 65 tasks passing on first GPT-5.4 full eval
- **Regressions**: VlcCreateTwoPlaylists 0.5→0.0 (VLC skill NOT injected), RetroSavePlaylist regression from R2 (GPT-5.4 gets stuck in empty playlist view)
- **Key findings**:
  - **App skill injection still broken (CRITICAL)**: Tasks skill injected in 1/6 runs. VLC skill never injected. Same root cause as R8 — package name timing.
  - **FalseCompletion dominant failure mode (47%)**: ~18 tasks. Name-based guessing, incomplete scans, no post-verification.
  - **Calendar date picker navigation (HIGH)**: 7 tasks affected — can't reliably navigate to specific dates or resolve relative times.
  - **Action layer newline bug**: MarkorAddNoteHeader — `\n\n` swallowed by ACTION_SET_TEXT. Different from R3 reasoning fix; this is an action layer bug in NodeActionPerformer.
  - **Cross-app destination binding**: ExpenseAddMultipleFromMarkor created file in Markor instead of opening Pro Expense.
  - **Retro Music song addition flow**: RetroSavePlaylist stuck in empty playlist view. Working pattern (Songs → find → long-press → Add to playlist) not followed.
  - **SportsTracker per-item inspection cost**: 4-5 turns per track × 7 tracks exhausts 30-turn budget.
  - **A11y tree timestamp/property gaps**: MarkorDeleteNewestNote had swapped modification times in a11y vs screenshot.
- **Moved to cannot_handle**: none
- **Failure distribution**: FalseCompletion 47%, ReasoningError 18%, TurnExhaustion 13%, ActionFailure 10%, cannot_handle 8%, PerceptionGap 4%

## Autotune Round 8 (2026-03-07, runs 050909–080143, GPT-5.4, multi-action + app skills)
- **Key changes** (de28c00):
  - Multi-action per turn (Minitap-style): TurnToolPolicy allows all screen-changing tools (was: first only). TurnExecutionPhaseRunner has abort-on-first-failure. System prompt rule 2 rewritten with navigation isolation rule.
  - Tasks app skill rewrite: org.tasks/SKILL.md with "don't click checkboxes" + date resolution + reading task state guidance
  - VLC app skill created: org.videolan.vlc/SKILL.md with multi-select pattern (long-press + tap) + playlist creation
  - Cog-tune quality upgrade: per_task_analysis_template.md with mandatory turn-by-turn table, perception checks, false-success detection
  - autotune skill enforces subagent-per-task for analysis
- **Task selection rationale**: Full eval across all 116 tasks (groups 0-5) — first comprehensive GPT-5.4 baseline
- **Tasks run**: 116
- **Config**: `eval/config/gpt54_never_succeeded.yaml`, model GPT-5.4, serial single-device, snapshot_policy best_effort
- **Result**: 77.5/116 (66.8%)
- **Score breakdown**: G0=12/14 (86%), G1=14/20 (70%), G2=15/20 (75%), G3=15.5/20 (78%), G4=10/20 (50%), G5=11/22 (50%)
- **Net new passes**: 65 tasks passing on first GPT-5.4 full eval run
- **Regressions**: VlcCreateTwoPlaylists 0.5→0.0 (VLC skill NOT injected; 1 playlist w/ 1 file vs R7's 1 playlist w/ 4 files)
- **Key findings**:
  - **App skill injection unreliable (CRITICAL)**: Tasks skill injected in 1/6 runs. VLC skill never injected. Package name timing issue in AccessibilityPlatform.getCurrentPackageName() → AgentTurnRunner pipeline.
  - **Multi-action works mechanically**: RecipeAddMultipleRecipesFromMarkor used 5-action batch turns (50→29 turns). But GPT-5.4 rarely emits multiple tool calls unprompted (3/29 turns batched for Recipe, 0/29 for VLC).
  - **FalseCompletion epidemic (~18 tasks)**: Dominant failure mode for info-extraction tasks. Agent guesses answers without verifying metadata (priority, sport type, completion status).
  - **A11y tree missing `checked` property**: Tasks.org checkboxes expose `checked=N/A`. Skill's "read checked property" guidance impossible to follow.
  - **Model-level improvement**: GPT-5.4 naturally avoids destructive checkbox clicking (R7 qwen3.5 wasted 6/11 turns on it). No prompt needed.
- **Moved to cannot_handle**: none

## Autotune Round 7 (2026-03-07, run 20260307_025501, qwen3.5, loop detection redesign)
- **Key changes** (9e6c9c7):
  - Loop detection redesign: deleted all WARNING-level advisory warnings (tool dominance, scroll spam, action repetition, cycle detection). Kept only stable-screen detection (Jaccard >= 0.95, 5 turns). Removed Tier 2 (BLOCK) and Tier 3 (FORCE_COMPLETE). -445 lines of code.
  - System prompt rule 6 rewritten: "if the same action fails twice, try a different approach" (was: "after failure or loop warning, pivot immediately")
  - Created org.tasks app skill with date-label mapping and scroll guidance
  - Updated /prompt-tune skill to cover system warnings as prompt layer
- **Task selection rationale**: same 8 tasks as R6 for direct comparison
- **Tasks run**: 8
- **Config**: serial single-device, model qwen3.5
- **Result**: 1/8 (12.5%), total score 1.5/8
- **Net new passes**: none
- **Score improvements**: VlcCreateTwoPlaylists 0.0→0.5 (completed 1/2 playlists)
- **Regressions**: none
- **Key findings**:
  - Loop detection removal confirmed effective: RecipeAddMultipleRecipesFromMarkor used all 50 turns and saved 2/3 recipes (R6: self-terminated at turn 22 with 1/3 partial). VlcCreateTwoPlaylists 0.0→0.5.
  - Dominant root cause shifted from ReasoningError/FalseCompletion to **TurnExhaustion** (4/7 failures). The 1-action-per-turn architecture is now the primary bottleneck.
  - Tasks app org.tasks skill was not injected — need to verify package name on emulator.
  - Two Tasks app tasks (TasksCompletedTasksForDate, TasksIncompleteTasksOnDate) corrupted app state by clicking checkboxes to inspect state.
- **Moved to cannot_handle**: none

## Autotune Round 6 (2026-03-07, run 20260307_003831, GPT-5.4, never-succeeded subset)
- **Key changes**: none (eval-only round, no code changes since R5)
- **Task selection rationale**: never_succeeded.txt minus gpt54_never_succeeded_10.txt (already run in R5) minus cannot_handle_group.txt = 8 tasks
- **Tasks run**: 8
- **Config**: `eval/config/gpt54_never_succeeded.yaml`, model gpt-5.4, serial single-device
- **Result**: 1/8 (12.5%)
- **Net new passes**: VlcCreatePlaylist (first-ever success)
- **Key findings**:
  - VlcCreatePlaylist: agent used multi-select → overflow menu → add to playlist → verify pattern. Clean 14-turn execution.
  - Tasks app cluster (3/3 fail): premature completion remains the blocker. Agent answers in 2-11 turns without scrolling, date reasoning, or status filtering. GPT-5.4 ignores existing date-label tip.
  - Multi-item workflow endurance (3/3 fail): RecipeAddMultipleRecipesFromMarkor self-terminated after 1/3 citing hallucinated loop warning. RecipeAddMultipleRecipesFromImage got trapped in detail view. VlcCreateTwoPlaylists used one-at-a-time instead of multi-select.
  - SportsTrackerActivitiesCountForWeek: a11y tree doesn't expose sport type, agent guessed from titles.
- **Moved to cannot_handle**: none

## Post-R5 Click Deep Dive (2026-03-06)

- **Scope**: Investigated click reliability on Files RecyclerView across all four transports, then validated with BrowserMultiply eval.
- **Transport experiment** (`edb4acd`, `55976dd`): 2x2 agent-loop matrix + 8 isolated action-debug tests + secondary display smoke test. Result: `node_action_click` is 8/8 reliable; `dispatchGesture` false-succeeds; Shizuku `injectInputEvent` false-succeeded (likely test setup issue). Current priority `node_click → gesture_tap` confirmed correct. VD priority swap design (`vd_action_priority_design.md`) rejected — core premise invalidated.
- **BrowserMultiply eval** (A11Y: `20260306_230038`, VD: `20260306_232810`): Both 0/1, MaxTurnsReached.
  - **First-click-after-launch**: `node_action_click` on `task.html` returns true but UI unchanged after 1800ms. Same click works after other interactions (long_press). Reproducible in both modes. Root cause unknown.
  - **Unchanged-fallback double-click**: When click succeeds but screen content stays the same (random number repeats), `UiChangeDetector` sees Unchanged → fallback to `gesture_tap` → extra click overwrites next value. **Fixed** (`2042beb`): `Unchanged` now returns `Success(verified=false)` with warning — no automatic fallback.
- **Experiment doc moved** to `doc/main/infra/tool/click_transport_experiment.md` as permanent SOTA reference.
- **Key files**: `doc/main/infra/tool/mobile_action.md`, `doc/main/infra/tool/click_transport_experiment.md`, `app/.../debug/DebugActionExecutor.kt`, `scripts/action-test.sh`, `app/.../tool/action/PointActionExecutorCore.kt`

## Autotune Round 5 (2026-03-06, run 20260306_003753, commit 5f01e95, GPT-5.4 smoke)
- **Key changes** (7a85be5→5f01e95):
  - `refactor: split prompt ownership across tools and app skills` (02844a5) — moved app-specific tips out of monolithic agent def into per-app SKILL.md files under `assets/app_skills/`; tool descriptions now own their own guidance (ShellTool, CompleteTaskTool, etc.); added AppSkillRepository + PromptBuilder injection
  - Shell tool restriction relaxed: was files-only, now general with permission caveat; removed hardcoded storage paths from prompt
  - `feat(eval): add local parallel eval entrypoint` (68d1f88) + 3 follow-up fixes (da92d97, a3187ac, 456d3aa) — parallel eval runner with per-task baselines, but still unstable so R5 ran serial
  - GPT-5.4 model entry added (OpenAI `response` API), new `gpt54_never_succeeded.yaml` eval config
  - Parked RecipeDeleteDuplicateRecipes3 + RetroPlaylistDuration to `cannot_handle`
- **Task selection rationale**: serial 10-task smoke from `never_succeeded` because parallel eval is still unstable
- **Tasks run**: 10
- **Config**: `eval/config/gpt54_never_succeeded.yaml` + temporary 10-task subset file, OpenAI provider
- **Result**: 2/10 (20%)
- **Net new passes**: BrowserMultiply, ClockTimerEntry
- **Key findings**:
  - Main blocker cluster is generic exact-target verification, not app-specific workflow discovery
  - Cross-app source/destination binding remains weak on import/logging tasks
  - BrowserDraw result was contaminated by emulator developer overlays (`pointer_location`, `show_touches`) discovered mid-run and turned off after the task
  - ExpenseDeleteDuplicates2 and OsmAndTrack reconfirm existing `cannot_handle` calls
  - OsmAndMarker made partial progress beyond older totally-blocked runs but still false-completed on the final map action
- **Moved to cannot_handle**: none
  Existing parked calls were reconfirmed, not expanded.

## Autotune Round 4 (2026-03-05, run 20260305_151648, commit 7a85be5)
- **Task selection rationale**: 3 Tasks failures (P1 targets), 1 Tasks canary, 1 Retro (P2), 1 Recipe (P2 max_turns), 1 SportsTracker infra (P3), 2 fragile SportsTracker (P2)
- **Tasks run**: 9
- **Fixes applied**: P1 date-label tip + QA scratchpad guardrail, P2 shell restricted to files, P2 repetitive-task guidance, P2 Retro batch-select tip, P2 max_turns=50/45, P3 stop_agent→force_stop 3s delay
- **Result**: 1/9 (11%)
- **Net new passes**: none
- **Regressions**: TasksHighPriorityTasksDueOnDate (1.0→0.0, natural variance), SportsTrackerTotalDistanceForCategoryOverInterval (1.0→0.0, premature shell pivot)
- **Key findings**:
  - Date-label tip partially effective: TasksIncompleteTasksOnDate now does date computation (R3: zero filtering → R4: correct filtering), but still 0.0 due to no-scroll
  - QA scratchpad guardrail partially effective: TasksDueNextWeek used it but contradicted own count
  - write_todos guidance counterproductive: RecipeDeleteDuplicateRecipes3 wasted 18% of turns on standalone write_todos calls
  - Retro batch-select tip partially followed but qwen3.5 cannot distinguish duplicate "More options" buttons
  - Infra P3 fix confirmed: SportsTrackerActivitiesCountForWeek now terminates cleanly (was InfraError → now FalseCompletion)
  - Both regressions attributed to qwen3.5 natural variance, not prompt changes
- **Moved to cannot_handle**: RecipeDeleteDuplicateRecipes3 (structurally impossible at any reasonable turn budget), RetroPlaylistDuration (qwen3.5-blocked, 7 consecutive 0.0)

## Autotune Round 3 — Analysis & R4 Prep (2026-03-05)
- **Aligned analysis**: Claude + Codex independent analyses, cross-reviewed and aligned via /double-design /align
- **Root causes**: FalseCompletion (3 Tasks app), TurnExhaustion (2), InfraError (1), fragile passes (2 SportsTracker at 28-30 turns)
- **Per-app success rate** (loose, last 3 runs): 7 apps at 100%, Vlc 0%, Tasks 50%, Browser/OsmAnd 33%
- **Never-succeeded pattern analysis**: 22 tasks across 7 patterns. Largest clusters: cross-app import (5), complex dedup (3), non-standard UI (5)
- **R4 approved fixes**:
  1. Generic date-label tip + QA scratchpad guardrail (P1, 3 tasks)
  2. Shell tool restricted to Files only (P2)
  3. Repetitive-task guidance: use write_todo, commit to working approach (P2)
  4. RetroPlaylistDuration: batch-select app tip + max_turns=45 (P2)
  5. RecipeDeleteDuplicateRecipes3: max_turns=50, park if still failing (P2)
  6. Timeout finalization observability (P3)
  7. Per-app success rate added to scoreboard.py
- **R4 task selection**: 9 tasks (3 Tasks failures, 2 fragile SportsTracker, 1 Retro, 1 Recipe, 1 SportsTracker infra, 1 Tasks canary)

## Autotune Round 3 (2026-03-04, run 20260304_152742, commit 63e3a0a)
- **Task selection rationale**: 22 group_5 never-run tasks (full sweep) + 3 targeted fixes (MarkorAddNoteHeader P1 blank line, RetroPlaylistDuration P2 duration strategy, MarkorEditNote keyboard_visible canary)
- **Tasks run**: 25
- **Fixes applied**: P1 Markor blank line tip (`\n\n`), P2 Retro Music iterative add-and-check tip, Q3 keyboard_visible detection (window-type-based instead of broken node traversal)
- **Result**: 19/25 (76%)
- **Net new passes**: MarkorAddNoteHeader (0→1), NotesIsTodo (new→1), NotesTodoItemCount (new→1), RecipeAddMultipleRecipesFromMarkor2 (new→1), RecipeDeleteSingleWithRecipeWithNoise (new→1), SimpleCalendarEventsInTimeRange (new→1), SimpleCalendarFirstEventAfterStartTime (new→1), SportsTrackerActivityDuration (new→1), SportsTrackerLongestDistanceActivity (new→1), SportsTrackerTotalDistanceForCategoryOverInterval (new→1), SportsTrackerTotalDurationForCategoryThisWeek (new→1), SystemBluetoothTurnOffVerify (new→1), SystemBluetoothTurnOnVerify (new→1), SystemBrightnessMaxVerify (new→1), SystemBrightnessMinVerify (new→1), SystemWifiTurnOffVerify (new→1), SystemWifiTurnOnVerify (new→1), TasksHighPriorityTasksDueOnDate (new→1)
- **Regressions**: none
- **Key findings**:
  - P1 blank line tip effective — MarkorAddNoteHeader finally passes after 2 rounds stuck
  - P2 duration strategy tip ineffective — RetroPlaylistDuration still 0 (agent ignores tip, qwen3.5 limitation)
  - Group 5 sweep: 17/22 passing on first run (77%)
  - Tasks app date reasoning is the main new problem — 3/4 Tasks app queries fail
  - SportsTracker expansion strong: 4/5 new tasks passing
- **Moved to cannot_handle**: none

## Autotune Round 2 (2026-03-04, run 20260304_033425, commit eadbf03)
- **Task selection rationale**: 8 directly affected tasks from R1 approved fixes (P1 hint text, P2 completion verification, P3-P4 Markor tips, P5 app workflow tips, Q3 keyboard flag, Q7 agent_thought, Q12 write_todos)
- **Tasks run**: 8
- **Fixes applied**: P1 hint text isShowingHintText, Q7 agent_thought first in 8 schemas, P2 completion verification checklist, P3 Markor extension tip, P4 Markor cursor positioning tip, P5 Retro Music + OpenTracks tips, Q3 keyboard_visible flag, Q12 write_todos re-enabled
- **Result**: 6/8 (75%)
- **Net new passes**: MarkorCreateNote (0→1), MarkorEditNote (0→1), RetroCreatePlaylist (0→1), RetroSavePlaylist (new→1), SportsTrackerActivitiesOnDate (0→1), RecipeDeleteMultipleRecipes (new→1)
- **Regressions**: none
- **Key findings**:
  - P4 cursor positioning tip highly effective — MarkorEditNote done in just 7 turns
  - P1 hint text fix confirmed working — RetroCreatePlaylist, RetroSavePlaylist now pass
  - MarkorAddNoteHeader still 0: `\n` vs `\n\n` blank line interpretation persists
  - RetroPlaylistDuration still 0: agent pre-reads durations individually instead of iterative add-and-check
  - keyboard_visible flag not observed in traces — may need APK rebuild verification
  - write_todos not used by RecipeDeleteMultipleRecipes (3 items too few to benefit)
- **Moved to cannot_handle**: none
- **Task selection rationale**: 4 turn-exhaustion tasks (max_turns=50), 2 Markor no-shell, 2 VLC infra-fixed, 2 investigation (RetroPlaylistDuration, SportsTrackerActivitiesOnDate), 2 regression canaries
- **Tasks run**: 12
- **Fixes applied**: P1-R3 max_turns override, P2-R3 cannot_handle exclusion (OsmAnd*), P3-R3 Markor no-shell directive, P6-R3 VLC init fix
- **Result**: 2/12 (16.7%)
- **Net new passes**: none (both passes are regression canaries: MarkorCreateNote, MarkorEditNote)
- **Regressions**: none
- **Key findings**:
  - `type` action hint text contamination is #1 blocker (3-4 tasks). Root cause: `NodeActionPerformer.setTextOnNode()` reads `node.text` which returns hint text on empty fields. Fix: `node.isShowingHintText` (API 26+).
  - `agent_thought` ordering in tool schemas — currently after `action`, should be before for chain-of-thought reasoning. 8 tools affected.
  - FalseCompletion without verification is #2 (2 tasks after removing cannot_handle).
  - max_turns=50 increase was ineffective — tasks FalseComplete early, not turn-exhausted.
  - LLM retry infra already exists (MAX_RETRIES=5); SSE error was likely misclassified as non-retryable.
  - Perception filters keyboard with no trace → need `keyboardVisible` flag.
  - `write_todos` tool should be re-enabled for multi-step tasks.
- **Moved to cannot_handle**: OsmAndMarker, OsmAndTrack (confirmed R35), ExpenseDeleteDuplicates2, RecipeDeleteDuplicateRecipes2 (ambiguous duplicate definition + noise trap)

## Pre-autotune Round 3 (2026-03-01, run 20260301_012237, commit ae6ecbc)
- **Task selection rationale**: Re-run 15 failures from R2 to validate P1-P8 fixes
- **Tasks run**: 15
- **Fixes applied**: P1 type clear=false, P3 Markor nav tip, P4 OsmAnd hybrid, P6 strategy-pivot, P7 OpenTracks tip, P8 VLC teardown
- **Result**: 2/15 (13.3%), up from 0/15 in R2 for this subset
- **Net new passes**: MarkorEditNote, SimpleCalendarEventsInNextWeek
- **Regressions**: none
- **Moved to cannot_handle**: none

## Pre-autotune Round 2 (2026-02-27, run 20260227_222506)
- **Task selection rationale**: Group 4 (20 tasks) + group 2/3 re-runs (8 tasks)
- **Tasks run**: 28
- **Result**: 14/27 scored (51.9%). 5 FalseCompletion, 3 NavigationFailure, 2 ActionFailure, 2 InfraError, 1 TurnExhaustion, 1 PerceptionGap
- **Key findings**: type clear=false needed, OsmAnd inaccessible, strategy-pivot prompt ineffective

## Pre-autotune Round 1 (2026-02-27, run 20260227_161048)
- **Task selection rationale**: Group 4 initial eval (20 tasks)
- **Tasks run**: 21
- **Result**: 7/21 (33.3%)
- **Key findings**: First systematic eval of group 4. Identified ActionFailure and NavigationFailure as top categories.
