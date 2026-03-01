# Group 4 Round 1 — Implementation Summary

**Date**: 2026-02-27
**Consensus Design**: `final/group4_round1_consensus.md`
**Loop Detection Design**: `loop_detection_v2_design.md`

---

## Changes Implemented

### Phase 1: Prompt + Config (no code changes)

| # | Item | File | Description |
|---|------|------|-------------|
| 1 | D2.1 Calendar prompt | `StandaloneAgentDef.kt` | Replaced Calendar section: warns about NumberPicker unreliability, guides to Agenda view for date queries, removed harmful "type into NumberPicker" from General section |
| 2 | D3 File Operations | `StandaloneAgentDef.kt` | New section: exact filename matching, shell `stat` for timestamps, pre-commit verification for destructive ops |
| 3 | D4 Completion ordering | `StandaloneAgentDef.kt` | Added to complete_task: verify BEFORE completing, don't run post-completion verification pass |
| 4 | D5 Form/Multi-Item/Memory | `StandaloneAgentDef.kt` | New sections: Form Filling (direct type, turn budget awareness), Multi-Item Operations (batch select, scratchpad workflow), Working Memory (one-pass survey, commit to flow) |
| 5 | D6 Vision overrides | `default.yaml` | Added `MarkorTranscribeVideo: { perception_mode: hybrid }` and `RecipeAddMultipleRecipesFromImage: { perception_mode: hybrid }` |

### Phase 2: Code Changes

| # | Item | File | Description |
|---|------|------|-------------|
| 6 | D1.2 Progress detection | `LoopDetectionPolicy.kt` | Added `hasProgressInGroup()`: checks symmetric token difference between matching screens. Applied to **both** cycle detection and isStable branches. Screens with content changes (>= 2 token diff) downgraded CRITICAL → WARNING, preventing BLOCK/FORCE_COMPLETE. |
| 7 | D1.3 Cycle threshold | `LoopDetectionPolicy.kt` | `cycleMinOccurrences`: 2 → 3. Screen appearing twice is normal for multi-item tasks. |
| 8 | D2.2 TypeExecutor guard | `NodeActionPerformer.kt` | Post-type value verification: after `ACTION_SET_TEXT`, calls `node.refresh()` and reads back text. Returns failure if value didn't change (catches NumberPicker silent failures). |

### Tests Added

| File | Tests |
|------|-------|
| `LoopDetectionPolicyTest.kt` | 4 new: cycle downgrade with progress, cycle stays CRITICAL without progress, progress override of consecutiveLoopTurns, isStable downgrade with progress |
| `NodeActionPerformerTest.kt` | 1 new (NumberPicker verification failure), 1 updated (setText mock for refresh/text readback) |

### Key Design Insight

Previous fixes tried to make pattern matching more precise (richer signatures, different thresholds). This fix changes the fundamental question from **"do these screens look the same?"** to **"is the content changing?"** — a progress gate that resolves the ambiguity no level of threshold tuning can fix.

Research of 5 reference mobile agent implementations (AutoDevice, MobileAgent-E/v3, DroidRun, Minitap) confirmed that none use fuzzy screen similarity. They use exact hashing or action repetition. Our progress gate bridges the gap between our fuzzy similarity approach and their exact matching by checking for content-level changes before emitting CRITICAL.

---

## Verification

- `./gradlew clean assembleDebug lint test` — all pass
- Code review: APPROVE (0 HIGH, 1 MEDIUM addressed, 4 LOW/NITPICK)

## Expected Impact

**Group 4**: 7/19 (36.8%) → target 11-14/19 (58-74%)
- Anti-loop FP fixes: +3 tasks (RetroPlayingQueue, RecipeAddMultipleRecipesFromMarkor, RecipeDeleteMultipleRecipesWithNoise)
- Calendar prompt: +1-2 tasks
- File operations prompt: +1 task (MarkorMoveNote)
- Vision overrides: +1-2 tasks (MarkorTranscribeVideo, RecipeAddMultipleRecipesFromImage)

**Group 2/3 regressions**: Progress-aware detection should maintain or improve prior fixes. ForceComplete count should drop further.
