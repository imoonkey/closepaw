# OsmAndTrack — R34 Trace Analysis

**Run**: `20260310_012156` | **Task**: `OsmAndTrack` | **Score**: 0.0 | **Turns**: 22 | **Reason**: Error (agent self-declared failure)

## Goal

Save a track with waypoints **Schonberg, Liechtenstein → Triesen, Liechtenstein → Bendern, Liechtenstein** (in that order) in OsmAnd as a GPX file.

Expected coords (from evaluator `_PRELOADED_MAP_LOCATIONS`):
- Schonberg: (47.1303814, 9.5930117)
- Triesen: (47.106997, 9.5274854)
- Bendern: (47.2122151, 9.5062101)

## Evaluator Logic

`OsmAndTrack.is_successful()` pulls files from `/data/media/0/Android/data/net.osmand/files/tracks/`, parses each `.gpx` file for `<trk>/<trkseg>/<trkpt>` elements, and checks that the three target coordinates appear **in order** within 0.001 degrees (~100m). No GPX file was saved, so score = 0.0.

## Agent Action Sequence

| Turn | Action | Outcome |
|------|--------|---------|
| 1 | `open_app("OsmAnd")` | App opens, lands on Welcome/download onboarding |
| 2 | Click "SKIP DOWNLOAD" | Enters map view |
| 3 | Click "Back to menu" | Opens hamburger menu |
| 4 | Click "Plan a route" | Correct — opens route planner |
| 5 | Click "Create new route" | Correct — starts new route |
| 6 | Tap search icon (x=98,y=142) | Opens search — but map shows "200 mi" zoom, centered far from Liechtenstein |
| 7 | Click search field | Search screen with tabs: History, Categories, **Address** |
| 8 | Type "Schonberg" | Only result: "SHOW ON MAP" — no locality match |
| 9 | Type "Schonberg" (no diacritic) | Same: only "SHOW ON MAP" |
| 10 | Click "INCREASE SEARCH RADIUS" | Expanded to 5 mi — still "Could not find anything" |
| 11 | Click "SHOW ON MAP" | Map centers on **wrong continent** (West Africa) |
| 12 | Press Back | Returns to search |
| 13-17 | Retry "Schonberg, Liechtenstein", "Schonberg" again, increase radius to 10 mi | All fail — "Could not find anything" |
| 18 | Press back to route planner | Gives up on Schonberg |
| 19 | Type "Triesen" | Same failure — only "SHOW ON MAP" |
| 20-21 | Press Back x2 | Returns to map |
| 22 | `complete_task(failure)` | Agent declares it cannot complete the task |

## Did the Agent Use "Plan a Route"?

**Yes.** The agent correctly navigated: hamburger menu → Plan a route → Create new route → search. This was the right workflow per the app skill.

## Did the Agent Save a GPX Track?

**No.** The agent never reached the point of adding any waypoints to the route, so there was nothing to save.

## Root Cause: Search Used Wrong Tab

The search screen showed three tabs: **History**, **Categories**, **Address**. The agent typed directly into the default search bar (general POI search), which searches by proximity to current map center. Since:

1. The map was centered far from Liechtenstein (likely default/Africa region after skipping download)
2. The general search only finds results near the current view

...the locality search returned nothing. The agent **never tried the Address tab**, which performs structured address lookup across the entire downloaded map data and would have found Schonberg, Triesen, and Bendern regardless of current map position.

This is the same root cause as the OsmAndMarker failures in previous rounds — the general search is proximity-based and fails when the map is centered on the wrong region, while Address search works globally on offline data.

## Secondary Issues

1. **"SHOW ON MAP" geocoded to West Africa** — the agent correctly identified this as wrong and backed out, following the skill guidance ("Do NOT trust SHOW ON MAP blindly").
2. **No attempt to scroll/zoom the map to Liechtenstein** — the agent could have manually navigated the map to Europe/Liechtenstein and then searched, which would have made proximity search work.
3. **No attempt to use coordinates directly** — the agent could have tried entering lat/lon coordinates if it knew them.

## Skill Gap

The OsmAnd SKILL.md says:
> "All Liechtenstein localities are available in the offline map data."
> "If a specific village doesn't appear, try just the village name without the country."

But it does **not** instruct the agent to use the **Address tab** when general search fails. The skill should explicitly say:

> If the general search shows only "SHOW ON MAP" with no locality result, switch to the **Address** tab for structured offline lookup.

## Fix Recommendation

**Priority: Update SKILL.md** — Add Address tab fallback guidance to the "Searching for Locations" section and to the "Saving GPX Tracks" section:

```
## Searching for Locations
- Use the search bar for location names.
- If general search shows only "SHOW ON MAP" or "Could not find anything",
  tap the **Address** tab at the top of the search screen for structured
  offline address lookup. Address search works regardless of current map position.
- All Liechtenstein localities are available in the offline map data.
```

This same fix would also help OsmAndMarker and OsmAndFavorite tasks that rely on search.
