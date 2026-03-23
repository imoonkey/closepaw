## R57 (20260317_194418/20260317_205840) — gpt-5.4 — FAIL
- Score: 0.0 | Context: app skill rewrite validation sweep (-65.6% skill tokens)
- Unparked task — still can't handle, reparking.

# OsmAndMarker

**Status: cannot_handle** — Offline map data doesn't support reliable locality search. Agent follows skill correctly but coords are outside 0.001-degree evaluator tolerance. RE-PARKED R35.

## R39 (20260312_094821) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 16 | Failed — GoalAchieved. Answer: Added a location marker for Planken, Liechtenstein in Osm... (unchanged)

## R35 (20260310_013847) — gpt-5.4 — RE-PARKED
- Score: 0.0 | Turns: 15 | StructuralMapData — Agent followed Address tab guidance, used coordinate fallback (47.19, 9.55), clicked Marker. But coords are ~0.007 degrees off from evaluator target (47.1859, 9.5452). Tolerance is 0.001. Agent can't know exact coordinates. Structural.

## R34 (20260310_012156) — gpt-5.4
- Score: 0.0 | Turns: 29 | SearchFail — All name searches returned "Could not find anything" or country-level "Liechtenstein". Agent saw COORDINATES SEARCH button but didn't use it. Never tried coords unlike R33. Need explicit Address tab / coord search guidance.

## R33 (20260310_004519) — gpt-5.4
- Score: 0.0 | Turns: 28 | OfflineGeocode — agent used "SHOW ON MAP" per skill, but offline geocoder sent map to Ghana/Africa. Agent tried coords `47.18, 9.54` which resolved correctly to Liechtenstein, but then abandoned them. Need coord-first priority.

## R32 (20260310_001826) — gpt-5.4
- Score: 0.0 | Turns: 9 | WrongResult — infra fixed (map_markers_db restored). Agent searched "Planken, Liechtenstein" but no village POI found. Picked "Liechtenstein" country result → marker at country centroid, ~47km off target. Agent falsely claimed GoalAchieved.

## R31 (20260309_235826) — gpt-5.4 — INFRA_FAILURE
- Score: N/A | Turns: 0 | InfraFailure — `sqlite3.OperationalError: no such table: map_markers`. The `map_markers_db` is lazily created by OsmAnd only on first marker add. Snapshot doesn't have it. Need manual marker creation on emulator + re-snapshot.

## R27 (20260309_204943) — gpt-5.4 — RE-PARKED
- Score: 0.0 | Turns: 27 | SearchFailure + InfraFailure — attempt 0: infra_failure. Attempt 1: searched "Planken, Liechtenstein" but OsmAnd opened blank/wrong view. 3rd consecutive failure.

## R26 (20260309_203139) — gpt-5.4
- Score: 0.0 | Turns: 17 | InfraFailure + FalseSuccess — attempt 0: infra_failure. Attempt 1: agent claimed success, evaluator disagreed.

## R24 (20260309_194958) — gpt-5.4
- Score: 0.0 | Turns: 23 | A11yGap — found Planken on map. Marker button NOT in a11y tree (only 0-14 elements, tried index 15). Exception: "no such table: map_markers."

## AT-R5-GPT54 (20260306_003753) — FAIL
- Score: 0.0
- Turns: 21/30
- GPT-5.4 progressed further than the earlier parked runs: it used coordinate search to reach Planken and surfaced the map action bar. But it then misbound the final action, clicking `Share` instead of `Marker`, and false-completed without verifying a saved marker.
- See `round_5/20260306_003753/per_task/OsmAndMarker_codex.md` for details.
- R58 (20260323): score=1.0, turns=14, reason=GoalAchieved
