# OsmAndTrack -- Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 30 | **Reason**: MaxTurnsReached | **Duration**: 248s
**Goal**: Save a track with waypoints Schoenberg, Liechtenstein, Triesen, Liechtenstein, Bendern, Liechtenstein in the OsmAnd maps app in the same order as listed.

## Root Cause
**Category**: Planning / App-Knowledge Gap + Perception Degradation
**Summary**: The agent failed because it lacked knowledge of OsmAnd's track-creation workflow and cycled through two incorrect approaches (Navigation route planning, then Plan a route) without completing either. Contributing factors: (1) the a11y tree for OsmAnd's "Plan a route" canvas was nearly opaque (only 5 elements -- just the status bar), leaving the agent with almost no actionable UI affordances; (2) the device had no internet connectivity, severely limiting OsmAnd's place-name search; (3) the agent burned ~10 turns on back-navigation and repeated searches without making progress. The task was likely impossible under these conditions (offline + opaque a11y tree).

## Turn-by-Turn Analysis

### Phase 1: App Launch (Turns 1-2)
- **Turn 1**: `open_app { app_name: "OsmAnd" }` -- Successfully launched OsmAnd. Post-action screen shows only status bar (app still loading splash screen).
- **Turn 2**: `wait {}` -- Agent correctly waited for OsmAnd to finish loading. Post-action screen still shows minimal elements (5 -- status bar + a loading RelativeLayout covering the full screen).
- **Assessment**: Reasonable start. OsmAnd is a heavy app that takes time to load.

### Phase 2: First Wrong Turn -- Route Button (Turns 3-6)
- **Turn 3**: `click { element_index: 9 }` -- Agent thought: "Click the Route button to begin creating a track." However, this appears to have been a premature interaction with the still-loading map. Post-action shows only status bar elements (6 elements, no meaningful UI exposed).
- **Turn 4**: `wait {}` -- Waited again for UI to stabilize. Still only status bar visible.
- **Turn 5**: `click { element_index: 5 }` -- "Click the main content area to see route/tracks options." Clicked on something but the screen remained minimal.
- **Turn 6**: `system_button { button: "back" }` -- "Press back to exit this dialog state." Agent retreated after getting no useful response from the UI.
- **Assessment**: 4 turns wasted on a premature interaction pattern. The agent interacted with UI that was still loading/rendering and got no useful feedback from the a11y tree. The "Route" button actually triggered OsmAnd's Navigation/Directions feature, not track creation.

### Phase 3: Navigation Route Planning (Wrong Feature) (Turns 7-12)
- **Turn 7**: `click { element_index: 16 }` -- "Click the From field to enter the first waypoint." The agent is now on OsmAnd's Navigation screen (From/To fields, Driving/Cycling/Walking mode selectors). This is the **wrong feature** -- Navigation plans driving directions between A and B, not "save a track with waypoints."
- **Turn 8**: `click { element_index: 7 }` -- "Click the Search option to enter waypoint locations by name." Opens the OsmAnd search UI.
- **Turn 9**: `type { input_text: "Schoenberg, Liechtenstein", element_index: 5 }` -- Typed first waypoint into search. Post-action shows the EditText with the query but only 5 elements visible (search loaded but results may not have rendered or returned nothing due to **no internet**).
- **Turn 10**: `click { element_index: 7 }` -- "Click on the Liechtenstein search result." Agent saw some result and clicked it. Returned to a screen with 12 elements.
- **Turn 11**: `click { element_index: 16 }` -- "Click on the From field to modify the starting point." Back on the route planning screen, trying to set the From field again.
- **Turn 12**: `system_button { button: "back" }` -- "Press back to exit the route dialog and find track creation feature." Agent realized this is the wrong feature.
- **Assessment**: 6 turns burned on the Navigation feature. The agent correctly identified it was in the wrong place by turn 12, but the damage was done. The search for "Schoenberg, Liechtenstein" was partially functional (some results appeared) but the agent could not reliably select the right one given the sparse a11y tree.

