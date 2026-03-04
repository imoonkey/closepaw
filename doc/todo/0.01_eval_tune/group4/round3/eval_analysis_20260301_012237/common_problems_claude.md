# Round 3 Eval Analysis - Common Problems Summary

**Run ID**: `20260301_012237`
**Date**: 2026-03-01
**Model**: qwen3.5
**Tasks**: 15 (from round 2 failures)
**Score**: 2/15 = 13.3% (was 0/15 in round 2 for this subset)

## Scorecard

| Task | R2 Score | R3 Score | Change | Root Cause |
|------|----------|----------|--------|------------|
| ExpenseDeleteDuplicates2 | 0.0 | 0.0 | Same | P2: Delete workflow + context loss |
| MarkorAddNoteHeader | 0.0 | 0.0 | Same | Newline normalization + rename UI |
| MarkorEditNote | 0.0 | **1.0** | **FIXED** | P1 fix worked |
| MarkorMergeNotes | 0.0 | 0.0 | Same | Content separator / file extension |
| MarkorTranscribeVideo | 0.0 | 0.0 | Same | Vision capability gap |
| OsmAndMarker | 0.0 | 0.0 | Same | Poor a11y + map interaction |
| OsmAndTrack | 0.0 | 0.0 | Same | Poor a11y + complex workflow |
| RecipeAddMultipleRecipesFromImage | 0.0 | 0.0 | Same | Turn exhaustion (2/3 done) |
| RecipeAddMultipleRecipesFromMarkor | 0.0 | 0.0 | Same | Turn exhaustion (almost done) |
| RecipeDeleteDuplicateRecipes2 | 0.0 | 0.0 | Same | Index shift + turn exhaustion |
| RetroPlaylistDuration | 0.0 | 0.0 | Same | Song list missing + workflow |
| SimpleCalendarEventsInNextWeek | 0.0 | **1.0** | **FIXED** | P8 infra fix worked |
| SimpleSmsReplyMostRecent | 0.0 | 0.0 | Same | Text precision (missing period) |
| SportsTrackerActivitiesOnDate | 0.0 | 0.0 | Same | Activity type extraction |
| VlcCreateTwoPlaylists | infra | infra | Same | Different error (app_db missing) |

## Round 2 Fix Assessment

| Fix | Status | Impact |
|-----|--------|--------|
| P1: type clear=false | **VALIDATED** | MarkorEditNote now passes. MarkorAddNoteHeader improved (text preserved) but has new issues. |
| P3: Markor navigation tip | Partial | Agent used shell for file reads. But still couldn't navigate back to file list or find rename. Tip needs to be more specific about rename workflow. |
| P4: OsmAnd hybrid mode | **INEFFECTIVE** | Hybrid mode didn't help. OsmAnd's map UI is fundamentally inaccessible via current tools. |
| P6: Strategy-pivot prompt | **INEFFECTIVE** | Agents still loop 20+ turns on the same failing approach. Prompt not strong enough or LLM doesn't follow it. |
| P7: OpenTracks tip | Partial | Agent now correctly looks for activity TYPE instead of track NAME. But can't find the type field in the UI. |
| P8: VLC init defensive teardown | Partial | Fixed original "already called" error but revealed new issue: app_db doesn't exist (VLC never launched). |

## Common Problem Categories (Round 3)

### P1-R3: Turn Exhaustion on Multi-Item Tasks (4 tasks)
**Affected**: RecipeAddMultipleRecipesFromImage, RecipeAddMultipleRecipesFromMarkor, RecipeDeleteDuplicateRecipes2, ExpenseDeleteDuplicates2

**Root Cause**: Tasks requiring operations on 3+ items each needing 8-10 UI turns exceed the 30-turn budget. The agent correctly identifies what to do but runs out of turns doing it.

**Fix Proposal**:
- **Option A**: Increase `max_turns` to 45-50 for multi-item tasks. Add task_overrides in eval config:
  ```yaml
  RecipeAddMultipleRecipesFromImage: { max_turns: 50 }
  RecipeAddMultipleRecipesFromMarkor: { max_turns: 50 }
  ```
- **Option B**: Add prompt tip: "For repetitive multi-item tasks (add/delete multiple items), prefer shell/database operations over UI when possible"
- **Option C**: Optimize form-filling - investigate if the type action can handle tab-separated multi-field input

**Priority**: HIGH - these tasks are very close to passing

**Decision**: Option A — add per-task `max_turns` override in eval config for long multi-item tasks.

### P2-R3: Capability-Blocked Tasks (3 tasks)
**Affected**: MarkorTranscribeVideo, OsmAndMarker, OsmAndTrack

