# Global Issues

## Open
- [ ] **Turn exhaustion on multi-item tasks** — RecipeAddMultipleRecipesFromImage, RecipeAddMultipleRecipesFromMarkor, RecipeDeleteDuplicateRecipes2, ExpenseDeleteDuplicates2. Tasks need 8-10 turns per item × 3+ items, exceeding 30-turn budget. Options: raise max_turns or batch operations.
- [ ] **OsmAnd a11y fundamentally broken** — OsmAndMarker, OsmAndTrack. Map UI inaccessible via accessibility service. Hybrid mode didn't help. Candidate for cannot_handle.
- [ ] **Markor file rename workflow** — MarkorAddNoteHeader, MarkorMergeNotes. Agent can't find rename in file settings menu; needs to use long-press context menu from file list. Tip exists but too vague.
- [ ] **FalseCompletion / goal claim precision** — Agent claims success but scorer disagrees in ~39% of "success" claims (R34). SportsTrackerActivitiesOnDate, RetroPlaylistDuration particularly affected.
- [ ] **VLC infrastructure** — VlcCreateTwoPlaylists, VlcCreatePlaylist. Recurring init failures (app_db missing, "already called" errors). Partially fixed but new issues emerge.

## Resolved
- [x] **type clear=false for edit fields** — MarkorEditNote now passes (fixed R35, commit ae6ecbc)
- [x] **LLM timeout on calendar queries** — SimpleCalendarEventsInNextWeek now passes (fixed R35, infra retry)
- [x] **Strategy-pivot prompt for loops** — Added but ineffective (R35). Agents still loop 20+ turns. Needs stronger intervention.

## Parked (cannot_handle)
- MarkorTranscribeVideo — requires vision/OCR capability beyond current perception mode
