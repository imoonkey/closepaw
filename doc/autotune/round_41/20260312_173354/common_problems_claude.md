# Round 41 Eval Analysis — Common Problems

**Run ID**: `20260312_173354` | **Date**: 2026-03-12 | **Model**: gpt-5.4
**Tasks**: 23 evaluated | **Pass Rate**: 4/23 = 17.4%

Note: This is a failure-focused task set (R39+R40 failures minus cannot_handle). The 17.4% rate is against known-hard tasks, not the full suite.

## Scorecard

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| AudioRecorderRecordAudioWithFileName | 1.0 | 9 | Success (timing luck — save dialog appeared) |
| BrowserDraw | 0.0 | 14 | PlatformGap (canvas needs JS mouse events) |
| BrowserMultiply | 1.0 | 12 | Success (non-deterministic, first-click bug absent) |
| ExpenseAddMultipleFromMarkor | 0.0 | 21 | ActionFailure (element index stale after app switch) |
| FilesMoveFile | 1.0 | 20 | Success (model-native search for exact filename) |
| MarkorDeleteNewestNote | 0.0 | 12 | ReasoningError (wrong note identified as newest) |
| MarkorMergeNotes | 0.0 | 21 | PoisonedMemory (recalled memory overrides correct skill) |
| MarkorMoveNote | 0.0 | 9 | GroundingError (selected wrong file, same as R39) |
| RecipeAddMultipleRecipesFromMarkor2 | 0.0 | 34 | ActionFailure (Broccoli silent save failure) + FalseCompletion |
| RecipeDeleteDuplicateRecipes2 | 0.0 | 50 | TurnExhaustion (known — prescriptive algo removed) |
| RecipeDeleteDuplicateRecipes3 | 0.0 | 80 | TurnExhaustion (known — same) |
| RetroPlaylistDuration | 0.0 | 13 | PlatformGap (duration not in a11y tree, hybrid didn't help) |
| SimpleCalendarAddOneEventRelativeDay | 0.0 | 21 | InstructionIgnored ("5h" interpreted as 17:00 despite skill) |
| SimpleCalendarEventsInNextWeek | 0.0 | 5 | FalseCompletion (wrong "next week" boundary, same as R39) |
| SportsTrackerActivitiesCountForWeek | 0.0 | 5 | ReasoningError (name-based inference, ignored app skill) |
| SportsTrackerActivitiesOnDate | 1.0 | 8 | Success (non-deterministic flip) |
| SportsTrackerLongestDistanceActivity | 0.0 | 50 | TurnExhaustion (non-deterministic, was borderline R39) |
| SportsTrackerTotalDistanceForCategoryOverInterval | 0.0 | 16 | SmartFilteringConflict (app skill shortcut overrode system prompt) |
| TasksCompletedTasksForDate | 0.0 | 12 | A11yGap (checkbox state not exposed) + NavigationDerailment |
| TasksDueNextWeek | 0.0 | 6 | FalseCompletion (duplicate counting, but scrolling improved!) |
| TasksHighPriorityTasksDueOnDate | 0.0 | — | (analysis pending) |
| TasksIncompleteTasksOnDate | 0.0 | 8 | A11yGap (checkbox state not exposed) |
| VlcCreateTwoPlaylists | 0.0 | — | (analysis pending) |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| Success | 4 | AudioRecorder, BrowserMultiply, FilesMoveFile, SportsTrackerOnDate |
| FalseCompletion | 3 | SimpleCalendarEventsInNextWeek, TasksDueNextWeek, RecipeAddMultiple2 |
| ReasoningError/InstructionIgnored | 3 | SportsTrackerCountForWeek, SimpleCalendarAddEvent, MarkorDeleteNewest |
| GroundingError | 1 | MarkorMoveNote |
| PoisonedMemory | 1 | MarkorMergeNotes |
| SmartFilteringConflict | 1 | SportsTrackerTotalDistance |
| A11yGap/PlatformGap | 4 | TasksCompleted, TasksIncomplete, BrowserDraw, RetroPlaylistDuration |
| ActionFailure | 2 | ExpenseAddMultiple, RecipeAddMultiple2 |
| TurnExhaustion | 3 | RecipeDelete2, RecipeDelete3, SportsTrackerLongestDistance |

## Previous Round Comparison

| Fix Applied in R41 | Status | Impact |
|---------------------|--------|--------|
| System prompt: info-extraction verification line | **Partial** | TasksDueNextWeek: agent now scrolls + uses scratchpad (behavioral change!), but miscounts. Other targets: no improvement. |
| Files skill: drawer fallback + shell mv | **Not applicable** | FilesMoveFile uses `com.google.android.documentsui`, not `com.google.android.apps.nbu.files`. Pass was model-native. |
| Markor skill: extension field (already present) | **Ineffective** | MarkorMergeNotes failed due to poisoned recalled memory overriding the correct skill. |

### Info-extraction fix detailed impact

| Task | R39 Behavior | R41 Behavior | Fix Helped? |
|------|-------------|-------------|-------------|
| TasksDueNextWeek | 5 turns, 0 scrolls, no scratchpad | 6 turns, 3 scrolls, scratchpad used | **YES** — behavioral shift, but new failure mode (duplicate counting) |
| SimpleCalendarEventsInNextWeek | 5 turns, wrong week boundary | 5 turns, same wrong week boundary | **NO** — semantic reasoning error, not verification |
| SportsTrackerActivitiesCountForWeek | 5 turns, name-based inference | 5 turns, same name-based inference | **NO** — ignored both system prompt AND app skill |
| SportsTrackerTotalDistance | 14 turns, incomplete scan | 16 turns, even more selective scanning | **NO** — Smart Filtering in app skill overrode "scroll full list" |
| SimpleCalendarAddOneEvent | 21 turns, wrong time | 21 turns, same wrong time | **NO** — different root cause (instruction ignored) |
| MarkorMoveNote | 9 turns, wrong file | 9 turns, same wrong file | **NO** — different root cause (grounding error) |

**Verdict**: The info-extraction fix had exactly 1 partial success out of 6 target tasks. The fix addresses verification behavior but not the deeper issues: semantic reasoning errors, instruction ignoring, and conflicting guidance.

## Common Problems

### P1: Poisoned Recalled Memory — HIGH — 1 task (with risk of more)

**Affected**: MarkorMergeNotes

**Root Cause**: A prior failed run saved a `remember_experience` entry that explicitly names `mIObBbo4.md` as a correct outcome. This memory is recalled in subsequent runs and overrides the correct Markor skill guidance ("clear extension field for extensionless filenames"). The LLM trusts specific recalled examples over general skill rules.

**Proposed Fix**:
- Delete the poisoned memory entry from the device. This is a one-time data fix, not a prompt change.
- Consider adding to system prompt: "When recalled memory conflicts with app skill guidance, prefer the skill."
- Strengthen Markor skill from informational to imperative: "ALWAYS check goal filename for extension. No dot → MUST clear extension field."

---

### P2: Semantic Date Reasoning — HIGH — 2 tasks

**Affected**: SimpleCalendarEventsInNextWeek, TasksDueNextWeek (partially)

**Root Cause**: Agent computes wrong "next week" boundary when today is Sunday. It treats "next week" as starting 2 Mondays away instead of the immediately upcoming Monday. This is a reasoning error, not a verification gap — the agent is confidently wrong about the boundary from the start.

**Proposed Fix**:
- Add to system prompt (Information section): "For relative date queries ('next week', 'this month'): compute the exact date range using the device date FIRST, write it to scratchpad, then filter. 'Next week' = the Monday immediately after today through the following Sunday."
- This passes tuning gates: any real user asking about "next week" benefits from correct date computation.
- Cost: ~30 tokens.

---

### P3: Smart Filtering Conflict — HIGH — 1-2 tasks

**Affected**: SportsTrackerTotalDistanceForCategoryOverInterval (directly), SportsTrackerActivitiesCountForWeek (related)

**Root Cause**: The OpenTracks app skill contains "Smart Filtering" guidance that says "skip tracks whose names clearly indicate an unrelated activity." This directly conflicts with the system prompt's "scroll the full list" instruction for info-extraction. The agent uses the app skill shortcut to skip 3/5 tracks without opening Edit, answering "0" incorrectly.

**Proposed Fix**:
- Remove or weaken Smart Filtering in OpenTracks skill. Replace with: "Track names do NOT indicate activity type. For counting/summing: check ALL tracks in the date range via Edit."
- This passes tuning gates: any real user counting activities by type needs exhaustive checking.

---

### P4: Instruction Ignoring by gpt-5.4 — MED — 2 tasks

**Affected**: SimpleCalendarAddOneEventRelativeDay ("5h" → 17:00 despite skill saying 05:00), SportsTrackerActivitiesCountForWeek (name-based inference despite skill saying "always verify via Edit")

**Root Cause**: gpt-5.4 sometimes ignores explicit app skill instructions when its default reasoning produces a "plausible" answer. The model's priors (5h = 5 PM for meetings, track names indicate activity type) override clear instructions to the contrary.

**Proposed Fix**:
- Strengthen skill language from informational to imperative (MUST/ALWAYS).
- For SimpleCalendar: "ALWAYS interpret 'Nh' as 24-hour format. '5h' MUST be 05:00."
- For OpenTracks: covered by P3 fix.
- Accept that some model biases may be unfixable via prompt. If R42 fails with strengthened language, consider for cannot_handle.

---

### P5: MarkorMoveNote Grounding Error — MED — 1 task

**Affected**: MarkorMoveNote

**Root Cause**: Agent selected element 16 (first file in list) instead of element 20 (`BlJB_shy_king_copy.md`). The randomized prefix confused matching. Same failure as R39. No verification after move.

**Proposed Fix**:
- Add to Markor skill: "File names may include randomized prefixes. Before selecting, scan ALL visible entries and match the target name as substring. After move/copy, verify the target file is gone from source."
- Cost: ~25 tokens.

---

### P6: Files Skill Wrong Package — LOW — 0 tasks (fix was misapplied)

**Affected**: None (FilesMoveFile passed anyway via model-native behavior)

**Root Cause**: The Files skill change was applied to `com.google.android.apps.nbu.files` (Google Files), but the eval task uses `com.google.android.documentsui` (Android Documents UI). The skill was never loaded.

**Proposed Fix**:
- Create `app/src/main/assets/app_skills/com.google.android.documentsui/SKILL.md` with the drawer fallback and filename verification guidance.
- Or: move the guidance to the existing Files skill and add both package names.

## Next Steps

### Must Fix (R42)
1. **Delete poisoned memory entry**: Remove the `mIObBbo4.md` memory from the device. This is a data fix on the emulator, not a code change. → Targets: MarkorMergeNotes
2. **Remove Smart Filtering from OpenTracks skill**: Replace with exhaustive-check guidance. → Owner: app skill `de.dennisguse.opentracks` → Targets: SportsTrackerTotalDistance, SportsTrackerCountForWeek
3. **Add explicit date computation rule to system prompt**: Force scratchpad-first date range calculation for relative date queries. → Owner: core prompt (Information section) → Targets: SimpleCalendarEventsInNextWeek, TasksDueNextWeek
4. **Strengthen Markor skill filename matching**: Add substring matching and post-move verification guidance. → Owner: app skill `net.gsantner.markor` → Targets: MarkorMoveNote

### Should Fix (R42)
5. **Strengthen SimpleCalendar "Nh" skill language**: Make imperative (MUST/ALWAYS). → Owner: app skill `com.simplemobiletools.calendar.pro`
6. **Create documentsui skill**: Add filename verification and drawer fallback for Android Documents UI. → Owner: new app skill `com.google.android.documentsui` → Targets: FilesMoveFile (prevent regression)

### Accept as Capability Gap
- **TasksCompletedTasksForDate**: A11y checkbox state not exposed. 22% lifetime. Keep monitoring.
- **TasksIncompleteTasksOnDate**: Same a11y gap. 20% lifetime. Keep monitoring.
- **BrowserDraw**: Canvas drawing needs JS events. Confirmed platform limitation.
- **RetroPlaylistDuration**: Duration not in a11y tree. Hybrid mode didn't help.
- **RecipeDeleteDuplicateRecipes2/3**: Prescriptive algorithm was correctly removed per anti-overfit. 10% lifetime.
- **SportsTrackerActivitiesCountForWeek**: Structurally infeasible (14 tracks × 4 turns/track > 50 turn budget). Consider for cannot_handle if P3 fix doesn't help.
- **ExpenseAddMultipleFromMarkor**: Element index stale after app switch. Needs code fix.
- **AudioRecorderRecordAudioWithFileName**: Passed this time (timing luck), but 8% lifetime. Not prompt-fixable.
- **BrowserMultiply**: Passed (first-click bug absent). Non-deterministic. Not prompt-fixable.
- **SportsTrackerLongestDistanceActivity**: Non-deterministic turn exhaustion. Borderline.
- **RecipeAddMultipleRecipesFromMarkor2**: Broccoli silent save failure. Environmental/timing. REGRESSION from R39 pass.
- **VlcCreateTwoPlaylists**: Skill injection reliability. Needs code fix.
