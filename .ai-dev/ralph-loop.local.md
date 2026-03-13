---
active: true
iteration: 1
session_id: 
max_iterations: 20
completion_promise: "AUTOTUNE_LOOP_COMPLETE"
started_at: "2026-03-13T16:29:26Z"
---

Run /autotune-loop --remote --parallel 2. One round per iteration. Model: gpt-5.4.
Must read:
- /autotune skill steps to follow exactly, e.g., do not skip Step 4.
- MUST read tuning_principles.md before every change.
- Per-task files in doc/autotune/meta/per_task/*.md for diagnosis context.
Task list: eval/config/autotune_round_44.txt (20 tasks).
Goal: all 20 tasks pass. Your goal is to pass all 20, sometimes later round may pick a smaller subset, do not only pick failed tasks from there, and call it done. You will decide your group from the original 20 tasks, and their follow-up rounds successes (check scoreboard.json). Do NOT stop until at least 18 out of 20 passes. Each of this task has past before based on scoreboard. Adimittly a couple of them were borderline pass or based on overfitted app skill prompt, but majority should be passable with prompts following the tuning principles. 

Process: diagnose via /cog-tune, apply targeted fixes, eval, analyze. Revert what doesn't help. 


Track token counts each round. Keep prompts generalizable.
<promise>AUTOTUNE_LOOP_COMPLETE</promise>.
