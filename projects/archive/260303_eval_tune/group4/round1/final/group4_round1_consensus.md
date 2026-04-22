# Group 4 Round 1 — Aligned Consensus Design

**Sources**: `group4_design_claude.md` (Claude) + `20260227_group4_round1_codex.md` (Codex)
**Eval Run**: 20260227_161048 (20 tasks, 7/19 pass = 36.8%)

---

## Alignment Summary

Both designs independently identify the same top problems in the same priority order. The consensus merges the best approaches from each side, resolving divergences where they exist.

| Problem | Claude | Codex | Consensus |
|---------|--------|-------|-----------|
| Anti-loop FP | S1: Screen diff progress detection + downgrade CRITICAL→WARNING | P0: Enrich action signatures with element semantics + safe escalation gate | **Progress-aware downgrade on BOTH detection branches** (cycle + isStable) + threshold bump + safe escalation gate. Signature enrichment deferred to Phase 2. |
| Calendar a11y | S2: Prompt guidance for Agenda view; defer NumberPicker | P1.1: Prompt correction + P1.2: TypeExecutor guard for NumberPicker | **TypeExecutor guard adopted** (Codex P1.2) + prompt (merged) |
| False success | S3: Exact-match prompt | P1.3: Exact match + completion guard | **Merged prompt** (both agree) |
| Vision gap | S6: Hybrid override for MarkorTranscribeVideo | P2: Hybrid overrides + stable fail-fast | **Both agree** |
| Turn efficiency | S4+S5: Direct-type, batch-select, scratchpad memory | (not explicitly addressed) | **Claude additions adopted** |
| Completion ordering | S1.3: Verify-before-complete prompt | (not explicitly addressed) | **Claude addition adopted** |

---

## Consensus Design

### D1. Anti-Loop V2: Progress-Aware Detection (P0 priority)

Both designs agree the anti-loop system needs progress awareness. Codex correctly identified that Group 4 false positives are primarily triggered by the **cycle detection** path (screen appearing N+ times with similarity >= 0.75), NOT the `isStable` path (5 consecutive similar screens). For multi-item tasks, screens alternate (list → action → list → action) so consecutive screens differ — `isStable` doesn't fire. But the list screen recurs, triggering cycle detection.

The consensus prioritizes progress-aware downgrade on **both** detection branches (D1.2) and threshold increase (D1.3) as Phase 1 code changes. Action signature enrichment (D1.1) is deferred to Phase 2 as a lower-priority improvement.

#### D1.1. Enrich Action Signatures with Element Semantics (from Codex P0.1) — Phase 2

**Problem**: `mobile_action:click:idx=10` is the same signature whether clicking "Add to queue" for Song A or Song B.

**Design**: Extend `classifyActionSignature()` to include the target element's semantic identity (text/desc/hintText) from the current screen snapshot when available.

**Changes**:
- `ActionSignature.kt`: Add an overload `classifyActionSignature(toolCall, snapshot)` that looks up the element at the given index and appends a text/desc hash to the signature.
  ```kotlin
  internal fun classifyActionSignature(
      toolCall: ToolCallRequest,
      snapshot: ScreenSnapshot? = null
  ): String {
      val base = classifyActionSignatureBase(toolCall)
      if (snapshot == null) return base
      // For click/type with element_index, append element text hash
      val idx = toolCall.arguments.optInt("element_index", -1)
      if (idx < 0 || idx >= snapshot.elements.size) return base
      val elem = snapshot.elements[idx]
      val semantic = (elem.text + "|" + elem.description).trim().take(32).lowercase()
      if (semantic.isBlank() || semantic == "|") return base
      return "$base:sem=${semantic.hashCode().toUInt().toString(16)}"
  }
  ```
- `TurnExecutionPhaseRunner.kt`: Pass `currentSnapshot` to signature generation in `selectActionSignatureForNextTurn()`.

