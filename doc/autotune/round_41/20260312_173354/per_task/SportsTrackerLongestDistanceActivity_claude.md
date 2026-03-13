# SportsTrackerLongestDistanceActivity -- Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 16/50 | **Reason**: GoalAchieved | **Duration**: 116s

**Goal**: Find the longest distance mountain biking activity this week in OpenTracks. Answer in meters, rounded.

## Root Cause

**Category**: WRONG_ANSWER / ActivityTypeMismatch

The agent answered 18668 (11.60 mi converted to meters) based on the "Scenic Cycling" track whose Edit screen shows activity type "biking". The agent equated "biking" with "mountain biking", but these are distinct activity types in OpenTracks. The scorer rejected the answer.

Additionally, the agent only verified 2 of 4 biking-related tracks this week. "Road Bike Ride" (Thursday, 8.65 mi) and "Road Bike Session" (Tuesday, 0.62 mi) were never opened to check their actual activity types. One of these -- or another track the agent skipped -- likely had the "mountain biking" type.

## Turn-by-Turn Summary

| Turn | Action | What Happened |
|------|--------|---------------|
| 1 | open_app OpenTracks | Launched app |
| 2-3 | click(8) x2 | Dismissed onboarding screens |
| 4 | write_todos | Created plan: identify tracks, verify types, compute answer |
| 5 | click(13) | Opened "Wave Exploration" (Today, 8.09 mi) |
| 6 | scratchpad | Stored distance 8.09 mi |
| 7 | click(43) | Opened More options menu |
| 8 | click(9) | Clicked Edit |
| 9 | scratchpad | Recorded activity type: "surfing" -- eliminated |
| 10 | back | Return from Edit |
| 11 | back | Return to track list |
| 12 | click(31) | Opened "Scenic Cycling" (Tuesday, 11.60 mi) |
| 13 | scratchpad + click(43) | Stored distance, opened More options |
| 14 | click(9) | Clicked Edit |
| 15 | scratchpad + write_todos | Recorded activity type: "biking" -- treated as mountain biking |
| 16 | complete_task | Answered 18668 |

## Cognitive Errors

### 1. "biking" != "mountain biking" (primary failure)
The agent saw activity type "biking" in the Edit screen and assumed it satisfied the "mountain biking" query. OpenTracks distinguishes between "biking" and "mountain biking" as separate activity types. The agent needed to find a track whose type was specifically "mountain biking" (or a close variant like "mountain bike").

### 2. Incomplete track coverage
The track list this week contained 8 tracks. The agent only verified 2 (Wave Exploration and Scenic Cycling). Skipped candidates that should have been checked:
- **Road Bike Ride** (Thursday, 8.65 mi) -- name suggests road biking but skill guidance says names are unreliable
- **Road Bike Session** (Tuesday, 0.62 mi) -- same issue
- **Skill work** (Tuesday, 0.75 mi) -- ambiguous name, could be any sport

The "Smart Filtering" guidance in the app skill says to "prioritize tracks with ambiguous names" and "always verify via Edit when in doubt". The agent applied over-aggressive filtering, skipping tracks that could have been mountain biking.

### 3. Premature completion
The agent completed after checking only 2 tracks because it found one with a biking-adjacent type. A more thorough approach would have verified all plausible candidates before selecting the maximum.

## What Worked

- Efficient navigation (16 turns vs 25-29 in prior rounds)
- Correct use of Edit screen to verify activity types per app skill
- Good use of scratchpad to track distances and types
- Structured todo-driven approach

## What Didn't Work

- Treated "biking" as equivalent to "mountain biking"
- Smart filtering was too aggressive -- skipped "Road Bike Ride" which should have been checked
- No verification that the found type exactly matches "mountain biking"

## Comparison with Prior Rounds

| Round | Answer | Score | Turns | Issue |
|-------|--------|-------|-------|-------|
| R39 | 1207 | 1.0 | 29 | Passed (different AW instance data) |
| R40 | 0 | 0.0 | 25 | Could not recognize mountain biking type at all |
| R41 | 18668 | 0.0 | 16 | Equated "biking" with "mountain biking" |

Non-deterministic across rounds. Core problem: the agent lacks exact-match logic for activity types.

## Recommendations

1. **Exact type matching**: When the task asks for "mountain biking", the agent must find a track whose Edit activity type says exactly "mountain biking" (or a recognized variant). "biking" alone should not match.
2. **Check all biking-adjacent tracks**: Do not skip "Road Bike Ride" or similar names -- they could have any activity type in the Edit screen. The app skill already says names are unreliable.
3. **App skill update**: Add guidance that OpenTracks has distinct types: "biking", "mountain biking", "road biking", etc. Matching must be exact, not fuzzy.
4. **Exhaustive before answering**: For "longest/shortest/max/min" queries, verify all plausible candidates before computing the answer, not just the first match.
