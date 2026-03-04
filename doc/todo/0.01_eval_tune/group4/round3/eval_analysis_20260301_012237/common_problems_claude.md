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

### P3-R3: File Operation Workflow Gaps (2 tasks)
**Affected**: MarkorAddNoteHeader, MarkorMergeNotes

**Root Cause**: Agent doesn't effectively use shell for file operations when UI proves difficult:
- MarkorAddNoteHeader: Couldn't rename file (never tried `mv` command), newline normalization issue
- MarkorMergeNotes: Typed content via UI instead of using `cat file1 > merged && cat file2 >> merged`

**Fix Proposal**:
- Strengthen Markor shell tip: "For file operations (rename, merge, prepend text), prefer shell commands. Examples:
  - Rename: `mv /sdcard/Documents/Markor/old.txt /sdcard/Documents/Markor/new.txt`
  - Merge: `cat file1.txt > merged.txt && echo '' >> merged.txt && cat file2.txt >> merged.txt`
  - Prepend text: `echo 'header\n' | cat - file.txt > temp && mv temp file.txt`"
- Fix newline handling in type action: investigate why `\n\n` is reduced to `\n`

**Priority**: HIGH - shell-based approach would solve both tasks

### P4-R3: Text Precision Errors (1 task)
**Affected**: SimpleSmsReplyMostRecent

**Root Cause**: Agent typed "A quick brown fox" without the trailing period. Small text precision error.

**Fix Proposal**:
- Add prompt: "When a task specifies exact text to enter (in quotes), reproduce it character-for-character including all punctuation."
- Investigate if this is actually the failure cause (could also be SMS sending on emulator)

**Priority**: MEDIUM

### P5-R3: App-Specific Workflow Discovery (2 tasks)
**Affected**: RetroPlaylistDuration, SportsTrackerActivitiesOnDate

**Root Cause**: Agent can't discover app-specific workflows:
- Retro Music: "Add song to playlist" workflow unknown
- OpenTracks: Activity type field location unknown

**Fix Proposal**:
- Add Retro Music tip: "To add songs to a playlist: browse songs, tap 3-dot menu, 'Add to playlist', select playlist"
- Improve OpenTracks tip: "Activity type appears in track detail header as an icon. Use Edit to see the activity type dropdown/selector."

**Priority**: MEDIUM

### P6-R3: VLC Infrastructure (1 task)
**Affected**: VlcCreateTwoPlaylists

**Root Cause**: `app_db` directory doesn't exist because VLC was never opened. Task init tries to clear playlists from a non-existent directory.

**Fix Proposal**:
- In VLC task's `_clear_playlist_dbs()`, add `os.path.exists()` check
- Or: Launch VLC once before init with `am start -n org.videolan.vlc/.gui.MainActivity`

**Priority**: MEDIUM (infra fix, not agent fix)

## Summary of Next Steps

### Must Fix (directly unblocks task passes):
1. **Markor shell tips** (P3-R3): Explicit rename/merge shell commands in prompt
2. **Newline handling**: Debug why `\n\n` becomes `\n` in type action
3. **Max turns increase**: For Recipe tasks, increase to 45-50 turns
4. **VLC init fix**: Handle missing app_db directory gracefully

### Should Fix (improves behavior):
5. **Retro Music + OpenTracks app tips** (P5-R3)
6. **Text precision prompt** (P4-R3)
7. **Post-delete verification** prompt for delete tasks
8. **Strategy-pivot strengthening**: Current prompt is too weak

### Accept as Capability Gap:
9. MarkorTranscribeVideo - needs video frame extraction
10. OsmAndMarker / OsmAndTrack - needs map widget interaction