**Effect**: "click:idx=10:sem=a3f2" (Song A) vs "click:idx=10:sem=7b1c" (Song B) are now distinct signatures. The "3 identical actions" heuristic no longer fires for multi-item workflows on different targets.

**Why Phase 2**: This only affects the repeated-action check (WARNING severity, not escalating) — it doesn't fix the cycle detection CRITICAL that causes BLOCK/FORCE_COMPLETE. D1.2+D1.3 address the primary FP path. D1.1 is a nice-to-have that reduces noisy warnings.

#### D1.2. Screen Content Progress Detection — Both Branches (from Claude S1.1 + Codex feedback)

**Problem**: Both the cycle detection and `isStable` heuristics can fire CRITICAL on multi-item workflows where the screen layout is similar but content is meaningfully different (different song selected, different form fields visible, etc.).

**Key insight** (Codex): Group 4 FPs are triggered by cycle detection, not isStable. For RetroPlayingQueue, screens alternate [songs_list, selection_toolbar, songs_list, ...]. The songs_list recurs, hitting `cycleMinOccurrences=2` with similarity >= 0.75. But consecutive screens differ (list vs toolbar), so isStable doesn't fire.

**Design**: Before emitting CRITICAL on either detection branch, check for content-level token changes between the matching screens. If matching screens show meaningful token differences, the agent is making progress despite similar layout — downgrade from CRITICAL to WARNING.

**Changes**:
- `NavigationState.kt`: Add `Set<String>.symmetricDifference()` extension.
- `LoopDetectionPolicy.kt`: Add a unified progress check and apply it to both branches:

  ```kotlin
  companion object {
      /** Minimum token differences between matching screens to count as progress. */
      private const val MIN_PROGRESS_TOKEN_DIFF = 2
  }

  /**
   * Check if a group of similar screens shows content-level progress.
   * Returns true if any consecutive pair in the group has at least
   * [MIN_PROGRESS_TOKEN_DIFF] tokens that differ (symmetric difference).
   */
  private fun hasProgressInGroup(screens: List<ScreenSignature>): Boolean {
      if (screens.size < 2) return false
      return screens.zipWithNext().any { (a, b) ->
          val diff = a.tokens.subtract(b.tokens).size + b.tokens.subtract(a.tokens).size
          diff >= MIN_PROGRESS_TOKEN_DIFF
      }
  }
  ```

  **Cycle detection branch** (primary FP fix):
  ```kotlin
  if (matchCount >= config.cycleMinOccurrences) {
      val matchingScreens = state.recentSignatures.filter {
          it.similarityTo(current) >= config.cycleMatchThreshold
      }
      if (hasProgressInGroup(matchingScreens)) {
          return LoopWarning(
              message = "Screen layout is recurring ($matchCount times) but " +
                  "content changes detected between visits. Continuing current " +
                  "strategy. Switch approach if no further progress.",
              severity = LoopWarningSeverity.WARNING  // downgraded
          )
      }
      return LoopWarning(message = "Cycle detected: ...", severity = CRITICAL)
  }
  ```

  **isStable branch** (secondary, for completeness):
  ```kotlin
  if (latestSignatures.isStable(config.similarityThreshold)) {
      if (hasProgressInGroup(latestSignatures)) {
          return LoopWarning(
              message = "Screen layout is stable but content changes detected. " +
                  "Continuing current strategy. Switch approach if no further progress.",
              severity = LoopWarningSeverity.WARNING  // downgraded
          )
      }
      return LoopWarning(message = "Screen state unchanged...", severity = CRITICAL)
  }
  ```

**Threshold**: `MIN_PROGRESS_TOKEN_DIFF = 2` — at least 2 tokens must differ between any consecutive pair of matching screens. This catches real changes (different song name, different selection state) while ignoring noise (timestamp updates).

#### D1.3. Cycle Detection Threshold Increase (from Claude S1.2)

Increase `cycleMinOccurrences` from 2 to 3. A screen appearing twice is normal for multi-item tasks (return to list after each operation). Three occurrences with high similarity indicates genuine stuckness.

