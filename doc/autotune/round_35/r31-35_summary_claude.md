# Unpark Loop R2 Summary (R31-R35)

> Autotune rounds 31-35, model gpt-5.4, 2026-03-10

## Overview

| Metric | Value |
|--------|-------|
| Rounds | 5 (R31-R35) |
| Tasks attempted | 9 |
| Fixed | **5 (56%)** |
| Re-parked | 4 (44%) |
| Remaining | 0 |

## Fixed Tasks (5)

| Task | Fixed in | Root Cause | Fix Applied |
|------|---------|------------|-------------|
| AudioRecorderRecordAudioWithFileName | R31 | Skill said "strip .m4a", evaluator expected double extension | Remove "strip .m4a", type filename verbatim |
| ExpenseDeleteDuplicates2 | R31 | Agent only compared list-view fields, evaluator checks ALL fields | "Open detail view, compare ALL fields" in skill |
| ExpenseAddMultipleFromGallery | R32 | Gallery skill on wrong package + agent opened wrong image | New skill for `com.simplemobiletools.gallery.pro` with filename verification |
| RecipeDeleteDuplicateRecipes3 | R32 | Turn budget + no systematic tracking | Scratchpad tracking + compare all 7 fields + max_turns 80 |
| RetroPlaylistDuration | R33 | "Add to playlist" in multi-select toolbar not in a11y tree | Per-song 3-dot menu fallback + add-all-then-prune strategy |

## Re-parked Tasks (4)

| Task | Re-parked in | Category | Reason |
|------|-------------|----------|--------|
| MarkorDeleteNewestNote | R33 | Evaluator bug | Agent correct, evaluator uses `ls -au` (atime) which Markor mutates on delete |
| BrowserDraw | R33 | Platform limitation | A11y click doesn't fire JS DOM events in Chrome WebView canvas |
| OsmAndMarker | R35 | Offline map data | Offline geocoder can't resolve villages; coord fallback outside 0.001° tolerance |
| OsmAndTrack | R35 | Offline map data | Address search can't find Liechtenstein villages; agent has no coords to fall back to |

## Round-by-Round Progress

```
R31: ██░░░░░░░ 2/4 pass — AudioRecorder + ExpenseDelete2 FIXED
R32: ██░░░░░░░ 2/4 pass — ExpenseAddGallery + RecipeDelete3 FIXED
R33: █░░░░░░░░ 1/5 pass — RetroPlaylist FIXED, Markor+Browser RE-PARKED
R34: ░░░░░░░░░ 0/2 pass — OsmAnd pair: search tab diagnosis
R35: ░░░░░░░░░ 0/2 pass — OsmAnd pair: RE-PARKED (structural)
```

Cumulative fix curve: 0 → 2 → 4 → 5 → 5 → 5

## Key Insights

1. **Prior loop (R22-R30) misdiagnosed 3+ tasks.** Post-R30 deep dive into evaluator `is_successful` code revealed the actual root causes (e.g., AudioRecorder extension handling, ExpenseDelete field comparison, Gallery package name).

2. **Skill-level fixes are highly effective** for navigation/strategy errors. All 5 fixes were app skill changes + config adjustments — no core prompt or code changes needed.

3. **Remaining 4 are genuinely structural:**
   - MarkorDeleteNewestNote: evaluator-side atime bug (would need evaluator code change)
   - BrowserDraw: Chrome WebView a11y→JS event gap (platform limitation)
   - OsmAnd pair: offline map data doesn't index village-level POIs (would need map data fix or online geocoding)

## Cumulative Autotune Stats (R1-R35)

| Loop | Rounds | Started | Fixed | Re-parked |
|------|--------|---------|-------|-----------|
| Initial (R1-R21) | 21 | 42 | 29 | 7 |
| Unpark R1 (R22-R30) | 9 | 9 | 4 | 9 |
| **Unpark R2 (R31-R35)** | **5** | **9** | **5** | **4** |

Cannot-handle group now contains 5 tasks: MarkorTranscribeVideo, MarkorDeleteNewestNote, BrowserDraw, OsmAndMarker, OsmAndTrack.

