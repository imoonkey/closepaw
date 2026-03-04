# Global Issues

## Open
- [ ] **`type` action hint text contamination** — VlcCreatePlaylist, VlcCreateTwoPlaylists, RecipeAddMultipleRecipesFromMarkor, RecipeAddMultipleRecipesFromImage. `NodeActionPerformer.setTextOnNode()` line 197: `node.text` returns hint text on empty fields. Fix: `node.isShowingHintText` check + unit tests. P0 platform fix. (NEW: AT-R1)
- [ ] **`agent_thought` ordering in tool schemas** — All tools. Currently `agent_thought` listed after `action` in JSON schema. Move to first position so LLM generates chain-of-thought before decision. 8 tools: MobileActionTool, WaitTool, DelegateTaskTool, OpenAppTool, ScratchpadTool, WriteTodosTool, AskUserTool, SystemButtonTool. (NEW: AT-R1 Q7)
- [ ] **FalseCompletion without verification** — SportsTrackerActivitiesOnDate, MarkorMergeNotes. Agent claims success without checking work. Need pre-completion self-check guardrail in system prompt. (UPDATED: AT-R1, removed ExpenseDeleteDuplicates2/RecipeDeleteDuplicateRecipes2 → cannot_handle)
- [ ] **Markor file extension default** — MarkorMergeNotes. New file dialog has separate extension field defaulting to `.md`. Agent doesn't clear it when target has no extension. Need app tip. (NEW: AT-R1)
- [ ] **Markor cursor positioning** — MarkorAddNoteHeader. Agent can't reliably position cursor at document start. Markor-specific: Special Keys → Jump to Beginning. Generic: ACTION_SET_SELECTION-based cursor positioning at tool layer. (UPDATED: AT-R1)
- [ ] **Keyboard visibility flag** — All text input tasks. Perception filters keyboard from a11y tree but leaves no trace. Add `keyboardVisible` boolean to ScreenSnapshot/prompt JSON so agent knows BACK will dismiss keyboard first. (NEW: AT-R1 Q3)
- [ ] **App workflow discovery** — RetroPlaylistDuration (add-to-playlist: 3-dot → Add to playlist), SportsTrackerActivitiesOnDate (activity type: More options → Edit). Need targeted app tips. (UPDATED: AT-R1)
- [ ] **Re-enable `write_todos` tool** — Currently excluded in `eval/config/default.yaml`. Needed for multi-step tasks (RecipeAddMultiple, ExpenseAddMultiple). Remove from `excluded_tools`, add soft guidance in system prompt. (NEW: AT-R1 Q12)
- [ ] **SSE error classification** — RecipeAddMultipleRecipesFromImage. Retry infra exists (CloudStreamRetryRunner, MAX_RETRIES=5) but SSE stream disconnects may be classified as non-retryable by OpenAIErrorClassifier. Check error classification logic. (UPDATED: AT-R1, downgraded from "no retry" to "error classification")

## Resolved
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
