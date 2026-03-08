# SportsTrackerLongestDistanceActivity — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 30/30 | **Reason**: GoalAchieved | **Duration**: 144s

**Goal**: Find the longest distance covered in a mountain biking activity in OpenTracks this week (Mon-Sun). Answer in meters rounded to nearest integer.

## Root Cause

**Category**: FalseCompletion (via TurnExhaustion)

The agent checked 4 of 7 this-week tracks for activity type but ran out of turns before reaching "Skill work" (mountain biking, 0.75 mi = ~1207m). It concluded no mountain biking activities existed and answered "0". The same task passed in R3/R4 because the agent happened to check Skill work before running out of turns. The fundamental issue is the same as R4: the one-by-one edit-to-check-type approach costs ~4-5 turns per track, making 7 tracks uncheckable within 30 turns.

**R4 comparison**: R4 scored 1.0 at 30/30 but was a fragile pass (checked 4/7 tracks, got lucky that the mountain biking track was among them). R9 checked 4/7 different tracks and got unlucky: the mountain biking track (Skill work) was in the unchecked set.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `open_app(OpenTracks)` + `write_todos` | Y | G | G | G | Opened app, hit intro screen |
| 2-3 | click intro dismiss buttons | Y | G | G | G | 2 turns on onboarding |
| 4 | click Search icon | Y | G | G | G | Search bar opened, full list visible |
| 5 | type "mountain" in search | N | B | G | G | Search didn't filter; OpenTracks searches by name not type |
| 6 | system_button(back) | N | G | G | G | Dismissed keyboard |
| 7-9 | open Scenic Cycling > overflow > Edit | Y/N | G | G | G | Edit screen blank on a11y (turn 9) |
| 10 | scratchpad: Scenic Cycling = biking | N | B | -- | G | Inferred "biking" but edit screen was empty; name-based guess |
| 11-12 | cancel edit, navigate back to list | Y | G | G | G | -- |
| 13-15 | open Wave Exploration > overflow > Edit | Y/N | G | G | G | Edit screen blank on a11y (turn 15) |
| 16 | scratchpad: Wave Exploration = surfing | N | B | -- | G | Inferred "surfing" from name; edit screen was empty |
| 17-18 | cancel, back to list | Y | G | G | G | -- |
| 19 | open Road Bike Ride detail (stats) | Y | G | G | G | Skipped edit, went to stats |
| 20-21 | overflow > Edit | Y | G | G | G | Edit screen rendered: "road biking" visible |
| 22 | scratchpad: Road Bike Ride = road biking | N | G | -- | G | Correctly read from edit screen |
| 23-24 | cancel, back to list | Y | G | G | G | -- |
| 25 | open Downhill Skiing detail (stats) | Y | G | G | G | -- |
| 26-27 | overflow > Edit | Y | G | G | G | Edit screen rendered: "skiing" visible |
| 28 | scratchpad: Downhill Skiing = skiing | N | G | -- | G | Correctly read from edit screen |
| 29 | write_todos (all checked, none = mountain biking) | N | B | -- | G | Premature conclusion: 3 tracks unchecked |
| 30 | `complete_task(answer=0)` | -- | B | -- | G | Wrong answer; Skill work was mountain biking |

### Key Turns

**Turn 5** — Searching "mountain" in the track name search field was a wasted turn. OpenTracks search filters by track name, not activity type. All 7 tracks remained visible after the search, so no useful filtering occurred.

**Turn 9-10 (Scenic Cycling edit)** — The edit screen a11y tree was empty (only status bar nodes). The agent recorded "biking" as the activity type, but this was inferred from the track name "Scenic Cycling", not read from the UI. The inference happened to be correct but the agent's reasoning didn't acknowledge the perception gap.

**Turn 15-16 (Wave Exploration edit)** — Same pattern: empty edit screen on a11y, agent inferred "surfing" from the name. Correct inference but not grounded in screen data.

**Turn 29** — The agent concluded "none are mountain biking" after checking only 4 of 7 this-week tracks (Scenic Cycling, Wave Exploration, Road Bike Ride, Downhill Skiing). It never checked Road Bike Session, Skill work, or City Stroll. "Skill work" is the mountain biking activity (same as R3/R4). This premature conclusion is the direct cause of the wrong answer.

## What Worked

- Systematic approach: opened each track's Edit view to check activity type
- Good use of scratchpad to record findings per track
- Multi-action per turn (turn 1: write_todos + open_app)
- Faster than R4 (144s vs 266s)

## What Didn't Work

- **Incomplete coverage**: Checked 4/7 tracks, missed the mountain biking one (Skill work)
- **Wasted turns on search**: Typing "mountain" in the name search consumed 3 turns (open search, type, dismiss keyboard) with zero benefit
- **Edit screen perception gap**: Turns 9 and 15 showed empty a11y trees for the edit screen; agent inferred types from names instead of flagging the perception failure
- **No priority ordering**: The agent checked tracks in order of visual position (top to bottom) rather than prioritizing ambiguously-named tracks like "Skill work" that can't be classified by name alone
- **Same fragility as R4**: The 4-5 turns per track cost makes it impossible to check all 7 tracks within 30 turns, especially after spending 3 turns on the fruitless search

## Recommendations

- **Skip search, start checking immediately**: Removing the "mountain" search saves 3 turns, enough to check one more track
- **Prioritize ambiguous track names**: "Scenic Cycling" is obviously not mountain biking; "Skill work" is ambiguous. Check ambiguous names first to maximize information per turn
- **Read activity type from stats view**: The detail stats screen shows metric labels ("MAX SPEED" vs "FASTEST PACE") that hint at activity type. If the agent could infer type from the stats screen, it would save the 2-turn Edit detour entirely
- **Detect and report edit screen perception failures**: When the edit screen a11y tree is empty, the agent should retry or scroll rather than silently inferring from the track name
- **App skill for OpenTracks**: A dedicated skill could encode the efficient path: list view -> detail -> read type from stats labels, avoiding the costly Edit workflow entirely