---

## Git Diff Summary (R31-R35)

10 commits, 43 files changed, +1388 / -194 lines.

### App Skill Changes (shipped in APK)

**7 skill files touched** — all fixes were skill-level, no core prompt or Kotlin code changes.

| File | Change |
|------|--------|
| `com.dimowner.audiorecorder/SKILL.md` | Remove "strip .m4a" — type filename verbatim including extension chars |
| `com.arduia.expense/SKILL.md` | "Fill ALL 4 fields" emphasis; "open detail view, compare ALL fields" for duplicates |
| `com.flauschcode.broccoli/SKILL.md` | Scratchpad tracking + compare all 7 fields (was 4) + progress tracking |
| `code.name.monkey.retromusic/SKILL.md` | Per-song 3-dot menu as primary add method; add-all-then-prune for duration playlists |
| `com.google.android.apps.photos/SKILL.md` | **NEW** — full-screen image reading guidance |
| `com.simplemobiletools.gallery.pro/SKILL.md` | **NEW** — full-screen + filename verification in title bar |
| `net.osmand/SKILL.md` | Major rewrite across R31-R35: Address tab search, "Plan a route" for GPX, warn against SHOW ON MAP |

### Eval Config Changes

| File | Change |
|------|--------|
| `default.yaml` | `ExpenseDeleteDuplicates2` 50→60 turns, `RecipeDeleteDuplicateRecipes3` 60→80, `RetroPlaylistDuration` 50→60, `OsmAndTrack` +max_turns 80 |
| `cannot_handle_group.txt` | +4 tasks: MarkorDeleteNewestNote, BrowserDraw, OsmAndMarker, OsmAndTrack |
| `unpark_active.txt` | Emptied (was 9 tasks) |
| `autotune_round_{31-35}.txt` | 5 new per-round task configs |

### Documentation (analysis artifacts)

| Directory | Contents |
|-----------|----------|
| `doc/autotune/round_31/` | 2 run dirs, per-task analyses for ExpenseAddGallery + OsmAndMarker |
| `doc/autotune/round_33/` | per-task analyses for OsmAndMarker, OsmAndTrack, MarkorDeleteNewest |
| `doc/autotune/round_34/` | per-task analyses for OsmAndMarker, OsmAndTrack |
| `doc/autotune/round_35/` | combined OsmAnd analysis + this summary |
| `doc/autotune/meta/` | loop_state.json, scoreboard.json/md, changelog.md, 8 per-task files updated |

### Key Diff Highlights

**AudioRecorder** — the one-line fix:
```diff
-7. Enter the exact filename specified in the task (without the .m4a extension — the app adds it automatically)
+7. Enter the **exact filename** specified in the goal, character for character. Do not strip or add any extension — type it verbatim.
```

**Pro Expense** — detail view strategy:
```diff
-4. If you find a name that appears 4+ times but NO two amounts match, you likely missed 1 entry during scrolling.
+4. **Open detail view for each candidate**: Tap each same-name entry to see ALL fields (name, amount, category, note). Two entries are duplicates only if ALL fields match.
```

**Retro Music** — strategy flip:
```diff
-1. First, go to the Songs tab. **Song durations are visible on screen but NOT in the accessibility tree.** You must read durations from the screenshot (visual perception).
-2. Record all song names and their durations in scratchpad...
+1. **Add ALL available songs** to the playlist first. Don't try to read individual durations upfront.
+2. Go to the playlist page. The **total playlist duration is shown** on the playlist detail view.
+3. If the total duration exceeds the target, remove songs one at a time...
```

**OsmAnd** — search tab rewrite (R31→R35 evolution):
```diff
-## Searching for Locations
-- Use the search bar, not manual map navigation
-- For Liechtenstein locations, type the full name (e.g., "Planken, Liechtenstein")
+## Searching for Locations (CRITICAL)
+The default search tab is proximity-based — it only finds POIs near the current map center.
+1. **Switch to the "Address" tab** in the search screen...
+**NEVER use "SHOW ON MAP" from the general search tab** — it often geocodes to the wrong continent in offline mode.
```
