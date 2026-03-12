# OsmAndTrack — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 34/60 | **Reason**: Error | **Duration**: 253s

**Goal**: Create a GPX track in OsmAnd.

## Root Cause

**Category**: NavigationFailure

OsmAnd's map-based GPX track creation UI is rendered on a native map canvas, making most interactive elements inaccessible via the Android accessibility service. Track creation requires tapping waypoints on the map canvas, using a floating action toolbar that is not exposed as a11y nodes, and navigating map-rendered menus. The agent cannot reach the track creation mode reliably and exhausted 34 of 60 turns attempting to navigate to it.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1-4 | Launch OsmAnd, reach map view | Yes | Correct | Good | OK | Map shown |
| 5-10 | Navigate to GPX/Track creation via menu | Partial | Correct intent | Degraded | Partial | Menu hierarchy partially accessible |
| 11-18 | Attempt to enter track creation mode | Minimal | Correct | Degraded | Failed | Track creation UI not in a11y tree |
| 19-26 | Retry via different menu paths | No | Reasonable exploration | Degraded | Failed | Map canvas elements not accessible |
| 27-34 | Final attempts, error reported | No | Stuck | Poor | Failed | Platform limitation exhausted agent |

### Key Turns

**Turn 10** — Agent found a menu path toward track recording/creation but the actual track creation view renders entirely on the map canvas. From this point, no accessible UI elements are available for the workflow.

**Turn 11** — Track creation mode entered but all interactive elements (waypoint placement, route drawing toolbar) are rendered as map overlays outside the a11y tree. Agent cannot proceed.

**Turn 34** — Error reported after exhausting half the turn budget. Agent correctly identified it was stuck rather than false-completing.

## What Worked

- OsmAnd launched and map was reached
- Agent navigated through part of the menu hierarchy correctly
- Agent identified the correct general area (track/GPX creation)
- Agent correctly errored rather than false-completing

## What Didn't Work

- Track creation UI uses map canvas rendering — not accessible via a11y service
- Floating toolbars and waypoint buttons not in a11y tree
- No alternative input path available for map-canvas interaction
- 34 turns wasted on an inaccessible workflow

## Recommendations

- Already in `cannot_handle_group.txt` — map canvas inaccessibility is a fundamental platform limitation
- No prompt change will fix this; track creation requires canvas tap events that a11y service cannot synthesize
- Long-term fix: implement coordinate-based map tap tool that fires native touch events on the map canvas
- Could add to OsmAnd app skill: "GPX track creation via map canvas is not supported; fail fast if this workflow is required" to save turns
