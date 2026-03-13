
# R41+: Success Rate Recovery (add-back phase)

## Step 1: One-time setup

1. **Read goal + starting point**: `doc/autotune/round_41/prompt_optimization_goal.md` and `doc/autotune/round_41/starting_point.md`.
2. **Read trace-verified analysis**: `doc/autotune/round_39/r39_failure_analysis.md` and Codex review `doc/autotune/round_39/r39_failure_analysis_review_codex.md`.
3. **Read per-task files** for all failing tasks: `doc/autotune/meta/per_task/*.md`.
4. **Update `doc/autotune/meta/loop_state.json`**: new loop `success_rate_recovery`, R41+, status active.
5. **Prepare task list**: `eval/config/aw_fullset.txt` minus `eval/config/cannot_handle_group.txt` (OsmAnd×2 only).
6. **Verify remote parallel env**:
   ```bash
   ssh qiguo@qiguo-ld1 'echo ok'
   git push
   ssh qiguo@qiguo-ld1 'cd ~/androidagent && git pull && ./gradlew assembleDebug'
   ssh qiguo@qiguo-ld1 'source ~/.android-agent-env && emulator -list-avds'
   ssh qiguo@qiguo-ld1 'cd ~/androidagent && ./scripts/remote/proxy_tunnel.sh status'
   python3 scripts/token_counts.py
   ```

## Step 2: Ralph loop (copy-paste this)

/ralph-loop:ralph-loop "Run /autotune-loop --remote --parallel 2.  One round per iteration. Model: gpt-5.4.
Must read:
- /autotune skill steps to follow exactly, e.g., do not skip Step 4.
- Goal: doc/autotune/round_41/prompt_optimization_goal.md.
- Start: doc/autotune/round_41/starting_point.md.
- MUST read tuning_principles.md before every change.
Process: add-back phase. Apply targeted general additions, eval, analyze. Revert what doesn't help.
Track token counts each round.
Stop when all historically-passing tasks pass again (or remaining failures justified as non-generalizable).
<promise>AUTOTUNE_LOOP_COMPLETE</promise>." --max-iterations 20 --completion-promise "AUTOTUNE_LOOP_COMPLETE"

# (Done) R39-40: Prompt Token Optimization (cut phase, -992 tokens, -28.4%)

## (Done) Step 1: One-time setup

1. **Read goal + starting point**: `doc/autotune/round_39/prompt_optimization_goal.md` and `doc/autotune/round_39/starting_point.md`.
2. **Update `doc/autotune/meta/loop_state.json`**: new loop `prompt_optimization`, R39+, status active.
3. **Prepare task list**: `eval/config/aw_fullset.txt` minus `eval/config/cannot_handle_group.txt` → `eval/config/autotune_round_39.txt`.

## (Done) Step 2: Ralph loop (completed R39-R40, stop_success)

# (Done) R36-38: App Skill Generalization

## (Done) Goal

Remove task-specific / overfitted language from app skills. Retain only general knowledge a real user would benefit from. Retest impacted tasks to maintain success rate.

## Overfitted skills (4 files)

| Skill | Overfitted Sections | Impacted Tasks |
|-------|-------------------|----------------|
| `com.android.chrome` | "Drawing Tasks (Canvas Pages)" → BrowserDraw, "Maze Tasks" → BrowserMaze | BrowserMaze, BrowserMultiply |
| `code.name.monkey.retromusic` | "Duration-Constrained Playlists" → RetroPlaylistDuration | RetroCreatePlaylist, RetroPlayingQueue, RetroPlaylistDuration, RetroSavePlaylist |
| `com.arduia.expense` | "Finding and Deleting Duplicate Expenses" → ExpenseDeleteDuplicates | ExpenseAdd*, ExpenseDelete* (9 tasks) |
| `com.flauschcode.broccoli` | "Deleting Duplicate Recipes" → RecipeDeleteDuplicateRecipes | RecipeAdd*, RecipeDelete*, NotesRecipeIngredientCount (14 tasks) |

Total impacted: 29 tasks (minus cannot_handle). Run all 29.

## (Done) Step 1: One-time setup

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

## (Done) Step 2: Ralph loop (completed R36-R38, see doc/autotune/round_38/r36-38_summary.md)

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
