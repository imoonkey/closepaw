# OsmAnd Combined Analysis — R35 run 20260310_013847

## OsmAndMarker (score=0.0, 15 turns, GoalAchieved)

**Task:** Add a location marker for Planken, Liechtenstein.

**Agent behavior:**
1. Opened OsmAnd, skipped onboarding (turn 2)
2. Opened search (turn 3)
3. **Switched to Address tab** (turn 4) — guidance followed
4. Typed "Planken" — got "Could not find anything: 100 mi" (turn 5-6)
5. Tried to increase search radius — still no results (turn 6)
6. Fell back to Coordinates Search (turns 7-9)
7. Entered lat=47.19237, lon=9.54620 for Planken (turns 11-12)
8. Selected the coordinate result — context sheet showed "No address determined" (turn 13)
9. Clicked "Marker" button on context sheet (turn 14)
10. Called `complete_task(success)` (turn 15)

**Why score=0.0 despite correct workflow:**
The agent did everything right procedurally. The coordinates are correct for Planken. The marker was added ("Marker" clicked successfully). However, the scorer found score=0.0. Most likely cause: **the marker has no label/name** — it was placed at raw coordinates with "No address determined," so the evaluator cannot verify it is a marker for "Planken." The offline map data for Liechtenstein is not downloaded, so neither Address search nor reverse geocoding works.

## OsmAndTrack (score=0.0, 25 turns, Error)

**Task:** Save a track with waypoints Schonberg, Triesen, Bendern (Liechtenstein) in order.

**Agent behavior:**
1. Opened OsmAnd — hit blank screen, waited/retried (turns 1-4)
2. Navigated past onboarding (turns 5-9)
3. Opened main menu, selected **"Plan a route"** (turns 10-11) — guidance followed
4. Started new route (turn 12)
5. Opened search from route planner (turns 13-14)
6. **Switched to Address tab** (turn 15) — guidance followed
7. Typed "Schonberg" — no results (turn 16)
8. Tried locality selector, typed "Schonberg" again — no results (turns 17-19)
9. Increased search radius — still nothing (turn 20)
10. Tried "Schonberg, Liechtenstein" — no results (turn 21)
11. Attempted to switch to Coordinates Search but gave up — no coordinates available (turns 22-24)
12. Called `complete_task(failure)` — "blocked because OsmAnd is offline and Address search cannot find the requested places" (turn 25)

**Why score=0.0:**
The agent correctly followed all skill guidance (Plan a route, Address tab, increase radius, coordinate fallback). But it had no coordinates hardcoded for Schonberg/Triesen/Bendern, and the offline map data is missing. The agent correctly identified it was stuck and gave up.

## Address Tab Guidance Compliance

| Task | Address tab used? | Plan a route used? | Coordinate fallback? |
|------|-------------------|---------------------|----------------------|
| OsmAndMarker | Yes (turn 4) | N/A | Yes — succeeded with hardcoded coords |
| OsmAndTrack | Yes (turn 15) | Yes (turn 11) | Attempted but no coords available |

Both agents followed the skill guidance correctly.

## Root Cause

**Structural — missing offline map data.** The emulator has no Liechtenstein map downloaded and no internet access. Address search returns nothing for any Liechtenstein locality. The Marker task only half-succeeded because the LLM happened to know Planken's coordinates, but the marker lacks a proper name label.

## Verdict

**Not fixable with skill changes alone.** The core blocker is environment setup:
- The emulator needs the Liechtenstein offline map pre-downloaded for Address search to work
- Without offline maps, coordinate search is the only fallback, and it requires the LLM to know exact coordinates for every possible locality (unreliable, and labels won't resolve)
- Even when coordinates work (Marker), the scorer may reject unlabeled markers

**Recommended fix:** Pre-download the Liechtenstein map in the eval environment setup, or add the task localities' coordinates to the OsmAnd app skill as a lookup table. The map download is the cleaner solution since it also enables proper labeling and makes Address search work as designed.