#### D1.4. Safe Escalation Gate (from Codex P0.3)

FORCE_COMPLETE should require stronger evidence:
- Consecutive no-progress (existing: `consecutiveLoopTurns >= 5`)
- AND no new semantic content in screen signatures across the window (new)
- The downgraded WARNING from D1.2 should NOT increment `consecutiveLoopTurns`

**Implementation**: In `AgentTurnRunner.prepareTurn()`, only increment `consecutiveLoopTurns` when the warning severity is CRITICAL (unchanged from current logic). The progress-downgrade in D1.2 changes severity to WARNING, which naturally skips the increment.

### D2. Calendar & Picker Reliability (P1 priority)

#### D2.1. Prompt Correction (merged)

Both designs agree the current NumberPicker guidance ("type the value directly into the editable text field") is wrong and harmful. Replace with:

```
### Calendar
- For date-query tasks (what events on date X), switch to Agenda or Event List view
  using the "Change view" button. The monthly grid cells have NO a11y labels — you
  CANNOT identify dates from them.
- For navigating to a specific date: use the day-by-day forward/back arrows in daily
  view (reliable but slow), NOT the monthly grid cells or NumberPicker dialogs.
- NumberPicker spinners do NOT respond to type actions reliably. Avoid date pickers
  that use NumberPicker spinners. Use Agenda view + scroll instead.
- Prefer creating events via the "New Event" button with date fields in the form.
- For time pickers, switch to text/keyboard input mode (tap keyboard icon at bottom
  of the time picker dialog) and type the time value directly.
```

Also remove/update the `### General` line about NumberPicker:
```
# REMOVE: "When faced with NumberPicker widgets, type the value directly..."
```

#### D2.2. TypeExecutor NumberPicker Guard (from Codex P1.2)

**Problem**: `type` on a NumberPicker EditText silently succeeds (no error returned) but the value doesn't change. The agent believes the action worked.

**Design**: In `TypeExecutor` (or the a11y action handler), after executing `ACTION_SET_TEXT` on a node, read back the node's text value. If the value didn't change to the expected input, return a failure result instead of success.

**Changes**:
- `TypeExecutor.kt` (or equivalent action performer): After `setText()`, re-read the node's text via `getText()`. If the text doesn't match the typed value, return `ToolCallResult.Error("Type action failed: value did not change (NumberPicker or read-only field). Try scroll gestures instead.")`.

**Scope**: This is a narrow, targeted fix. It doesn't require NumberPicker detection — it simply validates that the type action had the intended effect, regardless of widget type.

**Effect**: Agent receives explicit failure, prompting it to try an alternative (day-by-day arrows, scroll, etc.) instead of pressing OK on an unchanged value.

### D3. Exact-Match & False Success Prevention (P1 priority)

Both designs fully agree. Add to system prompt:

```
### File Operations
- When a task specifies a filename, match it EXACTLY — do NOT select files containing
  the target name as a substring (e.g., for 'report.md', do NOT select '2023_report.md').
- In scrollable file lists, scroll through ALL visible items before selecting, especially
  if your first match is only a partial match. Use scratchpad to track candidates.
- For newest/oldest file tasks: file managers display MODIFICATION time, not creation time.
  Use shell `stat` to check actual creation timestamps before acting.
- For destructive operations (delete, move), verify the EXACT target before committing.
  Use shell `ls` or `stat` for metadata. This costs 1 turn but prevents wrong-file errors.
```

### D4. Task Completion Ordering (from Claude S1.3)

```
### Task Completion Ordering
Call complete_task as soon as core operations are done. Do NOT run a post-completion
verification pass that resembles the original workflow — this wastes turns and may
trigger loop detection. If you need to verify, verify BEFORE calling complete_task.
```

### D5. Efficient Multi-Item Workflows (from Claude S4 + S5)

