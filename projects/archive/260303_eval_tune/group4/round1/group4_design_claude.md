# Group 4 Improvement Design — Claude

**Eval Run**: 20260227_161048 (20 tasks, 7/19 pass = 36.8%)
**Prior Work**: Group 2/3 anti-loop escalation (commit `75c3bf4`) is already deployed — this design builds on top.

---

## Common Problems Summary

After analyzing all 12 failures across 20 Group 4 tasks, I identify **6 systemic problems**, ordered by impact:

### P1. Anti-Loop False Positives (3 tasks — RetroPlayingQueue, RecipeAddMultipleRecipesFromMarkor, RecipeDeleteMultipleRecipesWithNoise)

The 3-tier anti-loop system from Group 2/3 fires correctly on genuinely stuck agents but produces false positives on **legitimate repetitive workflows**. The FP rate is 43% (3/7 triggers).

**Root pattern**: The loop detector uses two signals — screen signature similarity and repeated action signatures. Both fail on:

1. **Multi-item batch operations** (RetroPlayingQueue): Adding 5 songs to a playing queue requires long-pressing each song and clicking "Add to playing queue" at the same element index 10. The screen returns to the same song list each time, producing high screen similarity (~0.85+), and the action signature `mobile_action:click:idx=10` repeats. But this is **correct linear progress** — a different song is selected each time.

2. **Form filling with re-indexed elements** (RecipeAddMultipleRecipesFromMarkor): After scrolling a form, element indices 10/11/12 now refer to different fields (Servings/Time/Ingredients instead of Title/Description/Categories). The action signature `mobile_action:click:idx=10` repeats but targets a completely different form field.

3. **Post-completion verification** (RecipeDeleteMultipleRecipesWithNoise): After successfully completing the task (score=1.0), the agent's verification pass (searching to confirm deletion) resembled the delete workflow, triggering loop detection. The agent reported failure despite the task being done.

**Why current mitigations are insufficient**: The finer-grained action signatures from the H2 fix (e.g., `mobile_action:click:idx=10` instead of `mobile_action:click`) actually make P1 *worse* for multi-item tasks, because the same button at the same index is the correct target each iteration.

### P2. Calendar A11y Perception Gap (2 tasks — SimpleCalendarAnyEventsOnDate, SimpleCalendarEventOnDateAtTime)

Simple Calendar Pro's monthly grid renders 42 day cells as `View` elements with `text: ""` — completely invisible to a11y-only perception. The agent cannot determine which cell is which date.

Additionally, `NumberPicker` widgets (used in date pickers) don't respond to `type` actions. The agent typed "27" into the day field but the widget submitted its scroll-position value ("22") when OK was pressed. This creates a silent false-positive action — the tool reports success but nothing changed.

**Contrast**: SimpleCalendarLocationOfEvent and SimpleCalendarNextMeetingWithPerson both succeeded in 5 turns by using the **search** function or viewing events already visible.

### P3. False Success Claims (2 tasks — MarkorDeleteNewestNote, MarkorMoveNote)

The agent confidently claims GoalAchieved but the scripted evaluator scores 0.0:

- **MarkorDeleteNewestNote**: Conflated modification time with creation time. Deleted the most recently *modified* note instead of the most recently *created* one. Did not use `stat` or "Details" to verify.
- **MarkorMoveNote**: Selected `2023_02_13_shy_king_copy.md` (substring match) instead of the exact `shy_king_copy.md`. Did not scroll the file list to find the exact match. 3 other files contained "shy_king_copy" as a substring.

Both cases share a pattern: the agent takes the **first plausible match** without verifying it's the **exact correct target**.

### P4. Turn Budget Exhaustion on Multi-Item Tasks (3 tasks — RecipeAddMultipleRecipesFromMarkor, RecipeDeleteDuplicateRecipes2, RetroPlaylistDuration)

Tasks requiring many repeated operations far exceed the 30-turn budget:

| Task | Items | Turns/Item | Est. Total | Budget |
|------|-------|-----------|-----------|--------|
| RecipeAddMultipleRecipesFromMarkor | 3 recipes × 6 fields | 2 (click+type) | ~48 | 30 |
| RecipeDeleteDuplicateRecipes2 | ~5 duplicates | 4 (open→menu→delete→confirm) | ~28 | 30 |
| RetroPlaylistDuration | ~10 songs | 4 (select→menu→add→pick) | ~45 | 30 |

Contributing factors:
- Agent uses **click-then-type** for form fields (2 turns/field when 1 suffices)
- Agent doesn't estimate turn cost upfront
- Agent **re-surveys** lists instead of acting from memory (RecipeDeleteDuplicateRecipes2 spent 6/27 turns scrolling)

