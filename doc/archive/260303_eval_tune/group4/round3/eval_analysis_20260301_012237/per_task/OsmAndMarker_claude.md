# OsmAndMarker - Round 3 Analysis

## Task
Add a location marker for Planken, Liechtenstein in OsmAnd maps app.

## Result
- Score: 0.0 (FAIL)
- Turns: 30/30
- Stop reason: MaxTurnsReached
- Duration: 368s
- Perception mode: hybrid (screenshot + a11y tree)

## Agent Behavior Summary
1. Opened OsmAnd, tapped Search (turns 1-2)
2. Typed "Planken, Liechtenstein" into search (turn 3)
3. Got "INCREASE SEARCH RADIUS" - clicked it (turn 4)
4. Clicked "Liechtenstein" country result (turn 5)
5. Spent turns 6-30 trying various approaches:
   - Clicking search results, SHOW ON MAP buttons
   - Pressing back to reset
   - Clicking on map coordinates
   - Navigating between search and map views
6. Never successfully added a marker

## Root Cause Analysis
**OsmAnd poor a11y tree + complex UI**: Even with hybrid mode:
1. The a11y tree only exposed ~6 elements (search bar, a couple of buttons, status bar items)
2. Map canvas has no a11y nodes - markers must be added by long-pressing on the map at specific coordinates
3. Search found "Liechtenstein" but not "Planken" specifically (offline Maps may not have detailed POI data)
4. The agent couldn't navigate the search UI to:
   - First search for and navigate to Planken area
   - Then long-press on the map to add a marker
   - Then save the marker

**Hybrid mode insufficient**: Screenshots were attached but the LLM couldn't effectively interpret the OsmAnd map UI to determine where to click/long-press.

## Key Observations
- OsmAnd search didn't find "Planken" - only "Liechtenstein" (country level)
- Even "SHOW ON MAP" was visible but clicking it didn't help the agent proceed
- Map interactions (pan, zoom, long-press to add marker) are not accessible via a11y tree
- The agent looped between search and map views for 25+ turns without progress
- Strategy-pivot prompt didn't trigger effectively

## Recommendations
- OsmAnd tasks may be beyond current capability without:
  - Detailed map element accessibility
  - Coordinate-based long-press with precise lat/lon mapping
  - Better screenshot interpretation for map UIs
- Consider accepting these as capability-blocked
- If keeping: Add tip about OsmAnd marker workflow (long-press on map -> Add favorite/marker)
