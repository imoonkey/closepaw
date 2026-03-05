# SportsTrackerActivitiesCountForWeek

## AT-R3 (20260304_152742) - FAIL
- Score: 0.0 (timeout; trace shows ~25 turns)
- Turns: 0/30 (bridge timeout — no session_stopped event)
- Root cause: InfraError + TurnExhaustion. Agent ran many actions checking each track's type but session didn't terminate cleanly.
- R4 fix: Timeout finalization observability (session_stopped instrumentation + run summary flush).
