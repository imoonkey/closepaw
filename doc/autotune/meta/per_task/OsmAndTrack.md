## R57 (20260317_194418/20260317_205840) — gpt-5.4 — FAIL
- Score: 0.0 | Context: app skill rewrite validation sweep (-65.6% skill tokens)
- Unparked task — still can't handle, reparking.

# OsmAndTrack

**Status: cannot_handle** — Offline map Address search can't resolve Liechtenstein village names. Agent has no coordinates to fall back to. RE-PARKED R35.

## R39 (20260312_094821) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 34 | Failed — Error. Answer: I’m blocked in OsmAnd. I verified the app is in route/sea... (unchanged)

## R35 (20260310_013847) — gpt-5.4 — RE-PARKED
- Score: 0.0 | Turns: 25 | StructuralMapData — Agent correctly used Plan a route + Address tab per skill. But Address search couldn't find Schonberg/Triesen/Bendern. No coordinates available for fallback. Structural.

## R34 (20260310_012156) — gpt-5.4
- Score: 0.0 | Turns: 22 | SearchFail — Correctly used "Plan a route" workflow. But general search tab is proximity-based and map was far from Liechtenstein. Address tab (structured offline lookup) was visible but never used. No waypoints added, no GPX saved.

## R33 (20260310_004519) — gpt-5.4
- Score: 0.0 | Turns: 44 | WrongWorkflow — Agent used Navigation/Directions to set waypoints, then couldn't find "save as track". Correct path: hamburger → "Plan a route" → add points → Save. Evaluator checks for .gpx file with trkpt matching target coords in order.

## AT-R5-GPT54 (20260306_003753) — FAIL → confirms cannot_handle
- Score: 0.0
- Turns: 28/30
- GPT-5.4 reached the route planner and tried route search, address search, locality-first search, and increased-radius recovery, but the UI still degraded to generic `Country Liechtenstein` rather than the requested locality waypoints. It failed safely instead of false-completing.
- See `round_5/20260306_003753/per_task/OsmAndTrack_codex.md` for details.
- R58 (20260323): score=0.0, turns=57, reason=Error, ans="I’m blocked from completing this exactly in OsmAnd’s current offline state. I ve"