### P5. Self-Doubt / Abandoned Strategy (1-2 tasks — RecipeDeleteDuplicateRecipes2, partly RetroPlaylistDuration)

The agent discovers and executes a correct workflow, then second-guesses itself mid-execution:
- **RecipeDeleteDuplicateRecipes2**: Agent successfully deleted 2 duplicates using the correct 4-turn flow. Then at turn 22, it *cancelled* a pending deletion mid-flow because it noticed some same-name entries had different descriptions. This derailed it into a navigate-back loop. The recipe it was about to delete WAS a duplicate.
- **RetroPlaylistDuration**: Agent identified the correct "More options" button (element 12) on the first attempt but reverted to element 11 (the wrong button) on subsequent attempts, wasting 8 turns.

### P6. Vision/Screenshot Gap (2 tasks — MarkorTranscribeVideo, RecipeAddMultipleRecipesFromImage)

Both tasks require reading visual content (video frame text, image text) that is invisible to a11y-only perception. Already addressed in Group 2/3 design (perception_mode: hybrid overrides, early-fail guidance). Listed here for completeness.

---

## Proposed Solutions

### S1. Screen-Change-Aware Loop Detection (fixes P1)

**Problem**: The CRITICAL "screen unchanged" heuristic (`isStable` similarity >= 0.85 across 5 turns) fires even when the agent is making real progress on multi-item workflows, because the overall screen layout stays the same and only a small subset of elements change (e.g., which song is selected).

**Design**: Add a **progress signal** check that suppresses CRITICAL escalation when measurable screen-content changes exist between consecutive turns.

#### S1.1. Screen Diff Progress Detection

Add a `hasProgressBetweenTurns()` check in `LoopDetectionPolicy.detectWarning()` that compares adjacent screen signatures for **content-level changes** (not just overall similarity):

```kotlin
// In NavigationState, extend ScreenSignature comparison:
private fun hasContentProgress(
    signatures: List<ScreenSignature>,
    window: Int
): Boolean {
    val recent = signatures.takeLast(window)
    if (recent.size < 2) return false
    // Check for token churn: at least changeFraction of tokens changed between
    // consecutive pairs. Even if overall similarity is high, specific text tokens
    // changing indicates new content appearing on screen.
    return recent.zipWithNext().any { (a, b) ->
        val changed = a.tokens.symmetricDifference(b.tokens).size
        val total = a.tokens.union(b.tokens).size
        total > 0 && changed.toDouble() / total >= MIN_PROGRESS_CHURN
    }
}
```

Where `MIN_PROGRESS_CHURN = 0.05` — if even 5% of screen tokens changed between any two consecutive turns in the window, there's evidence of progress.

**Integration**: In `detectWarning()`, after the `isStable` check returns true, additionally check `hasContentProgress()`. If progress is detected, downgrade from CRITICAL to WARNING (still shows a cautionary message but does not trigger escalation):

```kotlin
if (latestSignatures.isStable(config.similarityThreshold)) {
    // Before emitting CRITICAL, check for content-level progress
    if (hasContentProgress(state.recentSignatures, config.repeatedScreenWindow)) {
        // Screen layout is similar but content changed → progress detected
        return LoopWarning(
            message = "Screen layout is similar across recent turns but content changes detected. " +
                    "Proceeding with current strategy. Consider varying your approach if stuck.",
            severity = LoopWarningSeverity.WARNING  // downgraded from CRITICAL
        )
    }
    return LoopWarning(
        message = "Screen state looks unchanged...",
        severity = LoopWarningSeverity.CRITICAL
    )
}
```

**Effect on RetroPlayingQueue**: The "1 selected" label changes between "Through the Storm" and "Hidden Paths" selections, producing token churn. Progress is detected → CRITICAL suppressed → no escalation → task continues.

**Effect on RecipeAddMultipleRecipesFromMarkor**: After scrolling, the visible form fields change (Title → Servings). Token churn exceeds 5% → no escalation.

#### S1.2. Cycle Detection Minimum Window

The cycle detection heuristic (first check in `detectWarning()`) fires when the current screen appears `cycleMinOccurrences` (2) times in recent history. This is too aggressive — a list screen naturally appears many times during multi-item operations.

**Change**: Increase `cycleMinOccurrences` from 2 to 3. A screen appearing twice is normal for multi-item tasks (return to list after each item). Three times with high similarity means genuinely no progress.

#### S1.3. Post-Completion Verification Exemption

When the agent calls `complete_task` and then gets blocked by loop detection during verification, the actual task result may be correct (as with RecipeDeleteMultipleRecipesWithNoise, score=1.0).

