# R39 Comprehensive Failure & Messy-Pass Analysis

**Round**: 39 | **Run**: 20260312_094821 | **Model**: gpt-5.4 | **Tasks**: 51 (30 pass, 21 fail)

## Summary

21 failures + 3 messy passes analyzed. Grouped by **actual root cause**, not by app. Every task with at least 1 historical pass is treated as fixable — NOT dismissed as "platform limitation."

### Root Cause Distribution

| Group | Root Cause | Tasks | R39 Cut Related? | Fixable via Prompt? |
|-------|-----------|-------|-------------------|---------------------|
| A | FalseCompletion — premature answer on info-extraction | 8 | Partially (verification guidance cut) | Yes — 1 general line |
| B | Markor file operation errors | 2 | Yes (skill guidance cut) | Yes — restore 1-2 lines |
| C | Low-pass-rate a11y/platform issues | 4 | No | No — accept as flaky |
| D | R36-R38 generalization regressions | 2 | No (pre-R39) | No — anti-overfit |
| E | Action layer / infra bugs | 3 | No | No — code fix needed |
| F | Cross-app element stale | 1 | No | No — code fix needed |
| — | Already in cannot_handle | 4+1 | N/A | N/A |

---

## Group A: FalseCompletion — Premature Answers (8 tasks)

The biggest cluster. Agent answers too quickly without exhaustive verification.

