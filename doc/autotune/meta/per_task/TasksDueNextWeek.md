# TasksDueNextWeek

### R44 (20260313_095409)
- Score: 0.0 | Turns: 7 | Premature answer in 7 turns, no detail-view verification

## R43 (20260312_194856) — gpt-5.4 — FAIL — SCRATCHPAD TRUNCATION
- Score: 0.0 | Turns: 7 | GoalAchieved (false completion) | Duration: ~80s
- Agent answer: 13 | Correct: 6. Dedup guidance followed initially (8 unique in scratchpad), but scratchpad truncated to ~200 chars at answer time. Agent fell back to counting raw visible rows. Even 8 is wrong (correct 6) — date resolution across recurring instances still broken.

## R42 (20260312_185423) — gpt-5.4 — FAIL — DUPLICATE COUNTING
- Score: 0.0 | Turns: 8 | GoalAchieved (false completion) | Duration: 82s
- Agent answer: 14 | Correct: 6. R42 date computation rule worked (computed Oct 16-22 in Turn 1, scratchpad used). Full list scrolled. But counted every visible row with a next-week date label, including recurring/duplicate task instances. Same failure as R41 (answer=13), slightly worse.
- R42 prompt change ("compute date range first") had zero effect — agent already computed dates correctly in R41. Root cause is deduplication, not date computation.
- **Next fix**: Add Tasks.org deduplication guidance: "deduplicate by task name, count each unique name only once."

## R41 (20260312_173354) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 6 | Failed — GoalAchieved. Answer: 13 (correct: 6)
- Targeted by R41 info-extraction verification fix. Agent now scrolls full list (3 scrolls, end confirmed), uses scratchpad (2 writes), and computes correct date range (Oct 16-22). Major behavioral improvement over R39.
- Failure mode shifted from "premature answer without scrolling" (R39) to "overcounting due to duplicate task entries." Tasks.org shows recurring/duplicate tasks with same name but different date labels. Agent counted 13 visible rows instead of 6 unique tasks.
- App skill guidance "check section headers to disambiguate" was not followed. Agent did not open detail views or use list sort position relative to Today/Tmrw markers to verify dates.
- Pattern: same overcounting bug as R7 (answered 20) and R8 (answered 12). The deduplication gap identified in R7 remains unresolved.

## R39 (20260312_094821) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 5 | Failed — GoalAchieved. Answer: 9 (regressed from 1.0)

## R16 (gpt-5.4, 20260309_125810)
- Score: 1.0 | Turns: 9 | GoalAchieved — NEW PASS. gpt-5.4 counted correctly (6). Fixed R13's off-by-1 and R8's over-counting.

## R8 (GPT-5.4, 20260307)
Score: 0.0 | Turns: 7 | App skill NOT injected. Agent over-counted (12 vs correct 6) due to viewport overlap + pre-existing tasks.

## R7 (run 20260307_025501, qwen3.5)
- **Score**: 0.0 | **Turns**: 7/30
- **Behavior**: FalseCompletion. Answered "20" after full scroll-through of task list. Significant behavioral improvement: used scratchpad, scrolled 4x (confirmed end-of-list), correctly resolved relative date labels to Oct 16-22 range. Failure likely due to overcounting across overlapping scroll boundaries — no dedup strategy for items visible in consecutive viewports.
- **Delta vs R6**: Major improvement in methodology — 7 turns (vs 2), full scrolling (vs none), scratchpad with structured date reasoning (vs none). Still wrong answer, but failure mode shifted from "didn't try" to "counted incorrectly."
- **Notable**: No org.tasks app skill was injected despite being mentioned as new in R7. Agent operated without app-specific guidance.
- **Key gap**: Needs scroll-boundary deduplication protocol for count tasks.

## R6 (run 20260307_003831, gpt-5.4)
- **Score**: 0.0 | **Turns**: 2/30
- **Behavior**: ReasoningError. Answered "4" from single viewport without scrolling or date reasoning.
- **Delta vs R4**: Regression — fewer turns (2 vs 6), no scratchpad usage, no date-label mapping attempted.

## R4 (AT-R4, run 20260305_151648)
- **Score**: 0.0 | **Turns**: 6/30 | **Agent**: qwen3.5
- **Behavior**: FalseCompletion. Used scratchpad (R4 guardrail) but contradicted own count (13 in scratchpad, submitted 14). Date-label tip helped (correctly mapped "next week" to Oct 16-22). Still shallow scan without full scroll.
- **R4 fix result**: Scratchpad guardrail partially effective; date-label tip worked; counting accuracy still broken.

## AT-R3 (20260304_152742) - FAIL
- Score: 0.0
- Turns: 6/30
- Root cause: FalseCompletion. Returned count after shallow scan without date-window verification. Tasks app relative date labels prevent reliable date mapping.
- R4 fix: Generic date-label tip + QA scratchpad guardrail.
