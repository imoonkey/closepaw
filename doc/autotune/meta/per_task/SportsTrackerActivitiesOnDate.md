## R57 (20260317_194418/20260317_205840) — gpt-5.4 — PASS
- Score: 1.0 | Context: app skill rewrite validation sweep (-65.6% skill tokens)
- Clean pass, rewritten skill injected correctly.

# SportsTrackerActivitiesOnDate

## R41 (20260312_173354) — gpt-5.4 — PASS
- Score: 1.0 | Turns: 17 | GoalAchieved. Answer: "snow boarding, cycling"
- Clean pass matching R39 pattern: 17 turns, both Oct 1 tracks verified via Edit dialog. Scratchpad used at turns 6/10/16 to persist track names and confirmed types.
- Confirms R40 failure was sampling variance (9-turn shallow path), not a prompt regression. No prompt change between R40 and R41.

## R40 (20260312_122824) — gpt-5.4 — FAIL — FALSECOMPLETION
- Score: 0.0 | Turns: 9 | GoalAchieved. Answer: "snow boarding" (regressed from R39 1.0)
- Agent answered after only 9 turns (vs 17 in R39 pass). Stopped at first matching track on Oct 1 without verifying it was the correct one or checking for additional activities. Answered wrong activity type.
- Non-deterministic sampling produced a shallower search path. Not caused by R40 tool description cuts — R39 passed with the same system prompt.

## R39 (20260312_094821) — gpt-5.4 — PASS
- Score: 1.0 | Turns: 17 | Clean pass in 17 turns. (unchanged)

## AT-R2 (20260304_033425) — PASS
- Score: 1.0
- Turns: 16/30
- P5 OpenTracks tip highly effective — agent used Edit dialog to find activity types ("snow boarding", "cycling") instead of reporting track names. P2 completion verification also worked — checked for additional Oct 1 tracks before completing.

## AT-R1 (20260303_232753) — FAIL
- Score: 0.0
- Turns: 15/30
- FalseCompletion: Agent reported track names ("Active Rest Day", "Mindful Movement") instead of activity types (e.g., "running", "biking"). Activity type only shown as icon in a11y tree. Agent never explored Edit form where type field is labeled.
- See `round_1/20260303_232753/per_task/SportsTrackerActivitiesOnDate_claude.md` for details.

## R3 (20260301_012237) — FAIL
- Score: 0.0
- Root cause: Activity type extraction
- R58 (20260323): score=1.0, turns=18, reason=GoalAchieved
