## R57 (20260317_194418/20260317_205840) — gpt-5.4 — FAIL
- Score: 0.0 | Context: app skill rewrite validation sweep (-65.6% skill tokens)
- Persistent failure — over-deletion at scale in 30-item noise pool.

# RecipeDeleteDuplicateRecipes3

**Status: FIXED R32** — Scratchpad tracking + 80 turn budget. Agent checked all 7 fields.

### R44 (20260313_095409)
- Score: 0.0 | Turns: 61 | Full-field comparison followed but ran out of turns (61/80)

## R41 (20260312_173354) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 59/80 | GoalAchieved | Over-deleted BSS noise pool (4 of 5 removed, some unique). Correctly handled Avocado Toast (1 dup deleted) and Pan-Seared Salmon (1 dup deleted). Ghost-delete issue: ~10 BSS deletion attempts in T5-24 only registered 2. Without field-comparison algorithm, agent defaults to title+desc matching and cannot distinguish noise rows. Known issue confirmed.

## R39 (20260312_094821) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 50 | Failed — GoalAchieved. Answer: Done — I deleted the extra exact duplicates and verified ... (unchanged)

## R38 (20260312_000547) — gpt-5.4 — FAIL → ACCEPTED REGRESSION
- Score: 0.0 | Over-deletion at scale. 3rd consecutive failure. Accepted regression.

## R37 (20260311_232252) — gpt-5.4 — FAIL
- Score: 0.0 | GoalAchieved but scorer 0.0. Same over-deletion pattern — 30-item noise pool with same-title-same-desc entries.

## R36 (20260311_215019) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 60 | Over-deleted: 11 deletions (9 Butternut noise + 1 Avocado Toast + 1 target) when 1 needed. 30-item noise pool.

## R32 (20260310_001826) — gpt-5.4 — FIXED
- Score: 1.0 | Turns: 45 | Fix: scratchpad tracking emphasis, compare all 7 fields, max_turns 80.

## R27 (20260309_204943) — gpt-5.4 — RE-PARKED
- Score: 0.0 | Turns: 49 | FalseCompletion — claimed no duplicates found. 3rd consecutive failure.

## R26 (20260309_203139) — gpt-5.4
- Score: 0.0 | Turns: 59 | TurnExhaustion — used 59/60 turns. Claimed success, evaluator disagrees. Near-miss on budget.

## R23 (20260309_192810) — gpt-5.4
- Score: 0.0 | Turns: 53 | FalseCompletion — used 53/60 turns. Navigated 30+ recipes, either deleted wrong one or missed true duplicate.

## R4 (AT-R4, run 20260305_151648)
- **Score**: 0.0 | **Turns**: 50/50 | **Agent**: qwen3.5
- **Behavior**: TurnExhaustion. write_todos over-use (9 calls = 18% budget waste). 8 deletions vs ~4 in R3, still insufficient for 10+ duplicates.
- **R4 fix result**: max_turns=50 + repetitive-task guidance applied. Marginal improvement only; recommend park.

## AT-R3 (20260304_152742) - FAIL
- Score: 0.0
- Turns: 30/30
- Root cause: TurnExhaustion. Agent correctly identified duplicates but ran out of turns mid-deletion. Many duplicates to process.
- R4 fix: Set max_turns=50. Park if still failing.
- R58 (20260323): score=0.0, turns=77, reason=GoalAchieved, ans="Deleted the confirmed exact duplicate recipes while leaving one instance of each"
