# SportsTrackerActivitiesOnDate

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
