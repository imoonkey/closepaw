# OsmAndMarker - Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 21/30 | **Reason**: GoalAchieved | **Duration**: 105s

**Goal**: Add a location marker for `Planken, Liechtenstein` in OsmAnd.

## Root Cause

**Category**: FalseCompletion

This run went further than the older "map UI inaccessible" failures: the agent switched from text search to coordinate search, resolved the target location, and opened the bottom action bar on the map. But at the final step it misbound the action. The visible bottom bar exposed separate actions `Add`, `Marker`, `Share`, and `Actions`; the agent thought it was tapping `Marker`, but it actually clicked the adjacent `Share` element and then completed without verifying that any marker was created.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1-14 | Open OsmAnd, try standard search flows for `Planken, Liechtenstein` | Y | G | M | G | Progress was limited but it kept exploring |
| 15-18 | Pivot to coordinate search and enter `47.1959`, `9.5463` | Y | G | G | G | This correctly resolved the target location |
| 19 | Select the coordinate result and open map action bar | Y | G | G | G | Reached the correct pre-action state |
| 20 | Attempt to tap marker action | Y | G | B | G | Click landed on `Share`, not `Marker` |
| 21 | `complete_task(success)` | N | B | B | G | Completion happened without any marker verification |

### Key Turns

**Turn 19** - The agent successfully selected the coordinate result and surfaced the actionable bottom sheet, which is better progress than the earlier fully blocked OsmAnd runs.

**Turn 20** - The screen exposed separate clickable elements for `Add` and `Marker`. The agent's thought said "tap Marker", but the actual tool call targeted element `14`, which was `Share`. This is the decisive miss.

## What Worked

- The agent escaped the failed text-search loop by pivoting to coordinates.
- It reached the correct map location and a state where marker creation looked one action away.

## What Didn't Work

- Bottom-bar action grounding was still brittle in the map UI.
- Completion relied on inferred success rather than checking for a persisted marker/favorite entry.

## Recommendations

- Keep this near the `cannot_handle` bucket for now, but note that GPT-5.4 did make partial progress beyond the old total-blocked pattern.
- Group it under map-action grounding + post-action verification, not under generic prompt tips.
