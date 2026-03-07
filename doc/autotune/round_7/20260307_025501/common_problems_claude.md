# Round 7 Eval Analysis — Common Problems

**Run ID**: `20260307_025501` | **Date**: 2026-03-07 | **Model**: qwen3.5
**Tasks**: 8 evaluated | **Pass Rate**: 1/8 = 12.5% | **Total Score**: 1.5/8

## Scorecard

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| RecipeAddMultipleRecipesFromImage | 0.0 | 50 | TurnExhaustion |
| RecipeAddMultipleRecipesFromMarkor | 0.0 | 50 | TurnExhaustion |
| SportsTrackerActivitiesCountForWeek | 0.0 | 30 | TurnExhaustion + FalseCompletion |
| TasksCompletedTasksForDate | 0.0 | 14 | StateCorruption + SelfContradiction |
| TasksDueNextWeek | 0.0 | 7 | FalseCompletion |
| TasksIncompleteTasksOnDate | 0.0 | 11 | DestructiveExploration |
| VlcCreatePlaylist | 1.0 | 20 | Success |
| VlcCreateTwoPlaylists | 0.5 | 30 | TurnExhaustion |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| Success | 1 | VlcCreatePlaylist |
| TurnExhaustion | 4 | RecipeAddMultipleRecipesFromImage, RecipeAddMultipleRecipesFromMarkor, SportsTrackerActivitiesCountForWeek, VlcCreateTwoPlaylists |
| DestructiveExploration / StateCorruption | 2 | TasksCompletedTasksForDate, TasksIncompleteTasksOnDate |
| FalseCompletion | 2 | TasksDueNextWeek, SportsTrackerActivitiesCountForWeek |

## Previous Round Comparison

