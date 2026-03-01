# Group 4 Eval Run — Summary Analysis

**Run ID**: 20260227_161048
**Date**: 2026-02-27
**Tasks**: 20 (19 with traces + 1 infra failure)
**Model**: qwen3.5 | **Max Turns**: 30 | **Perception**: accessibility_only

---

## Results Overview

| # | Task | Score | Turns | Stop Reason | Root Cause Category |
|---|------|-------|-------|-------------|---------------------|
| 0 | **MarkorCreateNoteAndSms** | **1.0** | 17 | GoalAchieved | Success |
| 1 | **MarkorDeleteAllNotes** | **1.0** | 8 | GoalAchieved | Success |
| 2 | MarkorDeleteNewestNote | 0.0 | 7 | GoalAchieved (false) | Reasoning |
| 3 | MarkorMoveNote | 0.0 | 12 | GoalAchieved (false) | Reasoning |
| 4 | MarkorTranscribeVideo | 0.0 | 16 | Error (loop) | CapabilityGap (vision) |
| 5 | OsmAndMarker | 0.0 | 30 | MaxTurnsReached | Planning + Perception |
| 6 | OsmAndTrack | 0.0 | 30 | MaxTurnsReached | CapabilityGap (a11y blind) |
| 7 | RecipeAddMultipleRecipesFromImage | 0.0 | 16 | Error | CapabilityGap (vision) |
| 8 | RecipeAddMultipleRecipesFromMarkor | 0.0 | 16 | Error (loop) | AntiLoop FP + Planning |
| 9 | RecipeDeleteDuplicateRecipes2 | 0.0 | 27 | Error (loop) | Reasoning (self-doubt) |
| 10 | **RecipeDeleteMultipleRecipes** | **1.0** | 14 | GoalAchieved | Success |
| 11 | **RecipeDeleteMultipleRecipesWithNoise** | **1.0** | 23 | Error (loop) | Success (AntiLoop FP) |
| 12 | RetroPlayingQueue | 0.0 | 9 | Error (loop) | AntiLoop FP |
| 13 | RetroPlaylistDuration | 0.0 | 30 | MaxTurnsReached | Planning + Perception |
| 14 | SimpleCalendarAnyEventsOnDate | 0.0 | 13 | Error (loop) | Perception (unlabeled grid) |
| 15 | SimpleCalendarEventOnDateAtTime | 0.0 | 11 | Error (loop) | Perception (unlabeled grid) |
| 16 | **SimpleCalendarLocationOfEvent** | **1.0** | 5 | GoalAchieved | Success |
| 17 | **SimpleCalendarNextMeetingWithPerson** | **1.0** | 5 | GoalAchieved | Success |
| 18 | **SimpleSmsReplyMostRecent** | **1.0** | 10 | GoalAchieved | Success |
| 19 | VlcCreateTwoPlaylists | N/A | 0 | InfraFailure | InfraFailure |

**Pass rate**: 7/19 = 36.8% (excluding infra failure)
**Goal claim precision**: 75% (agent claimed success 8 times, 6 were true)
**False success rate**: 2/19 = 10.5% (MarkorDeleteNewestNote, MarkorMoveNote)
**Anti-loop false positive rate**: 3/19 = 15.8% (RetroPlayingQueue, RecipeAddMultipleRecipesFromMarkor, RecipeDeleteMultipleRecipesWithNoise)

---

## Systemic Issues (Ordered by Impact)

### 1. Anti-Loop False Positives (HIGH — 3 tasks directly affected, generalizable)

**Tasks**: RetroPlayingQueue, RecipeAddMultipleRecipesFromMarkor, RecipeDeleteMultipleRecipesWithNoise

**Pattern**: The anti-loop detector matches on surface-level `{action, element_index}` tuples without considering semantic context changes:

- **RetroPlayingQueue**: Clicking "Add to playing queue" (index 10) for different songs was flagged because it's the same index. The agent had successfully completed the first song and was working on the second.
- **RecipeAddMultipleRecipesFromMarkor**: Sequential form filling (click field → type value) for different fields at re-indexed positions was flagged as repetitive. The agent was making linear progress through form fields.
- **RecipeDeleteMultipleRecipesWithNoise**: Post-completion verification (search to confirm deletion) triggered loop detection because it resembled the delete workflow. Task was actually complete (score=1.0).

**Impact**: RetroPlayingQueue and RecipeAddMultipleRecipesFromMarkor would likely have succeeded without false-positive intervention. RecipeDeleteMultipleRecipesWithNoise scored 1.0 despite agent reporting failure.

**Proposed Fix**:
1. **Semantic context check**: Compare element `hint_text`, `text`, or `desc` — not just `element_index` — when assessing action repetition
2. **Progress detection**: If screen state changes meaningfully between repeated actions (new data visible, different selection), don't escalate
3. **Intervening-action credit**: Successful distinct actions between "repeated" actions should reduce loop confidence
4. **Post-completion verification exemption**: If agent has already performed multiple successful operations, allow verification passes

