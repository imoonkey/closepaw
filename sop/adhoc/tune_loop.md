
# R36+: App Skill Generalization

## Goal

Remove task-specific / overfitted language from app skills. Retain only general knowledge a real user would benefit from. Retest impacted tasks to maintain success rate.

## Overfitted skills (4 files)

| Skill | Overfitted Sections | Impacted Tasks |
|-------|-------------------|----------------|
| `com.android.chrome` | "Drawing Tasks (Canvas Pages)" → BrowserDraw, "Maze Tasks" → BrowserMaze | BrowserMaze, BrowserMultiply |
| `code.name.monkey.retromusic` | "Duration-Constrained Playlists" → RetroPlaylistDuration | RetroCreatePlaylist, RetroPlayingQueue, RetroPlaylistDuration, RetroSavePlaylist |
| `com.arduia.expense` | "Finding and Deleting Duplicate Expenses" → ExpenseDeleteDuplicates | ExpenseAdd*, ExpenseDelete* (9 tasks) |
| `com.flauschcode.broccoli` | "Deleting Duplicate Recipes" → RecipeDeleteDuplicateRecipes | RecipeAdd*, RecipeDelete*, NotesRecipeIngredientCount (14 tasks) |

Total impacted: 29 tasks (minus cannot_handle). Run all 29.

## Step 1: One-time setup

Before running the ralph-loop, do this manually:

1. **Create `doc/autotune/round_36/generalization_plan.md`**: document which sections in each skill are overfitted and what to remove/rewrite. This is the input for R36's fix step.
2. **Create `eval/config/generalize_active.txt`**: all 29 impacted tasks.
3. **Update `doc/autotune/meta/loop_state.json`**: new loop `skill_generalization`, R36+, status active.
4. **Push to remote**: `git push`, then on qiguo-ld1: `git pull && ./gradlew assembleDebug`.

### Task list (29 tasks)

```
BrowserMaze
BrowserMultiply
ExpenseAddMultiple
ExpenseAddMultipleFromGallery
ExpenseAddMultipleFromMarkor
ExpenseAddSingle
ExpenseDeleteDuplicates
ExpenseDeleteDuplicates2
ExpenseDeleteMultiple
ExpenseDeleteMultiple2
ExpenseDeleteSingle
NotesRecipeIngredientCount
RecipeAddMultipleRecipes
RecipeAddMultipleRecipesFromImage
RecipeAddMultipleRecipesFromMarkor
RecipeAddMultipleRecipesFromMarkor2
RecipeAddSingleRecipe
RecipeDeleteDuplicateRecipes
RecipeDeleteDuplicateRecipes2
RecipeDeleteDuplicateRecipes3
RecipeDeleteMultipleRecipes
RecipeDeleteMultipleRecipesWithConstraint
RecipeDeleteMultipleRecipesWithNoise
RecipeDeleteSingleRecipe
RecipeDeleteSingleWithRecipeWithNoise
RetroCreatePlaylist
RetroPlayingQueue
RetroPlaylistDuration
RetroSavePlaylist
```

## Step 2: Ralph loop (copy-paste this)

/ralph-loop:ralph-loop "Run /autotune-loop. Follow /autotune-loop and /autotune steps exactly — do NOT skip steps (especially /autotune step 4 analysis).

Context:
- Goal: generalize overfitted app skills. R36 fix step will edit 4 skills to remove task-specific language, keeping only general guidance.
- 29 tasks in eval/config/generalize_active.txt. These are impacted by the skill edits.
- doc/autotune/meta/loop_state.json initialized (R36+, skill_generalization).
- Prior rounds R10-R35 tuned these skills with task-specific language; this loop intentionally removes that.
- The skills were overfitted: Chrome had Drawing Tasks / Maze Tasks sections, Retro Music had Duration-Constrained Playlists, Expense had duplicate-finding jargon, Broccoli had duplicate-detection eval patterns.

