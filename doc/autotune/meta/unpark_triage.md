# Unpark Triage — R31+ Loop (Corrected Root Causes)

> **Source**: Post-R30 deep dive reviewed scripted `is_successful` code and agent traces for all 9 re-parked tasks. See `doc/autotune/round_30/r22-30_failed/per_task/*_claude.md` for full analysis.

## Tier 1: High confidence fix (do first)

| Task | Old Park Reason (WRONG) | Actual Root Cause | Fix | Config |
|------|------------------------|-------------------|-----|--------|
| AudioRecorderRecordAudioWithFileName | "scoped storage: UI rename ≠ filesystem rename" | **Skill bug**: skill says "strip .m4a", evaluator expects double extension `.m4a.m4a` | Remove "strip .m4a" from `app_skills/com.dimowner.audiorecorder/SKILL.md`. Agent must type filename exactly as given in goal. | — |
| ExpenseDeleteDuplicates2 | "scroll gap: 41/42 items" | **Strategy gap**: agent never opened detail views. Evaluator checks ALL fields (category, note). Agent only compared list-view fields. | Add to Pro Expense skill: "For duplicates, open detail view for each same-name entry, compare ALL fields." | max_turns: 60 |
| ExpenseAddMultipleFromGallery | "image perception unreliable" | **Navigation error**: agent stayed on Gallery grid view, tried OCR on thumbnails. Never opened `expenses.jpg` full-screen. | Add Gallery skill: "open image full-screen first". Pro Expense skill: "fill ALL 4 fields (name, amount, category, note)." | max_turns: 60, hybrid (already set) |
| OsmAndMarker | "marker button not in a11y tree" | **Missing map data**: `Liechtenstein_europe.obf` not on emulator. `OsmAndApp.setup()` pushes it but our snapshot predates that. | **Before eval**: `adb push Liechtenstein_europe.obf` to `/storage/emulated/0/Android/data/net.osmand/files/` + `chcon u:object_r:media_rw_data_file:s0`. Add coord fallback to OsmAnd skill. | hybrid (already set) |

## Tier 2: Strategy change, medium confidence

| Task | Old Park Reason | Actual Root Cause | Fix | Config |
|------|----------------|-------------------|-----|--------|
| RetroPlaylistDuration | "song durations not accessible" | **Wrong strategy**: agent tried to read individual durations. Doesn't need them — playlist view shows total duration. Task guarantees target songs sum to 47.5 min. | Add Retro Music skill: "For duration playlists: add ALL songs to playlist, check total. If over target, remove songs one at a time until in range." | max_turns: 60 |
| RecipeDeleteDuplicateRecipes3 | "turn budget insufficient" | **Partially correct** — 49/60 turns used. But history compression NOT the bottleneck (11K/18K tokens at turn 49). Agent needs systematic scratchpad tracking. | Add Broccoli skill: "Track checked entries in scratchpad. Compare ALL 7 fields." | max_turns: 80 |

## Tier 3: Platform limitation, try but may re-park

| Task | Root Cause | Potential Fix | Risk |
|------|-----------|---------------|------|
| MarkorDeleteNewestNote | Evaluator uses `change_time` which Markor's delete mutates on surviving files | Investigate shell `stat`/`rm` on Markor storage. If scoped storage blocks it, structural. | High — scoped storage likely blocks shell access |
| BrowserDraw | a11y click doesn't fire JS DOM events in Chrome WebView. `ctx.strokeStyle` never changes. | Would need JS injection via adb or Chrome DevTools. Deep platform limitation. | Very high — may be truly structural |

## Priority Order for Ralph Loop

Round 31: Tier 1 batch — AudioRecorder, ExpenseDelete2, ExpenseAddGallery, OsmAndMarker (4 tasks, all high-confidence fixes)
Round 32: Tier 2 batch — RetroPlaylistDuration, RecipeDeleteDuplicateRecipes3 (2 tasks, strategy changes)
Round 33+: Tier 3 — MarkorDeleteNewestNote, BrowserDraw (investigate, likely re-park)

## ENV PREREQUISITE (must do before R31 eval)

OsmAndMarker requires the Liechtenstein map file on the emulator. The file `Liechtenstein_europe.obf` should be at:
- Source: `.reference/eval/android_world/android_world/env/setup_device/` (or wherever AndroidWorld stores APK/data assets)
- Dest: `/storage/emulated/0/Android/data/net.osmand/files/Liechtenstein_europe.obf`
- SELinux: `adb shell chcon u:object_r:media_rw_data_file:s0 /storage/emulated/0/Android/data/net.osmand/files/Liechtenstein_europe.obf`
- Then re-snapshot the emulator.
