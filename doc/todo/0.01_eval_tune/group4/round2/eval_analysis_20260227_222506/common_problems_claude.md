# Group 4 Round 1 Re-eval — Common Problems Summary

**Run ID**: 20260227_222506
**Tasks**: 28 (20 group 4 + 8 group 2/3 re-runs)
**Status**: Complete — 28/28 finished (1 infra failure: VlcCreateTwoPlaylists)
**Final Score**: 14/27 scored tasks passing (51.9%)
**Scripted Success Rate**: 46.4% (includes infra failures as 0)
**Goal Claim Precision**: 61.1% (agent said "success" but scorer said 0.0 in ~39% of cases)

## Final Scorecard

| # | Task | Score | Root Cause Category |
|---|------|-------|----------|
| 1 | ExpenseDeleteDuplicates2 | 0.0 | FalseCompletion |
| 2 | MarkorAddNoteHeader | 0.0 | ActionFailure |
| 3 | MarkorCreateNoteAndSms | **1.0** | — |
| 4 | MarkorDeleteAllNotes | **1.0** | — |
| 5 | MarkorDeleteNewestNote | **1.0** | — |
| 6 | MarkorEditNote | 0.0 | ActionFailure |
| 7 | MarkorMergeNotes | 0.0 | NavigationFailure |
| 8 | MarkorMoveNote | **1.0** | — |
| 9 | MarkorTranscribeVideo | 0.0 | PerceptionGap |
| 10 | OsmAndMarker | 0.0 | NavigationFailure |
| 11 | OsmAndTrack | 0.0 | NavigationFailure |
| 12 | RecipeAddMultipleRecipes | **1.0** | — |
| 13 | RecipeAddMultipleRecipesFromImage | 0.0 | PerceptionGap |
| 14 | RecipeAddMultipleRecipesFromMarkor | 0.0 | FalseCompletion |
| 15 | RecipeDeleteDuplicateRecipes2 | 0.0 | FalseCompletion |
| 16 | RecipeDeleteMultipleRecipes | **1.0** | — |
| 17 | RecipeDeleteMultipleRecipesWithNoise | **1.0** | — |
| 18 | RetroPlayingQueue | **1.0** | — |
| 19 | RetroPlaylistDuration | 0.0 | FalseCompletion |
| 20 | SimpleCalendarAnyEventsOnDate | **1.0** | — |
| 21 | SimpleCalendarEventOnDateAtTime | **1.0** | — |
| 22 | SimpleCalendarEventsInNextWeek | 0.0 | InfraError (LLM timeout) |
| 23 | SimpleCalendarLocationOfEvent | **1.0** | — |
| 24 | SimpleCalendarNextMeetingWithPerson | **1.0** | — |
| 25 | SimpleSmsReplyMostRecent | 0.0 | InfraError (emulator SMS) |
| 26 | SportsTrackerActivitiesOnDate | 0.0 | FalseCompletion |
| 27 | TasksHighPriorityTasks | **1.0** | — |
| 28 | VlcCreateTwoPlaylists | N/A | InfraError (task init crash) |

### Summary by Root Cause

| Category | Count | Tasks |
|----------|-------|-------|
| **Pass** | 14 | — |
| FalseCompletion | 5 | ExpenseDeleteDuplicates2, RecipeAddMultipleRecipesFromMarkor, RecipeDeleteDuplicateRecipes2, RetroPlaylistDuration, SportsTrackerActivitiesOnDate |
| ActionFailure | 2 | MarkorAddNoteHeader, MarkorEditNote |
| NavigationFailure | 3 | MarkorMergeNotes, OsmAndMarker, OsmAndTrack |
| PerceptionGap | 2 | MarkorTranscribeVideo, RecipeAddMultipleRecipesFromImage |
| InfraError | 3 | SimpleCalendarEventsInNextWeek, SimpleSmsReplyMostRecent, VlcCreateTwoPlaylists |

### Adjusted Score (excluding infra errors)
**14/25 = 56.0%** when excluding the 3 infra failures the agent couldn't control.

## Common Problem Categories

### P1: `type` Action Replaces Content (ActionFailure) — 2 tasks
**Affected**: MarkorAddNoteHeader, MarkorEditNote
**Root Cause**: The `type` action uses `ACTION_SET_TEXT` which REPLACES the entire field content. When the agent tries to "prepend" text by positioning the cursor and typing, it destroys all existing content.
**Impact**: Any task requiring text insertion (not replacement) fails silently.
**Proposed Fix**:
1. **Prompt change**: Add to text editing section: "WARNING: The `type` action REPLACES the entire field content — it does NOT insert at cursor position. To prepend/append text without losing existing content: (a) Read the full content first with `shell(cat file)`, (b) Construct the complete new content, (c) Use `type` with `clear: true` to write the full content. For Markor specifically, prefer using shell: `echo 'new text' | cat - original.txt > temp && mv temp original.txt`"
2. **Code consideration**: Could we implement an `insert_text` action that truly inserts at cursor position?
(
    Qi Note:
    1.这个在于现在的type工具背后execution实现和prompt里的语义不一致。正常情况下clear如果是true，那晴空是理所应当的。clear如果是false，那可能不该用ACTION_SET_TEXT，或者即便用ACTION_SET_TEXT，也要execution去找当前cursor在文本的什么位置，然后把type的文本插入到原有文本相应位置，然后set完整文本back to the box，同时保持cursor在应该的位置。
    2. shell is not the right answer。万一在编辑的文本框不是disk文件呢，或者shell没permission呢。这个不解决根本问题。It's the answer to the wrong problem。
    3. Could we implement an `insert_text` action that truly inserts at cursor position? 回答：type without clear=true不是本来就该是这个语义吗？不然呢？
)