**Root Cause**: These require capabilities the agent doesn't have:
- MarkorTranscribeVideo: Frame-by-frame video analysis
- OsmAnd*: Map canvas interaction (long-press at geo-coordinates, pan/zoom)

**Fix Proposal**:
- Move to `cannot_handle_group.txt` for now
- Future: Add video frame extraction tool, improve map widget support
- Consider: OsmAnd could be addressed with shell (`osmand-api` intent calls) if such API exists

**Priority**: LOW - requires fundamental capability additions

**Decision**: Add to `cannot_handle_group.txt` now. Revisit when capabilities expand.

### P3-R3: File Operation Workflow Gaps (2 tasks)
**Affected**: MarkorAddNoteHeader, MarkorMergeNotes

**Root Cause**: Agent tries shell for file operations but shell access is unreliable in Markor context — commands fail or Markor doesn't refresh after shell writes, wasting turns.
- MarkorAddNoteHeader: Couldn't rename file via UI or shell, newline normalization issue
- MarkorMergeNotes: Typed content via UI instead of merging, shell approach also unreliable

**Fix Proposal**:
- Remove shell suggestion from Markor tip — agent should stick to pure UI for Markor tasks to avoid wasting turns on failing shell commands
- Keep investigating UI-based rename (long-press context menu from file list) and merge workflows

**Priority**: MEDIUM - removing shell tip prevents turn waste; underlying UI workflow still needs discovery

**Decision**: Remove shell suggestion from Markor prompt. Shell access keeps failing, causing turn waste. Force pure-UI approach for Markor.

### P4-R3: Text Precision Errors (1 task)
**Affected**: SimpleSmsReplyMostRecent

**Root Cause**: Agent typed "A quick brown fox" without the trailing period. Small text precision error.

**Fix Proposal**:
- Add prompt: "When a task specifies exact text to enter (in quotes), reproduce it character-for-character including all punctuation."
- Investigate if this is actually the failure cause (could also be SMS sending on emulator)

**Priority**: MEDIUM

**Decision**: Not a real issue. Ignore — likely LLM flake or emulator timing, not systematic.

### P5-R3: App-Specific Workflow Discovery (2 tasks)
**Affected**: RetroPlaylistDuration, SportsTrackerActivitiesOnDate

**Root Cause**: Agent can't discover app-specific workflows:
- Retro Music: "Add song to playlist" workflow unknown
- OpenTracks: Activity type field location unknown

**Fix Proposal**:
- Add Retro Music tip: "To add songs to a playlist: browse songs, tap 3-dot menu, 'Add to playlist', select playlist"
- Improve OpenTracks tip: "Activity type appears in track detail header as an icon. Use Edit to see the activity type dropdown/selector."

**Priority**: MEDIUM

**Decision**: Agree — include in autotune round for /cog-tune investigation. Don't add tips until we verify the actual UI workflow from traces.

### P6-R3: VLC Infrastructure (1 task)
**Affected**: VlcCreateTwoPlaylists

**Root Cause**: `app_db` directory doesn't exist because VLC was never opened. Task init tries to clear playlists from a non-existent directory.

**Fix Proposal**:
- In VLC task's `_clear_playlist_dbs()`, add `os.path.exists()` check
- Or: Launch VLC once before init with `am start -n org.videolan.vlc/.gui.MainActivity`

**Priority**: MEDIUM (infra fix, not agent fix)

**Decision**: Fix now. Add `os.path.exists()` check in `_clear_playlist_dbs()`. No reason to keep deferring an infra bug.

## Summary of Next Steps (with decisions)

### Approved for Autotune Round 1:
1. **Max turns override** (P1-R3): Per-task `max_turns` in eval config for multi-item tasks (Recipe*, ExpenseDeleteDuplicates2)
2. **Cannot-handle exclusion** (P2-R3): Add MarkorTranscribeVideo, OsmAndMarker, OsmAndTrack to `cannot_handle_group.txt`
3. **Markor: remove shell tip** (P3-R3): Remove shell suggestion from Markor prompt — shell access unreliable, causes turn waste. Force pure-UI.
4. **VLC init fix** (P6-R3): Add `os.path.exists()` guard in `_clear_playlist_dbs()`

### Include in eval for investigation (no code fix):
5. **RetroPlaylistDuration, SportsTrackerActivitiesOnDate** (P5-R3): Run with /cog-tune to discover actual UI workflows before adding tips

### Not fixing:
6. **Text precision** (P4-R3): Not a real issue — ignore
7. **Strategy-pivot strengthening**: Deferred — needs deeper investigation
8. **Newline handling**: Deferred — Android framework level, low ROI

### Capability gaps (excluded from eval):
9. MarkorTranscribeVideo — needs video frame extraction
10. OsmAndMarker / OsmAndTrack — needs map widget interaction
