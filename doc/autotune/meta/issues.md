# Global Issues

## Open
- [ ] **Tasks app date reasoning** — TasksCompletedTasksForDate, TasksDueNextWeek, TasksIncompleteTasksOnDate. Agent cannot map between absolute dates in goals (e.g., "October 16, 2023") and relative date labels in the Tasks app UI ("Mon", "Tue", "Oct 22"). Leads to navigation failure, hallucination, and missing date filters. Need system prompt tip for date computation. (NEW: AT-R3 P1)
- [ ] **Duration-constrained playlist strategy** — RetroPlaylistDuration. 3-round persistent failure. P2 tip added in R2 but agent (qwen3.5) doesn't follow it. May be model capability limitation. (UPDATED: AT-R3, tip ineffective)
- [ ] **Turn budget pressure on repetitive tasks** — RecipeDeleteDuplicateRecipes3, SportsTrackerActivitiesCountForWeek. Tasks requiring many repetitive actions (4-5 turns each) exceed 30-turn budget. SportsTracker could benefit from name heuristic tip. (NEW: AT-R3 P3)
- [ ] **Markor file extension default** — MarkorMergeNotes. New file dialog has separate extension field defaulting to `.md`. Agent doesn't clear it when target has no extension. Need app tip. (UNCHANGED: AT-R1)
- [ ] **SSE error classification** — RecipeAddMultipleRecipesFromImage. Retry infra exists (CloudStreamRetryRunner, MAX_RETRIES=5) but SSE stream disconnects may be classified as non-retryable by OpenAIErrorClassifier. Check error classification logic. (UNCHANGED: AT-R1)

## Resolved
- [x] **`\n` vs `\n\n` blank line interpretation** — Fixed AT-R3 (commit 63e3a0a). Markor blank line tip added. MarkorAddNoteHeader 0→1 after 2 rounds stuck. (RESOLVED: AT-R3, was AT-R2 P1)
- [x] **FalseCompletion on formatting tasks** — Resolved by blank line fix. MarkorAddNoteHeader now passes correctly. (RESOLVED: AT-R3, was AT-R2 Q1)
- [x] **Keyboard visibility flag broken** — Fixed AT-R3 (commit 63e3a0a). Moved detection from Perceptor node traversal to window-type check in AccessibilityPlatform. Also fixed captureScreen() which never propagated keyboardVisible. (RESOLVED: AT-R3, was AT-R2 Q3)
- [x] **`type` action hint text contamination** — Fixed AT-R2 (commit eadbf03). `isShowingHintText` check in NodeActionPerformer. RetroCreatePlaylist, RetroSavePlaylist now pass. (RESOLVED: AT-R2, was AT-R1 P1)
- [x] **`agent_thought` ordering in tool schemas** — Fixed AT-R2 (commit eadbf03). Moved to first position in all 8 tool schemas. 0 tool failures across 101 calls in R2. (RESOLVED: AT-R2, was AT-R1 Q7)
- [x] **FalseCompletion without verification** — Fixed AT-R2 (commit eadbf03). Pre-completion checklist added to system prompt. SportsTrackerActivitiesOnDate now passes with thorough verification. (RESOLVED: AT-R2, was AT-R1 P2)
- [x] **Markor cursor positioning** — Fixed AT-R2 (commit eadbf03). System prompt tip for Special Keys → Document Start. MarkorEditNote 0→1. (RESOLVED: AT-R2, was AT-R1 P4)
- [x] **App workflow discovery** — Fixed AT-R2 (commit eadbf03). Retro Music and OpenTracks workflow tips added. RetroCreatePlaylist 0→1, SportsTrackerActivitiesOnDate 0→1. (RESOLVED: AT-R2, was AT-R1 P5)
- [x] **Re-enable `write_todos` tool** — Fixed AT-R2 (commit eadbf03). (RESOLVED: AT-R2, was AT-R1 Q12)
- [x] **type clear=false for edit fields** — MarkorEditNote now passes (fixed R35, commit ae6ecbc)
- [x] **LLM timeout on calendar queries** — SimpleCalendarEventsInNextWeek now passes (fixed R35, infra retry)
- [x] **VLC infrastructure (app_db missing)** — Fixed AT-R1 (commit b04e36d local patch). (RESOLVED: AT-R1)
- [x] **OsmAnd a11y fundamentally broken** — Moved to cannot_handle (AT-R1).
- [x] **Turn exhaustion hypothesis** — Resolved as misdiagnosis AT-R1. Tasks FalseComplete early, not turn-exhausted.

## Parked (cannot_handle)
- MarkorTranscribeVideo — continuous (video) vision/OCR capability beyond current perception mode
- OsmAndMarker — map UI inaccessible via accessibility service (added AT-R1)
- OsmAndTrack — map UI inaccessible via accessibility service (added AT-R1)
- ExpenseDeleteDuplicates2 — ambiguous "exact duplicate" definition + visually identical noise entries with different hidden fields (added AT-R1)
- RecipeDeleteDuplicateRecipes2 — intentional same-description noise trap, agent over-deleted (4 instead of 1), information asymmetry (added AT-R1)