### 2. Calendar Date Navigation via A11y (HIGH — 2 tasks affected)

**Tasks**: SimpleCalendarAnyEventsOnDate, SimpleCalendarEventOnDateAtTime

**Pattern**: Simple Calendar Pro's monthly grid renders 42 day cells as `View` elements with `text: ""` — completely invisible to accessibility-only perception. The agent cannot determine which cell corresponds to which date.

Additionally, `NumberPicker` widgets (used in date pickers) don't accept `type` actions via accessibility — the display text changes but the underlying value doesn't. This creates silent false-positive actions.

**Contrast**: SimpleCalendarLocationOfEvent and SimpleCalendarNextMeetingWithPerson succeeded in 5 turns each by using the **search** function to find events by name, bypassing date navigation entirely.

**Proposed Fix**:
1. **P0**: Add NumberPicker scroll gesture support (increment/decrement actions)
2. **P1**: Teach agent to use search-first strategy for ALL calendar QA tasks — search by event name or keyword rather than navigating to a date
3. **P2**: Consider enabling screenshot/hybrid mode for calendar grid views where a11y labels are empty
4. **P3**: Add `content_description` injection in the a11y tree sanitizer for calendar grid cells

### 3. Vision/Screenshot Capability Gap (MEDIUM — 2 tasks affected)

**Tasks**: MarkorTranscribeVideo, RecipeAddMultipleRecipesFromImage

**Pattern**: Both tasks require reading visual content (video frame text, image text) which is invisible to accessibility-only perception.

- MarkorTranscribeVideo: Video player exposes only playback controls, no frame content
- RecipeAddMultipleRecipesFromImage: Image viewer shows filename, not visual content

**Proposed Fix**:
1. **Task-level perception override**: Set `perception_mode: hybrid` for tasks known to require reading visual content
2. **Capability-aware early exit**: If vision is disabled and the task requires reading from images/video, fail fast with clear explanation rather than spending 16 turns exploring dead ends
3. **On-device OCR tool**: Provide a `read_screen` or `ocr` tool that can extract text from the current screen

### 4. False Success Claims (MEDIUM — 2 tasks affected)

**Tasks**: MarkorDeleteNewestNote, MarkorMoveNote

**Pattern**: Agent confidently claims success but the scripted evaluator scores 0.0:

