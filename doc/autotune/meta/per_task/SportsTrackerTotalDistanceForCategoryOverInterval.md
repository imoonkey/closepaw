# SportsTrackerTotalDistanceForCategoryOverInterval

**Status: PARKED** — 3 consecutive rounds of scroll instruction escalation (R41-R43) without compliance + a11y gap on Edit form.

## R43 (20260312_194856) — gpt-5.4 — FAIL — SCROLL PROTOCOL IGNORED
- Score: 0.0 | Turns: 35 | GoalAchieved (false completion) | Duration: ~300s
- 4-step scroll-first protocol injected. Agent followed steps 2-4 (checklist, Edit, mark checked) but skipped step 1 (scroll). Checked all 5 visible tracks, found no kayaking, answered 0. Kayaking tracks below fold.
- **Parking**: 3 rounds of escalating guidance without scroll compliance + a11y gap on Edit screens.

## R42 (20260312_185423) — gpt-5.4 — FAIL — IMPROVED BEHAVIOR
- Score: 0.0 | Turns: 37 | GoalAchieved (false completion) | Duration: 313s
- R42 skill change (Smart Filtering → exhaustive-check) succeeded: agent checked all 5 visible tracks in Oct 9-10 range (R41 checked 2/5). But never scrolled down to discover additional tracks below the fold. Correct answer is 6502m (kayaking tracks exist further down).
- **A11y gap**: OpenTracks Edit form has complete a11y tree failure — zero form fields exposed. Agent hallucinated all 5 activity types from track names (all happened to be correct, but fragile). screenshot_input was disabled.
- **Next fix**: Strengthen scroll-first requirement + checklist protocol. If scroll-first doesn't help in R43, park due to a11y gap.

## R41 (20260312_173354) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 16 | Failed — GoalAchieved. Answer: 0 (wrong)
- SmartFilteringOverreach + NoScroll. Agent checked only 2/5 visible tracks in date range (Road Riding, Sailing Excursion). Skipped City Stroll, Morning Walk, Brisk Walk because names "clearly indicate an unrelated activity." Never scrolled. App skill's Smart Filtering contradicts system prompt's "scroll the full list" instruction. Info-extraction verification fix present in prompt but ineffective against app-skill shortcut.
- Fix needed: Remove/weaken Smart Filtering guidance; for sum/count tasks, mandate checking ALL items in range regardless of name.

## R39 (20260312_094821) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 14 | Failed — GoalAchieved. Answer: 2832 (regressed from 1.0)

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
