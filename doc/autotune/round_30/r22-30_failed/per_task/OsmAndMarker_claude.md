# OsmAndMarker — Failure Analysis

## Scripted Success Criteria

From `osmand.py`, class `OsmAndMarker` (line 279):

```python
class OsmAndMarker(_OsmTaskEval, SQLiteApp):
    db_path = '/data/data/net.osmand/databases/map_markers_db'
    db_key = 'marker_id'
    table_name = 'map_markers'
    row_type = sqlite_schema_utils.OsmAndMapMarker

    def is_successful(self, env):
        for row in self.list_rows(env):
            if _marker_matches_location(row, self.params['location']):
                return super().is_successful(env)
        return 0.0
```

Validation reads the SQLite database at `/data/data/net.osmand/databases/map_markers_db`, table `map_markers`. It iterates all rows and checks if any row's `(marker_lat, marker_lon)` is within 0.001 degrees (~50-100m) of the target location's known coordinates. The target is `Planken, Liechtenstein` which maps to `(47.1858882, 9.5452201)` via `_PRELOADED_MAP_LOCATIONS`.

`OsmAndMarker` does NOT override `initialize_task` — it inherits from both `_OsmTaskEval` and `SQLiteApp`. `SQLiteApp.initialize_task` clears the `map_markers` table. No custom setup beyond that.

## Task Setup

- Goal: "Add a location marker for Planken, Liechtenstein in the OsmAnd maps app."
- Seed: 124863876
- The `map_markers` table is cleared before the task starts.
- **Infra issue (attempt 0)**: `initialize_task` failed with `no such table: map_markers`. The `map_markers_db` database did not yet contain the `map_markers` table — OsmAnd likely creates it lazily on first marker use. Attempt 0 was marked `infra_failure`.
- **Attempt 1** proceeded (bridge_status=completed), but the same table-missing issue resurfaced at `tear_down` (`exception: tear_down=no such table: map_markers`), confirming the agent never successfully created a marker.

## Agent's Last Attempt (R27, run 20260309_204943)

27 turns, 140.9 seconds, 1 tool failure.

**Turn-by-turn summary:**

1. **Turn 1**: `open_app` OsmAnd
2. **Turn 2**: Click Search button
3. **Turn 3**: Type "Planken, Liechtenstein" into search field
4. **Turn 4**: Click "Show on map" (no search results appeared)
5. **Turn 5**: Tap the map to bring up context menu — nothing happened
6. **Turn 6**: Tap coordinates on map — landed on wrong location (Burkina Faso)
7. **Turn 7**: Back out from wrong result
8. **Turn 8**: Click "INCREASE SEARCH RADIUS"
9. **Turn 9-10**: Refocus search, try shorter query "Planken"
10. **Turn 11**: Increase search radius again (still "Could not find anything: 5 mi")
11. **Turn 12**: Press Enter to submit search
12. **Turn 13**: Re-enter "Planken, Liechtenstein"
13. **Turn 14**: Increase search radius (10 mi)
14. **Turn 15-16**: Show on map, wait 2s
15. **Turn 17-18**: Navigate back from blank map view
16. **Turn 19-20**: Keep increasing radius (20 mi, then more)
17. **Turn 21-22**: Show on map again, back out — still blank
18. **Turn 23-26**: More radius increases (50+ mi), re-enter query, show on map
19. **Turn 27**: `complete_task` with status=failure

**Agent's self-reported failure**: "Searching for 'Planken, Liechtenstein' only opened a blank map/search view with no visible search result or bottom place context menu, so there was no accessible 'Marker/Add marker' action to tap."

**Screenshots confirm**: The map area is blank/unloaded (no map tiles visible). Search consistently returns "Could not find anything" at all radii. The "Show on map" view shows a blank canvas with no place pin or context menu.

## Gap Analysis

There are **two distinct failures** here:

### 1. Map data not loaded (root cause)
OsmAnd is an offline maps app. The screenshots show a completely blank map — no tiles, no roads, no features. The Liechtenstein map data that the eval harness assumes is "pre-loaded" (see `_PRELOADED_MAP_LOCATIONS` comment: "Random location names and coords present in the pre-loaded Liechtenstein map") was apparently not available in this emulator session. This explains why:
- Search returned zero results at all radii
- "Show on map" displayed a blank canvas
- The agent could never reach the place context menu with the "Marker" button

### 2. Database table does not exist
The `map_markers_db` database's `map_markers` table does not exist until OsmAnd creates it (likely on first marker addition). This caused:
- Attempt 0: `infra_failure` during `initialize_task` (table clear failed)
- Attempt 1: `tear_down` exception after agent run (non-fatal but confirms no marker was ever created)

### Agent behavior assessment
The agent followed the OsmAnd skill instructions correctly: opened OsmAnd, used Search, typed the location name, tried "Show on map", attempted to increase search radius repeatedly. The skill has no fallback for when search fails due to missing map data. The agent never attempted:
- Long-pressing the map at approximate coordinates (the skill explicitly warns against this, but it might have been the only viable path if map tiles were missing)
- Using the ADDRESS tab in search to enter coordinates directly
- Navigating to coordinates via OsmAnd's coordinate search feature

## Verdict

**Emulator setup bug — fixable.**

- **Primary blocker**: Missing Liechtenstein `.obf` map file on emulator. `OsmAndApp.setup()` in AndroidWorld pushes `Liechtenstein_europe.obf` to `/storage/emulated/0/Android/data/net.osmand/files/` and sets SELinux context (`chcon u:object_r:media_rw_data_file:s0`). Our config has `perform_emulator_setup: false`, relying on snapshot. If the snapshot was created before successful map push, all subsequent runs see blank map.
- **Fix**: Re-run emulator setup with `perform_emulator_setup: true` (or manually `adb push` the `.obf` + `chcon`), then re-snapshot.
- **Secondary (skill)**: Add coordinate-based fallback to OsmAnd skill: "If search returns no results, use the ADDRESS tab to search by coordinates."
- **Tertiary (infra)**: The `map_markers` table doesn't exist until first use — `initialize_task` should handle this gracefully.
