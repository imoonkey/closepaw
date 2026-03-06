# TasksDueNextWeek

## R4 (AT-R4, run 20260305_151648)
- **Score**: 0.0 | **Turns**: 6/30 | **Agent**: qwen3.5
- **Behavior**: FalseCompletion. Used scratchpad (R4 guardrail) but contradicted own count (13 in scratchpad, submitted 14). Date-label tip helped (correctly mapped "next week" to Oct 16-22). Still shallow scan without full scroll.
- **R4 fix result**: Scratchpad guardrail partially effective; date-label tip worked; counting accuracy still broken.

## AT-R3 (20260304_152742) - FAIL
- Score: 0.0
- Turns: 6/30
- Root cause: FalseCompletion. Returned count after shallow scan without date-window verification. Tasks app relative date labels prevent reliable date mapping.
- R4 fix: Generic date-label tip + QA scratchpad guardrail.