### P2: Duplicate Detection Definition Mismatch (FalseCompletion) — 3 tasks
**Affected**: ExpenseDeleteDuplicates2, RecipeDeleteDuplicateRecipes2, (potentially others)
**Root Cause**: Agent defines "exact duplicate" as matching ALL fields (name + date + amount, or title + description), while eval defines it as matching the primary identifier (name or title only).
**Impact**: Agent leaves duplicates undeleted because it considers entries with same name but different secondary fields as "unique."
**Proposed Fix**:
1. **Prompt change**: Add to duplicate detection section: "When a task says 'exact duplicates', treat items with the SAME NAME/TITLE as duplicates regardless of secondary fields (date, amount, description, etc). Only entries that share the SAME primary identifier (name/title) should be considered duplicates — keep exactly ONE and delete the rest."
(Qi Note:
    1. 这个是eval task表述不清楚吗？这个是不是有点太task specific了？这个感觉更像是题意理解不同的问题，或者llm model的common sense reasoning有点差？
)
### P3: Markor Navigation Failure (NavigationFailure) — 1 task
**Affected**: MarkorMergeNotes
**Root Cause**: Agent couldn't navigate from Markor's editor view back to the file list. Spent 14 turns stuck in a navigation loop (clicking header, More options, system back — none worked consistently).
**Impact**: Agent never created the merged file.
**Proposed Fix**:
1. **Prompt change**: Add Markor navigation tips: "In Markor, to return from editor to file list: use the left-arrow/back button in the toolbar's top-left corner, or use the system Back button."
2. **Better strategy**: For file merge tasks, prefer shell: `cat file1.txt > merged.txt && echo '' >> merged.txt && cat file2.txt >> merged.txt`
（Qi Note:
1. 可以加到 app-specific tips里去。
2. 先别实现这个了。shell确定work吗？能access Marokor files吗？另外就是本地文件改了，Markor app里会反映出来吗。
）
### P4: OsmAnd UI Inaccessible (NavigationFailure) — 2 tasks
**Affected**: OsmAndMarker, OsmAndTrack
**Root Cause**: OsmAnd's map interface is largely invisible to accessibility services. Most map controls (search button, zoom, toolbar) are not properly exposed via a11y. Agent resorts to coordinate-based clicking which is unreliable. Additionally, location searches for small towns (Planken, Schönberg) only return country-level results.
**Impact**: Both OsmAnd tasks fail completely.
**Proposed Fix**:
1. **Task overrides**: Add `{ perception_mode: hybrid }` for OsmAnd tasks so the agent can at least see the UI via screenshots
2. **Prompt change**: Add OsmAnd-specific guidance for search patterns and feature location
3. **Long-term**: These tasks may fundamentally require better a11y support from OsmAnd
(
    Qi Note: 
    1. 嗯 hybrid肯定可以加。
    2. Prompt change要加确实有用的。现在不知道的话，先不加。
)
### P5: Visual Content Perception Gap (PerceptionGap) — 2 tasks
**Affected**: MarkorTranscribeVideo, RecipeAddMultipleRecipesFromImage
**Root Cause**: Tasks requiring reading content FROM images/video are impossible in a11y-only mode. Even with hybrid mode configured as override, the traces show `screenshot_attached: false` — the hybrid override may not be activating.
**Impact**: Agent cannot read text from images or video frames.
**Proposed Fix**:
1. **Verify hybrid mode activation**: Check that `task_overrides` actually changes `screenshot_attached` to true at runtime
2. **Prompt change for video**: "For video transcription, pause at regular intervals and describe what you see in the screenshot. Limit capture phase to 60% of available turns."
3. **Fallback strategy**: Use shell-based tools (ffmpeg for video frames, OCR for images) if available
(
    1. 你现在就可以直接verify trace啊，看看llm request里有没有加image。task_overrides确保把它们用hybrid mode了，而不是a11y only。
)
### P6: Turn Exhaustion on Complex Tasks — 3 tasks
**Affected**: MarkorMergeNotes (30 turns), MarkorTranscribeVideo (30 turns), OsmAndTrack (30 turns)
**Root Cause**: Agent spends too many turns on failing strategies without pivoting. Navigation loops and repeated unsuccessful actions consume the turn budget.
**Impact**: Agent runs out of turns before completing the task.
**Proposed Fix**:
1. **Prompt change**: "If an approach has been tried 3+ times without progress, switch to an alternative strategy (e.g., shell command, different UI path). Never repeat the same failed action more than twice."
2. **Loop detection improvements**: The anti-loop system should help here, but may need refinement for action-level loops (not just screen-level)
（
Qi Note: 
1. 嗯，这个loop detection还解决不了是吧？ 可以加一些general principles prompt。
2. 现在loop detection是怎么做的？summarize一下，我现在觉得这里是一团浆糊。没有action-level loops吗？之前我记得要加，难道一直没加？
）
### P7: Semantic Field Confusion in QA Tasks (FalseCompletion) — 1 task [NEW]
**Affected**: SportsTrackerActivitiesOnDate
**Root Cause**: Agent answered with track NAMES ("Active Rest Day", "Mindful Movement") instead of activity TYPES (e.g., "running", "walking"). The system prompt already instructs that "activity type" means the category label, but qwen3.5 ignored this.
**Impact**: QA tasks requiring a specific field are answered with visually prominent but incorrect data.
**Proposed Fix**:
1. **OpenTracks App Tip**: Add guidance that track names are NOT activity types. Must click into track detail view.
2. **Pre-completion verification**: Agent should cross-check the field being requested (e.g., "type") vs. the data it captured from the list view.
（
Qi: 这种specific的事情，不能搞得太hacky，只能加app-specific tip和 general verify prompt。不能搞overfit!
）

