# R35 Common Problems — claude

**Run**: `20260310_013847` | **Model**: gpt-5.4 | **Tasks**: 2 (OsmAnd pair, final attempt)

## Results

| Task | Score | Turns | Status |
|------|-------|-------|--------|
| OsmAndMarker | 0.0 | 15 | RE-PARKED (structural: map data + coord tolerance) |
| OsmAndTrack | 0.0 | 25 | RE-PARKED (structural: map data) |

## Verdict: Both RE-PARKED

The agents **followed all skill guidance correctly** this round (Address tab, Plan a route, coordinate fallback). The failures are structural:

1. **OsmAndMarker**: Agent used Address tab + coordinate fallback. But coordinate search yielded coords ~0.007 degrees off from evaluator's expected location (tolerance: 0.001). The agent can't know the exact expected coordinates.

2. **OsmAndTrack**: Agent used Plan a route + Address tab correctly. But Address search couldn't resolve any of the three village names (Schonberg, Triesen, Bendern). Without coordinates for these locations, no waypoints can be added.

**Root cause**: The Liechtenstein offline map data on the emulator doesn't index village-level POIs for OsmAnd's search. This requires either a different map data source, internet access for online geocoding, or pre-hardcoded coordinates — none of which are agent-side fixes.

## Loop Summary (R31-R35)

| Status | Count | Tasks |
|--------|-------|-------|
| FIXED | 5 | AudioRecorder, ExpenseDelete2, ExpenseAddGallery, RecipeDelete3, RetroPlaylist |
| RE-PARKED (evaluator bug) | 1 | MarkorDeleteNewestNote |
| RE-PARKED (platform limit) | 1 | BrowserDraw |
| RE-PARKED (map data) | 2 | OsmAndMarker, OsmAndTrack |
| **Total** | **9** | |

**5/9 fixed (56%)** — all fixable tasks resolved. 4 re-parked with confirmed structural causes.
