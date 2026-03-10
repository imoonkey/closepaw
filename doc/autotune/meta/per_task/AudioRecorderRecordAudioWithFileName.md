# AudioRecorderRecordAudioWithFileName

**Status: FIXED R31** — Removed "strip .m4a" from skill. Agent types filename verbatim, evaluator gets expected double extension.

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
