/autotune — Run R57 as a regression sweep for app skill rewrite.

Context:
- R57 is a 51-task sweep to validate the app skill top-down rewrite (366→126 lines, -65.6%).
- All 17 app skills were rewritten to remove solver algorithms, scratchpad format prescriptions, and eval-specific patterns. Only app-local facts retained.
- AppSkillRepository now strips YAML frontmatter before injection.
- 4 tasks unparked from cannot_handle: BrowserDraw, MarkorDeleteNewestNote, OsmAndMarker, OsmAndTrack.
- Task list: eval/config/autotune_round_57.txt (51 tasks).
- Model: gpt-5.4.

This is a Step 1-skip round (fixes already committed). Go directly to Step 2 (verify task list) → Step 3 (sync + rebuild + run --remote --parallel 2) → Step 4 (analyze all 51 tasks).

Key focus for analysis: compare pass rates against R39/R40 baseline (R39: 30/51, R40: 32/51). Flag any regressions caused by the skill rewrite vs nondeterminism. The 4 unparked tasks are expected to mostly fail — track them separately.

Must read:
- /autotune skill steps, especially Step 3 (sync & rebuild, pull results) and Step 4 (per-task analysis + common problems).
- tuning_principles.md before proposing any fixes.
- Per-task files in doc/autotune/meta/per_task/*.md for history.

Update loop_state.json: loop_name "appskill_rewrite_validation", R57, status active.