Rules:
- Execute exactly one /autotune round per Ralph iteration. Follow every /autotune step in order.
- R36 fix step: read doc/autotune/round_36/generalization_plan.md. Edit the 4 overfitted skill files — remove task-specific sections, keep/reorganize into general guidance. Commit, push to remote, sync.
- R36 eval: run all 29 tasks as baseline — measure how many pass with generalized skills.
- R37+: for failures, analyze traces and add GENERAL (not task-specific) guidance to skills. No eval-task jargon. Think: what would help a real user doing something similar?
- Model: gpt-5.4.
- Run eval on remote: `eval/.venv/bin/python eval/aw_bridge/runner.py --config eval/config/remote.yaml --tasks-file eval/config/generalize_active.txt`
- After eval, sync results to laptop: `rsync -avz qiguo@qiguo-ld1:~/androidagent/eval/results/<run_id>/ eval/results/<run_id>/`
- After eval, do /autotune step 4 analysis — read traces, compare scripted vs claimed.
- If a task passes (scripted_score=1.0), remove from generalize_active.txt.
- If a task fails 3 rounds with no progress and only task-specific fixes remain, accept the regression and document in issues.md.
- Stop when generalize_active.txt is empty OR no further general improvements are possible. Output <promise>AUTOTUNE_LOOP_COMPLETE</promise>." --max-iterations 15 --completion-promise "AUTOTUNE_LOOP_COMPLETE"

# (Done) R31+: Unpark Round 2 (corrected root causes)

## (Done) Step 1: One-time setup

Already done in this session:
- `eval/config/unpark_active.txt` — 8 tasks (Tier 1-3 from post-R30 deep dive)
- `eval/config/cannot_handle_group.txt` — MarkorTranscribeVideo + OsmAndTrack only
- `doc/autotune/meta/loop_state.json` — status: active, next_round: 31, loop_name: unpark_cannot_handle_r2
- `doc/autotune/meta/unpark_triage.md` — corrected root causes with specific fixes per task

## Step 2: Ralph loop (copy-paste this)

/ralph-loop:ralph-loop "Run /autotune-loop. Follow /autotune-loop and /autotune steps exactly — do NOT skip steps (especially /autotune step 4 analysis).

Context:
- 9 tasks in eval/config/unpark_active.txt. Corrected root causes in doc/autotune/meta/unpark_triage.md.
- doc/autotune/meta/loop_state.json initialized (R31+, unpark_cannot_handle_r2).
- Prior loop R22-R30 misdiagnosed 3+ tasks. This round has specific, verified fixes per task.
- OsmAnd map data (Liechtenstein_europe.obf) has been fixed via prepare_baseline re-run. Snapshot now includes it.

Key fixes to apply (from deep dive of scripted is_successful code):
- AudioRecorder: remove 'strip .m4a' from skill (evaluator expects double extension)
- ExpenseDelete2: add 'open detail view, compare ALL fields' to skill + max_turns 60
- ExpenseAddGallery: add 'open image full-screen' to Gallery skill + 'fill all 4 fields' to Pro Expense skill
- OsmAndMarker: map file pushed by prepare_baseline above + coord fallback in skill
- OsmAndTrack: same map fix + routing skill + max_turns 80
- RetroPlaylistDuration: add 'add all songs, check total duration on playlist page, prune if over' to skill + max_turns 60
- RecipeDeleteRecipes3: scratchpad tracking + max_turns 80

Rules:
- Execute exactly one /autotune round per Ralph iteration. Follow every /autotune step in order.
- R31: Tier 1 tasks (AudioRecorder, ExpenseDelete2, ExpenseAddGallery, OsmAndMarker). R32: Tier 2 (RetroPlaylist, RecipeDelete3, OsmAndTrack). R33+: Tier 3 (Markor, BrowserDraw).
- Model: gpt-5.4.
- After eval, do /autotune step 4 analysis — read traces, compare scripted vs claimed, update triage.
- If task passes (scripted_score=1.0), remove from unpark_active.txt.
- If task fails 3 rounds with no progress and fixes exhausted, re-park to cannot_handle_group.txt.
- Stop when unpark_active.txt is empty. Output <promise>AUTOTUNE_LOOP_COMPLETE</promise>." --max-iterations 10 --completion-promise "AUTOTUNE_LOOP_COMPLETE"

# (Done) R22-30: Unpark Round 1
# (Done) R16-21: gpt-5.4 loop
