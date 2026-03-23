## R57 (20260317_194418/20260317_205840) — gpt-5.4 — FAIL
- Score: 0.0 | Context: app skill rewrite validation sweep (-65.6% skill tokens)
- Date boundary nondeterminism — "next week" computation varies across runs.

# SimpleCalendarEventsInNextWeek

### R44 (20260313_095409)
- Score: 0.0 | Turns: 13 | Ignored date range scratchpad rule, included events outside window

## R43 (20260312_194856) — gpt-5.4 — FAIL — NONDETERMINISTIC REGRESSION
- Score: 0.0 | Turns: 10 | GoalAchieved (false completion) | Duration: ~90s
- Answer: "Meeting with Bob, Product demo, Movie night" (correct: "Product demo, Movie night"). Agent had correct answer in scratchpad but overrode it after scrolling up and seeing "Meeting with Bob" with off-screen date header. No R43 changes affected this — model variance.

## R42 (20260312_185423) — gpt-5.4 — PASS — RECOVERED
- Score: 1.0 | Turns: 5 | GoalAchieved | Duration: 50s
- **RECOVERED from R41 (was 0.0).** R41 failure: agent computed wrong "next week" boundary on Sunday. R42 fix: added explicit date computation rule to system prompt ("Next week = Monday immediately after today through following Sunday"). Agent correctly identified Mon Oct 16 – Sun Oct 22, found "Product demo, Movie night" in 5 turns.

## R41 (20260312_173354) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 5 | FalseCompletion — identical to R39. Agent listed 18 events from Oct 23-27 instead of 2 events from Oct 16-22. Same off-by-one-week boundary error on Sunday. New info-extraction prompt line did NOT help — wrong failure mode targeted. Correct data (Product demo, Movie night) was visible on screen but skipped. No scratchpad used, no date boundary verification. Fix needed: explicit relative-week date calculation rule in app skill or system prompt.

## R39 (20260312_094821) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 5 | Failed — GoalAchieved. Answer: Meeting with HR, Review session for Campaign, Catch up on... (regressed from 1.0)

## R16 (gpt-5.4, 20260309_125810)
- Score: 1.0 | Turns: 5 | GoalAchieved — Clean pass. Correctly identified "Product demo, Movie night" in 5 turns.

## R11 (qwen3.5, AT-R11)
- Score: 1.0 | First pass with qwen3.5 after calendar skill addition.