**Approach**: This is already partially addressed. Once `complete_task` executes, the turn should end. The issue was that the agent called `complete_task` AFTER doing verification, not before. The fix is **prompt-level guidance**: instruct the agent to call `complete_task` as soon as the primary operations are done, not after a verification pass. If verification reveals issues, do *another* iteration, then `complete_task`.

Add to system prompt:
```
### Task Completion Ordering
Call complete_task as soon as the core operations are done. Do NOT add a post-completion
verification pass that resembles the original workflow — this wastes turns and may trigger
loop detection. If you want to verify, verify BEFORE calling complete_task.
```

### S2. Calendar Navigation Strategy (fixes P2)

Two sub-problems: agent can't see date numbers in the grid, and NumberPicker type doesn't work.

#### S2.1. Prompt Guidance — Use Agenda/List View or Search

Simple Calendar Pro has a "Change view" button in the toolbar that switches between Monthly/Weekly/Daily/Agenda views. The Agenda view shows events as a scrollable text list with dates, fully accessible via a11y.

Update the Calendar App Tip:
```
### Calendar
- For date-query tasks (what events on date X), switch to Agenda or Event List view
  using the "Change view" button instead of navigating the monthly grid. The monthly
  grid cells have no a11y labels — you CANNOT identify dates from them.
- For navigating to a specific date: use the day-by-day forward arrow in daily view
  (reliable but slow), NOT the monthly grid cells.
- NumberPicker spinners (in date pickers) do NOT respond to type actions. Use the
  forward/back day arrows instead, or switch to Agenda view and scroll.
- Prefer creating events directly via the "New Event" button and using date fields in
  the event form, rather than navigating the calendar view to the target date first.
- For time pickers, ALWAYS switch to text/keyboard input mode (tap the keyboard/edit
  icon at the bottom of the time picker dialog) and type the time value directly.
```

**Impact**: SimpleCalendarAnyEventsOnDate and SimpleCalendarEventOnDateAtTime would switch to agenda/search view, bypassing the unlabeled grid entirely.

#### S2.2. NumberPicker Type Failure Detection (deferred)

The root issue is that Android's `NumberPicker` EditText accepts `setText()` via accessibility but doesn't commit the value internally (the value is stored by scroll position). A proper fix requires either:
- Implementing `ACTION_SCROLL_FORWARD` / `ACTION_SCROLL_BACKWARD` support in the a11y service
- A specialized `set_number_picker_value` tool

Both are significant platform-level changes. **Defer** to a separate design. The prompt guidance in S2.1 provides a workaround by avoiding NumberPicker entirely.

### S3. Exact-Match Verification for Destructive Actions (fixes P3)

Both false success cases share a pattern: the agent takes the first plausible match without verifying exactness.

#### S3.1. Prompt Guidance — Exact Match Protocol