| Fix Applied | Status | Impact |
|-------------|--------|--------|
| Loop detection redesign (remove all WARNING advisories) | **Effective** | VlcCreateTwoPlaylists 0.0→0.5; Recipe tasks used all turns instead of self-terminating; no regressions |
| Tasks app skill (org.tasks/SKILL.md) | **Not injected** | App skill was created but not loaded — package name mismatch likely |
| OpenTracks skill (verify wording) | Partial | Agent used edit flow (R6: didn't) but ran out of turns |
| System prompt rule 6 rewrite | Working | No premature pivots observed |

## Common Problems

### P1: One-Action-Per-Turn Architecture Bottleneck — HIGH — 4 tasks

**Affected**: RecipeAddMultipleRecipesFromImage, RecipeAddMultipleRecipesFromMarkor, SportsTrackerActivitiesCountForWeek, VlcCreateTwoPlaylists

**Root Cause**: The agent architecture allows at most one screen-changing action per turn. For form-filling (Recipe tasks: 6 fields × click+type per recipe = ~16 turns/recipe), file-by-file operations (VLC: 3 turns/file for long_press+menu+select), and item-by-item inspection (OpenTracks: 4-5 turns/track to open→More→Edit→back→back), the per-item cost exceeds what the turn budget allows. RecipeAddMultipleRecipesFromMarkor saved 2/3 recipes (R6: 1/3 partial). VlcCreateTwoPlaylists completed 1/2 playlists (R6: 0/2). Both are directly improved by the loop detection removal but still exhausted turns.

**Proposed Fix**:
- Primary: implement a batch form-fill tool (`batch_type` or `fill_form`) that fills multiple text fields in a single turn
- Secondary: teach VLC multi-select in app skill (after long-press, tap to select additional items → reduces 3 turns/file to ~1)
- Tertiary: for information-extraction tasks like SportsTracker, use shell + SQLite as a shortcut (1-2 turns instead of N×5)

---

### P2: Tasks App Skill Not Injected — HIGH — 3 tasks

**Affected**: TasksCompletedTasksForDate, TasksDueNextWeek, TasksIncompleteTasksOnDate

**Root Cause**: The org.tasks/SKILL.md file was created but per-task analysis shows no evidence the agent received the skill guidance. Agent behavior (clicking checkboxes, not resolving dates, not scrolling) matches R6 pre-skill behavior. Likely cause: the app skill injection pipeline matches on the foreground package name, and `org.tasks` may not be the exact package name the Tasks.org app uses on this device. Need to verify with `adb shell dumpsys window | grep mCurrentFocus` while Tasks.org is open.

**Proposed Fix**:
- Verify the actual Tasks.org package name on the emulator and rename the skill directory accordingly
- For TasksCompletedTasksForDate and TasksIncompleteTasksOnDate: add guidance to NOT click checkboxes (they toggle state). Instead read checked/unchecked property from a11y node attributes.

---

### P3: Destructive Observation (Checkbox Toggle) — MED — 2 tasks

**Affected**: TasksCompletedTasksForDate, TasksIncompleteTasksOnDate

**Root Cause**: Agent clicks checkboxes to "inspect" completion status, but clicking toggles the state. TasksCompletedTasksForDate wasted turns and contradicted its own scratchpad. TasksIncompleteTasksOnDate spent 6 of 11 turns on failed undo attempts. This is a general anti-pattern: the agent treats all UI elements as read-only, not realizing some clicks have side effects.

**Proposed Fix**:
- Add to core prompt or Tasks app skill: "For information tasks, observe element properties (checked, selected) from the screen state. Do not click toggle controls (checkboxes, switches) unless you intend to change them."
- Check if the a11y tree exposes `isChecked` property for checkboxes — if so, the agent can read state without clicking

---

### P4: Scroll Overlap Double-Counting — LOW — 1 task

**Affected**: TasksDueNextWeek

**Root Cause**: Agent scrolled and recorded tasks but likely double-counted items visible in overlapping viewports. Answered "20" (evaluator disagreed). Without deduplication, scroll-based enumeration is inherently error-prone.

**Proposed Fix**:
- Add enumeration guidance to org.tasks skill: "When counting items across scrolls, use anchor items (first/last visible) to detect overlap. Write each viewport's unique additions to scratchpad."

---

## Next Steps

### Must Fix
1. **Verify and fix Tasks app skill injection**: Check actual package name for Tasks.org on emulator (`adb shell pm list packages | grep task`). Rename `app_skills/org.tasks/` to match. Add checkbox guidance.
   - Targets: TasksCompletedTasksForDate, TasksDueNextWeek, TasksIncompleteTasksOnDate
   - Implementation: `app/src/main/assets/app_skills/<correct_package>/SKILL.md`
   - Owner layer: app skill

2. **VLC multi-select teaching**: Add to VLC app skill: "After long-pressing one file to enter selection mode, tap additional files to select them. Then use 'Add to playlist' to add all at once."
   - Targets: VlcCreateTwoPlaylists
   - Implementation: `app/src/main/assets/app_skills/org.videolan.vlc/SKILL.md`
   - Owner layer: app skill

### Should Fix
3. **Shell shortcut for data-heavy inspection**: For tasks that require inspecting many items, guide the agent to use shell + SQLite when available. E.g., OpenTracks stores activity types in a database.
   - Targets: SportsTrackerActivitiesCountForWeek
   - Implementation: `app/src/main/assets/app_skills/de.dennisguse.opentracks/SKILL.md`
   - Owner layer: app skill

4. **Checkbox/toggle observation rule**: Add to core prompt: "Do not click toggle controls to inspect their state. Read the checked/selected property from the screen."
   - Targets: TasksCompletedTasksForDate, TasksIncompleteTasksOnDate
   - Implementation: `agent/definition/StandaloneAgentDef.kt` (Information task mode)
   - Owner layer: core prompt

### Accept as Capability Gap
- RecipeAddMultipleRecipesFromImage, RecipeAddMultipleRecipesFromMarkor: Mechanically requires ~54 turns at 1-action-per-turn. A batch form-fill tool would solve this but is a significant infra change. Park for now — the agent behavior is correct, just slow.
