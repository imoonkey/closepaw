# MarkorAddNoteHeader

## AT-R3 (20260304_152742) - PASS (codex)
- Score: 1.0
- Turns: 27/30
- Delta: vs 20260304_033425: flipped FAIL->PASS by correctly inserting blank line and finishing rename; still high-turn.

## AT-R3 (20260304_152742) — PASS
- Score: 1.0
- P1 blank line tip (`\n\n`) worked. 2-round stuck bug finally resolved.
- Fix: commit 63e3a0a added Markor tip "When the task says 'blank line' or 'empty line', use `\n\n`"

## AT-R2 (20260304_033425) — FAIL
- Score: 0.0
- Turns: 15/30
- FalseCompletion: P4 cursor positioning tip worked (Special Keys → Document Start), but agent typed `\n` instead of `\n\n` for blank line. Declared success at turn 15 without verifying whitespace. Same `\n` vs `\n\n` bug as R1, now with false completion (worse: R1 at least tried to fix).
- keyboard_visible flag NOT observed in trace — APK rebuild concern persists.
- See `round_2/20260304_033425/per_task/MarkorAddNoteHeader_claude.md` for details.

## AT-R1 (20260303_232753) — FAIL
- Score: 0.0
- Turns: 30/30
- MaxTurnsReached: Agent typed header but cursor was at wrong position. Click element_index goes to center (cursor at end), not beginning. Spent 14 turns in select-replace loop. Turn 6: clicked toolbar ViewGroup (not navigate up button). Turn 7: BACK only dismissed keyboard (invisible in sanitized tree). Turn 26: tap (10,285) successfully placed cursor at beginning. Turn 27-28: discovered Special Keys → Document Start (most reliable method). Ran out of turns. Did NOT use shell for writes (P3-R3 fix working).
- Qi discussion: (1) keyboard_visible flag needed in perception (2) generic cursor positioning via ACTION_SET_SELECTION for non-Markor editors (3) Markor tip remains useful as supplement
- See `round_1/20260303_232753/per_task/MarkorAddNoteHeader_claude.md` for details.

## R3 (20260301_012237) — FAIL
- Score: 0.0
- Root cause: Newline normalization + rename UI
