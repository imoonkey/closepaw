## R57 (20260317_194418/20260317_205840) — gpt-5.4 — FAIL
- Score: 0.0 | Context: app skill rewrite validation sweep (-65.6% skill tokens)
- .md extension not cleared — known nondeterminism, agent kept default .md extension.

# MarkorMergeNotes

## R43 (20260312_194856) — gpt-5.4 — PASS — STABLE
- Score: 1.0 | Turns: 26 | GoalAchieved | Duration: ~150s
- Stable across R42-R43 (2 consecutive passes after R41 failure).

## R42 (20260312_185423) — gpt-5.4 — PASS — RECOVERED
- Score: 1.0 | Turns: 19 | GoalAchieved | Duration: 148s
- **RECOVERED from R41 (was 0.0).** R41 failure was caused by poisoned recalled memory overriding skill guidance. R42 fix: cleared all agent memory from emulator before eval. Without poisoned memory, agent correctly created extensionless file `mIObBbo4`, following Markor skill guidance.

## R41 (20260312_173354) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 21 | Failed — GoalAchieved (false completion)
- Same root cause as R39/R20/R5/R1: agent kept `.md` extension, creating `mIObBbo4.md` instead of `mIObBbo4`
- Skill guidance was correctly injected but overridden by **poisoned recalled memory** that explicitly mentions `mIObBbo4.md` as a success pattern (saved by a prior failed run)
- Agent thought: "Keep the default markdown extension for the new note" — never analyzed whether goal filename has an extension
- Fix: delete poisoned memory entry + strengthen skill with explicit decision rule based on goal filename
- See `round_41/20260312_173354/per_task/MarkorMergeNotes_claude.md` for full trace analysis

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
- R58 (20260323): score=0.0, turns=21, reason=GoalAchieved, ans="Done — I created the Markor note `mIObBbo4` with the merged contents in the requ"
