# R8/R9 Next Steps Alignment Draft

**Status**: Claude edits on Codex draft
**Baseline**: 77.5/116 = 66.8% (GPT-5.4, R8 code, full eval)
**Failure distribution**: FalseCompletion 47% > ReasoningError 18% > TurnExhaustion 13% > ActionFailure 10% > cannot_handle 8% > PerceptionGap 4%

## 1. Root Cause Corrections

Two major root-cause errors in the original Claude analysis were corrected based on Codex's trace review, then verified by Claude against the codebase:

1. **App skill injection is NOT broken for the re-audited Tasks.org set** — Across the 9 Tasks.org traces re-checked for this memo, all have `## App Skill` in `llm_input_items`. The original cog-tune analyses checked `llm_system_prompt` (wrong location — skills are injected as user-context input items by `PromptBuilder.buildInputItems()`). For that re-audited set, the real problem is that the skill IS injected but the model ignores it (FalseCompletion / reasoning failure). VLC skill injection is still genuinely inconsistent across the 4 audited VLC runs (1/4 injected).

2. **Newline collapse is in perception, not ACTION_SET_TEXT** — `PerceptorInternals.kt:231-236` has `normalizeWhitespace()` which replaces `\n{2,}` with `\n`. The raw a11y tree preserves `\n\n` after `ACTION_SET_TEXT`, but the perception pipeline strips it before the LLM sees the observation. The action layer is fine.

## 2. R10 Must-Fix

These items are supported by both the failure pattern and verified evidence. All are independent — can be done in parallel.

### Fix 1: Anti-Hallucination Prompt Guard — HIGH

**Impact**: Targets ~18 FalseCompletion failures (47% of all failures)
**Effort**: Small (prompt change only)

FalseCompletion is the clearest confirmed cross-cutting blocker. The agent answers from inference instead of from a verified field or complete scan.

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

### Fix 2: Cross-App Destination Binding — HIGH

**Impact**: 2-3 tasks (ExpenseAddMultipleFromMarkor, ExpenseAddMultipleFromGallery)
**Effort**: Small (prompt change)

Add to StandaloneAgentDef `## Critical Rules`:
```
10. When the goal names both a source app/file AND a destination app, open the destination
    app to enter data. Do not create artifacts in the source app.
```

### Fix 3: Exact-Target and Postcondition Verification — MED

**Impact**: FilesMoveFile + generalizable to other file/edit tasks
**Effort**: Small (prompt change)

Add to StandaloneAgentDef `## Completion`:
```
- For file operations (move, delete, rename): match the EXACT filename including extension.
  Scroll the full list if needed. After the operation, verify the source is gone and the
  destination contains the correct file.
```

### Fix 4: Perception Layer Newline Preservation — MED

**Impact**: MarkorAddNoteHeader (1 task, potentially others)
**Effort**: Small (1-line code fix)

Root cause is confirmed by both sides. `PerceptorInternals.kt:233-236`:
```kotlin
private val MULTI_NEWLINE = Regex("\\n{2,}")
internal fun normalizeWhitespace(value: String): String {
    return value
        .replace(HORIZONTAL_WHITESPACE, " ")
        .replace(MULTI_NEWLINE, "\n")  // <-- This strips \n\n to \n
        .trim()
}
```

**Fix**: Change the replacement to preserve paragraph breaks:
```kotlin
.replace(MULTI_NEWLINE, "\n\n")
```
This normalizes 3+ newlines to exactly 2, preserving intentional `\n\n` paragraph structure.

### Fix 5: Tasks.org Skill Enhancement — MED

**Impact**: Up to 6 Tasks.org tasks in the re-audited R8/R9 set (skill is injected but ineffective there)
**Effort**: Small (skill file update)

Across the 9 Tasks.org traces re-audited for this memo, the skill is being injected. The failures are because:
1. Model ignores the skill guidance (TasksHighPriorityTasks: 2 turns, zero evidence gathering)
2. `checked` property not exposed — Tasks.org checkboxes are `ImageView` with `checkable=false`, `isChecked` not set
3. Skill guidance says "observe checked property" which is impossible

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

