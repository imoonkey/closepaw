# SportsTrackerTotalDistanceForCategoryOverInterval

**Status: FIXED R25** — Downward-only scroll guidance (5-6 scrolls) resolved the scroll coverage issue.

## R25 (20260309_201619) — gpt-5.4 — FIXED
- Score: 1.0 | Turns: 45 | Success — downward-only scroll guidance worked. Found all kayaking tracks, computed 6502m correctly.

## R23 (20260309_192810) — gpt-5.4
- Score: 0.0 | Turns: 46 | ScrollFailure — scroll-up attempt "no observable effect." Scrolled down, found 0 kayaking tracks. Answered "0."

## R22 (20260309_190216) — gpt-5.4
- Score: 0.0 | Turns: 48 | ScrollCoverage — scrolled DOWN but never UP. Found 2 kayaking tracks (6518m), missed others above fold.

## R18 (gpt-5.4, 20260309_135649)
- Score: 0.0 | Turns: 35/50 | NavigationError — 3rd consecutive failure. Dates correct, activity type checks correct, but ZERO scrolls. Missed kayaking tracks below fold. Candidate for cannot_handle.

## R17 (gpt-5.4, 20260309_132808)
- Score: 0.0 | Turns: 36/50 | NavigationError — date mapping now correct (Mon=Oct9, Tue=Oct10) but didn't scroll. Missed kayaking tracks below fold. Answer: 0 (wrong).

## R16 (gpt-5.4, 20260309_125810)
- Score: 0.0 | Turns: 24/50 | ReasoningError — date mapping. Confused "Yesterday" (Oct 14) with target range (Oct 9-10). Never checked Tuesday tracks. Answer: 4828 (wrong).

## R4 (AT-R4, run 20260305_151648)
- **Score**: 0.0 | **Turns**: 30/30 | **Agent**: qwen3.5
- **Behavior**: ReasoningError. REGRESSED from 1.0 in R3. Agent checked only 2/5 tracks then pivoted to shell (5 failed find commands). Natural variance + premature shell pivot.
- **R4 fix result**: Shell restriction not the cause (commands were file ops). Regression due to nondeterminism.

## AT-R3 (20260304_152742) - PASS (fragile)
- Score: 1.0
- Turns: 28/30 (near turn limit — fragile pass)
- Root cause: Success but turn-inefficient. Repeated recounting and exploratory loops.
- R4 fix: Shell restriction + repetitive-task guidance should reduce turn waste.
