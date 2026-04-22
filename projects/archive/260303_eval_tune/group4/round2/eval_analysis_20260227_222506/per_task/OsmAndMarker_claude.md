# OsmAndMarker — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 30 (max) | **Reason**: NavigationFailure + FalseCompletion | **Duration**: ~340s
**Goal**: Add a location marker for Planken, Liechtenstein in the OsmAnd maps app.

## Root Cause
**Category**: NavigationFailure
**Summary**: Agent searched for "Planken, Liechtenstein" but only found "Liechtenstein" (country) — not the specific town. It then tried to add a marker but the marker was placed at the wrong location (country-level Liechtenstein, not Planken specifically). The agent also struggled with OsmAnd's UI navigation — repeatedly clicking on areas where buttons "should be" using x/y coordinates but failing due to the map-heavy UI having minimal a11y-exposed controls.

## Turn-by-Turn Analysis

### Turn 1
- **Action**: `open_app(app_name="OsmAnd")`
- **Result**: Success
- **Assessment**: Good

### Turn 2
- **Action**: `click(elem=6)` — Search button
- **Assessment**: Good

### Turn 3
- **Action**: `type(input_text="Planken, Liechtenstein", elem=5)`
- **Result**: Typed search query
- **Assessment**: Good

### Turn 4
- **Action**: `click(elem=9)` — clicked "Liechtenstein" result
- **Result**: Navigated to Liechtenstein (country level, not Planken)
- **Assessment**: Bad — should have looked for more specific results

### Turn 5
- **Action**: `click(elem=14)` — Marker button
- **Result**: Opened marker creation
- **Assessment**: Premature — at wrong location

### Turns 6-17 (Stuck in navigation)
- **Actions**: Various attempts: click map, long_press map, click coordinates (x,y), try to access search
- **Result**: Agent kept hitting "Configure map" or "Back to map" buttons instead of search
- **Assessment**: Bad — OsmAnd's map UI is opaque to a11y, agent clicking blindly by coordinates

### Turn 18
- **Action**: `click(elem=9)` — Liechtenstein result
- **Result**: Selected Liechtenstein again
- **Assessment**: Still at country level

### Turn 19
- **Action**: `click(elem=19)` — Marker button
- **Result**: Opened marker dialog
- **Assessment**: At wrong location

### Turn 20
- **Action**: `type(input_text="Planken, Liechtenstein", elem=9, clear=true)`
- **Result**: Named the marker
- **Assessment**: Good marker name but wrong location

### Turn 21
- **Action**: `click(elem=12)` — SAVE
- **Result**: Saved marker at Liechtenstein (wrong location)
- **Assessment**: Bad — marker at wrong coordinates

### Turns 22-29 (Verification attempts)
- **Actions**: Various clicks trying to verify marker placement
- **Assessment**: Couldn't effectively verify

### Turn 30
- **Action**: `complete_task(status="success")`
- **Assessment**: Bad — false completion, marker at wrong location

## Failure Points
1. **Search didn't find Planken specifically** — only found "Liechtenstein" country. OsmAnd search may need different query format
2. **Marker placed at wrong location** — country center, not the specific town
3. **OsmAnd maps UI is largely invisible to a11y** — map elements, zoom controls, and many buttons not exposed
4. **Excessive coordinate-based clicking** — agent guessed button positions by (x,y) which is unreliable

## What Worked
- Found OsmAnd's search function
- Created and saved a marker with correct name

## What Didn't Work
- Search specificity (Planken not found)
- Marker location accuracy
- Map UI navigation via a11y

## Suggested Fix
- Add OsmAnd-specific guidance: "When searching for a specific place in OsmAnd, try searching for just the place name (e.g., 'Planken') rather than 'Planken, Liechtenstein'. If no results, try alternative spellings."
- Consider: OsmAnd tasks may fundamentally require hybrid/screenshot mode for the map UI