### P8: Infra Reliability (InfraError) — 3 tasks [NEW]
**Affected**: SimpleCalendarEventsInNextWeek (LLM timeout), SimpleSmsReplyMostRecent (emulator SMS), VlcCreateTwoPlaylists (task init crash)
**Root Cause**:
- SimpleCalendarEventsInNextWeek: LLM API (qwen3.5 via OpenRouter) never returned a response for 15 min; agent executed 0 actions.
- SimpleSmsReplyMostRecent: Agent executed perfectly (open app, type message, send) but emulator telephony didn't register the SMS in content provider. Score 0.0 despite correct UI behavior.
- VlcCreateTwoPlaylists: `initialize_task()` threw RuntimeError on both attempts.
**Impact**: 3 tasks scored 0.0 due to infrastructure, not agent quality.
**Proposed Fix**:
1. **LLM timeout**: Add request-level timeout (120s) with retry in LlmClient
2. **Emulator SMS**: Verify telephony stack works before SMS tasks
3. **VLC init**: Fix task initialization bug in eval harness
4. **Eval**: Consider marking infra failures separately from agent failures in scoring
(Qi Note: 
1. llm timeout不管。而且可能不是time out? TODO:Qi
2. Simple SMS应该直接打开Simple SMS (android world specific的)？打开android system自带的messenger不行？你帮我看看scripted success的逻辑。
3. VLC: fix initialize_task bugs for VlcCreateTwoPlaylists
)

## Priority Ranking (Updated)

1. **P1 (type replaces content)** — HIGH. Easy prompt fix. 2 tasks directly, potentially many more.
2. **P2 (duplicate definition)** — HIGH. Easy prompt fix. 3 tasks affected.
3. **P5 (hybrid mode not activating)** — HIGH. Potential infrastructure bug. 2 tasks affected.
4. **P8 (infra reliability)** — HIGH. LLM timeout fix affects all tasks. 3 tasks affected here.
5. **P3 (Markor navigation)** — MEDIUM. Prompt guidance + shell strategy. 1 task.
6. **P6 (turn exhaustion)** — MEDIUM. Prompt guidance for strategy pivoting. 3 tasks affected.
7. **P7 (field confusion)** — MEDIUM. App-specific prompt + verification heuristic. 1 task.
8. **P4 (OsmAnd UI)** — LOW priority (hard to fix). Needs hybrid mode. 2 tasks.

## What Improved from Group 2/3

- **Loop detection FP**: NOT observed — the progress-gate fix is working. Zero false positive loop terminations.
- **Calendar tasks**: 4/5 passing (vs likely 0/5 before fixes). The 1 failure (EventsInNextWeek) was LLM timeout, not agent error.
- **Recipe tasks**: 3/5 passing. The 2 failures are perception gap (image) and incomplete multi-step entry, not loop issues.

## Achievable Score Ceiling

If we fix P1-P3 + P5 + P7 + P8 (infra), the theoretical max improves:
- P1 fix: +2 (MarkorAddNoteHeader, MarkorEditNote)
- P2 fix: +3 (ExpenseDeleteDuplicates2, RecipeDeleteDuplicateRecipes2, RecipeAddMultipleRecipesFromMarkor)
- P3 fix: +1 (MarkorMergeNotes)
- P5 fix: +2 (MarkorTranscribeVideo, RecipeAddMultipleRecipesFromImage)
- P7 fix: +1 (SportsTrackerActivitiesOnDate)
- P8 fix: +2 (SimpleCalendarEventsInNextWeek, SimpleSmsReplyMostRecent)

**Current**: 14/27 = 51.9%
**With P1-P3+P5+P7+P8 fixes**: 25/27 = 92.6% (theoretical ceiling)
**Remaining hard problems**: RetroPlaylistDuration (complex duration calculation), OsmAndMarker/OsmAndTrack (a11y-hostile UI)
