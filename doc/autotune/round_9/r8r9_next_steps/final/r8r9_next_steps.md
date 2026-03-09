# R8/R9 Next Steps — Aligned Design

**Baseline**: 77.5/116 = 66.8% (GPT-5.4, R8 code, full eval)
**Failure distribution**: FalseCompletion 47% > ReasoningError 18% > TurnExhaustion 13% > ActionFailure 10% > cannot_handle 8% > PerceptionGap 4%

## Root Cause Corrections

Two major root-cause errors in the original Claude analysis have been corrected based on trace evidence review (Codex review + Claude verification):

1. **App skill injection is NOT broken for Tasks.org** — All 9 Tasks.org runs have `## App Skill` in `llm_input_items`. The original cog-tune analyses checked `llm_system_prompt` (wrong location — skills are injected as user-context input items by `PromptBuilder.buildInputItems()`). The real problem is that the skill IS injected but the model ignores it (FalseCompletion / reasoning failure). VLC skill injection is still inconsistent (1/4 runs).

2. **Newline collapse is in perception, not ACTION_SET_TEXT** — `PerceptorInternals.kt:231-236` has `normalizeWhitespace()` which replaces `\n{2,}` with `\n`. The raw a11y tree preserves `\n\n` after `ACTION_SET_TEXT`, but the perception pipeline strips it before the LLM sees the observation. The action layer is fine.

---

## Fix 1: Anti-Hallucination Prompt Guard — HIGH

**Impact**: Targets ~18 FalseCompletion failures (47% of all failures)
**Effort**: Small (prompt change only)

### Root Cause

The current system prompt says: "Information: identify the exact field being asked for, collect concrete evidence in `scratchpad`, and answer only from verified evidence." This is too abstract — GPT-5.4 still:
- Infers priority from task names (TasksHighPriorityTasks: 2 turns, skill injected but ignored)
- Counts items without scrolling (SportsTracker: "0 running" without checking sport type)
- Reports data from wrong date (SimpleCalendarEventsOnDate: Oct 11 reported as Oct 24)

### Design

**StandaloneAgentDef `## Completion` additions:**
```
- For information/query tasks: NEVER infer metadata (priority, category, sport type,
  completion status) from display names or titles. Navigate to the actual data field
  and read its value. If the screen doesn't show the answer, scroll or navigate deeper.
- Before answering a date-specific query, verify the current view shows the target date.
- Cross-check your answer against scratchpad evidence. If evidence is incomplete,
  gather more before completing.
```

**StandaloneAgentDef `## Task Modes` — Information mode strengthened:**
```
- Information: identify the exact field, navigate to it, read its value from the a11y
  tree (not from titles or surrounding text). Store evidence in scratchpad. Scroll to
  see all items before counting. Answer only from verified, complete evidence.
```

---

## Fix 2: Cross-App Destination Binding — HIGH

**Impact**: 2-3 tasks (ExpenseAddMultipleFromMarkor, ExpenseAddMultipleFromGallery)
**Effort**: Small (prompt change)

### Design

Add to StandaloneAgentDef `## Critical Rules`:
```
10. When the goal names both a source app/file AND a destination app, open the destination
    app to enter data. Do not create artifacts in the source app.
```

---

## Fix 3: Exact-Target and Postcondition Verification — MED

**Impact**: FilesMoveFile + generalizable to other file/edit tasks
**Effort**: Small (prompt change)

### Root Cause

FilesMoveFile moved `holiday_photos_ZYUM.jpg` instead of `holiday_photos.jpg` (partial name match without scrolling to find exact target), then declared success without verifying the source file was gone. This is a grounding + postcondition problem.

### Design

Add to StandaloneAgentDef `## Completion`:
```
- For file operations (move, delete, rename): match the EXACT filename including extension.
  Scroll the full list if needed. After the operation, verify the source is gone and the
  destination contains the correct file.
```

---

## Fix 4: Perception Layer Newline Preservation — MED

**Impact**: MarkorAddNoteHeader (1 task, potentially others)
**Effort**: Small (1-line code fix)

### Root Cause

`PerceptorInternals.kt:233-236`:
```kotlin
private val MULTI_NEWLINE = Regex("\\n{2,}")
internal fun normalizeWhitespace(value: String): String {
    return value
        .replace(HORIZONTAL_WHITESPACE, " ")
        .replace(MULTI_NEWLINE, "\n")  // <-- This strips \n\n to \n
        .trim()
}
```

The raw a11y tree correctly shows `\n\n` after `ACTION_SET_TEXT`. But `normalizeWhitespace()` collapses it before the LLM sees the observation. The LLM then thinks the blank line wasn't inserted and retries or gives up.

### Design

**Option A — Preserve double newlines:**
Change `MULTI_NEWLINE` to only collapse 3+ newlines:
```kotlin
private val MULTI_NEWLINE = Regex("\\n{3,}")
```
This preserves intentional `\n\n` (paragraph breaks) while still collapsing excessive whitespace.

**Option B — Use `\n\n` as the replacement:**
```kotlin
.replace(MULTI_NEWLINE, "\n\n")
```
This normalizes 3+ newlines to exactly 2, preserving paragraph structure.

**Recommendation**: Option B — it's more intentional and handles all cases cleanly.

---

## Fix 5: Tasks.org Skill Enhancement — MED

**Impact**: Up to 6 Tasks.org tasks (skill is injected but ineffective)
**Effort**: Small (skill file update)

### Root Cause (corrected)

The skill IS being injected for all Tasks.org runs. The failures are because:
1. Model ignores the skill guidance (TasksHighPriorityTasks: 2 turns, zero evidence gathering)
2. `checked` property not exposed — Tasks.org checkboxes are `ImageView` with `checkable=false`, `isChecked` not set
3. Skill guidance says "observe checked property" which is impossible

