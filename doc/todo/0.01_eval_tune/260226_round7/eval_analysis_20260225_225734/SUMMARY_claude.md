# Round 7 Eval Analysis Summary — Run 20260225_225734

## Overall Results

| Metric | Value |
|---|---|
| Run ID | `20260225_225734` |
| Model | qwen3.5 (qwen/qwen3.5-plus-02-15) |
| Total Tasks | 20 |
| Pass | 6 (30.0%) |
| Fail | 14 (70.0%) |
| Infra Failures | 3 (never started) |
| goal_claim_precision | 60% |
| Perception Mode | accessibility_only (hybrid for BrowserDraw only) |
| Max Turns | 30 |

### Task-Level Results

| # | Task | Status | Completion | Turns | Root Cause |
|---|------|--------|------------|-------|-----------|
| 1 | AudioRecorderRecordAudio | **PASS** | GoalAchieved | 5 | N/A |
| 2 | AudioRecorderRecordAudioWithFileName | FAIL | MaxTurnsReached | 30 | Shell loop (no rename UI) |
| 3 | BrowserDraw | FAIL | Error | 1 | LLM provider error |
| 4 | BrowserMaze | FAIL | null | 18* | Unknown (trace lost) |
| 5 | CameraTakeVideo | **PASS** | GoalAchieved | 6 | N/A |
| 6 | ClockStopWatchPausedVerify | **PASS** | GoalAchieved | 3 | N/A |
| 7 | ClockStopWatchRunning | **PASS** | null | 5* | N/A |
| 8 | ContactsNewContactDraft | **PASS** | null | 8* | N/A |
| 9 | ExpenseAddMultiple | FAIL | null | 25* | Data entry error (likely amount) |
| 10 | ExpenseAddMultipleFromGallery | FAIL | MaxTurnsReached | 30 | Image inaccessible (a11y-only) |
| 11 | ExpenseAddMultipleFromMarkor | FAIL | GoalAchieved(FP) | 19 | Wrong categories selected |
| 12 | ExpenseDeleteDuplicates | FAIL | null | 12* | Incomplete scanning, premature completion |
| 13 | SimpleCalendarAddOneEvent | FAIL | null | 21* | Time picker element confusion |
| 14 | SimpleCalendarAddOneEventInTwoWeeks | **PASS** | null | 22* | N/A |
| 15 | SimpleCalendarAddOneEventRelativeDay | FAIL | null | 17* | "5h" → 17:00 instead of 05:00 |
| 16 | SimpleCalendarAddOneEventTomorrow | FAIL | GoalAchieved(FP) | 27 | Time picker idx 20 = OK button |
| 17 | SimpleCalendarAddRepeatingEvent | FAIL | MaxTurnsReached | 30 | Stuck in repeat/time settings |
| 18 | SimpleCalendarDeleteEvents | FAIL | null | 29* | Complete navigation failure |
| 19 | SimpleCalendarDeleteEventsOnRelativeDay | FAIL | infra_failure | 0 | DB state mismatch |
| 20 | SimpleCalendarDeleteOneEvent | FAIL | infra_failure | 0 | DB path not found |

*`*` = actual turns from logcat; runner reported 0 due to trace capture bug*

---

## Common Problems (Ranked by Impact)

### P1: Time Picker Element Index Confusion (5 calendar tasks affected)

**Impact**: Directly caused failure in SimpleCalendarAddOneEventTomorrow, SimpleCalendarAddOneEvent, SimpleCalendarAddOneEventRelativeDay. Contributed to waste in SimpleCalendarAddRepeatingEvent, SimpleCalendarDeleteEvents.

**Problem**: On the Android clock-face time picker, the agent confuses the **element_index** with the **time value** it wants to set. For example:
- Wanting to set 20:00, agent clicks `element_index: 20` — but idx 20 is the "OK" button, not the hour 20.
- Wanting to set 13:00, agent clicks `element_index: 11` — but idx 11 may map to a different clock position.

