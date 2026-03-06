# SportsTrackerLongestDistanceActivity — Cog-Tune Analysis

**Score**: 1.0 | **Turns**: 30/30 | **Reason**: GoalAchieved | **Duration**: 266s

**Goal**: Find the longest distance covered in a mountain biking activity in OpenTracks this week (Mon-Sun). Answer in meters rounded to nearest integer.

## Root Cause

**Category**: Success (fragile — used all 30 turns)

The agent correctly identified "Skill work" as the only mountain biking activity this week with 0.75 mi distance, converted to 1207 meters, and submitted the correct answer. However, it used all 30 turns — the same fragility as R3. The agent checked 4 of 7 weekday tracks' activity types (Wave Exploration=surfing, Road Bike Ride=road biking, Scenic Cycling=biking, Skill work=mountain biking) but could not verify the remaining 3 (Downhill Skiing, Road Bike Session, City Stroll x2). It got lucky: no unchecked track happened to be mountain biking with a longer distance.

**R3 comparison**: R3 also scored 1.0 at 30/30 turns. R4 did NOT improve turn efficiency despite adding repetitive-task guidance. The turn count is identical. The duration improved (266s vs unknown R3 duration), but the core issue remains: the one-by-one track checking approach (open > More options > Edit > read type > back > back) costs ~4-5 turns per track.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `open_app(OpenTracks)` | Y | G | G | G | — |
| 2-3 | click intro buttons | Y | G | G | G | 2 turns on intro screens |
| 4-7 | check Wave Exploration | Y | G | G | G | Found: surfing. 4 turns for one check |
| 8 | navigate back to list | Y | G | G | G | Extra nav turn |
| 9 | scratchpad (plan) | N | G | — | G | Recorded week tracks and plan |
| 10-14 | check Road Bike Ride | Y | G | G | G | Found: road biking. 5 turns |
| 15 | scratchpad (progress) | N | G | — | G | — |
| 16-17 | check Downhill Skiing | Y | G | G | B | Opened detail but didn't reach Edit, navigated back |
| 18-21 | check Scenic Cycling | Y | G | G | G | Found: biking. 4 turns (incl scratchpad) |
| 22 | navigate back to list | Y | G | G | G | — |
| 23-24 | check Road Bike Session | Y | B | G | G | Opened detail but navigated back without checking type |
| 25-27 | check Skill work | Y | G | G | G | Found: mountain biking, 0.75 mi |
| 28 | scratchpad (convert) | N | G | — | G | 0.75 mi = 1207 meters |
| 29 | navigate back | Y | G | G | G | — |
| 30 | `complete_task(answer=1207)` | — | G | — | G | Correct answer |

### Key Turns

**Turn 16-17** — Agent opened Downhill Skiing detail but navigated back without reaching Edit to check the activity type. The track name "Downhill Skiing" likely made the agent infer it wasn't mountain biking, saving turns but introducing risk.

**Turn 23-24** — Same pattern with Road Bike Session: opened detail but left without confirming type. Name-based inference saved turns but could have been wrong.

## What Worked

- Correct final answer (1207 meters)
- Good use of scratchpad to track checked types and conversion math
- Systematic approach: listed all week tracks, then checked one by one
- Clean session termination

## What Didn't Work

- **No turn improvement from R3**: Still 30/30 turns. The repetitive-task guidance added in R4 did not reduce the per-track checking cost.
- **3 unchecked tracks**: Downhill Skiing, Road Bike Session, and City Stroll were not fully verified. The agent relied on name-based inference for these, which is risky.
- **Intro screens**: 2 turns wasted on app intro — this is a recurring cost across all SportsTracker tasks.

## Recommendations

- **Skip Edit for type inference**: The track detail view shows metric labels like "FASTEST PACE" (running) vs "MAX SPEED" (biking/cycling). If the agent could learn to read these cues, it could identify activity type from the detail screen without the 2-turn Edit detour.
- **Intro screen handling**: Consider pre-dismissing OpenTracks intro in the eval setup, or teach the agent to recognize and quickly dismiss known intro patterns.
- **Name-based shortcutting with verification**: The agent implicitly skipped some tracks based on names. Making this explicit in reasoning ("Downhill Skiing is unlikely mountain biking, skipping") would be more honest and could free turns for verifying ambiguous names.