### Design

Update `app/src/main/assets/app_skills/org.tasks/SKILL.md`:

**a) Diff-based completion detection:**
```markdown
## Determining Task Completion Status
Tasks.org does NOT expose checkbox state in the accessibility tree.
To identify completed vs incomplete tasks:
1. Note all visible tasks with "Show completed" ON
2. Toggle "Show completed" OFF (hamburger menu → Show completed)
3. The tasks that disappeared are completed tasks
4. The remaining tasks are incomplete
Do NOT guess completion status from visual appearance or task names.
```

**b) Strengthen anti-hallucination for this specific app:**
```markdown
## Critical: Evidence-Based Answers Only
- Do NOT answer from task titles alone. Priority, due date, and completion status
  require navigating to each task's detail view or using the strategies above.
- Do NOT answer "0" or "none" without scrolling the full task list.
- Store findings in scratchpad before answering.
```

---

## Fix 6: VLC Skill Injection Consistency — LOW-MED

**Impact**: VlcCreateTwoPlaylists (1 task)
**Effort**: Small (debug + possible alias)

### Root Cause

VLC skill injection is genuinely inconsistent: injected in 1/4 VLC runs. This may be a package name timing issue (VLC starts with a splash/loading screen where `rootInActiveWindow` might return a different package).

### Design

1. Add debug logging to `buildAppSkillMessage()`: `Log.d(TAG, "App skill lookup: pkg=$currentPackageName, found=${skill != null}")`
2. Check on emulator what package name VLC reports during its splash screen
3. If needed, add package alias in `AssetAppSkillRepository`

---

## Fix 7: New App Skills — MED

**Impact**: 5-10 tasks across Calendar, Retro Music
**Effort**: Medium (create/update skill files)

### 7a: SimpleCalendar Skill Enhancement

Update `app/src/main/assets/app_skills/com.simplemobiletools.calendar.pro/SKILL.md`:
```markdown
## Date Navigation
- Month grid cells have NO per-cell a11y nodes. Do not click coordinates to select dates.
- Use < > arrows to reach the target month.
- For date pickers with NumberPicker spinners: use scroll/swipe gestures, NOT type().
  NumberPicker widgets reject typed input.
- Always verify the displayed date matches the target before reading or creating events.

## Time Format
- "Nh" notation uses 24-hour format: "5h" = 05:00, "17h" = 17:00.
```

Note: Specific navigation affordances (long-press header, "Go to date" in overflow) need emulator verification before adding.

### 7b: Retro Music Skill

First verify package name on emulator. Create skill at `app/src/main/assets/app_skills/{retro_package}/SKILL.md`:
```markdown
## Adding Songs to a Playlist
You CANNOT add songs from inside an empty playlist view.
Correct flow:
1. Navigate to the Songs tab (bottom navigation)
2. Find the target song
3. Long-press the song → context menu appears
4. Tap "Add to playlist" → select the target playlist

## Warning
The playlist's own context menu "Add to playlist" adds the PLAYLIST to another playlist,
NOT songs into it. Always add songs from the Songs tab.
```

---

## Fix 8: Multi-Action Encouragement — LOW

**Impact**: Turn efficiency broadly
**Effort**: Small

Per prompt-tune ownership model, batching examples belong in tool descriptions, NOT the core prompt. Add to the `mobile_action` tool description:
```
You may call this tool multiple times in one turn to batch non-navigating actions
(e.g., filling multiple form fields). Navigation actions (click that opens a new page,
back, open_app) must be the only screen-changing action in a turn.
```

---

## Priority & Sequencing

### R10 Must-Fix

| # | Fix | Expected Impact | Effort | Dependency |
|---|-----|-----------------|--------|------------|
| 1 | Anti-hallucination prompt (Fix 1) | ~10 of 18 FalseCompletion | Small | None |
| 2 | Cross-app destination (Fix 2) | 2-3 tasks | Small | None |
| 3 | Exact-target verification (Fix 3) | 1+ tasks | Small | None |
| 4 | Perception newline fix (Fix 4) | 1 task | Small | None |
| 5 | Tasks.org skill rewrite (Fix 5) | Up to 6 tasks | Small | None |

All independent — can be done in parallel.

### R10-11 Should-Fix

| # | Fix | Expected Impact | Effort |
|---|-----|-----------------|--------|
| 6 | VLC injection debug (Fix 6) | 1 task | Small |
| 7 | Calendar + Retro skills (Fix 7) | 5-10 tasks | Medium |
| 8 | Multi-action in tool desc (Fix 8) | Turn efficiency | Small |

### Eval Plan

**R10 eval tasks** (~15-20 tasks):
- All 6 Tasks.org tasks (Fix 5 validation)
- ExpenseAddMultipleFromMarkor (Fix 2 validation)
- SportsTrackerActivitiesCountForWeek (Fix 1 validation)
- FilesMoveFile (Fix 3 validation)
- MarkorAddNoteHeader (Fix 4 validation)
- SimpleCalendarEventsOnDate (Fix 1 validation)
- TasksHighPriorityTasks, TasksDueOnDate (fastest FalseCompletion canaries)
- 2-3 regression canaries from passing tasks
- VlcCreateTwoPlaylists (Fix 6 validation, if debug done)

### Corrected Impact Estimate

Original estimate assumed Fix 1 (app skill injection) would unblock 7+ tasks. With corrected root causes:
- Skills are injected for Tasks.org → issue is model ignoring guidance → Fix 1 (anti-hallucination) + Fix 5 (skill rewrite) are the actual levers
- Newline is perception, not action → Fix 4 is a 1-line code change, not a complex action-layer investigation

Conservative estimate: 66.8% → ~70-73% if Fixes 1-5 land effectively.
