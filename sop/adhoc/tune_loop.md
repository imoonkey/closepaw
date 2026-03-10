

# R22+: Unpark cannot_handle

## (Done) Step 1: One-time setup (run manually, NOT ralph-loop)

Prepare the unpark loop. Do the following:

1. Create eval/config/unpark_active.txt with these 13 tasks (one per line):
   AudioRecorderRecordAudioWithFileName
   BrowserDraw
   BrowserMaze
   ExpenseAddMultipleFromGallery
   ExpenseDeleteDuplicates2
   MarkorDeleteNewestNote
   OsmAndMarker
   OsmAndTrack
   RecipeAddMultipleRecipesFromImage
   RecipeDeleteDuplicateRecipes2
   RecipeDeleteDuplicateRecipes3
   RetroPlaylistDuration
   SportsTrackerTotalDistanceForCategoryOverInterval

2. Replace eval/config/cannot_handle_group.txt with only:
   MarkorTranscribeVideo

3. Reset doc/autotune/meta/loop_state.json:
   - status: "active"
   - round: 22
   - stop_reason: null
   - active_backlog_file: "eval/config/unpark_active.txt"
   - loop_name: "unpark_cannot_handle"

4. For each of the 13 tasks, read the original park reason from
   doc/autotune/round_21/20260309_163832/loop_summary_r16_r21.md and the scoreboard history from
   doc/autotune/meta/scoreboard.md. Write a brief triage note to doc/autotune/meta/unpark_triage.md
   with columns: Task | Park Reason | Has Ever Passed | Hypothesis | Suggested First Fix.

## Step 2: Ralph loop (copy-paste this)

/ralph-loop:ralph-loop "Run /autotune-loop for this repo.

  Context:
  This is an unpark loop targeting 13 previously-parked tasks. The setup has already been done:
  - eval/config/unpark_active.txt contains the active backlog (13 tasks).
  - eval/config/cannot_handle_group.txt contains only MarkorTranscribeVideo.
  - doc/autotune/meta/loop_state.json is initialized for this loop.
  - doc/autotune/meta/unpark_triage.md has per-task triage notes with hypotheses and suggested fixes.

  The prior park decisions were made conservatively under time pressure. Re-examine each task with
  fresh eyes: the original park reasons may have been wrong, overly pessimistic, or fixable with
  better app skills / tool descriptions / perception config.

  Model:
  - Use gpt-5.4 for this loop.

  Per-round rules:
  - Execute exactly one orchestrated /autotune round per Ralph iteration.
  - Read doc/autotune/meta/unpark_triage.md for the current hypothesis before each round.
  - Select 3-5 tasks from eval/config/unpark_active.txt per round. Prioritize tasks that have
    passed at least once in scoreboard history (BrowserDraw, BrowserMaze,
    SportsTrackerTotalDistanceForCategoryOverInterval, MarkorDeleteNewestNote) before attempting
    never-passed tasks.
  - Before running eval, form a hypothesis for why the task failed and apply a fix. Allowed fix
    types: app skills, tool descriptions, perception_mode overrides in default.yaml, max_turns
    adjustments, core prompt tweaks. No agent code changes.
  - After eval, update unpark_triage.md with what was tried and what happened.

  Progress tracking:
  - Use doc/autotune/meta/loop_state.json as the only control-plane state.
  - Use doc/autotune/meta/scoreboard.json / scoreboard.md as machine-readable progress tracking.
  - If a task is claimed fixed, require a passing eval result in eval/results/*/per_task.jsonl.
  - If a task passes eval (scripted_score=1.0), remove it from eval/config/unpark_active.txt.
  - If a task fails 3 consecutive rounds with no progress AND you have exhausted your fix ideas,
    re-park it back to eval/config/cannot_handle_group.txt with an updated, more precise reason.
  - If blocked by provider credits, emulator failure, or other infra issues, record that explicitly
    in loop_state.json and do not treat it as a model fix.

  Stop condition:
  - Stop when every task in eval/config/unpark_active.txt is either fixed with eval evidence or
    re-parked with a concrete, updated reason.
  - When loop_state.json status=complete, output <promise>AUTOTUNE_LOOP_COMPLETE</promise>." --max-iterations 10 --completion-promise "AUTOTUNE_LOOP_COMPLETE"

# (Done) R16-21
 /ralph-loop "Run /autotune-loop for this repo.

  Goal:
  Fix the remaining unresolved tasks from the full R8/R9 failure union using machine-verified eval traces as the only source of
  truth.

  Ground truth files:
  - eval/config/r8r9_failed_union_machine_verified.txt
  - eval/config/r8r9_remaining_active_machine_verified.txt
  - eval/config/autotune_round_16.txt
  - eval/config/cannot_handle_group.txt
  - doc/autotune/meta/loop_state.json
  - doc/autotune/meta/scoreboard.json

  Model:
  - Use gpt-5.4 for this loop, not qwen.
  - Keep using gpt-5.4 in subsequent rounds unless there is a concrete, documented reason to change models.

  Operating rules:
  - Do not trust the old 20/22 narrative unless it is backed by eval/results/*/per_task.jsonl.
  - Treat eval/config/r8r9_remaining_active_machine_verified.txt as the active backlog.
  - Start from eval/config/autotune_round_16.txt.
  - After Round 16, keep selecting only from eval/config/r8r9_remaining_active_machine_verified.txt.
  - Keep the currently parked tasks in eval/config/cannot_handle_group.txt excluded unless new evidence proves the park decision is
  wrong.
  - Use doc/autotune/meta/loop_state.json as the only control-plane state.
  - Execute exactly one orchestrated /autotune round per Ralph iteration.
  - When preparing or running eval, use gpt-5.4 configs/settings rather than qwen defaults.
  - If existing eval config files are qwen-specific, duplicate or replace them with gpt-5.4 equivalents before running the round.
  - Refresh and rely on doc/autotune/meta/scoreboard.json / scoreboard.md as machine-readable progress tracking.
  - If a task is claimed fixed, require a passing eval result in eval/results/*/per_task.jsonl.
  - If blocked by provider credits, emulator failure, or other infra issues, record that explicitly in loop_state.json and do not
  treat it as a model fix.
  - Stop only when every active task in eval/config/r8r9_remaining_active_machine_verified.txt is either fixed with eval evidence or
  consciously moved to cannot_handle with a concrete reason.
  - When loop_state.json status=complete, output <promise>AUTOTUNE_LOOP_COMPLETE</promise>."

  --max-iterations 10
  --completion-promise "AUTOTUNE_LOOP_COMPLETE"