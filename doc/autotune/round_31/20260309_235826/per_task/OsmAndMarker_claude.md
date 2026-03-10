# OsmAndMarker - R31 Infra Failure Analysis

## What happened

Both attempts (attempt 0 and retry 1) failed during `initialize_task` with:

```
sqlite3.OperationalError: no such table: map_markers
```

The crash path:
1. `SQLiteApp.initialize_task()` calls `_clear_db(env)`
2. `_clear_db` calls `sqlite_utils.delete_all_rows_from_table("map_markers", "/data/data/net.osmand/databases/map_markers_db", ...)`
3. `delete_all_rows_from_table` calls `table_exists()` which returns `False`
4. Code tries to fix this by launching OsmAnd (`adb shell am start -W ...`) and sleeping 7s
5. OsmAnd launch times out after 5s (the `-W` flag wait exceeded adb timeout)
6. Code proceeds to pull the DB file anyway, opens it with sqlite3, and runs `DELETE FROM map_markers` -- which fails because the table still doesn't exist

## Why the infra fix didn't work

The `prepare_baseline` re-run restored the OsmAnd snapshot, but the snapshot itself is the problem. The databases in the snapshot are:

- `analytics` / `analytics-journal`
- `gpx_database`
- `poi_filters` / `poi_filters-journal`

There is **no `map_markers_db`** file. OsmAnd only creates this database the first time a user adds a map marker through the UI. Simply opening the app and downloading map data does not trigger its creation.

The `delete_all_rows_from_table` fallback (launch the app, sleep 7s, then retry) is designed for databases that get auto-created on first launch. But `map_markers_db` is not one of those -- it is lazy-initialized on first marker creation. So the fallback launches OsmAnd, waits, but the DB is still never created.

## What needs to be done to fix the snapshot

**Option A (recommended): Manually seed the database in the snapshot.**
1. Boot the emulator
2. Open OsmAnd and add any map marker (e.g., long-press on the map, tap "Mark"), then remove it
3. Verify `/data/data/net.osmand/databases/map_markers_db` now exists and contains the `map_markers` table
4. Re-run `prepare_baseline` to capture the updated snapshot (or manually copy the db into the snapshot directory at `/data/data/android_world/snapshots/net.osmand/databases/`)

**Option B: Create the DB file programmatically in `prepare_baseline`.**
Add a step that creates an empty `map_markers_db` with the correct schema via `adb shell` + sqlite3 on the device, then snapshot it. This is more fragile since OsmAnd could change the schema across versions.

**Option C: Patch `delete_all_rows_from_table` to handle missing tables gracefully.**
Instead of crashing, catch the `OperationalError` and create the table. However, this is in the reference eval code (`.reference/eval/android_world/`) and modifying it has broader implications.

Option A is the safest: it requires no code changes and produces a snapshot that matches what OsmAnd expects at runtime.
