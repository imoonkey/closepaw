---
name: app-osmand
description: App-specific guidance for OsmAnd maps.
metadata:
  package: net.osmand
---

## CRITICAL — Use Address tab for search
- The default search is proximity-based and often geocodes to the wrong location.
- Switch to the "Address" tab for structured offline lookup. If Address fails, use "COORDINATES SEARCH".
- NEVER use "SHOW ON MAP" from the general search tab.

## Markers
- After finding a location via Address tab, tap the map pin → scroll action buttons horizontally → tap "Marker" (flag icon).
- Verify the context sheet shows the correct location name.

## GPX Tracks
- Use "Plan a route" (hamburger menu) to create saveable GPX tracks. "Directions" and "Navigation" do NOT save GPX files.
- Add waypoints by searching within Plan a route via Address tab.

## Safety

**DANGEROUS -- ask user before:**
- Starting turn-by-turn navigation (battery/data intensive, may lock screen)
- Deleting saved markers, favorites, or GPX tracks
- Downloading offline maps (large data usage)

**SAFE -- proceed normally:**
- Searching for locations and addresses
- Viewing the map, placing markers
- Planning routes and viewing GPX tracks
