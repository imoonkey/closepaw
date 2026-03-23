## R57 (20260317_194418/20260317_205840) — gpt-5.4 — FAIL
- Score: 0.0 | Context: app skill rewrite validation sweep (-65.6% skill tokens)
- Action layer newline collapse nondeterminism — ACTION_SET_TEXT collapses \n\n to \n.

# MarkorAddNoteHeader

## R40 (20260312_122824) — gpt-5.4 — PASS
- Score: 1.0 | Turns: 28 | Clean pass in 28 turns. (unchanged)

## R39 (20260312_094821) — gpt-5.4 — PASS
- Score: 1.0 | Turns: 21 | Clean pass in 21 turns. (unchanged)

## R16 (gpt-5.4, 20260309_125810) — PASS
- Score: 1.0 | Turns: 13 | GoalAchieved — Clean pass with gpt-5.4 via local proxy. Text insertion and rename both successful.

## R9 (GPT-5.4, 20260307, full baseline) — FAIL
- Score: 0.0 | Turns: 16 | ActionFailure — GPT-5.4 correctly sent \n\n but action layer swallowed double newline. Different from R2/R3 fix.

## AT-R8 (20260307_061407) — FAIL (gpt-5.4)
- Score: 0.0
- Turns: 16/30
- ActionFailure + FalseCompletion: GPT-5.4 correctly sends `\n\n` (unlike R2), but `ACTION_SET_TEXT` collapses `\n\n` to `\n` in Markor's EditText. Agent retried at turn 14 with same result, then declared success. **Different failure mode from R2** — not a reasoning error but an action layer bug. R3 blank-line tip absent from prompt but irrelevant since model already uses `\n\n`.
- Key finding: `NodeActionPerformer.setTextOnNode()` post-action verification uses `contains()` which misses newline normalization. Needs exact-match or newline-aware check.
- See `round_9/20260307_050909/per_task/MarkorAddNoteHeader_claude.md` for details.

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
- R58 (20260323): score=1.0, turns=15, reason=GoalAchieved
