# MarkorAddNoteHeader

## AT-R1 (20260303_232753) — FAIL
- Score: 0.0
- Turns: 30/30
- MaxTurnsReached: Agent typed header but cursor was at wrong position. Click element_index goes to center (cursor at end), not beginning. Spent 14 turns in select-replace loop. Turn 6: clicked toolbar ViewGroup (not navigate up button). Turn 7: BACK only dismissed keyboard (invisible in sanitized tree). Turn 26: tap (10,285) successfully placed cursor at beginning. Turn 27-28: discovered Special Keys → Document Start (most reliable method). Ran out of turns. Did NOT use shell for writes (P3-R3 fix working).
- Qi discussion: (1) keyboard_visible flag needed in perception (2) generic cursor positioning via ACTION_SET_SELECTION for non-Markor editors (3) Markor tip remains useful as supplement
- See `round_1/20260303_232753/per_task/MarkorAddNoteHeader_claude.md` for details.

## R3 (20260301_012237) — FAIL
- Score: 0.0
- Root cause: Newline normalization + rename UI
