
# R31+: Unpark Round 2 (corrected root causes)

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
