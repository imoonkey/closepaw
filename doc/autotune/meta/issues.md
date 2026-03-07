# Global Issues

## Open
- [ ] **One-action-per-turn architecture bottleneck** — RecipeAddMultipleRecipesFromImage, RecipeAddMultipleRecipesFromMarkor, SportsTrackerActivitiesCountForWeek, VlcCreateTwoPlaylists. Form-filling costs ~16 turns/recipe, file operations cost ~3 turns/file, item inspection costs ~4-5 turns/item. Tasks mechanically exceed turn budget. Batch form-fill tool or multi-select teaching needed. (NEW: AT-R7 P1)
- [ ] **Tasks app skill not injected** — TasksCompletedTasksForDate, TasksDueNextWeek, TasksIncompleteTasksOnDate. org.tasks/SKILL.md was created but not loaded at runtime. Need to verify actual package name on emulator. (NEW: AT-R7 P2)
- [ ] **Destructive observation (checkbox toggle)** — TasksCompletedTasksForDate, TasksIncompleteTasksOnDate. Agent clicks checkboxes to inspect completion state, toggling them and corrupting app state. Need read-only observation via a11y properties or skill guidance. (NEW: AT-R7 P3)
- [ ] **Literal target verification before completion** — AudioRecorderRecordAudioWithFileName, MarkorMergeNotes, OsmAndMarker. Agent claims success after approximate artifact creation or adjacent-action execution without verifying the exact requested end state. (NEW: GPT-5.4 smoke)
- [ ] **Cross-app destination binding on import/logging tasks** — ExpenseAddMultipleFromGallery, ExpenseAddMultipleFromMarkor. Agent extracts source data correctly but loses the required destination app/object, then mistakes preexisting or same-app state for success. (NEW: GPT-5.4 smoke)
- [ ] **Tasks app priority perception gap** — TasksHighPriorityTasksDueOnDate. Priority radio buttons have no a11y text labels. Agent cannot determine priority level. REGRESSED in R4 (1.0→0.0, natural variance). (NEW: AT-R4 P2)
- [ ] **Tasks app completed-tasks navigation** — TasksCompletedTasksForDate. Agent cannot find "Show completed" filter toggle. (NEW: AT-R4 P1 sub-issue)
- [ ] **Premature shell pivot on SportsTracker** — SportsTrackerTotalDistanceForCategoryOverInterval. Agent abandons working UI Edit workflow too early and wastes turns on shell. REGRESSED in R4 (1.0→0.0). (NEW: AT-R4 P3)
- [ ] **Markor file extension default** — MarkorMergeNotes. New file dialog has separate extension field defaulting to `.md`. Agent doesn't clear it when target has no extension. Need app tip. (UNCHANGED: AT-R1)
- [ ] **SSE error classification** — RecipeAddMultipleRecipesFromImage. Retry infra exists (CloudStreamRetryRunner, MAX_RETRIES=5) but SSE stream disconnects may be classified as non-retryable by OpenAIErrorClassifier. Check error classification logic. (UNCHANGED: AT-R1)
- [ ] **SportsTracker activity type perception** — SportsTrackerActivitiesCountForWeek. A11y tree shows activity titles but not sport type metadata. Agent now uses drill-down (R7) but exhausts turns before checking all items. Shell+SQLite shortcut proposed. (UPDATED: AT-R7, was AT-R6 P3)

## Resolved
- [x] **Multi-item workflow premature self-termination** — Loop detection WARNING advisories deleted in AT-R7. RecipeAddMultipleRecipesFromMarkor now uses all 50 turns and saves 2/3 recipes (was 22 turns, 1/3 partial). VlcCreateTwoPlaylists 0.0→0.5. Agent no longer self-terminates due to fabricated/real tool dominance warnings. (RESOLVED: AT-R7, was AT-R6 P2)
- [x] **Tasks app premature completion without scroll** — Partially addressed by org.tasks skill + loop detection removal in AT-R7. Tasks app tasks now spend more turns (2→7-14) but skill was not injected. Superseded by "Tasks app skill not injected" and "Destructive observation" issues. (RESOLVED partially: AT-R7, was AT-R6 P1)
- [x] **VLC playlist creation** — VlcCreatePlaylist first-ever pass in AT-R6 (gpt-5.4). Confirmed stable in AT-R7 (1.0, 20 turns). (RESOLVED: AT-R6)
- [x] **Tasks app date reasoning** — Date-label tip added AT-R4 (commit 7a85be5). Partially effective: TasksIncompleteTasksOnDate now computes date map correctly. But tasks still fail due to no-scroll and navigation issues. Downgraded from primary blocker to sub-issue. (RESOLVED partially: AT-R4, was AT-R3 P1)
- [x] **Timeout finalization observability** — Fixed AT-R4 (commit 7a85be5). 3s delay between stop_agent and force_stop. SportsTrackerActivitiesCountForWeek now emits session_stopped correctly (was InfraError → FalseCompletion). (RESOLVED: AT-R4, was AT-R3 P3)
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
- RecipeDeleteDuplicateRecipes3 — structurally impossible at any reasonable turn budget: 10+ duplicates × 4 turns/deletion = 40+ productive turns minimum (added AT-R4)
- RetroPlaylistDuration — qwen3.5-blocked: 7 consecutive 0.0, model cannot execute multi-step batch-select workflow due to duplicate "More options" grounding error (added AT-R4)
