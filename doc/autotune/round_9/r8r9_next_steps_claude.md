# R8/R9 Overall Summary & Next Steps

**Baseline**: 77.5/116 = 66.8% (GPT-5.4, R8 code, full eval)
**Failure distribution**: FalseCompletion 47% > ReasoningError 18% > TurnExhaustion 13% > ActionFailure 10% > cannot_handle 8% > PerceptionGap 4%

## Situation

R8 introduced multi-action, app skills, and cog-tune quality upgrades. R9 ran the first full GPT-5.4 baseline across all 116 tasks. The 66.8% pass rate has clear ceilings:

- **Groups 0-3 (74 tasks)**: 56.5/74 = 76.4% — strong baseline, mostly working
- **Groups 4-5 (42 tasks)**: 21/42 = 50.0% — hard tasks, most failures concentrated here

The top 3 blockers account for ~31 of 38.5 failures:
1. **FalseCompletion** (~18 failures) — agent guesses answers without evidence
2. **App skill not injected** (~7 failures) — skills exist but don't reach the model
3. **TurnExhaustion** (~5 failures) — multi-action underutilized, 30-turn budget insufficient

## Fix 1: App Skill Injection Pipeline — CRITICAL

**Impact**: Unblocks 7+ tasks (Tasks.org × 6, VLC × 1)
**Effort**: Small (debug + 1-file fix)

### Root Cause

`AgentTurnRunner.capturePreTurnContext()` (line 142) calls `services.platform.getCurrentPackageName()` which reads `service.rootInActiveWindow?.packageName`. This is per-turn, just-in-time — if the app isn't fully foregrounded when the a11y tree is captured, `rootInActiveWindow` returns the wrong package or null.

The skill lookup path: `AssetAppSkillRepository.load(packageName)` opens `assets/app_skills/{packageName}/SKILL.md`. If the package name doesn't exactly match the directory name, skill is silently skipped (FileNotFoundException caught → returns null).

### Likely Bug

Tasks.org's package is `org.tasks`, but the app might report a sub-activity package like `com.todoroo.astrid` (Tasks.org was originally Astrid). Need to verify on emulator what `rootInActiveWindow.packageName` actually returns when Tasks.org is foregrounded.

### Fix Design

**Option A — Session-sticky injection (recommended)**:
```
// In AgentTurnRunner or a new AppSkillTracker:
private val detectedPackages = mutableSetOf<String>()

fun getAppSkill(currentPackage: String?): String? {
    currentPackage?.let { detectedPackages.add(it) }
    // Try current package first, then all previously detected
    val candidates = listOfNotNull(currentPackage) + detectedPackages
    return candidates.firstNotNullOfOrNull {
        services.appSkillRepository.load(it)
    }
}
```
Once a package is detected, its skill stays available for the rest of the session. This handles the timing race.

**Option B — Package alias map**:
```kotlin
// In AssetAppSkillRepository:
private val ALIASES = mapOf(
    "com.todoroo.astrid" to "org.tasks",
    // add others as discovered
)

override fun load(packageName: String): String? {
    val normalized = ALIASES[packageName] ?: packageName
    // ... existing logic with normalized
}
```

**Option C — Both** (belt and suspenders). Recommended.

**Validation**: Add `Log.d(TAG, "App skill lookup: pkg=$packageName, skill=${skill != null}")` to `buildAppSkillMessage()`. Run TasksHighPriorityTasks (2-turn task, quick feedback).

---

## Fix 2: Anti-Hallucination Prompt Guard — HIGH

**Impact**: Reduces ~18 FalseCompletion failures
**Effort**: Small (prompt change only)

### Root Cause

The current system prompt (StandaloneAgentDef) says:
> "Information: identify the exact field being asked for, collect concrete evidence in `scratchpad`, and answer only from verified evidence."

This is correct in spirit but too abstract. GPT-5.4 still:
- Infers priority from task names (TasksHighPriorityTasks: 2 turns, zero evidence)
- Counts items without scrolling (SportsTracker: "0 running" without checking sport type)
- Reports data from wrong date (SimpleCalendarEventsOnDate: Oct 11 → reported as Oct 24)

### Fix Design

Add to StandaloneAgentDef `## Completion` section:

