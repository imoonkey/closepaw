---
name: net.osmand
description: App-specific guidance for OsmAnd maps.
---

# OsmAnd Skill

## Searching for Locations (CRITICAL)
The default search tab is proximity-based — it only finds POIs near the current map center. **If searching for a village or town and getting no results or wrong results:**

1. **Switch to the "Address" tab** in the search screen. Address search does structured offline lookup across ALL downloaded map data regardless of where the map is currently centered.
2. In the Address tab, type the location name (e.g., "Planken"). It should find Liechtenstein localities directly.
3. **If Address tab also fails**, use "COORDINATES SEARCH" (also in the search screen) and enter coordinates directly.

**NEVER use "SHOW ON MAP" from the general search tab** — it often geocodes to the wrong continent in offline mode.

## Adding a Location Marker
1. Open OsmAnd → tap Search (magnifying glass)
2. **Switch to the "Address" tab** and type the location name
3. Tap the correct result (look for the village/town name, NOT a country-level result)
4. The map will center on the location. **Tap the map pin** to open the context sheet.
5. In the context sheet, scroll action buttons horizontally and tap **"Marker"** (flag icon). Do NOT tap "Share" or "Direction".
6. **Verify**: the context sheet should mention the village name, not just a country.

## Saving GPX Tracks
When asked to save a track or route as GPX with specific waypoints:
1. Open OsmAnd → tap the **hamburger menu** (☰) at top-left
2. Select **"Plan a route"** (NOT "Directions" or "Navigation")
3. For each waypoint: use the search within Plan a route, **switch to Address tab**, find the locality, tap to add as waypoint
4. After all waypoints are added in the correct order, tap **Save**
5. **Important**: "Directions/Navigation" does NOT save GPX files — only "Plan a route" does.
