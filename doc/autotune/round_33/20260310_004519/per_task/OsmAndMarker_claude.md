# OsmAndMarker Trace Analysis — R33 run 20260310_004519

## Task
Add a location marker for Planken, Liechtenstein in OsmAnd.

## Result
FAILED, score=0.0, 28 turns, stop_reason=Error (agent called `complete_task` with status=failure).

## Turn-by-Turn Summary

| Turn | Action | Outcome |
|------|--------|---------|
| 1 | `open_app` OsmAnd | App opened |
| 2 | Click to skip onboarding | OK |
| 3 | Click search (magnifying glass) | Search opened |
| 4 | Type "Planken, Liechtenstein" | Only result: "Liechtenstein" (country) + SHOW ON MAP |
| 5 | Click SHOW ON MAP | Map centers — but geocodes to **Ghana/West Africa** |
| 6 | Tap map at (537,531) | "What's here" sheet: Ghana, Africa, Mango City |
| 7 | Back button | Close wrong sheet |
| 8-9 | Close search, reopen | Reset |
| 10 | Type coordinates "47.18, 9.54" | Result shows "47.18000 N, 9.54000 E — Liechtenstein" correctly |
| 11 | Click SHOW ON MAP for coordinates | Map centers (likely correct location) |
| 12 | **Abandons coordinate approach** — taps back | Agent thought: "coordinate query was interpreted incorrectly" |
| 13-15 | Retries text "Planken, Liechtenstein" + SHOW ON MAP | Same wrong geocode to Africa |
| 16-17 | Taps map, sees "Burkina Faso" in sheet | Wrong location confirmed again |
| 18-26 | Multiple retries, same loop | Stuck in retry loop |
| 27 | One more SHOW ON MAP attempt | Same failure |
| 28 | `complete_task` status=failure | Agent gives up |

## Key Findings

### 1. Did the agent use "SHOW ON MAP"?
Yes, correctly. Turn 5 follows the skill exactly: search "Planken, Liechtenstein" -> tap SHOW ON MAP.

### 2. After "SHOW ON MAP", did the agent tap the map pin?
Yes (turn 6), but the map had geocoded to the wrong continent. The "What's here" sheet showed Ghana/Africa instead of Liechtenstein.

### 3. Did the agent find the Marker button?
No — never reached this step because the location was always wrong.

### 4. Why did it fail after 28 turns?
Two compounding root causes:

**Root Cause A — Offline geocoding failure (environment):** The emulator has "No internet" (visible in status bar across all turns). OsmAnd's offline geocoder interprets "Planken, Liechtenstein" as a location in West Africa rather than the European village. The SHOW ON MAP button geocodes the raw search text offline, and without online Nominatim, the result is wrong.

**Root Cause B — Agent abandoned the working coordinate path (cognition):** In turn 10-11, the agent searched "47.18, 9.54" which correctly resolved to Liechtenstein. OsmAnd even showed "47.18000 N, 9.54000 E — Liechtenstein" in search results. After clicking SHOW ON MAP, the map view appeared (just the map, no context sheet yet). But instead of tapping the map pin to proceed, the agent concluded "the coordinate query was interpreted incorrectly" and went back. This was a false negative — the coordinates were correct, but the agent didn't verify by tapping the map. It then looped on the broken text search 4 more times.

## Root Cause

The offline geocoder maps "Planken, Liechtenstein" to Africa. The skill's advice ("SHOW ON MAP geocodes correctly") is wrong in the offline/no-internet environment. The coordinate fallback (47.18, 9.54) did work but the agent abandoned it prematurely.

## Proposed Fix

### Skill update (net.osmand SKILL.md)
1. **Prioritize coordinate search for small/obscure villages.** Change the marker flow to: search by name first; if the only result is the country-level entry (not the village), immediately fall back to coordinates rather than trusting SHOW ON MAP.
2. **Add explicit "verify after SHOW ON MAP" step.** After SHOW ON MAP, the agent must tap the map center to check the "What's here" sheet. If the location name doesn't match the target, immediately fall back to coordinates.
3. **Remove the misleading claim** that "SHOW ON MAP geocodes correctly" — it only works with online geocoding.

### Suggested skill diff
```
## Adding a Location Marker
1. Open OsmAnd -> tap Search
2. Type the location name (e.g., "Planken, Liechtenstein")
3. Check search results:
   - If an **exact village/city match** appears, tap it.
   - If only a country-level result appears (e.g., "Liechtenstein" without "Planken"):
     **use coordinates instead** (e.g., "47.18, 9.54"). Do NOT trust SHOW ON MAP
     for the text query — offline geocoding often resolves to the wrong place.
4. After tapping a result or SHOW ON MAP, **verify the location**:
   tap the map center to open "What's here". If the place name is wrong,
   go back and use coordinates.
5. Once verified, scroll action buttons horizontally and tap "Marker" (flag icon).
```

### Alternative: hardcode known coordinates
For eval reliability, pre-populate the skill with coordinates for known eval locations. This is fragile for generalization but reliable for the specific eval task.
