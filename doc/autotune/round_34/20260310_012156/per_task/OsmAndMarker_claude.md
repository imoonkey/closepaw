# OsmAndMarker Trace Analysis — R34 run 20260310_012156

## Task
Add a location marker for Planken, Liechtenstein in OsmAnd.

## Result
FAILED, score=0.0, 29 turns, stop_reason=Error (agent called `complete_task` with status=failure).

## Expected Coordinates
Planken, Liechtenstein: (47.1858882, 9.5452201) — evaluator checks `map_markers_db` within 0.001 degrees (~100m).

## No marker was placed
The agent never reached the marker placement step. It gave up after 29 turns, unable to find Planken in OsmAnd's offline search.

## Turn-by-Turn Summary

| Turn | Action | Outcome |
|------|--------|---------|
| 1 | `open_app` OsmAnd | App opened |
| 2 | Click skip/onboarding element [14] | OK |
| 3 | Click search (magnifying glass) [7] | Search screen opened |
| 4 | Type "Planken, Liechtenstein" | Only result: "Liechtenstein" (country, 3296 mi) + SHOW ON MAP |
| 5 | Click Clear [7] — agent decides to try just village name | Cleared search |
| 6 | Type "Planken" | "Could not find anything: 5 mi" — no village match, only SHOW ON MAP |
| 7 | Click SHOW ON MAP [8] | Map opens (geocodes to wrong location — West Africa per R33 evidence) |
| 8 | Back button — agent sees wrong region | Returns to search |
| 9 | Click INCREASE SEARCH RADIUS [12] | Radius -> 10 mi, still nothing |
| 10 | Click INCREASE SEARCH RADIUS [12] | Still nothing found |
| 11 | Click Navigate up [5] | Returns to map |
| 12 | Re-type "Planken" | Same: "Could not find anything: 5 mi" |
| 13 | Click SHOW ON MAP [8] | Again wrong geocode |
| 14 | Back button | Returns to search |
| 15-16 | INCREASE SEARCH RADIUS x2 | 10 mi, still nothing |
| 17 | Scroll down | Error: no scrollable node, no observable effect |
| 18 | Navigate up — return to map | OK |
| 19-20 | Re-open search, type "Planken" again | Same dead end |
| 21 | INCREASE SEARCH RADIUS | Still nothing |
| 22 | Click Address tab [9] | Switches to Address search (shows "SHOW ON MAP" again — wrong geocode) |
| 23 | Back button | Returns from wrong map |
| 24 | INCREASE SEARCH RADIUS | Nothing |
| 25 | Click Clear | Cleared |
| 26 | Click Address tab [9] | Shows "FIRST SPECIFY CITY/TOWN/LOCALITY", "POSTCODE SEARCH", "COORDINATES SEARCH" |
| 27 | Type "Planken" in Address tab | "Could not find anything: 50 mi" |
| 28 | INCREASE SEARCH RADIUS to 100 mi | "Could not find anything: 100 mi" |
| 29 | `complete_task` status=failure | Agent gives up |

## Key Findings

### 1. Did the agent find Planken in search?
No. Neither the History tab ("Planken, Liechtenstein" or "Planken") nor the Address tab produced a village-level result. The offline geocoder only returned "Liechtenstein" (country) or "Could not find anything" at every search radius up to 100 mi.

### 2. Did the agent try SHOW ON MAP?
Yes, twice (turns 7 and 13). Both times the map geocoded to West Africa (consistent with R33 findings). The agent correctly identified this was wrong and backed out both times.

### 3. Did the agent try coordinate search?
No. Unlike R33 where the agent tried "47.18, 9.54" (which worked), in R34 the agent never attempted coordinates despite:
- Seeing "COORDINATES SEARCH" option in the Address tab (turn 26, element [16])
- The skill lacking explicit coordinate-fallback guidance

### 4. Did the agent see the COORDINATES SEARCH option?
Yes. Turn 27 shows the Address tab with "COORDINATES SEARCH" visible at element [16]. The agent typed "Planken" into the Address field instead of tapping COORDINATES SEARCH and entering `47.1858882, 9.5452201`.

### 5. What was the root cause?
**Same as R33 but worse: the agent never tried the coordinate fallback.**

The offline geocoder cannot resolve "Planken" to the village — it only works for POIs/addresses in the downloaded map data within the search radius centered on the current map view (which starts far from Liechtenstein). The skill says "All Liechtenstein localities are available in the offline map data" but the search-by-name path simply does not work from a distant starting position.

In R33, the agent at least discovered that coordinate search works (turns 10-11: "47.18, 9.54" -> "47.18000 N, 9.54000 E — Liechtenstein") but then abandoned it. In R34, the agent never even attempted coordinates despite having 29 turns and seeing the COORDINATES SEARCH button.

## Root Cause

**Primary: Skill gap — no coordinate-fallback instruction.** The SKILL.md tells the agent to search by name, try scrolling, and avoid SHOW ON MAP for unreliable geocodes, but never says "if name search fails, use COORDINATES SEARCH with known coords." The agent followed the skill faithfully but the skill's strategy is insufficient for the offline environment.

**Secondary: Agent did not explore COORDINATES SEARCH autonomously.** The agent saw the option in the Address tab (turn 26) but chose to type a text query instead. A more exploratory agent would have tried the coordinates path when text search failed repeatedly.

## Comparison with R33

| Aspect | R33 | R34 |
|--------|-----|-----|
| Text search result | Same — no village found | Same — no village found |
| SHOW ON MAP | Geocoded to Africa | Geocoded to Africa |
| Coordinate search | Tried "47.18, 9.54" — worked but abandoned | Never attempted |
| COORDINATES SEARCH button | Not observed | Visible but ignored |
| Final outcome | Failure after 28 turns | Failure after 29 turns |
| Stop reason | complete_task failure | complete_task failure |

## Proposed Fix

### Skill update (net.osmand SKILL.md)

The skill must add a coordinate-first fallback for when name search fails:

```
## Adding a Location Marker
1. Open OsmAnd -> tap Search
2. Type the location name (e.g., "Planken, Liechtenstein")
3. Check search results:
   - If an **exact village/city match** appears in the list, tap it.
   - If only a country-level result or "Could not find anything" appears:
     **use coordinate search instead.** Go to Address tab -> COORDINATES SEARCH
     and enter the approximate coordinates for the location.
     Common Liechtenstein coordinates:
     - Planken: 47.186, 9.545
     - Balzers: 47.069, 9.506
     - Schaan: 47.166, 9.510
4. Do NOT trust "SHOW ON MAP" for text queries — offline geocoding often sends
   the map to the wrong continent.
5. After the map centers on the correct location, tap the pin to open the context sheet.
6. Scroll action buttons horizontally and tap "Marker" (flag icon).
7. Verify: the context sheet should mention the correct region (Liechtenstein), not Africa.
```

### Alternative: teach the agent to derive coordinates
Instead of hardcoding coords in the skill, add guidance like: "If name search fails, use COORDINATES SEARCH. You can estimate coordinates from the country (Liechtenstein is ~47.1-47.2 N, 9.5-9.6 E)."