### Phase 4: Menu Navigation -- Finding the Right Feature (Turns 13-16)
- **Turn 13**: `click { element_index: 34 }` -- "Click Cancel to exit the route dialog." Dismissed the Navigation dialog.
- **Turn 14**: `click { element_index: 8 }` -- "Click Back to menu." Navigated to OsmAnd's main drawer menu. Post-action a11y tree shows the full menu: Browse map, Configure profile, Map markers, My Places, Search, Navigation, Configure map, Download maps, Travel guides, **Plan a route**, Configure screen, Plugins, Settings, Help.
- **Turn 15**: `click { element_index: 24 }` -- "Click Plan a route to access route/track creation functionality." **Correct identification** of the right feature. OsmAnd's "Plan a route" is indeed the feature for creating GPX tracks with waypoints.
- **Turn 16**: `click { element_index: 7 }` -- "Click Create new route to start creating a track with waypoints." Created a new route in the route planner.
- **Assessment**: Good pivot. The agent found the correct feature ("Plan a route") after abandoning the Navigation approach. But the next phase reveals why this still fails.

### Phase 5: Plan-a-Route Canvas -- Opaque A11y Tree (Turns 17-18)
- **Turn 17**: `click { element_index: 0 }` -- "Click on the map area to reveal map controls." The Plan a route canvas rendered with **only 5 elements** -- all status bar items plus the Map view. The route planning toolbar, points editor, and all functional buttons were **invisible to the a11y service**. The agent clicked the map but nothing changed.
- **Turn 18**: `long_press { x: 540, y: 1168, duration_ms: 1500 }` -- "Long press on the map to bring up context menu for adding waypoints." Also resulted in a screen with only 5 elements. The long-press on OsmAnd's map canvas in Plan a route mode should add a point, but the agent could not see any feedback via the a11y tree.
- **Assessment**: This is the critical failure point. OsmAnd's Plan a route screen uses a custom OpenGL map canvas that does not expose its UI controls (add point, search, undo, save buttons in the bottom toolbar) via the accessibility service. The agent was effectively blind on this screen. Even if the long-press added a point, the agent had no way to verify it, search for specific named locations, or proceed with the workflow.

### Phase 6: Search Detour (Turns 19-24)
- **Turn 19**: `system_button { button: "back" }` -- "Press back to exit the blank map screen." Retreated from the opaque canvas.
- **Turn 20**: `click { element_index: 6 }` -- "Click Search button." Found a Search button after going back and tried a different approach: search for the location first, then try to create a track.
- **Turn 21**: `type { input_text: "Schoenberg, Liechtenstein", element_index: 5 }` -- Second attempt to search for the first waypoint.
- **Turn 22**: `click { element_index: 9 }` -- "Click on Liechtenstein country result." Found a result for Liechtenstein and clicked it.
- **Turn 23**: `click { element_index: 16 }` -- "Click Actions to see available options for creating tracks or waypoints." Found an Actions button on the search result detail screen.
- **Turn 24**: `click { text: "CLOSE" }` -- "Close this dialog and try a different approach." Whatever the Actions dialog showed, it did not contain a "save as track waypoint" option.
- **Assessment**: The agent tried a creative alternative approach (find location via search, then add to track), but OsmAnd's search result Actions did not provide a direct "add to track" workflow. This approach was a dead end.

### Phase 7: Disoriented Navigation (Turns 25-30)
- **Turns 25-27**: Three consecutive `system_button { button: "back" }` calls -- "Press back to exit search/location details and return to main." Agent was retreating through multiple levels of OsmAnd's UI hierarchy. Each back press consumed a turn.
- **Turn 28**: `click { element_index: 8 }` -- "Click Back to menu." Returned to OsmAnd drawer menu again.
- **Turn 29**: `click { element_index: 12 }` -- "Click My Places." Entered My Places section. This is a third distinct approach: trying to find track management in My Places.
- **Turn 30**: `click { element_index: 8 }` -- "Click TRACKS tab." Arrived at the TRACKS tab showing "You don't have track files." with an Import button. The agent hit the 30-turn limit here.
- **Assessment**: The final 6 turns were spent navigating to My Places > TRACKS, which is where existing tracks are listed but does not provide a direct way to create a new track with named waypoints. The session ended without any progress toward the actual goal.

