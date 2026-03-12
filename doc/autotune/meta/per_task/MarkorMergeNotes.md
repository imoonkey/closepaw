

## R39 (20260312_094821) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 19 | Failed — GoalAchieved. Answer: Done — I created and saved the merged Markor note as mIOb... (regressed from 1.0)

## R21 (20260309_153628) — gpt-5.4 — PASS
- Score: 1.0, Turns: 20
- Extension CRITICAL section worked. Agent correctly cleared the .md extension field when creating the extensionless target file.

## R20 (20260309_150119) — gpt-5.4
- Score: 0.0, Turns: 23
- UI path worked, content correct\! But .md extension not cleared. Solvable.

## R19 (20260309_143418) — gpt-5.4
- Score: 0.0, Turns: 17
- Skill regression: shell-write guidance caused UI dialog abandonment, shell lacks permissions

# MarkorMergeNotes

## AT-R5-GPT54 (20260306_003753) — FAIL
- Score: 0.0
- Turns: 20/30
- Same false-completion bug as AT-R1. The agent recovered the source note contents correctly, but when creating the destination note it filled the base name `mIObBbo4` and left Markor's separate extension field at the default `.md`, producing `mIObBbo4.md`.
- See `round_5/20260306_003753/per_task/MarkorMergeNotes_codex.md` for details.

## AT-R1 (20260303_232753) — FAIL
- Score: 0.0
- Turns: 22/30
- FalseCompletion: Agent merged content correctly but created file as `mIObBbo4.md` instead of `mIObBbo4` (no extension). Markor's new file dialog has a separate extension field defaulting to `.md` that agent didn't clear.
- See `round_1/20260303_232753/per_task/MarkorMergeNotes_claude.md` for details.

## R3 (20260301_012237) — FAIL
- Score: 0.0
- Root cause: Content separator / file extension
