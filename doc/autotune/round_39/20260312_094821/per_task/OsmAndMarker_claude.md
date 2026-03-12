# OsmAndMarker — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 16/30 | **Reason**: GoalAchieved | **Duration**: 128s

**Goal**: Add a map marker in OsmAnd at a specific location.

## Root Cause

**Category**: FalseCompletion

Two issues compound here. First, OsmAnd's location search is unreliable for small localities — the target location either does not appear in search results or returns an ambiguous result that is not the correct place. Second, the "Add marker" button after long-pressing on the map may not be in the a11y tree, so the agent cannot confirm the marker was placed at the correct coordinates. The agent claims success after completing the search-and-tap flow, but the placed marker is at the wrong location.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1-3 | Launch OsmAnd, reach map view | Yes | Correct | Good | OK | Map loaded |
| 4-6 | Open search, enter location name | Yes | Correct | Good | Partial | Search results returned, may be wrong/ambiguous |
| 7-9 | Select search result, view location on map | Yes | Agent believes correct | Degraded | Uncertain | May have selected wrong result |
| 10-12 | Long-press on map to place marker | Yes | Correct intent | Degraded | Failed | Marker button not in a11y tree |
| 13-15 | Attempt to confirm marker placement | Partial | Agent believes success | Poor | Wrong location | Marker placed but at wrong coordinates |
| 16 | Declare GoalAchieved | — | False | Failed | FalseCompletion | Scorer rejects wrong marker location |

### Key Turns

**Turn 7** — Search result selection is the likely failure point. For small localities, OsmAnd may return multiple results or an approximate match. The agent selects the first/most prominent result without verifying it matches the exact target location.

**Turn 10** — Long-press to place marker: the map context menu and "Add marker" button are rendered outside the a11y tree. The agent cannot confirm the marker was actually placed, only that a tap was executed.

**Turn 16** — GoalAchieved declared. The agent saw no error indication but also had no way to verify the marker coordinates.

## What Worked

- OsmAnd launched and map was reached
- Search functionality was accessible
- Agent attempted correct workflow (search → select → long-press → marker)
- Fast execution (16 turns)

## What Didn't Work

- Location search result for small locality was wrong or ambiguous
- "Add marker" button not in a11y tree — cannot confirm marker placement
- No coordinate verification after marker placement
- FalseCompletion: success declared without verifiable confirmation

## Recommendations

- Already in `cannot_handle_group.txt` — search reliability and a11y tree gaps are platform limitations
- Potential partial fix: after search, verify the displayed location name/address matches the target before placing marker
- Add to OsmAnd app skill: "After selecting a search result, read the displayed place name and coordinates to verify it matches the target location before proceeding"
- Map marker placement via a11y is fundamentally unreliable; this task class requires either a11y tree improvements or a different interaction model
