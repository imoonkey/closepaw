---
name: net.osmand
description: App-specific guidance for OsmAnd maps.
---

# OsmAnd Skill

## Adding a Location Marker
1. Open OsmAnd → tap Search (magnifying glass)
2. Type the location name (e.g., "Planken, Liechtenstein")
3. Look for an **exact locality match** in the results list. If you see the village/town name specifically, tap it.
4. **If only generic results appear** (e.g., "Liechtenstein" country, or "SHOW ON MAP" without a village match): the offline geocoder may be unreliable. Do NOT use these. Instead, go back and search using the location name alone (e.g., just "Planken") or look for it in the list by scrolling.
5. After the map centers on the correct location with a pin, **tap the pin** to open the context sheet.
6. In the context sheet, scroll action buttons horizontally and tap **"Marker"** (flag icon). Do NOT tap "Share" or "Direction".
7. **Verify**: after tapping Marker, check that the context sheet mentions the correct location name, not a country or wrong place.

## Common Mistakes
- Do NOT pick a country-level result (e.g., "Liechtenstein") — the marker will be at the country centroid, far from the village.
- Do NOT trust "SHOW ON MAP" blindly — in offline mode it may geocode to the wrong continent. Always verify the map shows the correct region.
- If you can't find the Marker button, scroll the action buttons horizontally.

## Saving GPX Tracks
When asked to save a track or route as GPX with specific waypoints:
1. Open OsmAnd → tap the **hamburger menu** (☰) at top-left
2. Select **"Plan a route"** (NOT "Directions" or "Navigation")
3. Add waypoints by searching for each location name and tapping on the map to add the point
4. After all waypoints are added in the correct order, tap **Save** (disk icon or "Save" button)
5. The track will be saved as a `.gpx` file in OsmAnd's tracks directory
6. **Important**: "Directions/Navigation" does NOT save GPX files — only "Plan a route" does.

## Searching for Locations
- Use the search bar for location names.
- All Liechtenstein localities are available in the offline map data.
- If a specific village doesn't appear, try just the village name without the country.