Add to system prompt under `### General` or a new `### File Operations` section:
```
### File Operations
- When a task specifies a filename, match it EXACTLY. Do NOT select files that merely
  contain the target name as a substring (e.g., for 'report.md', do NOT select
  '2023_report.md' or 'final_report.md').
- In scrollable file lists, scroll through the ENTIRE list before selecting, especially
  if your first visible match is only a partial/substring match. Multiple files may share
  the target name as a substring.
- For newest/oldest file tasks: Markor and most file managers display MODIFICATION time,
  not creation time. To determine actual creation order, use shell:
  `stat /sdcard/Documents/Markor/*.md` or check the file Details in the context menu.
- For destructive operations (delete, move), verify you have the EXACT target before
  committing. Use shell `ls` or `stat` for file metadata verification. This costs 1 turn
  but prevents wrong-file errors.
```

**Impact**: MarkorDeleteNewestNote would use `stat` to find the actual newest file by creation time. MarkorMoveNote would scroll the list to find the exact `shy_king_copy.md` instead of taking `2023_02_13_shy_king_copy.md`.

### S4. Efficient Multi-Item Workflow Strategy (fixes P4)

#### S4.1. Direct-Type Form Filling

The agent wastes a turn clicking to focus a field before typing. The `type` action with `element_index` should focus and type in one step.

Add to system prompt:
```
### Form Filling
- Use type with element_index directly — do NOT click to focus first then type.
  Separate click-to-focus wastes a turn. `type(element_index=10, input_text="...")` will
  focus and type in a single action.
- For multi-item tasks (add N recipes, send N messages), estimate total turns needed
  upfront. If your estimate exceeds 80% of the turn budget, use the most compact strategy:
  skip optional fields, batch-select when possible, use shell for data transfer.
```

**Impact**: Halves form-filling cost (1 turn/field instead of 2). RecipeAddMultipleRecipesFromMarkor goes from ~48 turns to ~27 turns — within budget.

#### S4.2. Batch Selection Strategy

For tasks like "add 5 songs to playing queue" or "delete 4 duplicate recipes":
- **Multi-select**: Long-press first item, then tap remaining items to accumulate selection, then perform the batch action once.
- This reduces N × 4 turns to N + 3 turns.

Add to system prompt:
```
### Multi-Item Operations
- When adding/deleting multiple items, prefer batch selection: long-press the first item
  to enter selection mode, then tap each additional item (they accumulate), then perform
  the action once for all selected items.
- If the app doesn't support multi-select for this operation, adopt the most compact
  single-item workflow discovered. Record it to scratchpad after the first success so
  you don't re-explore on subsequent items.
```

### S5. Scratchpad-Driven Working Memory (fixes P5)

The agent forgets its discoveries and re-surveys instead of acting from memory.

#### S5.1. Prompt: Survey Once, Plan, Execute

```
### Working Memory
- For tasks requiring a survey (find duplicates, identify items to act on), scan the
  full list ONCE and write findings to scratchpad. Then execute from scratchpad data
  without re-surveying.
- Once you find a working UI workflow (e.g., delete: open → More options → Delete →
  Confirm), write it to scratchpad and replicate exactly for remaining items. Do NOT
  re-explore the UI for each item.
- Once you reach a confirmation dialog for a destructive action, commit to it. Do NOT
  cancel and re-verify mid-flow — verify BEFORE entering the deletion flow.
```

**Impact**: RecipeDeleteDuplicateRecipes2 would survey once (2 turns), write findings to scratchpad, then execute 4-5 deletions without re-surveying. This saves ~6 turns of redundant scrolling and avoids the self-doubt cancellation that derailed the task.

### S6. Vision Task Overrides (fixes P6)

Already addressed in Group 2/3 implementation. Add remaining overrides:

```yaml
task_overrides:
  MarkorTranscribeVideo: { perception_mode: hybrid }
  # RecipeAddMultipleRecipesFromImage already covered by existing hybrid override
```

---

## Implementation Plan

### Phase 1: Low-Risk, High-Impact (prompt changes only)

| Item | Fix | Est. Impact |
|------|-----|-------------|
| S1.3 | Task completion ordering prompt | Avoids post-completion FP loops |
| S2.1 | Calendar agenda/search guidance | +1–2 tasks (calendar date queries) |
| S3.1 | Exact match + file verification prompt | +1–2 tasks (MarkorDeleteNewest, MarkorMove) |
| S4.1 | Direct-type form filling guidance | Improves multi-item task efficiency |
| S4.2 | Batch selection guidance | +1 task (RetroPlayingQueue) |
| S5.1 | Survey-once working memory guidance | +1 task (RecipeDeleteDuplicateRecipes2) |
| S6 | MarkorTranscribeVideo hybrid override | +1 task |

All prompt changes go in `StandaloneAgentDef.kt`. Config override in `default.yaml`.

### Phase 2: Code Changes (anti-loop policy)

| Item | Fix | Est. Impact |
|------|-----|-------------|
| S1.1 | Screen diff progress detection in LoopDetectionPolicy | Eliminates FP on multi-item tasks |
| S1.2 | Increase cycleMinOccurrences 2→3 | Reduces FP sensitivity |

Changes in `LoopDetectionPolicy.kt` and `NavigationState.kt`.

### Phase 3: Deferred

| Item | Fix | Reason |
|------|-----|--------|
| S2.2 | NumberPicker scroll support | Platform-level change, needs separate design |
| OsmAnd tasks | Screenshot-based perception | Fully a11y-blind apps need vision grounding |
| Turn budget increase | Per-task `max_turns: 50` | Needs eval validation to avoid masking issues |

---

## Projected Impact

| Fix | Tasks Impacted | Score Gain |
|-----|---------------|------------|
| S1 (anti-loop FP reduction) | RetroPlayingQueue, RecipeAddMultipleRecipesFromMarkor | +2.0 |
| S2 (calendar guidance) | SimpleCalendarAnyEventsOnDate, SimpleCalendarEventOnDateAtTime | +1.0–2.0 |
| S3 (exact match) | MarkorDeleteNewestNote, MarkorMoveNote | +1.0–2.0 |
| S4+S5 (efficiency + memory) | RecipeDeleteDuplicateRecipes2, RetroPlaylistDuration | +0.5–1.0 |
| S6 (vision override) | MarkorTranscribeVideo | +0.5–1.0 |

**Current pass rate**: 7/19 = 36.8%
**Projected pass rate**: 11–14/19 = 58–74%
**Hard ceiling tasks**: OsmAndMarker, OsmAndTrack (need vision grounding)
