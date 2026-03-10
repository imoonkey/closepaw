# R34 Common Problems — claude

**Run**: `20260310_012156` | **Model**: gpt-5.4 | **Tasks**: 2 (OsmAnd pair, final attempt)

## Results

| Task | Score | Turns | Status |
|------|-------|-------|--------|
| OsmAndMarker | 0.0 | 29 | FAIL (search fails, no coord fallback used) |
| OsmAndTrack | 0.0 | 22 | FAIL (right workflow, wrong search tab) |

## Common Root Cause

Both tasks fail because OsmAnd's **general search tab is proximity-based** — it only finds POIs near the current map center. When the map starts far from Liechtenstein (default position), all village searches return nothing or geocode to wrong continent.

The **Address tab** performs structured offline lookup across ALL downloaded map data regardless of map position. It was visible in the search UI but the agent never switched to it.

## R34 Specific Findings

### OsmAndMarker
- Agent searched "Planken, Liechtenstein" in general tab — 29 turns of fruitless searching
- Saw "COORDINATES SEARCH" button in Address tab but typed text query instead
- Never tried coordinate search unlike R33 (where coords worked)

### OsmAndTrack
- Correctly used "Plan a route" workflow (R33 fix worked)
- Search for waypoints (Schonberg, Triesen, Bendern) failed in general tab
- Address tab was visible but never used

## Next Steps (R35 — final attempt)

1. Update OsmAnd skill to explicitly say: **"Switch to the Address tab"** when general search fails
2. Add: "If Address tab also fails, use COORDINATES SEARCH"
3. If R35 fails, re-park both tasks — the search UI navigation is too unreliable
