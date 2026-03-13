
### R44 (20260313_095409)
- Score: 0.0 | Turns: 8 | Agent correctly deleted but evaluator scored 0 (atime bug)

## R41 (20260312_173354) — gpt-5.4 — FAIL (evaluator false negative, 3rd consecutive)
- Score: 0.0 | Turns: 8 | EvalBug — Identical to R33/R39. Agent sorted by Date, read timestamps (fine_jelly_final.md=15:34, final_friendly_king.md=15:17), correctly deleted the one with the later mtime. Evaluator scored 0.0. Post-deletion: surviving file's timestamp mutated from 15:17 to 15:34 (filesystem side-effect). 0/8 lifetime. Should remain in cannot_handle_group.txt — no prompt/skill fix possible for evaluator mismatch.

## R39 (20260312_094821) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 7 | Failed — GoalAchieved. Answer: Deleted the newest note in Markor. (unchanged)

## R33 (20260310_004519) — gpt-5.4 — RE-PARKED (evaluator false negative)
- Score: 0.0 | Turns: 7 | EvalBug — Agent correctly identified and deleted newest note (fine_jelly_final.md at 15:34). Evaluator uses `ls -au` (atime) to compare pre/post file lists. Markor's delete mutates surviving files' atimes, causing evaluator comparison to fail. This is an evaluator bug, not an agent issue. Cannot fix.

## R24 (20260309_194958) — gpt-5.4 — RE-PARKED
- Score: 0.0 | Turns: 7 | PerceptionGap — 3rd consecutive failure. Timestamps not visible for one note, still picked wrong one. RE-PARKED: modification time vs access time mismatch.

## R23 (20260309_192810) — gpt-5.4
- Score: 0.0 | Turns: 7 | SortOrderConfusion — Markor Date sort is ASCENDING. Deleted first item (oldest) thinking it was newest. Fix: enable Reverse Order.

## R22 (20260309_190216) — gpt-5.4
- Score: 0.0 | Turns: 7 | PerceptionGap — sorted by Date, deleted wrong note. Modification time ≠ creation time for pre-existing notes.

## R20 (20260309_150119) — gpt-5.4
- Score: 0.0, Turns: 10
- stat failed (shell can't access files). Agent guessed by list position. PARKING.

## R19 (20260309_143418) — gpt-5.4
- Score: 0.0, Turns: 10
- Perception/Skill: used modification-time sort, picked wrong note (creation-time differs)

# MarkorDeleteNewestNote

## R9 (GPT-5.4, 20260307, full baseline) — FAIL
- Score: 0.0 | Turns: 7 | PerceptionGap — a11y tree reported swapped timestamps vs screenshot. Deleted wrong note.