## What Went Wrong

1. **Opaque A11y Tree on Plan a Route (Primary Blocker)**: OsmAnd's "Plan a route" screen rendered as an OpenGL map canvas with virtually no a11y elements. The toolbar at the bottom (with Add point, Search, Profile, Undo, Save buttons) was completely invisible. This made it impossible for the agent to interact with the correct feature even when it found it. **This is a platform limitation, not an agent cognition error.**

2. **No Internet Connectivity**: The device showed "No internet" throughout. OsmAnd's place-name search relies on online geocoding (Nominatim) or downloaded offline vector maps. With no internet and potentially incomplete offline data, searching for specific places like "Schoenberg, Liechtenstein" may not return useful results.

3. **Wrong Feature First (Navigation vs. Tracks)**: The agent initially confused OsmAnd's Navigation (From/To route planning for driving directions) with track creation. This burned 6 turns (7-12). The agent did correctly self-diagnose and pivot, but the turn budget was already significantly depleted.

4. **Three Failed Approaches, No Convergence**: The agent tried three distinct strategies (Navigation routes, Plan a route, My Places > Tracks) and failed at all three. Rather than iterating deeper on the correct approach (Plan a route), it abandoned it after 2 turns due to the opaque a11y tree.

5. **Excessive Back-Navigation**: Turns 25-27 were three consecutive back presses that could potentially have been replaced by a single `open_app` to reset to a known state, saving 2 turns.

## Recommendations

### System-Level (Infrastructure)
- **A11y tree enrichment for map canvases**: OsmAnd (and likely other map apps) use custom OpenGL rendering that does not expose toolbar buttons via the accessibility service. Consider screenshot-based visual grounding as a fallback when the a11y tree has fewer than ~10 elements.
- **Network verification**: For tasks that require online services (geocoding, place search), the eval harness should verify internet connectivity before starting. If offline, the task should be skipped or the eval should note it as an environment issue.

### Prompt / Context Engineering
- **App-specific workflow hints**: For complex apps like OsmAnd, the system prompt could include brief workflow guidance: "To create a GPX track in OsmAnd: Menu > Plan a route > Create new route > use the bottom toolbar Search button to add named waypoints."
- **Turn budget awareness**: When the agent has used >50% of turns without making substantive progress toward the goal, the system should prompt early strategy abandonment or switch to a `complete_task { status: "failed" }` to avoid wasting remaining turns on low-probability recovery.

### Agent Cognition
- **Faster feature identification**: The agent should use the main menu/drawer earlier to survey all available features before committing to one. It spent turns 3-6 blindly interacting with a loading screen.
- **Back-press consolidation**: Three consecutive back presses (turns 25-27) is a pattern that should be replaced by re-launching the app or going directly to the target via the drawer menu.
- **Dead-end detection**: When a screen shows very few a11y elements (under ~8) after an action that should reveal rich UI, the agent should immediately recognize it as a perception limitation and try a different approach (e.g., coordinate-based interaction or app restart) rather than continuing to click blindly.

## Verdict

This task was likely **impossible to complete** given the combination of (1) OsmAnd's Plan a route screen being invisible to the a11y service and (2) no internet connectivity preventing place-name search. Even an optimal agent would struggle here. The agent did demonstrate reasonable meta-cognition by pivoting between approaches, but it lacked the app-specific knowledge and perception capabilities needed for this task.

**Difficulty**: Very Hard (complex multi-step workflow in a map app with opaque a11y + offline constraints)
**Fixability**: Low via prompt engineering alone; requires screenshot-based perception or app-specific scripted shortcuts.
