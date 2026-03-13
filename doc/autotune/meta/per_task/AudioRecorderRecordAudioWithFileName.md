# AudioRecorderRecordAudioWithFileName

**Status: FIXED R31** — Removed "strip .m4a" from skill. Agent types filename verbatim, evaluator gets expected double extension.

## R41 (20260312_173354) — gpt-5.4
**Score**: 1.0 | **Turns**: 9 | **Behavior**: App presented "New name" dialog on stop (bypassed rename UI hunt that failed in R39); typed filename verbatim with extension, 72s clean pass

## R39 (20260312_094821) — gpt-5.4 — FAIL — POSSIBLE R39 CUT REGRESSION
- Score: 0.0 | Turns: 29 | Error. Agent said "I recorded a clip but could not complete rename."
- **Trace-verified**: Agent recorded audio successfully but spent 29 turns unable to find the rename UI — tried overflow menus, long-press, toolbar actions, coordinate taps. Never found the rename option.
- **R31 passed** with 8 turns using detailed skill guidance (22 lines). R39 ran with simplified skill (14 lines, -8 lines from R39 cut). The cut removed explicit rename procedure guidance ("Do not strip or add any extension — type it verbatim", recording duration tips, post-rename verification).
- **Failure mode change**: R31 fix was about filename typing. R39 failure is about finding the rename UI itself — a different issue. Possibly the detailed skill had enough context for the agent to navigate to rename, while the condensed skill didn't.
- **Also**: scoped storage limitation (R25/R17) may still apply — even if rename UI is found, it only changes display name, not filesystem. R31 pass may have been lucky (evaluator got expected double extension `presentation_fGwr.m4a.m4a`).

## R31 (20260309_235826) — gpt-5.4 — FIXED
- Score: 1.0 | Turns: 8 | Fix: removed "strip .m4a" from skill. Evaluator expected double extension `.m4a.m4a`. Old skill was wrong about extension handling.

## R25 (20260309_201619) — gpt-5.4 — RE-PARKED
- Score: 0.0 | Turns: 9 | ScopedStorage — recorded, stopped, renamed via UI (9 turns). Evaluator checks filesystem at `/storage/emulated/0/Android/data/`. UI rename only changes display name. No fix without code changes.

## R17 (gpt-5.4, 20260309_132808)
- Score: 0.0 | Turns: 10 | AppBehaviorMismatch — UI rename worked but only changes display name, not filesystem. Evaluator checks filesystem.

## AT-R5-GPT54 (20260306_003753) — FAIL
- Score: 0.0
- Turns: 9/30
- FalseCompletion: Agent recorded and saved a clip, but typed `presentation_fGwr` instead of the literal target `presentation_fGwr.m4a`, then claimed success anyway.
- See `round_5/20260306_003753/per_task/AudioRecorderRecordAudioWithFileName_codex.md` for details.