The clock-face time picker's element indices do NOT correspond 1:1 with hour numbers. The agent must read the element's `content_description` or visible text to verify.

**Root Cause Category**: Perception + Reasoning

**Fix**:
1. **System prompt tip**: "For time pickers, ALWAYS switch to text/keyboard input mode (click the keyboard/edit icon at the bottom of the time picker dialog) and type the time value directly. Do NOT try to click numbers on the clock face, as element indices do not match hour values."
2. **Agent code**: Consider auto-detecting time picker dialogs and injecting a "switch to text mode" step.

---

### P2: Cycle / Loop Detection Weakness (4 tasks: 85+ wasted turns)

**Impact**: AudioRecorderRecordAudioWithFileName (12 shell loops), SimpleCalendarDeleteEvents (29 nav loops), SimpleCalendarAddRepeatingEvent (13 scroll loops), ExpenseAddMultipleFromGallery (20 gallery browsing loops).

**Problem**: The agent repeats the same failing strategy 10-20+ times without pivoting. Cycle detection should catch this much earlier.

Specific patterns:
- **Shell search loop**: `find /sdcard -name "*.m4a"` repeated 6 times → always empty
- **Calendar nav loop**: click header → open month picker → OK → click header → repeat (5+ cycles)
- **Time picker loop**: scroll down → click time → scroll up → scroll down → click time (7+ cycles)
- **Gallery browse loop**: swipe left → check properties → back → swipe left (10+ cycles)

**Root Cause Category**: Orchestration

**Fix**:
1. **Strengthen cycle detection**: If the same tool+action+target pattern appears 3 times without meaningful state change, force strategy pivot.
2. **Strategy pivot guidance**: "If you've attempted the same approach 3 times and it hasn't worked, you MUST try a fundamentally different approach or report inability."
3. **Turn budget awareness**: "If you've spent more than 10 turns on a sub-task (like navigation) without progress, stop and re-evaluate."

---

### P3: False Positive Goal Claims (3 tasks)

**Impact**: ExpenseAddMultipleFromMarkor (wrong categories), SimpleCalendarAddOneEventTomorrow (wrong time), ExpenseDeleteDuplicates (incomplete deletion). All claimed GoalAchieved but scored 0.0.

**Problem**: The agent declares success without verifying the actual result matches requirements. In all three cases, the agent completed the *mechanical steps* but made errors in the *data values*.

**Root Cause Category**: Observation (self-verification gap)

**Fix**:
1. **Pre-completion checklist**: Before calling `complete_task(success)`, verify every data field mentioned in the goal matches what was entered. Specifically:
   - Re-read the goal requirements
   - Check scratchpad notes against what was entered
   - For calendar events: verify date, start time, end time, title, description
   - For expense entries: verify name, amount, category, note
2. **Self-verification tip**: "Before claiming goal achieved, scroll to the saved entry and verify all fields match the goal requirements exactly."

---

### P4: Time Format Misinterpretation — "5h" = 05:00, not 17:00 (1 task, systemic risk)

**Impact**: Directly caused SimpleCalendarAddOneEventRelativeDay failure. May affect any task using "Nh" time notation where N < 12.

**Problem**: The goal says "at 5h" meaning 05:00 (24-hour format). The agent interpreted this as 17:00 (PM), likely because:
- "5h" looks like "5 o'clock" which defaults to PM in common understanding
- The model lacks explicit training on the "Nh" == 24-hour notation convention

**Root Cause Category**: Reasoning

**Fix**:
1. **System prompt tip**: "In task descriptions, time formats like '5h', '13h', '20h' use 24-hour notation. '5h' means 05:00 (5 AM), NOT 17:00. '13h' means 13:00. Never add 12 to hours below 12."

---

### P5: Calendar Date Navigation Failure (2 tasks)

