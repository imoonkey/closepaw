# OsmAndMarker -- Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 30 | **Reason**: MaxTurnsReached | **Duration**: 222s
**Goal**: Add a location marker for Planken, Liechtenstein in the OsmAnd maps app.

## Root Cause
**Category**: Planning / Loop -- No verification, no completion
**Summary**: The agent correctly opened OsmAnd, searched for "Planken, Liechtenstein", found only "Liechtenstein" (country) as a result (Planken was not indexed in the offline search), navigated to it, and clicked the "Marker" button at the location detail bottom sheet -- which likely added a marker. However, the agent never verified whether the marker was actually created, never called `complete_task`, and instead looped back to search and re-attempt the same sequence two more times across 30 turns. The core failure is a **search-navigate-marker-back loop** repeated 3 times with no progress detection.

## Turn-by-Turn Analysis

### Turn 1: App Launch
- **Action**: `open_app { app_name: "OsmAnd" }`
- **Result**: Success. OsmAnd begins loading (only status bar in a11y tree, 5 elements).
- **Assessment**: Correct first step.

### Turn 2: Wait for Load
- **Action**: `wait { duration_ms: 2000 }`
- **Result**: OsmAnd still loading (5 elements). Map not yet rendered in a11y tree.
- **Assessment**: Reasonable. OsmAnd init is slow.

### Turn 3: Open Search
- **Action**: `mobile_action { action: "click", element_index: 6 }` -- clicks "Search" button
- **Result**: Map fully loaded (13 elements: Map, Configure map, Search, Zoom in/out, Back to menu, Route, etc.). Successfully opened the search interface.
- **Assessment**: Good -- identified the Search button correctly from the OsmAnd toolbar.

### Turn 4: Type Search Query
- **Action**: `mobile_action { action: "type", input_text: "Planken, Liechtenstein", element_index: 5 }`
- **Result**: Search field populated. Results appear: "SHOW ON MAP" button, "Liechtenstein" (Country, 3296 mi), and "INCREASE SEARCH RADIUS". Critically, **Planken itself was not found** -- only the country "Liechtenstein" appeared.
- **Assessment**: The search term was correct, but OsmAnd's offline search database did not contain "Planken" as a distinct POI/settlement. The agent should have recognized this limitation.

### Turn 5: Scroll for More Results
- **Action**: `mobile_action { action: "scroll", direction: "down", element_index: 12 }`
- **Result**: Screen unchanged (same elements visible).
- **Assessment**: Reasonable attempt to find Planken in the results, but there were no additional results to scroll to.

### Turn 6: Click Liechtenstein Result
- **Action**: `mobile_action { action: "click", element_index: 9 }` -- clicks "Liechtenstein" result
- **Result**: Map navigates to Liechtenstein. Bottom sheet appears showing: "Liechtenstein", "Country", "0.39 mi", with action buttons: **Add**, **Marker**, **Share**, **Actions**, plus **DETAILS** and **NAVIGATION**.
- **Assessment**: Good -- this is the closest match to Planken. The map now shows the Liechtenstein area which contains Planken.

### Turn 7: Click Marker Button (FIRST ATTEMPT)
- **Action**: `mobile_action { action: "click", element_index: 14 }` -- clicks "Marker" button
- **Result**: Success (tool reports success). Post-action screen drops to 5 elements (bare map, status bar only). The bottom sheet with location details is dismissed.
- **Assessment**: **This likely added a map marker for Liechtenstein.** OsmAnd's "Marker" button adds a map marker at the selected location. The bottom sheet closing is the expected behavior after adding a marker. However, the agent did not recognize this as success.

### Turns 8-9: Lost Context, Navigate Back
- **Turn 8**: `mobile_action { action: "click", element_index: 0 }` -- clicks on bare map area. Thought: "The map view is displayed. I need to find the search button..." Screen still shows only 5 elements.
- **Turn 9**: `system_button { button: "back" }` -- presses back. Thought: "Go back to escape the current map view and return to search functionality." Returns to search screen (14 elements).
- **Assessment**: The agent failed to recognize that clicking "Marker" in Turn 7 had already added the marker. After the bottom sheet closed, the OsmAnd toolbar buttons disappeared from the a11y tree (possibly overlaid by the map), causing the agent to lose orientation. Instead of looking for success indicators or calling `complete_task`, it retreated to search.

### Turn 10: Click SHOW ON MAP
- **Action**: `mobile_action { action: "click", element_index: 7 }` -- clicks "SHOW ON MAP"
- **Result**: Map view with location details panel appears again (same as Turn 6).
- **Assessment**: Redundant -- restarting the same navigation to Liechtenstein that was already completed.

### Turn 11: Long-Press Map
- **Action**: `mobile_action { action: "long_press", element_index: 0, duration_ms: 1500 }` -- long-press on map center
- **Result**: Screen shows only 5 elements again (bare map).
- **Assessment**: The agent tried a different approach (long-press to create a marker at a specific point) but if the map details panel was showing, the long-press may have dismissed it instead of placing a marker.

### Turn 12: Back Again
- **Action**: `system_button { button: "back" }` -- presses back to return to search.
- **Assessment**: Retreating to search again. The same loop restarting.

### Turn 13: POLICY_REJECTION
- **Action**: Agent attempted `mobile_action { action: "click", element_index: 7 }` ("SHOW ON MAP")
- **Result**: **POLICY_REJECTION** -- the anti-loop system detected the repeated click on SHOW ON MAP and blocked the action.
- **Assessment**: The anti-loop detection correctly identified this as a repetitive action. Unfortunately, this did not cause the agent to fundamentally change strategy.

