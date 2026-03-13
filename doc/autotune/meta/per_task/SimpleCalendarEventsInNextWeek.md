# SimpleCalendarEventsInNextWeek

## R41 (20260312_173354) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 5 | FalseCompletion — identical to R39. Agent listed 18 events from Oct 23-27 instead of 2 events from Oct 16-22. Same off-by-one-week boundary error on Sunday. New info-extraction prompt line did NOT help — wrong failure mode targeted. Correct data (Product demo, Movie night) was visible on screen but skipped. No scratchpad used, no date boundary verification. Fix needed: explicit relative-week date calculation rule in app skill or system prompt.

## R39 (20260312_094821) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 5 | Failed — GoalAchieved. Answer: Meeting with HR, Review session for Campaign, Catch up on... (regressed from 1.0)

## R16 (gpt-5.4, 20260309_125810)
- Score: 1.0 | Turns: 5 | GoalAchieved — Clean pass. Correctly identified "Product demo, Movie night" in 5 turns.

## R11 (qwen3.5, AT-R11)
- Score: 1.0 | First pass with qwen3.5 after calendar skill addition.
