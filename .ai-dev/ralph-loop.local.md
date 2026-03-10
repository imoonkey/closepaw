---
active: true
iteration: 5
session_id: 
max_iterations: 0
completion_promise: null
started_at: "2026-03-09T22:50:30Z"
---

Run /autotune-loop for this repo.

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
  - When loop_state.json status=complete, output <promise>AUTOTUNE_LOOP_COMPLETE</promise>.