### Fix 6: App-Skill Content Upgrades — MED

**Impact**: 5-10 tasks across Calendar, Retro Music
**Effort**: Medium (create/update skill files)

**a) SimpleCalendar skill** — keep only evidence-backed rules:
- Month grid cells have NO per-cell a11y nodes. Do not click coordinates to select dates.
- Use < > arrows to reach the target month.
- For date pickers with NumberPicker spinners: use scroll/swipe gestures, NOT type(). NumberPicker widgets reject typed input.
- Always verify the displayed date matches the target before reading or creating events.
- "Nh" notation uses 24-hour format: "5h" = 05:00, "17h" = 17:00.

Calendar-specific instructions such as "long-press the header" or "use Go to date" should not be added until verified in the current app build.

**b) Retro Music skill** — first verify package name on emulator, then:
- Songs must be added from the Songs tab or song-level context, not from the empty playlist view.
- The playlist's own context menu "Add to playlist" adds the PLAYLIST to another playlist, NOT songs into it.

## 3. R10-11 Should-Fix

### Fix 7: VLC Skill Injection Debug — LOW-MED

**Impact**: VlcCreateTwoPlaylists (1 task)
**Effort**: Small (debug + possible alias)

VLC skill injection is genuinely inconsistent: injected in 1/4 VLC runs. This may be a package name timing issue (VLC starts with a splash/loading screen where `rootInActiveWindow` might return a different package).

1. Add debug logging to `buildAppSkillMessage()`: `Log.d(TAG, "App skill lookup: pkg=$currentPackageName, found=${skill != null}")`
2. Check on emulator what package name VLC reports during its splash screen
3. If needed, add package alias in `AssetAppSkillRepository`

Note: The app-skill injection audit for the memo's 9 Tasks.org traces is complete — all 9 have skills injected in `llm_input_items`. The "skill missing" hypothesis was based on checking `llm_system_prompt` (wrong location). In the audited VLC set, VLC is the only confirmed inconsistent case.

### Fix 8: Multi-Action Encouragement — LOW

**Impact**: Turn efficiency broadly
**Effort**: Small

Per prompt-tune ownership model, batching examples belong in tool descriptions, NOT the core prompt. Add to the `mobile_action` tool description:
```
You may call this tool multiple times in one turn to batch non-navigating actions
(e.g., filling multiple form fields). Navigation actions (click that opens a new page,
back, open_app) must be the only screen-changing action in a turn.
```

## 4. Priority & Sequencing

### R10 Must-Fix (all independent, can parallelize)

| # | Fix | Expected Impact | Effort | Dependency |
|---|-----|-----------------|--------|------------|
| 1 | Anti-hallucination prompt (Fix 1) | ~10 of 18 FalseCompletion | Small | None |
| 2 | Cross-app destination (Fix 2) | 2-3 tasks | Small | None |
| 3 | Exact-target verification (Fix 3) | 1+ tasks | Small | None |
| 4 | Perception newline fix (Fix 4) | 1 task | Small | None |
| 5 | Tasks.org skill rewrite (Fix 5) | Up to 6 tasks | Small | None |
| 6 | Calendar + Retro skills (Fix 6) | 5-10 tasks | Medium | None |

### R10-11 Should-Fix

| # | Fix | Expected Impact | Effort |
|---|-----|-----------------|--------|
| 7 | VLC injection debug (Fix 7) | 1 task | Small |
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
- VlcCreateTwoPlaylists (Fix 7 validation, if debug done)

### Impact Estimate

Conservative estimate: 66.8% → ~70-73% if Fixes 1-6 land effectively.

## 5. Open Questions

These should be resolved on the emulator before or during implementation:

1. In the current Simple Calendar build used by eval, are "long-press header" or "Go to date" affordances actually present and accessible?
2. Is there a stronger Tasks.org completion signal available anywhere in the tree or UI, or is diff-based visibility comparison the only reliable path?
3. What package name does VLC report during its splash screen?
