# Global Issues

## Open
- [ ] **`\n` vs `\n\n` blank line interpretation** — MarkorAddNoteHeader. Agent types `\n` when task requires "blank line" (`\n\n`). 2-round recurring bug. Now compounds with FalseCompletion (agent declares success at turn 15). Need explicit system prompt rule about blank lines. (NEW: AT-R2 P1)
- [ ] **Duration-constrained playlist strategy** — RetroPlaylistDuration. Agent pre-reads individual song durations via Details dialog (~3 turns each) instead of iterative add-and-check. Added only 2 songs in 30 turns. Need system prompt strategy tip. May also be turn-budget limited (30 turns marginal for ~15 songs). (NEW: AT-R2 P2)
- [ ] **FalseCompletion on formatting tasks** — MarkorAddNoteHeader. Completion verification not specific enough about whitespace/formatting. Agent verified content visually but missed `\n` vs `\n\n` difference. Need formatting-specific verification guidance. (NEW: AT-R2 Q1, split from general FalseCompletion)
- [ ] **Keyboard visibility flag broken** — All text input tasks. Root cause found: `AccessibilityPlatform.collectRootsOnActiveDisplay()` filters `TYPE_INPUT_METHOD` windows before passing roots to Perceptor, so keyboard nodes are never traversed and `keyboardFlag` stays false. Fix: detect from `windows` list directly. (UPDATED: AT-R2, root cause identified)
- [ ] **Markor file extension default** — MarkorMergeNotes. New file dialog has separate extension field defaulting to `.md`. Agent doesn't clear it when target has no extension. Need app tip. (UNCHANGED: AT-R1)
- [ ] **SSE error classification** — RecipeAddMultipleRecipesFromImage. Retry infra exists (CloudStreamRetryRunner, MAX_RETRIES=5) but SSE stream disconnects may be classified as non-retryable by OpenAIErrorClassifier. Check error classification logic. (UNCHANGED: AT-R1)

## Resolved
- [x] **`type` action hint text contamination** — Fixed AT-R2 (commit eadbf03). `isShowingHintText` check in NodeActionPerformer. RetroCreatePlaylist, RetroSavePlaylist now pass. (RESOLVED: AT-R2, was AT-R1 P1)
- [x] **`agent_thought` ordering in tool schemas** — Fixed AT-R2 (commit eadbf03). Moved to first position in all 8 tool schemas. 0 tool failures across 101 calls in R2. (RESOLVED: AT-R2, was AT-R1 Q7)
- [x] **FalseCompletion without verification** — Fixed AT-R2 (commit eadbf03). Pre-completion checklist added to system prompt. SportsTrackerActivitiesOnDate now passes with thorough verification. MarkorMergeNotes not retested. (RESOLVED for SportsTracker: AT-R2, was AT-R1 P2)
- [x] **Markor cursor positioning** — Fixed AT-R2 (commit eadbf03). System prompt tip for Special Keys → Document Start. MarkorEditNote 0→1 in 7 turns. MarkorAddNoteHeader cursor positioning now works, but `\n` vs `\n\n` bug persists. (RESOLVED: AT-R2, was AT-R1 P4)
- [x] **App workflow discovery** — Fixed AT-R2 (commit eadbf03). Retro Music and OpenTracks workflow tips added. RetroCreatePlaylist 0→1, SportsTrackerActivitiesOnDate 0→1. RetroPlaylistDuration still 0 (strategy issue, not workflow). (RESOLVED: AT-R2, was AT-R1 P5)
- [x] **Re-enable `write_todos` tool** — Fixed AT-R2 (commit eadbf03). Removed from excluded_tools, added soft guidance. RecipeDeleteMultipleRecipes passed without using it (3 items too few). (RESOLVED: AT-R2, was AT-R1 Q12)
- [x] **type clear=false for edit fields** — MarkorEditNote now passes (fixed R35, commit ae6ecbc)
- [x] **LLM timeout on calendar queries** — SimpleCalendarEventsInNextWeek now passes (fixed R35, infra retry)
- [x] **VLC infrastructure (app_db missing)** — Fixed AT-R1 (commit b04e36d local patch). VLC tasks now run without crash. (RESOLVED: AT-R1)
- [x] **OsmAnd a11y fundamentally broken** — Moved to cannot_handle (AT-R1). Map UI inaccessible.
- [x] **Turn exhaustion hypothesis** — max_turns=50 override applied AT-R1. Result: ineffective — tasks FalseComplete early, not turn-exhausted. The real blockers are hint text contamination and FalseCompletion. Consider reverting max_turns. (RESOLVED as misdiagnosis: AT-R1)

## Parked (cannot_handle)
- MarkorTranscribeVideo — continuous (video) vision/OCR capability beyond current perception mode
- OsmAndMarker — map UI inaccessible via accessibility service (added AT-R1)
- OsmAndTrack — map UI inaccessible via accessibility service (added AT-R1)
- ExpenseDeleteDuplicates2 — ambiguous "exact duplicate" definition + visually identical noise entries with different hidden fields (added AT-R1)
- RecipeDeleteDuplicateRecipes2 — intentional same-description noise trap, agent over-deleted (4 instead of 1), information asymmetry (added AT-R1)