| Task | Turns | What Went Wrong | Last Pass |
|------|-------|----------------|-----------|
| SimpleCalendarEventsInNextWeek | 5 | Wrong "next week" boundary (Sunday edge case) | R38 |
| SportsTrackerActivitiesCountForWeek | 5 | Didn't scroll, counted only visible tracks | R38 |
| TasksDueNextWeek | 5 | Premature answer, didn't verify due dates | R37 |
| TasksCompletedTasksForDate | 6 | "Show completed" toggle didn't register (a11y) | R36 |
| SportsTrackerTotalDistanceForCat... | 14 | Incomplete scan, wrong sum | R37 |
| TasksIncompleteTasksOnDate | 18 | A11y tree missing `checked` property | R36 |
| SimpleCalendarAddOneEventRelativeDay | 21 | Wrong relative date calculation | R38 |
| MarkorMoveNote | 9 | Selected wrong file (didn't match full filename) | R38 |

**Pattern**: All answered in ≤21 turns with GoalAchieved but wrong answer. The 5-turn failures (3 tasks) are extreme — the agent barely looked before answering.

**R39 contribution**: The system prompt Completion section was cut from 9 bullets to 5. Removed:
- "Cross-check your answer against scratchpad evidence. If evidence is incomplete, gather more."
- Detailed Information task mode guidance (3 lines → 1 line: "read values from a11y tree, not titles. Scroll to see all items.")

The condensed guidance may have reduced the agent's verification impulse, but this pattern exists pre-R39 too (these tasks were already flaky in R36-R38).

**Sub-patterns**:
- **5-turn FalseCompletion** (EventsInNextWeek, ActivitiesCountForWeek, TasksDueNextWeek): Agent reads visible list, answers immediately. Zero scrolling.
- **Date boundary errors** (EventsInNextWeek, AddOneEventRelativeDay): Agent miscalculates "next week" or relative dates. Sunday edge case for "next week" start.
- **A11y perception gap** (TasksCompletedForDate, TasksIncompleteOnDate): Tasks.org `checked` property missing from a11y tree. Toggle-based diff approach also fails because toggle click doesn't register. These 2 tasks have the weakest outlook — no prompt fix will overcome missing a11y data.
- **Filename mismatch** (MarkorMoveNote): Agent grabbed file at element_index 16 instead of scrolling to find target at index 20. Randomized prefix `BlJB_` confused matching.

### Clean Fix (Compliant with Tuning Principles)

Add **one general line** to system prompt Completion section:

```
For info-extraction: scroll the full list, cross-check totals against scratchpad, verify date boundaries before answering.
```

**Why this passes tuning gates**:
- Anti-overfit: Helps any info-extraction task in any app, not just eval tasks
- Token minimalism: 1 line (~20 tokens)
- Generalization: A real user asking "how many activities this week?" benefits from thorough scanning

**Expected impact**: Should recover 3-5 of the 8 tasks. The 2 Tasks.org a11y-gap tasks (Completed/Incomplete) likely remain broken regardless.

---

## Group B: Markor File Operation Errors (2 tasks)

| Task | Turns | What Went Wrong | Last Pass |
|------|-------|----------------|-----------|
| MarkorMergeNotes | 19 | Saved with wrong filename (extension field not cleared) | R38 |
| MarkorMoveNote | 9 | Selected wrong file (covered in Group A too) | R38 |

**R39 contribution**: Markor app skill was cut by 8 lines. The extension field handling guidance was condensed. File sorting guidance was simplified.

MarkorMergeNotes specifically failed because the agent created a merged file but didn't clear the `.md` extension field in Markor's new-file dialog. This is a genuine Markor UI quirk: the dialog has a **separate** extension field that defaults to `.md`. If the target filename has no extension or a different one, the agent must explicitly clear it.

### Clean Fix

Restore one line to Markor app skill:

```
New-file dialog has a separate extension field (default `.md`). Clear it if the target filename has no extension or a different extension.
```

**Why this passes tuning gates**:
- Anti-overfit: Any real user creating files in Markor with custom extensions needs this
- Token minimalism: 1 line (~25 tokens)
- Generalization: Markor extension field is a real app quirk, not eval-specific

**Expected impact**: Should recover MarkorMergeNotes. MarkorMoveNote's primary issue is filename matching (Group A fix covers this).

---

## Group C: Low-Pass-Rate A11y/Platform Issues (4 tasks)

| Task | Turns | Root Cause | Lifetime Pass Rate | Last Pass |
|------|-------|-----------|-------------------|-----------|
| AudioRecorderRecordAudioWithFileName | 29 | Scoped storage blocks filename rename | 1/12 (8%) | R31 |
| RetroPlaylistDuration | 2 | Song duration not in a11y tree | 1/16 (6%) | R33 |
| TasksCompletedTasksForDate | 6 | "Show completed" toggle doesn't register | 2/9 (22%) | R36 |
| TasksIncompleteTasksOnDate | 18 | A11y tree missing `checked` property | 2/10 (20%) | R36 |

**NOT adding to cannot_handle.** These tasks HAVE historical passes, but at very low rates. The passes likely occurred when:
- AudioRecorder: The filename happened to match default naming or a different code path was hit
- RetroPlaylistDuration: Agent found duration info through an alternative UI path (rare)
- Tasks.org pair: The toggle sometimes registers, or the agent found an alternative way to identify completion status

**R39 contribution**: None. These fail regardless of prompt content.

**Action**: No prompt fix. Accept as low-probability flaky. Track in scoreboard. If pass rate drops to 0/20+ lifetime, revisit for cannot_handle.

---

## Group D: R36-R38 Generalization Regressions (2 tasks)

| Task | Turns | Root Cause | Lifetime Pass Rate | Last Pass |
|------|-------|-----------|-------------------|-----------|
| RecipeDeleteDuplicateRecipes2 | 27 | Lost count during multi-deletion | 1/10 (10%) | R28 |
| RecipeDeleteDuplicateRecipes3 | 50 | Same, larger scale (50 turns exhausted) | 1/10 (10%) | R32 |

These tasks had prescriptive deletion algorithms in app skills before R36. The R36-R38 generalization pass removed those algorithms because they were eval-specific solvers (violated anti-overfit gate).

The single historical passes were with the prescriptive algorithm. Without it, the agent loses count after 5+ deletions because Broccoli reorders the list after each deletion.

**Could a general fix help?** A general "after bulk deletions, re-scan and recount" line would help, but:
- Broccoli's edit-as-create bug (overwrites instead of creating new) is the deeper issue
- Even with re-counting, 50 turns may not be enough for variant 3
- The re-scan guidance would be somewhat Broccoli-specific

**Action**: No prompt fix per tuning principles. The prescriptive algorithm was correctly removed. Accept regression. Already tracked in issues.md as parked.

---

## Group E: Action Layer / Infra Bugs (3 tasks)

| Task | Turns | Root Cause | Lifetime Pass Rate |
|------|-------|-----------|-------------------|
| BrowserMultiply | 12 | First-click-after-launch bug (click returns true but no UI change) | ~40% |
| FilesMoveFile | 13 | Files app hamburger/drawer not accessible via a11y click | ~50% |
| VlcCreateTwoPlaylists | 17 | VLC navigation failure + unreliable skill injection | ~40% |

**R39 contribution**:
- BrowserMultiply: Not prompt-related. The first-click bug is in the action layer.
- FilesMoveFile: Files app skill was cut by 10 lines. Drawer navigation guidance was removed. **Possible regression** — agent no longer has "If the side drawer opens..." recovery steps.
- VlcCreateTwoPlaylists: VLC skill injection is unreliable (known issue in issues.md). Not prompt-related.

### Potential Fix for FilesMoveFile

Restore one line to Files app skill:

```
If hamburger menu doesn't open via click, try swiping right from left edge. Use shell `mv` as fallback.
```

**Impact**: May recover FilesMoveFile. Low confidence — the a11y issue is in the action layer.

**BrowserMultiply and VlcCreateTwoPlaylists**: Need code-level fixes, not prompt changes.

---

## Group F: Cross-App Element Stale (1 task)

| Task | Turns | Root Cause | Lifetime Pass Rate |
|------|-------|-----------|-------------------|
| ExpenseAddMultipleFromMarkor | 21 | Element indices become stale after app switch | 1/17 (6%) |

When the agent switches between Markor (to read data) and Pro Expense (to enter data), `element_index` values from the previous screen become stale. Text input routes to wrong fields. The agent doesn't detect the mismatch.

**R39 contribution**: None. This is a fundamental action layer issue.

**Action**: No prompt fix. Would need action layer fix (re-resolve element indices after app switch). Already tracked in issues.md.

---

## Already in cannot_handle (4 tasks in R39 eval)

| Task | Reason | Still Valid? |
|------|--------|-------------|
| BrowserDraw | Canvas drawing needs JS mouse events, not a11y clicks | Yes |
| MarkorDeleteNewestNote | Deleted wrong note — but has 0/7 lifetime. Revisit? | Edge case |
| OsmAndMarker | A11y tree doesn't expose map interaction points | Yes |
| OsmAndTrack | GPX save flow unreliable via a11y | Yes |

---

## Messy Passes (3 tasks)

| Task | Turns | Max | Issue |
|------|-------|-----|-------|
| RetroSavePlaylist | 30/30 | 30 | Over-verification: task done by turn ~15, wasted 15 turns re-checking |
| TasksHighPriorityTasks | 44/50 | 50 | Scanned tasks one-by-one instead of using filter/sort. Would fail at default 30-turn budget |
| SportsTrackerLongestDistanceActivity | 29/30 | 30 | Borderline. Scanning individual tracks is expensive. Non-deterministic |

**RetroSavePlaylist**: The agent doesn't recognize when a task is done and keeps verifying. The R39 system prompt CUT the line "Do not run a post-completion workflow that repeats the original task." This removal may have contributed.

**Fix**: Not needed — the removed line was overly specific. The condensed Completion section says "Re-read the goal. Verify the EXACT requested outcome" which should be sufficient. This is non-deterministic behavior.

**TasksHighPriorityTasks**: 44 turns is unsustainable (would fail at 30). The agent opens each task individually to check priority. A filter/sort approach would be 5-10x faster. But adding "use sort by priority" to Tasks.org skill would be eval-specific.

**SportsTrackerLongestDistanceActivity**: 29/30 is tight but passed. Non-deterministic variance.

---

## Recommended Changes (Tuning-Principles Compliant)

### Change 1: System Prompt — Add Info-Extraction Verification (HIGH priority)

Add to Completion section in `StandaloneAgentDef.kt`:

```
For info-extraction: scroll the full list, cross-check totals against scratchpad, verify date boundaries before answering.
```

**Cost**: ~20 tokens | **Expected recovery**: 3-5 tasks from Group A

### Change 2: Markor Skill — Restore Extension Field Note (MEDIUM priority)

Add to `app/src/main/assets/app_skills/net.gsantner.markor/SKILL.md`:

```
New-file dialog has a separate extension field (default `.md`). Clear it if target has no extension or a different extension.
```

**Cost**: ~25 tokens | **Expected recovery**: MarkorMergeNotes

### Change 3: Files Skill — Restore Drawer Fallback (LOW priority)

Add to `app/src/main/assets/app_skills/com.google.android.apps.nbu.files/SKILL.md`:

```
If hamburger menu doesn't open via click, swipe right from left edge. Use shell `mv` as fallback for move operations.
```

**Cost**: ~20 tokens | **Expected recovery**: FilesMoveFile (low confidence)

### Total Cost: ~65 tokens (+2.6% of always-on budget)

All three changes pass the tuning principle gates:
- **Anti-overfit**: Each describes a real app behavior useful to any user
- **Token minimalism**: 3 lines total, ~65 tokens
- **Generalization**: None are eval-specific; all help unseen tasks in the same apps

### NOT Changing (and why)

| Task/Group | Why No Fix |
|-----------|-----------|
| RecipeDelete2/3 | Prescriptive algorithm was correctly removed (anti-overfit). Accept regression |
| AudioRecorderFileName | Scoped storage limitation. 1/12 pass rate = flaky, not prompt-fixable |
| RetroPlaylistDuration | Duration not in a11y tree. 1/16 = flaky |
| TasksCompleted/Incomplete | A11y tree missing checked property. Needs platform fix |
| BrowserMultiply | Action layer first-click bug. Needs code fix |
| VlcCreateTwoPlaylists | Skill injection reliability issue. Needs code fix |
| ExpenseAddMultipleFromMarkor | Element index stale after app switch. Needs code fix |
| Messy passes | Non-deterministic variance, not prompt-related |
