# OsmAndTrack — R33 Trace Analysis

**Run**: `20260310_004519` | **Score**: 0.0 | **Turns**: 44/80 | **Stop reason**: Error (agent self-reported failure)

## Goal

Save a track (GPX) with waypoints **Schonberg, Triesen, Bendern** (all Liechtenstein) in OsmAnd, in that order.

## What the Evaluator Checks

`OsmAndTrack.is_successful()` pulls the entire `tracks/` directory from the device, parses every `.gpx` file, and checks whether any saved track's `<trkpt>` sequence contains all three target coordinates **in order** (within 0.001 deg / ~100 m). The coordinates come from `_PRELOADED_MAP_LOCATIONS`:

| Waypoint | Lat | Lon |
|---|---|---|
| Schonberg | 47.1303814 | 9.5930117 |
| Triesen | 47.106997 | 9.5274854 |
| Bendern | 47.2122151 | 9.5062101 |

Key insight: the evaluator looks for a **saved GPX track file** in `tracks/`, not a route, not markers, not navigation state.

## What the Agent Did

| Phase | Turns | Summary |
|---|---|---|
| Open app + onboarding | 1-4 | Opened OsmAnd, dismissed download/speed-camera dialogs |
| Route planning — add waypoints | 5-22 | Used Directions flow: searched each location by name, fell back to coordinates when name search returned only country-level results. Added all 3 points as route destinations. |
| Attempt to start/save route | 23-43 | Clicked "Start" (turn 25), then spent 18 turns trying to find a "save as track" or "export" option. Tried overflow menus, destinations editor, sort dialog — none produced a save action. |
| Give up | 44 | Called `complete_task(status=failure)` |

## Where It Got Stuck

**Root cause: the agent used the Navigation/Directions workflow instead of the "Plan a route" (track creation) workflow.**

OsmAnd has two distinct features:
1. **Navigation** (Directions icon) — plans a turn-by-turn route for driving/walking. This does NOT save a GPX track file by default.
2. **Plan a route** (accessible from the main menu hamburger -> "Plan a route") — creates a track with waypoints that gets saved as a `.gpx` file in the `tracks/` directory.

The agent went down path #1. After entering all three waypoints and hitting "Start", it was in active navigation mode. From there, there is no obvious "save as track" button exposed in the UI. The agent spent turns 25-43 flailing:
- Tapping map areas and overflow menus that produced no screen change (turns 37-39)
- Opening the destinations editor, which showed generic "Intermediate destination 1/2" labels
- Trying sort options, scrolling, pressing back — never finding a save/export action

The agent correctly added all 3 waypoints by coordinates (falling back when name search failed), so the route was geometrically correct. But the route was never exported/saved as a GPX track file, which is what the evaluator requires.

## Secondary Issues

1. **Wrong coordinates for Schonberg**: Agent searched `47.1716, 9.5339` (turn 10), but the evaluator expects `47.1303814, 9.5930117`. The agent's coordinates are ~5 km off — would fail the 0.001-deg check even if the track had been saved. The agent likely used approximate/hallucinated coordinates instead of the actual Schonberg location.

2. **Wrong coordinates for Triesen**: Agent used `47.1075, 9.5256` (turn 16); evaluator expects `47.106997, 9.5274854`. Delta is ~0.002 deg — borderline fail on the 0.001-deg tolerance.

3. **Wrong coordinates for Bendern**: Agent used `47.2148, 9.5010` (turn 21); evaluator expects `47.2122151, 9.5062101`. Delta is ~0.005 deg — would fail.

## Required Fix

The app skill (`net.osmand/SKILL.md`) has a "Navigating Routes" section that tells the agent to use Directions — this is actively misleading for OsmAndTrack tasks. The skill needs a new section:

### Saving a Track (GPX)
1. Open hamburger menu -> "Plan a route"
2. Add waypoints in order by tapping the map or searching
3. Tap "Save" (disk icon) to save as a GPX file in `tracks/`

This is fundamentally different from the Directions/Navigation flow and must be called out explicitly.

### Coordinate Accuracy
When falling back to coordinates, the agent hallucinated approximate values. The skill should advise using "SHOW ON MAP" with the place name (which geocodes correctly) rather than guessing coordinates. If coordinates must be used, the agent needs accurate ones — which it cannot obtain from its training data alone.
