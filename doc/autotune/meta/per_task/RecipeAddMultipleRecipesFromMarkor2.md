
### R44 (20260313_095409)
- Score: 0.0 | Turns: 24 | Silent save failure for Pan-Seared Salmon, agent claimed success

## R41 (20260312_173354) — gpt-5.4 — FAIL — REGRESSION
- Score: 0.0 | Turns: 34/50 | GoalAchieved but scorer 0.0
- Pan-Seared Salmon with Quinoa silently failed to save in Broccoli (twice). SAVE click reported success, detail view displayed correctly, but recipe not persisted to DB. Beef Stir Fry and Raspberry Almond Smoothie saved successfully using identical workflow.
- Agent detected failure on verification search but still declared complete_task(success) — false completion.
- Likely cause: race condition between batched typing (5 fields in one turn) and SAVE click, or emulator flakiness. Not seen in R39/R36.

## R39 (20260312_094821) — gpt-5.4 — PASS
- Score: 1.0 | Turns: 26 | Clean pass in 26 turns. (unchanged)

## R20 (20260309_150119) — gpt-5.4
- Score: 1.0, Turns: 31
- PASS. Broccoli skill + no Source fabrication.

## R19 (20260309_143418) — gpt-5.4
- Score: 0.0, Turns: 24
- Reasoning: fabricated Source field value not in input data

# RecipeAddMultipleRecipesFromMarkor2


## R36 (20260311_215019) — gpt-5.4 — PASS
- Score: 1.0 | Turns: 30 | Success

## AT-R3 (20260304_152742) - PASS (codex)
- Score: 1.0
- Turns: 48/50
- Delta: No prior local eval run for this task; baseline recorded.
