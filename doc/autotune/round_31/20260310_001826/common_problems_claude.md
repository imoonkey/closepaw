# R32 Common Problems — claude

**Run**: `20260310_001826` | **Model**: gpt-5.4 | **Tasks**: 4 (R31 retries + Tier 2)

## Results

| Task | Score | Turns | Status |
|------|-------|-------|--------|
| ExpenseAddMultipleFromGallery | 1.0 | 27 | PASS |
| RecipeDeleteDuplicateRecipes3 | 1.0 | 45 | PASS |
| OsmAndMarker | 0.0 | 9 | FAIL (false GoalAchieved) |
| RetroPlaylistDuration | 0.0 | 60 | FAIL (turn limit) |

**Pass rate**: 2/4 (50%)

## Fixed This Round

1. **ExpenseAddMultipleFromGallery**: Gallery skill on correct package (`com.simplemobiletools.gallery.pro`) with filename verification fixed the wrong-image problem.
2. **RecipeDeleteDuplicateRecipes3**: Scratchpad tracking + 80 turn budget sufficient. Agent checked all 7 fields systematically.

## Remaining Failures

### OsmAndMarker
- **Root cause**: OsmAnd search for "Planken, Liechtenstein" returns no village-level POI. Only "SHOW ON MAP" (generic) and "Liechtenstein" (country). Agent picked country result → marker at country centroid, far from target.
- **Fix**: Update skill — after "SHOW ON MAP" centers the map, interact with the map pin/point to get context sheet, then tap Marker. Don't go back to search results.

### RetroPlaylistDuration
- **Root cause**: "Add to playlist" button in multi-select toolbar is not in a11y tree. Agent tried coordinate taps but at wrong y-position (status bar y=91 instead of toolbar y=211).
- **Fix**: Update skill — use per-song context menu "Add to playlist" instead of multi-select toolbar. Or provide correct toolbar coordinate guidance.

## Next Steps

1. Update OsmAnd skill: "After 'SHOW ON MAP' centers the map, tap the map pin to open context sheet, then tap Marker."
2. Update Retro Music skill: add per-song "Add to playlist" fallback via 3-dot context menu.
3. Re-run OsmAndMarker and RetroPlaylistDuration in R33.
4. Remove ExpenseAddMultipleFromGallery and RecipeDeleteDuplicateRecipes3 from unpark_active.txt.