```
- For information/query tasks: NEVER infer metadata (priority, category, sport type,
  completion status) from display names or titles. Navigate to the actual data field
  and read its value. If the screen doesn't show the answer, scroll or navigate deeper.
- Before answering a date-specific query, verify the current view shows the target date.
- Cross-check your answer against scratchpad evidence. If evidence is incomplete,
  gather more before completing.
```

Also strengthen the existing "Information" task mode:
```
- Information: identify the exact field, navigate to it, read its value from the a11y
  tree (not from titles or surrounding text). Store evidence in scratchpad. Scroll to
  see all items before counting. Answer only from verified, complete evidence.
```

---

## Fix 3: A11y `checked` Property for Tasks.org — HIGH

**Impact**: Unblocks 6 Tasks.org tasks (combined with Fix 1)
**Effort**: Medium (investigation + possible code change)

### Root Cause (confirmed via code exploration)

`Perceptor.kt` line 251 reads `node.isCheckedCompat()` which calls the platform API. The issue is NOT sanitization — Tasks.org checkboxes are `ImageView` elements that never set `isChecked()` on their AccessibilityNodeInfo. The boolean stays at default `false`, and `toPromptJson()` omits `checked` when false (by design, for compact JSON).

So the a11y tree genuinely lacks this information for Tasks.org.

### Fix Design

**Option A — Diff-based completion detection (prompt-layer)**:
Update `org.tasks/SKILL.md`:
```markdown
## Determining task completion status
Tasks.org does not expose checkbox state in the accessibility tree.
To identify completed vs incomplete tasks:
1. Note all visible tasks with "Show completed" ON
2. Toggle "Show completed" OFF
3. The tasks that disappeared are completed tasks
4. The remaining tasks are incomplete
```

**Option B — Include `checkable` + visual position heuristic**:
In `toPromptJson()`, also emit `"checkable": true` when applicable. Then the skill can say: "The leftmost clickable ImageView in each task row is the checkbox. If a task row has strikethrough text, it's completed."

**Option C — Emit `checked: false` explicitly** (change `toPromptJson()`):
Currently: `if (elem.isChecked) put("checked", true)` — only emits when true.
Change to: `if (elem.isCheckable) put("checked", elem.isChecked)` — always emit for checkable items.
This way the LLM sees `"checked": false` explicitly, which is at least more informative than omission. But if the underlying `isChecked()` always returns false for Tasks.org, this still won't help.

**Recommendation**: Option A (diff-based) is the most reliable since it doesn't depend on broken a11y properties. Combine with Option C as a general improvement.

---

## Fix 4: Cross-App Destination Binding — HIGH

**Impact**: 2-3 tasks (ExpenseAddMultipleFromMarkor, ExpenseAddMultipleFromGallery)
**Effort**: Small (prompt change)

### Fix Design

Add to StandaloneAgentDef `## Critical Rules`:
```
10. When the goal names both a source app/file AND a destination app, open the destination
    app to enter data. Do not create artifacts in the source app.
```

This is a one-line prompt addition. The pattern is clear and consistent across all cross-app tasks.

---

## Fix 5: New App Skills — MED

**Impact**: 5-10 tasks across Calendar, Retro Music
**Effort**: Medium (create 2 new skill files)

### 5a: SimpleCalendar App Skill

Create `app/src/main/assets/app_skills/com.simplemobiletools.calendar.pro/SKILL.md` (already exists but needs enhancement):

```markdown
## Date Navigation
- Month grid cells have NO per-cell a11y nodes. Do not try to click specific dates by guessing coordinates.
- To navigate to a specific date: use the < > arrows to reach the target month, then use the date picker dialog (long-press the month/year header or find "Go to date" in overflow menu).
- NumberPicker widgets (hour/minute/day spinners) do NOT accept typed input. Use scroll/swipe gestures to change values.

## Time Format
- "Nh" notation uses 24-hour format: "5h" = 05:00, "17h" = 17:00.
- When creating events, always verify the time matches the goal before saving.
```

### 5b: Retro Music App Skill

Create `app/src/main/assets/app_skills/code.name.monkey.retromusic/SKILL.md`:

```markdown
## Adding Songs to a Playlist
You CANNOT add songs from inside an empty playlist view.
Correct flow:
1. Navigate to the Songs tab (bottom navigation)
2. Find the target song
3. Long-press the song → context menu appears
4. Tap "Add to playlist" → select the target playlist

## Playlist Context Menu Warning
The playlist's own "Add to playlist" option adds the PLAYLIST to another playlist, not songs into it. Always add songs from the Songs tab.
```