- **MarkorDeleteNewestNote**: Conflated modification time (displayed by Markor's "Sort by Date") with creation time (what "newest" means). Deleted `fine_jelly_final.md` (most recently modified) instead of the actual newest note.
- **MarkorMoveNote**: Selected `2023_02_13_shy_king_copy.md` (substring match) instead of the exact `shy_king_copy.md`. Did not scroll through the full file list before selecting.

**Proposed Fix**:
1. **Pre-action verification for destructive operations**: "Before deleting or moving, verify you have the EXACT target. Use shell `stat` for file metadata, or check file Details."
2. **Exact filename matching guidance**: "If the goal specifies a filename, ensure you match it EXACTLY — not as a substring."
3. **Markor app tip**: "Markor displays modification time, not creation time. For 'newest/oldest' tasks, use `stat` via shell to check actual creation timestamps."

### 5. Turn Budget & Planning for Multi-Item Tasks (MEDIUM — 3 tasks affected)

**Tasks**: RecipeAddMultipleRecipesFromMarkor, RecipeDeleteDuplicateRecipes2, RetroPlaylistDuration

**Pattern**: Multi-item tasks (add/delete multiple recipes, build playlist with duration target) require many turns per item. At 3-4 turns per item action, completing 3+ items exhausts the 30-turn budget or triggers anti-loop detection.

- RecipeAddMultipleRecipesFromMarkor: 3 recipes × ~7 fields × 2 actions/field ≈ 42 turns needed
- RecipeDeleteDuplicateRecipes2: Multiple duplicates × 4 turns/deletion + survey overhead
- RetroPlaylistDuration: ~10 songs × 3-4 turns/add + duration planning

**Proposed Fix**:
1. **Efficient action patterns**: Type directly into fields with `element_index` without separate click-to-focus (halves turn cost)
2. **Turn budget awareness**: Agent should estimate total turns needed upfront. If > 25, adopt most compact strategy
3. **Per-task turn override**: Some tasks need `max_turns: 50` to be completable

### 6. OsmAnd A11y Blindspot (LOW — 2 tasks affected, likely not fixable)

**Tasks**: OsmAndMarker, OsmAndTrack

**Pattern**: OsmAnd's map canvas and route planning toolbar are OpenGL-rendered and expose virtually no accessibility information. When the bottom sheet closes, the agent is left with a bare map showing only 5 status bar elements. No search results, no toolbar buttons, no waypoint controls.

**Verdict**: OsmAnd tasks are likely impossible without screenshot-based visual grounding. These represent a hard ceiling for accessibility-only perception.

### 7. Agent Self-Interaction (LOW — 1 task affected)

**Task**: SimpleSmsReplyMostRecent

**Pattern**: Agent typed the SMS message into its own chat input UI before realizing it needed to open the SMS app. Wasted 3 turns.

**Proposed Fix**: Add package-awareness check — "Before typing, verify the current app package matches your intended target."

---

## Anti-Loop Escalation Assessment

| Outcome | Count | Tasks |
|---------|-------|-------|
| Correctly triggered | 4 | MarkorTranscribeVideo, RecipeDeleteDuplicateRecipes2, SimpleCalendarAnyEventsOnDate, SimpleCalendarEventOnDateAtTime |
| False positive | 3 | RetroPlayingQueue, RecipeAddMultipleRecipesFromMarkor, RecipeDeleteMultipleRecipesWithNoise |
| Not triggered (correct) | 9 | All successes + OsmAnd tasks (MaxTurns) + RecipeAddMultipleRecipesFromImage |

**False positive rate**: 3/7 = 43% of all anti-loop triggers were false positives. This is a significant reliability concern.

---

## Success Pattern Analysis

The 7 successful tasks share common patterns:

1. **Search-first information retrieval** (SimpleCalendarLocationOfEvent, SimpleCalendarNextMeetingWithPerson): Use app's built-in search to find items by name rather than browsing. 5 turns each — theoretical minimum.

2. **Sequential deletion with consistent UI pattern** (RecipeDeleteMultipleRecipes, MarkorDeleteAllNotes): Learn the delete workflow once, replicate. 8-14 turns with zero failures.

3. **Multi-app cross-task execution** (MarkorCreateNoteAndSms): Switch between apps cleanly using `open_app`. Handle app name mismatches via error recovery.

4. **Resilience to minor errors** (SimpleSmsReplyMostRecent, MarkorCreateNoteAndSms): Recover from wrong app context or app name mismatch within 1-2 turns.

---

## Recommended Next Actions

### P0 — Immediate (anti-loop + perception)
1. **Anti-loop semantic context**: Check element identity (hint_text, text, desc) not just index. Actions on different elements with different content are NOT loops.
2. **Anti-loop post-completion exemption**: Allow verification passes after multi-item operations.
3. **NumberPicker scroll support**: Add scroll/increment gesture for NumberPicker widgets.

### P1 — High Priority (reasoning + strategy)
4. **Pre-action verification prompt**: "For destructive operations on ambiguous targets, verify EXACT identity before committing."
5. **Search-first calendar guidance**: "For calendar QA tasks, always try search before date navigation."
6. **Exact filename matching**: "Match filenames exactly, not by substring."
7. **Efficient form filling**: "Use `type` with `element_index` directly — no separate click-to-focus."

### P2 — Medium Priority (capability + config)
8. **Vision task overrides**: Set `perception_mode: hybrid` for image/video content tasks.
9. **Capability-aware early exit**: Detect and bail from tasks requiring disabled capabilities.
10. **Turn budget planning**: Add pre-task turn estimation to guide strategy selection.

### P3 — Low Priority (environment + tooling)
11. **VLC pre-launch**: Fix eval harness to launch VLC before accessing `app_db`.
12. **App name fuzzy matching**: Accept "Simple SMS Messenger" as alias for "SMS Messenger".
13. **Self-interaction guard**: Detect when agent interacts with its own UI during task execution.

### Projected Impact

| Fix | Tasks Impacted | Projected Score Gain |
|-----|---------------|---------------------|
| Anti-loop semantic context (P0) | RetroPlayingQueue, RecipeAddMultipleRecipesFromMarkor | +2.0 |
| Pre-action verification (P1) | MarkorDeleteNewestNote, MarkorMoveNote | +1.0-2.0 |
| Calendar search-first (P1) | SimpleCalendarAnyEventsOnDate, SimpleCalendarEventOnDateAtTime | +1.0-2.0 |
| Vision task overrides (P2) | MarkorTranscribeVideo, RecipeAddMultipleRecipesFromImage | +1.0-2.0 |
| Anti-loop post-completion (P0) | RecipeDeleteMultipleRecipesWithNoise | +0 (already 1.0, but fixes false failure claim) |

**Projected new pass rate**: 11-13/19 = 58-68% (up from current 37%)
**Hard ceiling tasks**: OsmAndMarker, OsmAndTrack (need screenshot grounding), RecipeDeleteDuplicateRecipes2 (needs working memory + turn budget), RetroPlaylistDuration (needs duration planning + efficient adds)
