# TasksDueNextWeek

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