**Note**: Need to verify Retro Music's actual package name on the emulator (`code.name.monkey.retromusic` vs something else).

---

## Fix 6: ACTION_SET_TEXT Newline Bug — MED

**Impact**: 1 task (MarkorAddNoteHeader), potentially others
**Effort**: Medium (investigation + possible code change)

### Root Cause (confirmed via code exploration)

`NodeActionPerformer.setTextOnNode()` passes text to `AccessibilityNodeInfo.ACTION_SET_TEXT` via Bundle with no normalization. The `\n\n` → `\n` collapse happens at the Android framework level or in Markor's EditText `setText()` handler.

The post-action verification (line 235-246) uses `actualText.contains(text)` which may pass because the base text (without newlines) is still present.

### Fix Design

**Option A — Use clipboard paste for multi-line text**:
When `text` contains `\n\n`, use a different insertion method:
1. Copy text to clipboard via shell (`am broadcast -a clipper.set -e text "..."`)
2. Focus the field
3. Paste via `KEYCODE_PASTE`

**Option B — Post-action newline verification**:
In `setTextOnNode()`, add specific newline comparison:
```kotlin
if (text.contains("\n\n") && !actualText.contains("\n\n")) {
    return ActionResult.Failure("Double newline was collapsed to single. Use paste instead.")
}
```
This would at least surface the failure instead of silently succeeding.

**Option C — prompt workaround (already exists from R3)**:
The Markor skill already has a blank line tip. But GPT-5.4 sends correct `\n\n` already — the model-side is fine. This is purely an action-layer bug.

**Recommendation**: Option B first (make the failure visible), then Option A if clipboard paste is reliable.

---

## Fix 7: Multi-Action Encouragement — LOW

**Impact**: Reduces turn count broadly, helps TurnExhaustion tasks
**Effort**: Small (prompt change)

GPT-5.4 rarely emits multiple tool calls despite TurnToolPolicy allowing it. The prompt already says "You may batch multiple actions in one turn" but this is too passive.

### Fix Design

Add a concrete example to the system prompt `## Critical Rules` after rule 2:

```
Example batch turn: filling a form with title, description, and time fields can be
3 type() calls in one turn. Navigation (click/back/open_app) must be alone.
```

---

## Priority & Sequencing

### R10 Must-Fix (target: +10-15 tasks)

| # | Fix | Tasks Unblocked | Effort | Dependency |
|---|-----|-----------------|--------|------------|
| 1 | App skill injection (Fix 1) | 7+ | Small | None |
| 2 | Anti-hallucination guard (Fix 2) | ~10 of 18 | Small | None |
| 3 | A11y checked (Fix 3, Option A) | 6 (with Fix 1) | Small | Fix 1 |
| 4 | Cross-app binding (Fix 4) | 2-3 | Small | None |

Fixes 1, 2, 4 are independent — can be done in parallel.
Fix 3 depends on Fix 1 (skill must be injected to test diff-based strategy).

### R10-11 Should-Fix (target: +3-5 tasks)

| # | Fix | Tasks | Effort |
|---|-----|-------|--------|
| 5 | Calendar + Retro skills (Fix 5) | 5-10 | Medium |
| 6 | Newline bug investigation (Fix 6) | 1+ | Medium |
| 7 | Multi-action encouragement (Fix 7) | Turn efficiency | Small |

### Eval Plan

**R10 eval tasks** (targeted, ~20 tasks):
- All 6 Tasks.org tasks (Fix 1 + 3 validation)
- VlcCreateTwoPlaylists (Fix 1 validation)
- ExpenseAddMultipleFromMarkor (Fix 4 validation)
- SportsTrackerActivitiesCountForWeek (Fix 2 validation)
- SimpleCalendarEventsOnDate (Fix 2 + 5a validation)
- TasksHighPriorityTasks, TasksDueOnDate (fastest FalseCompletion canaries)
- 2-3 regression canaries from passing tasks
- RetroSavePlaylist (Fix 5b validation, if Retro skill ready)

**Expected outcome**: 66.8% → ~72-75% if Fixes 1-4 land and unblock 10+ tasks.
