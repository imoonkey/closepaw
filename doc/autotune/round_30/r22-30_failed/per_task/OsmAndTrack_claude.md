# OsmAndTrack — Failure Analysis

## Scripted Success Criteria

The evaluator (`is_successful`) does the following:

1. Pulls the entire `tracks/` directory from the device path `/data/media/0/Android/data/net.osmand/files/tracks` to a temp directory on the host.
2. Iterates over every GPX file in that directory.
3. For each file, parses XML to extract all `<trkpt>` elements (lat/lon) in document order across `trk > trkseg > trkpt`.
4. Calls `_track_matches()` which checks whether the target waypoint coordinates appear **in order** within the track points, using Chebyshev distance tolerance of 0.001 degrees (~111 meters).
5. If any track file contains all target waypoints in the correct order, the task succeeds. Otherwise returns 0.0.

The matching algorithm is a sequential scan: it walks through the track points and tries to match each target waypoint in order, allowing arbitrary intermediate points between matches. All waypoints must be found in sequence.

## Task Setup

`initialize_task` does:

1. Calls `_clear_tracks()` — runs `rm -rf` on all files under `tracks/*` via ADB, wiping any pre-existing saved tracks.
2. Looks up target waypoint coordinates from `_PRELOADED_MAP_LOCATIONS` (12 cities in Liechtenstein with hardcoded lat/lon) via `_lookup_target_waypoints()`.
3. `generate_random_params()` picks 2-4 random Liechtenstein locations as waypoints and names the track after the first and last waypoint.

The goal presented to the agent is: "Save a track with waypoints X, Y, Z in the OsmAnd maps app in the same order as listed."

## Why Pre-excluded

This task is structurally complex for several compounding reasons:

1. **Multi-step route planning UI**: The agent must navigate OsmAnd's route-planning interface — add 2-4 waypoints by searching for each location name, ensure they are in the correct order, then save the result as a GPX track. This involves deep, stateful multi-screen navigation.

2. **Save-as-track requirement**: OsmAnd distinguishes between navigation routes and saved tracks. The agent must not just plan a route but explicitly save/export it as a track (GPX file) to the `tracks/` directory. The exact UI flow to "save as track" is non-obvious and version-dependent.

3. **Coordinate precision**: The evaluator checks that actual track points fall within ~111m of the target waypoints. OsmAnd's routing engine snaps to roads, so the track points may not pass through the exact city center coordinates in `_PRELOADED_MAP_LOCATIONS`. If the road network doesn't route close enough to the reference point, the match fails even if the agent did everything correctly from a UX perspective.

4. **Order sensitivity**: Waypoints must appear in exact sequence in the GPX file. If the agent adds them out of order or the routing engine reorders segments, the check fails.

5. **Complexity rating**: The task has `complexity = 12`, the highest tier in the OsmAnd eval set, reflecting that even human testers find this multi-step.

6. **No eval results exist**: Grep across `eval/results/` found zero `per_task.jsonl` entries for OsmAndTrack — it was never attempted in any recorded eval run available in this repo.

## Verdict

For OsmAndTrack to become feasible, the following would need to change:

- **App skill with explicit save-track flow**: A detailed OsmAnd skill documenting the exact UI steps to plan a multi-waypoint route and save it as a GPX track, including the specific buttons/menus to tap.
- **Coordinate tolerance investigation**: Verify that OsmAnd's routing engine for Liechtenstein actually produces track points within 0.001 degrees of the reference city coordinates. If not, this is a structural evaluator mismatch that no agent behavior can fix.
- **Possible alternative approach**: Instead of using the route planner, the agent could potentially use OsmAnd's "Plan a route" (track recording/drawing) mode to manually place points near the target coordinates — but this requires even more complex UI interaction (long-press to add points on the map).

This task remains in `cannot_handle_group.txt` and is not a reasonable target for prompt/skill tuning alone.