```
### Form Filling
- Use type with element_index directly — do NOT click to focus first. Separate
  click-to-focus wastes a turn.
- For multi-item tasks, estimate total turns needed upfront. If your estimate exceeds
  80% of the turn budget, use the most compact strategy.

### Multi-Item Operations
- Prefer batch selection: long-press first item, tap additional items to accumulate,
  then perform the action once for all.
- Record your working workflow to scratchpad after the first successful item, then
  replicate exactly for remaining items. Do NOT re-explore the UI.

### Working Memory
- For survey tasks (find duplicates, identify items), scan the full list ONCE and write
  findings to scratchpad. Then execute from scratchpad without re-surveying.
- Once you commit to a destructive flow (reached confirmation dialog), follow through.
  Do NOT cancel and re-verify mid-flow — verify BEFORE entering the flow.
```

### D6. Vision Task Overrides (both agree)

Add to `eval/config/default.yaml` `task_overrides` (neither is currently present):

```yaml
task_overrides:
  MarkorTranscribeVideo: { perception_mode: hybrid }
  RecipeAddMultipleRecipesFromImage: { perception_mode: hybrid }
```

---

## Implementation Plan

### Phase 1: Prompt + Config (no code changes, low risk)

| # | Item | File |
|---|------|------|
| 1 | D2.1: Replace Calendar/NumberPicker prompt | `StandaloneAgentDef.kt` |
| 2 | D3: Exact-match file operations prompt | `StandaloneAgentDef.kt` |
| 3 | D4: Task completion ordering prompt | `StandaloneAgentDef.kt` |
| 4 | D5: Form filling + multi-item + working memory prompt | `StandaloneAgentDef.kt` |
| 5 | D6: Vision task overrides (add both) | `default.yaml` |

### Phase 2: Code Changes (anti-loop + execution)

| # | Item | Files | Priority |
|---|------|-------|----------|
| 6 | D1.2: Screen content progress detection (both branches) | `LoopDetectionPolicy.kt`, `NavigationState.kt` | **Critical** — fixes primary FP path |
| 7 | D1.3: cycleMinOccurrences 2→3 | `LoopDetectionPolicy.kt` | High — reduces FP sensitivity |
| 8 | D2.2: TypeExecutor post-type value verification | `TypeExecutor.kt` or action performer | High — fixes silent type failures |
| 9 | D1.4: Safe escalation gate (WARNING doesn't increment loop turns) | `AgentTurnRunner.kt` | High — already implemented, verify correctness |

### Phase 3: Deferred / Optional

| Item | Reason |
|------|--------|
| D1.1: Enrich action signatures with element semantics | Lower priority — only reduces WARNING noise, not the CRITICAL FP path. Higher risk (snapshot threading). |
| NumberPicker scroll/increment actions | Platform-level a11y service change |
| OsmAnd tasks | Need screenshot-based visual grounding |
| Per-task max_turns override | Needs eval validation |
| Calendar grid cell a11y injection | App-specific, fragile |

---

## Verification Plan

1. Full eval re-run with same config: `eval/.venv/bin/python eval/aw_bridge/runner.py --config eval/config/default.yaml --tasks-file eval/config/aw_subset_group_4.txt`
2. Metrics targets:
   - `scripted_success_rate`: 0.37 → target >= 0.53
   - `goal_claim_precision`: 0.75 → target >= 0.90
   - Forced completion count: 7 → target <= 3
3. Key regression cases:
   - `RetroPlayingQueue` — anti-loop FP must not trigger
   - `RecipeDeleteMultipleRecipesWithNoise` — verification must not trigger forced failure
   - `SimpleCalendarEventOnDateAtTime` — NumberPicker failure should be reported, agent should pivot
   - `MarkorMoveNote` — agent must scroll and find exact filename match

## Projected Impact

**Current**: 7/19 = 36.8%
**Projected**: 11–14/19 = 58–74%
**Hard ceiling**: OsmAndMarker, OsmAndTrack (vision), VlcCreateTwoPlaylists (infra)
