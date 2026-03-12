**Status: FIXED R32** — Correct Gallery package skill + filename verification.

## R39 (20260312_094821) — gpt-5.4 — PASS
- Score: 1.0 | Turns: 30 | Clean pass in 30 turns. (unchanged)

## R32 (20260310_001826) — gpt-5.4 — FIXED
- Score: 1.0 | Turns: 27 | Fix: Gallery skill on correct package (com.simplemobiletools.gallery.pro), filename verification in title bar.

## R31 (20260309_235826) — gpt-5.4
- Score: 0.0 | Turns: 13/60 | WrongFile — opened `old_expenses_6.jpg` instead of `expenses.jpg`. Gallery skill was on wrong package (`com.google.android.apps.photos` vs `com.simplemobiletools.gallery.pro`). Agent gave up early citing overlay obstruction.

## R30 (20260309_215257) — gpt-5.4 — RE-PARKED
- Score: 0.0 | Turns: 19 | FieldAlignment + ImagePerception — 3rd failure. Only added 2 expenses (Mortgage, Shoes). Specialty Foods amount unreadable from image. Error at turn 19/60.

## R29 (20260309_213307) — gpt-5.4
- Score: 0.0 | Turns: 36 | FieldAlignment — added expenses but wrong data. Created duplicate Mortgage, tried to fix. Name/note field confusion persists.

## R28 (20260309_210911) — gpt-5.4
- Score: 0.0 | Turns: 30 | TurnExhaustion — 1st unpark attempt. Added 3/6 expenses (Mortgage, Shoes, Furnishing). Error at turn 30.

## R21 (20260309_161737) — gpt-5.4 — PARKED
- Score: 0.0, Turns: 28
- Added 3/6+ expenses but ran out of turns. Image has many expenses; agent needs to open gallery, read image, switch to Pro Expense, enter each expense. 30 turns insufficient. 12 consecutive failures (0/12). Parked to cannot_handle.

## R20 (20260309_150119) — gpt-5.4
- Score: 0.0, Turns: 16
- Still no scroll on category RecyclerView. Need Pro Expense skill.

## R19 (20260309_143418) — gpt-5.4
- Score: 0.0, Turns: 19
- Reasoning: failed to scroll category RecyclerView, gave up prematurely

# ExpenseAddMultipleFromGallery


## R37 (20260311_232252) — gpt-5.4 — PASS
- Score: 1.0 | Flaky — passed after R36 failure. No skill changes between rounds.

## R36 (20260311_215019) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 27 | GoalAchieved but scorer 0.0. Pre-existing tool/perception issue

## AT-R5-GPT54 (20260306_003753) — FAIL
- Score: 0.0
- Turns: 17/30
- FalseCompletion: Agent never opened `expenses.jpg`; it extracted rows from `old_expenses_6/7/8.jpg`, then claimed success because similar expenses were already present in Pro Expense.
- See `round_5/20260306_003753/per_task/ExpenseAddMultipleFromGallery_codex.md` for details.
