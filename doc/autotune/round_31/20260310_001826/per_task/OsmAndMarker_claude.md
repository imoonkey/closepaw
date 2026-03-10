# OsmAndMarker Trace Analysis (R32 / 20260310_001826)

**Task**: Add a location marker for Planken, Liechtenstein in the OsmAnd maps app.
**Result**: score=0.0, 9 turns, agent_completion_reason=GoalAchieved (false positive)
**Model**: gpt-5.4

## Turn-by-Turn Summary

| Turn | Action | Result |
|------|--------|--------|
| 1 | `open_app("OsmAnd")` | OsmAnd launched |
| 2 | `click(element_index=14)` | Dismissed initial prompt |
| 3 | `click(element_index=7)` | Opened search (magnifying glass) |
| 4 | `type("Planken, Liechtenstein")` | Typed query into search field |
| 5 | `click("SHOW ON MAP")` | Map shown but no context sheet appeared |
| 6 | `system_button(back)` | Returned to search results |
| 7 | `click("Liechtenstein")` | Opened context sheet for **Liechtenstein (country)** |
| 8 | `click(element_index=15, "Marker")` | Placed marker on Liechtenstein country centroid |
| 9 | `complete_task(success)` | Claimed success |

## Root Cause

**Wrong search result selected.** After typing "Planken, Liechtenstein", the search returned only two actionable items:
- "SHOW ON MAP" (generic query)
- "Liechtenstein" (country-level result, labeled "Country", 3296 mi away)

No village-level "Planken" result appeared. The agent clicked "SHOW ON MAP" first (turn 5), which showed the map but no context sheet. After pressing back, it clicked "Liechtenstein" (the country result) instead, which opened a context sheet showing "Liechtenstein / Country". The agent tapped "Marker" on this country-level result.

**Why the evaluator failed it:** `is_successful()` reads the `map_markers_db` SQLite database and checks if any marker's (lat, lon) is within 0.001 degrees (~100m) of Planken's expected coordinates (47.1858882, 9.5452201). The marker was placed at Liechtenstein's country centroid, which is far from Planken village.

**Why the agent falsely claimed success:** The agent saw the context sheet disappear after tapping "Marker" and assumed the marker was placed correctly. It had no way to verify the marker's actual coordinates or that it was on the right location.

## Key Observation

The "SHOW ON MAP" action in turn 5 likely did center the map near the search query area, but OsmAnd's offline search for "Planken, Liechtenstein" failed to return a village-level POI. Only the country "Liechtenstein" appeared as a named result. The skill doc says to "select the correct search result from the list" but there was no correct result to select.

## Proposed Fix

Update the OsmAnd app skill (`app/src/main/assets/app_skills/net.osmand/SKILL.md`) with a fallback strategy:

1. **After "SHOW ON MAP"**: If "SHOW ON MAP" centers the map on the right area but no context sheet appears, the agent should **long-press the map center** (or tap the map pin if visible) to open a context sheet for that specific point, then tap "Marker". The "SHOW ON MAP" action does geocode correctly -- the issue is that the agent navigated away instead of using the resulting map position.

2. **Alternative**: If no village-level result appears in search, the agent should still use "SHOW ON MAP" and then interact with the resulting map point. After "SHOW ON MAP" centers the map, the agent may need to tap the pin/point on the map to bring up the context sheet, or use "INCREASE SEARCH RADIUS" to find more specific results.

3. **Verification hint**: After placing a marker, the agent should check for a visual flag icon on the map before claiming success.

**Note**: The skill doc currently warns against long-pressing the map ("may place it at wrong coordinates"), but "SHOW ON MAP" already geocodes to the right area. The real risk is navigating away from a correctly-positioned map and selecting a wrong-level result instead.