**Impact**: SimpleCalendarDeleteEvents (29 turns wasted, never reached Oct 27), SimpleCalendarAddRepeatingEvent (couldn't find correct settings).

**Problem**: The agent doesn't know how to navigate Simple Calendar Pro to a specific date. It keeps opening the month/year header picker (which just confirms the current month) instead of clicking the day cell in the calendar grid.

**Root Cause Category**: Execution (app-specific knowledge gap)

**Fix**:
1. **App-specific tip**: "In Simple Calendar Pro monthly view: to navigate to a specific date within the visible month, click directly on the DAY NUMBER in the calendar grid. The header '← October 2023 →' just changes the month view, NOT the selected date. Use left/right arrows on the header to change months."
2. **Date picker navigation**: "In date picker dialogs, dates are displayed as a grid of numbered cells. Click the target date number directly."

---

### P6: Image Content Inaccessible (1 task, capability gap)

**Impact**: ExpenseAddMultipleFromGallery — completely blocked, 30 turns wasted.

**Problem**: Accessibility-only perception cannot read text from images. The task requires extracting expense data from `expenses.jpg`, which is impossible without screenshot/OCR capability.

**Root Cause Category**: Perception (capability limitation)

**Fix**:
1. **Task-level perception override**: Detect tasks that involve reading image content and auto-switch to hybrid perception mode.
2. **OCR fallback**: Add a shell-based OCR tool (e.g., `tesseract` on-device) for extracting text from images when hybrid perception isn't available.
3. **Early detection**: Agent should recognize within 3-5 turns that it cannot read image content and report inability rather than wasting 30 turns.

---

### P7: Shell Command Fallback Loops (1 task, pattern risk)

**Impact**: AudioRecorderRecordAudioWithFileName — 12 turns searching filesystem for files stored in app-internal database.

**Problem**: When the UI approach fails, the agent falls back to shell commands but repeats the same failing `find`/`ls` commands without adapting. Audio Recorder stores files in an internal DB, not the filesystem.

**Root Cause Category**: Reasoning + Execution

**Fix**:
1. **Shell loop breaker**: "After 2 failed shell search commands for the same file, conclude the file may not be on the filesystem and try a different approach."
2. **App-specific knowledge**: "Audio Recorder app stores recordings in an internal database, not as files on /sdcard."

---

### P8: Trace Capture Infrastructure Bug (8 tasks affected)

**Impact**: Runner reported 0 turns and null trace_dir for 8 tasks that actually ran 5-29 turns per logcat. This corrupts eval metrics and prevents trace-based analysis.

**Problem**: The runner's trace capture mechanism silently fails for some tasks — it doesn't pull traces from the device, resulting in null `trace_dir` and 0 `turns_executed` in `per_task.jsonl`.

**Root Cause Category**: Infra (not agent)

**Fix**:
1. **Debug trace pull**: Investigate why `adb pull` for trace files fails for some tasks. Check if the trace directory on-device exists at the expected path.
2. **Fallback trace source**: Use logcat as backup trace source when device trace pull fails.
3. **Runner validation**: Runner should log a warning when it detects 0 turns but non-zero duration (indicating agent ran but traces weren't captured).

---

### P9: Infra Failure — Calendar DB Initialization (3 tasks)

**Impact**: SimpleCalendarDeleteEventsOnRelativeDay (both attempts), SimpleCalendarDeleteOneEvent (both attempts), SimpleCalendarAddOneEventTomorrow (attempt 0), SimpleCalendarAddRepeatingEvent (attempt 0).

**Problem**: `initialize_task()` fails with "no such table: events" or "No such file or directory" for Calendar DB. The `initialize_task()` retry guard also prevents recovery.

**Root Cause Category**: Infra (not agent)

**Fix**:
1. **Pre-eval setup**: Ensure Simple Calendar Pro is launched at least once before eval so it creates its database.
2. **Retry idempotency**: Fix `initialize_task()` to be idempotent — allow retry after failure.
3. **Database path verification**: Add pre-check for Calendar DB existence before running calendar tasks.

---

### P10: LLM Provider Error (1 task)

**Impact**: BrowserDraw — failed on first LLM call.

**Problem**: "SseException - 200: Provider returned error" from OpenRouter for the qwen3.5 model. The task requires hybrid perception mode which may have resulted in a larger payload that the provider couldn't handle.

**Root Cause Category**: Infra (LLM provider)

**Fix**:
1. **LLM retry**: Add retry logic for transient LLM provider errors (with exponential backoff).
2. **Payload size check**: Verify the hybrid perception payload (with screenshots) doesn't exceed provider limits.

---

## Proposed Changes by Priority

### Critical (address before next eval)

| # | Change | Type | Affected Tasks | Estimated Impact |
|---|--------|------|----------------|-----------------|
| 1 | **Time picker text input tip** — Always switch to keyboard mode | System Prompt | 5 calendar tasks | +2-3 passes |
| 2 | **"Nh" = 24-hour format tip** | System Prompt | All calendar tasks | +1 pass |
| 3 | **Calendar day-cell navigation tip** | System Prompt | 2 calendar tasks | +1-2 passes |
| 4 | **Strengthen cycle detection** (3 repeats → force pivot) | Agent Code | 4 tasks | +1-2 passes |
| 5 | **Fix trace capture infra** | Runner Code | 8 tasks | Better diagnostics |

### Important (high value, moderate effort)

| # | Change | Type | Affected Tasks | Estimated Impact |
|---|--------|------|----------------|-----------------|
| 6 | **Pre-completion self-verification** tip | System Prompt | 3 FP tasks | +1-2 passes |
| 7 | **Shell loop breaker** (2 failures → stop) | Agent Code / Prompt | 1 task | +0-1 passes |
| 8 | **Calendar DB pre-initialization** | Eval Infra | 3 infra tasks | +0-1 passes |
| 9 | **LLM retry for provider errors** | Agent Code | 1 task | +0-1 passes |
| 10 | **Category verification before click** | System Prompt | 1 task | +0-1 passes |

### Nice to have (capability gaps)

| # | Change | Type | Affected Tasks | Estimated Impact |
|---|--------|------|----------------|-----------------|
| 11 | **Image OCR capability** | New Feature | 1 task | +1 pass |
| 12 | **Task-level perception auto-detection** | Agent Code | 1-2 tasks | +1 pass |

### Projected Impact

If all Critical + Important changes are implemented:
- Current: 6/20 = 30%
- Projected: 10-13/20 = 50-65%
- The 3 infra-failure tasks would need env fixes to even attempt.

---

## Appendix: Root Cause Distribution

| Root Cause Category | Count | Tasks |
|---|---|---|
| **Execution** (wrong targets, navigation) | 5 | CalendarDeleteEvents, CalendarAddRepeatingEvent, CalendarAddOneEvent, CalendarAddOneEventTomorrow, AudioRecorderWithFileName |
| **Reasoning** (misinterpretation, wrong logic) | 3 | CalendarRelativeDay (time format), ExpenseFromMarkor (categories), ExpenseDeleteDuplicates |
| **Perception** (can't see content) | 1 | ExpenseFromGallery |
| **Observation** (false positive claims) | 3 | ExpenseFromMarkor, CalendarTomorrow, ExpenseDeleteDuplicates |
| **Orchestration** (cycle/loop management) | 4 | AudioRecorderWithFileName, CalendarDeleteEvents, CalendarRepeatingEvent, ExpenseFromGallery |
| **Infra** (not agent) | 5 | CalendarDeleteRelativeDay, CalendarDeleteOneEvent, BrowserDraw (LLM), trace capture (8 tasks) |
| **N/A** (pass) | 6 | AudioRecorder, CameraTakeVideo, ClockPaused, ClockRunning, Contacts, CalendarInTwoWeeks |

*Note: Some tasks have multiple root causes.*
