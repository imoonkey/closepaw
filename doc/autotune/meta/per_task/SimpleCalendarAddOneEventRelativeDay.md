# SimpleCalendarAddOneEventRelativeDay

## R43 (20260312_194856) — gpt-5.4 — PASS — STABLE
- Score: 1.0 | Turns: 29 | GoalAchieved | Duration: ~150s
- MUST/ALWAYS language continues to work. Near budget limit (29/30 turns) but succeeded.

## R42 (20260312_185423) — gpt-5.4 — PASS — RECOVERED
- Score: 1.0 | Turns: 17 | GoalAchieved | Duration: 150s
- **RECOVERED from R41 (was 0.0).** R41/R39/R9 failure: agent interpreted "5h" as 17:00. R42 fix: strengthened SimpleCalendar skill from informational to imperative ("ALWAYS means 24-hour format: '5h' MUST be 05:00, NOT 17:00"). Agent correctly created event at 05:00-06:00 on Oct 19.

## R41 (20260312_173354) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 21 | InstructionIgnored — interpreted "5h" as 17:00 instead of 05:00 despite app skill explicitly stating "5h" = 05:00. Date correct (Oct 19 Thu). Title, description, duration all correct. Same bug as R9 and R39. App skill was injected from turn 3 onward but gpt-5.4 ignored it. Verification showed 17:00-18:00 but agent did not cross-check against "5h" notation rule.

## R39 (20260312_094821) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 21 | Failed — GoalAchieved. Answer: The event was created and verified in Simple Calendar Pro... (regressed from 1.0)

## R9 (GPT-5.4, 20260307, full baseline) — FAIL
- Score: 0.0 | Turns: 24 | ReasoningError — interpreted "5h" as 17:00 instead of 05:00. Everything else correct.
