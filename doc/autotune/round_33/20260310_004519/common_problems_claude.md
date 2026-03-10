# R33 Common Problems — claude

**Run**: `20260310_004519` | **Model**: gpt-5.4 | **Tasks**: 5 (all remaining)

## Results

| Task | Score | Turns | Status |
|------|-------|-------|--------|
| RetroPlaylistDuration | 1.0 | 57 | PASS |
| OsmAndMarker | 0.0 | 28 | FAIL (offline geocode to wrong continent) |
| OsmAndTrack | 0.0 | 44 | FAIL (wrong workflow: Navigation vs Plan a route) |
| MarkorDeleteNewestNote | 0.0 | 7 | RE-PARKED (evaluator atime bug) |
| BrowserDraw | 0.0 | 30 | RE-PARKED (platform limitation) |

**Pass rate**: 1/5 (20%), 1/3 excluding re-parks (33%)

## Fixed This Round

1. **RetroPlaylistDuration**: Per-song 3-dot menu "Add to playlist" bypassed the a11y gap in multi-select toolbar. Add-all-then-prune strategy worked at 57 turns.

## Re-parked (structural, cannot fix)

1. **MarkorDeleteNewestNote**: Agent correctly deleted newest note. Evaluator uses `ls -au` (atime) which Markor's delete mutates on surviving files, causing false negative. Evaluator bug.
2. **BrowserDraw**: A11y click doesn't fire JS DOM events in Chrome WebView canvas. Structural platform limitation.

## Remaining Failures (actionable)

### OsmAndMarker
- **Root cause**: OsmAnd's offline geocoder maps "Planken, Liechtenstein" to Ghana/West Africa. Agent tried coords `47.18, 9.54` which resolved correctly but then abandoned them.
- **Fix**: Skill should say "ALWAYS try coordinates first for small localities" + verify map shows correct region after search.

### OsmAndTrack
- **Root cause**: Agent used Navigation/Directions workflow, which doesn't save GPX tracks. Evaluator checks for `.gpx` file in `tracks/` with ordered trkpts. Correct path: hamburger → "Plan a route" → add waypoints → Save.
- **Fix**: Add "Saving GPX Tracks" section to OsmAnd skill with explicit "Plan a route" workflow.

## Next Steps

1. Update OsmAnd skill: coord-first search for markers, "Plan a route" workflow for tracks.
2. R34: OsmAndMarker + OsmAndTrack only (2 remaining active tasks).
3. If both fail R34, re-park and stop loop.