### Turns 14-17: Second Search Attempt (simplified query)
- **Turn 14**: Click element 6 (Clear button), clearing the search field.
- **Turn 15**: Click element 5 (search field to focus it).
- **Turn 16**: Type "Planken" (just the town name without country).
- **Turn 17**: Click element 11 ("INCREASE SEARCH RADIUS") hoping to find Planken with expanded search.
- **Assessment**: Reasonable strategy variation -- trying just "Planken" instead of the full "Planken, Liechtenstein", and expanding the search radius. However, Planken still did not appear in results.

### Turns 18-22: Third Strategy -- Address and Coordinates Search
- **Turn 18**: Clear current search.
- **Turn 19**: Click element 8 (Address tab) to try address-based search.
- **Turn 20**: Click element 15 ("COORDINATES SEARCH") to try searching by GPS coordinates (agent thought mentions 47.1667 N, 9.5167 E).
- **Turn 21**: Click element 12 (a search result for Liechtenstein from coordinates search).
- **Turn 22**: Click element 14 ("Marker" button) again -- **SECOND marker click on Liechtenstein.**
- **Assessment**: The agent demonstrated good cognitive flexibility by trying different search strategies (address, coordinates). However, it never actually typed the coordinates into a field -- it just clicked the coordinates search option, which showed existing Liechtenstein results. Clicking "Marker" a second time is purely redundant.

### Turns 23-30: Third Loop Iteration (Same Pattern Repeats)
- **Turn 23**: System back button to escape map.
- **Turn 24**: Click search field to focus it.
- **Turn 25**: Type "Planken" into search field again.
- **Turn 26**: Click "INCREASE SEARCH RADIUS" again.
- **Turn 27**: Clear search again.
- **Turn 28**: Click "COORDINATES SEARCH" again.
- **Turn 29**: Click search result (Liechtenstein via coordinates) again.
- **Turn 30**: Click "Marker" (index 14) again -- **THIRD marker click on Liechtenstein.**
- **Assessment**: Exact repetition of the same pattern from Turns 14-22. The agent exhausted all 30 turns without ever calling `complete_task`.

## What Went Wrong

1. **No success verification after "Marker" click**: The agent clicked the "Marker" button successfully in Turn 7 (and again in Turns 22 and 30), but never verified whether a marker was actually placed. After the bottom sheet closed, the bare map showed no obvious marker indicator in the a11y tree, so the agent assumed the action failed. A better approach would have been to check the map for marker indicators, use shell commands to inspect OsmAnd's marker database, or simply accept the successful tool result as confirmation and call `complete_task`.

2. **Search limitation not adapted to**: OsmAnd's offline search did not index "Planken" as a searchable location. The agent correctly tried alternative approaches (simplified term, address search, coordinates search) but never succeeded in narrowing to Planken specifically. The agent knows Planken's approximate coordinates (47.1667 N, 9.5167 E) based on its thought text, but never actually typed them into a coordinate input field.

3. **Never called `complete_task`**: Even after successfully clicking "Marker" (which likely added a marker at the Liechtenstein location), the agent never declared the task complete. This is the direct cause of the MaxTurnsReached failure.

4. **3x loop without escape**: The agent repeated the same search-navigate-marker-back sequence three times. Despite the anti-loop POLICY_REJECTION in Turn 13, the agent only made surface-level strategy modifications (different search terms) before falling into the same cycle.

5. **Bare map a11y tree blindspot**: When the OsmAnd map was in a "clean" state (no panels or bottom sheets), the a11y tree showed only 5 elements (status bar). The agent could not navigate the map controls because they were not in the a11y tree at that point. This repeatedly caused the agent to press "back" to escape to the search screen.

## Recommendations

1. **Marker verification heuristic**: After clicking a "Marker" or "Add" button in OsmAnd and seeing the bottom sheet dismiss, treat this as a likely success signal. The agent should then verify (e.g., go to map markers list, or use shell to check OsmAnd files) or call `complete_task` with a qualified answer.

2. **Coordinate entry for precise locations**: When text search fails to find a specific small town, the agent should directly type GPS coordinates into OsmAnd's coordinate search field rather than just clicking an existing result. The agent demonstrated knowledge of Planken's coordinates (47.1667 N, 9.5167 E) but never entered them.

3. **Loop detection at the cognitive level**: The anti-loop system blocked one action, but the agent continued with minor variations of the same failing strategy. A stronger self-assessment ("I have already tried this approach and it did not produce new results") would help break the cycle.

4. **Graceful degradation**: If the exact location cannot be found but a close match is available (Liechtenstein center, which is near Planken), the agent should complete the task with a caveat rather than spinning indefinitely.

## Efficiency Notes
- 30 turns used with zero progress beyond Turn 7. The agent was effectively done at Turn 7 (marker added for Liechtenstein) but wasted 23 turns trying to refine/repeat.
- 1 POLICY_REJECTION at Turn 13 (anti-loop on repeated SHOW ON MAP clicks).
- 0 tool failures -- all tools executed successfully, highlighting that the failure is purely cognitive (planning/verification), not mechanical.
- The ideal solution would be approximately 7-8 turns: open app, search, select result, click Marker, verify, complete_task.
