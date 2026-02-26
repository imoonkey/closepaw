# Round 7 Eval Analysis — Aligned Summary

Run: `eval/results/20260225_225734` | Model: qwen3.5 | Tasks: 20 | Pass: 6/20 (30%)

---

## 1. Task-Level Results (Consensus)

| # | Task | Status | Completion | Turns | Aligned Root Cause |
|---|------|--------|------------|-------|-------------------|
| 1 | AudioRecorderRecordAudio | **PASS** | GoalAchieved | 5 | N/A — clean execution |
| 2 | AudioRecorderRecordAudioWithFileName | FAIL | MaxTurnsReached | 30 | Shell+UI loop: no rename capability found, 12+ shell searches (Reasoning, Orchestration) |
| 3 | BrowserDraw | FAIL | Error | 1 | LLM provider SSE error on first call (Infra) |
| 4 | BrowserMaze | FAIL | null | 18† | Trace missing; 18 actual turns per logcat. Root cause unknown — needs trace fix to analyze (Infra + Unknown) |
| 5 | CameraTakeVideo | **PASS** | GoalAchieved | 6 | N/A — clean execution |
| 6 | ClockStopWatchPausedVerify | **PASS** | GoalAchieved | 3 | N/A |
| 7 | ClockStopWatchRunning | **PASS** | null | 5† | N/A — trace missing but scored pass |
| 8 | ContactsNewContactDraft | **PASS** | null | 8† | N/A — trace missing but scored pass |
| 9 | ExpenseAddMultiple | FAIL | null | 25† | Trace incomplete (4 events); logcat shows 25 tool calls with all 3 expenses entered. Likely data entry error — amount formatting or missing clear:true (Reasoning, Execution) |
| 10 | ExpenseAddMultipleFromGallery | FAIL | MaxTurnsReached | 30 | A11y-only perception cannot read image content. 20 turns wasted on gallery browsing (Perception, Orchestration) |
| 11 | ExpenseAddMultipleFromMarkor | FAIL | GoalAchieved(FP) | 19 | Multiple errors: wrong categories (Food instead of Clothes, Housing instead of Transport) + note included "Reimbursable" tag (Reasoning, Observation) |
| 12 | ExpenseDeleteDuplicates | FAIL | null | 12† | Trace shows 1 turn (open_app); logcat shows 12 actual turns. Premature completion without full scan (Reasoning, Observation) |
| 13 | SimpleCalendarAddOneEvent | FAIL | null | 21† | Trace missing; logcat shows 21 turns. Time picker element index confusion — agent clicked idx instead of time value (Perception, Reasoning) |
| 14 | SimpleCalendarAddOneEventInTwoWeeks | **PASS** | null | 22† | N/A — trace missing but scored pass |
| 15 | SimpleCalendarAddOneEventRelativeDay | FAIL | null | 17† | Trace missing; logcat shows 17 turns. Agent interpreted "5h" as 17:00 instead of 05:00 (Reasoning) |
| 16 | SimpleCalendarAddOneEventTomorrow | FAIL | GoalAchieved(FP) | 27 | Time picker confusion: agent clicked element idx 20 thinking it sets hour 20, but likely hit wrong control. Saved event with wrong time (Execution, Observation) |
| 17 | SimpleCalendarAddRepeatingEvent | FAIL | MaxTurnsReached | 30 | Got stuck in repeat settings / time picker loop. Couldn't find correct date (Oct 15) or configure recurrence properly (Execution, Orchestration) |
| 18 | SimpleCalendarDeleteEvents | FAIL | null | 29† | Trace has 2 events; logcat shows 29 turns. Complete navigation failure — agent spent all turns failing to reach Oct 27 in calendar grid (Execution, Orchestration) |
| 19 | SimpleCalendarDeleteEventsOnRelativeDay | FAIL | infra_failure | 0 | DB state mismatch: "42 rows vs expected 22." Both attempts failed (Infra) |
| 20 | SimpleCalendarDeleteOneEvent | FAIL | infra_failure | 0 | Calendar DB path not found on device. Both attempts failed (Infra) |

*`†` = actual turn count from logcat; runner reported 0 due to trace capture bug*

---

## 2. Common Problems (Ranked by Impact)

### CP1: Trace Capture Infrastructure Bug

**Affected**: 8 tasks (BrowserMaze, ClockStopWatchRunning, ContactsNewContactDraft, ExpenseAddMultiple, SimpleCalendarAddOneEvent, SimpleCalendarAddOneEventInTwoWeeks, SimpleCalendarAddOneEventRelativeDay, SimpleCalendarDeleteEvents) — plus partial trace for ExpenseDeleteDuplicates.

**Problem**: Runner reports `turns_executed=0` and `trace_dir=null` for tasks that actually ran 5-29 turns per logcat. This is a recurring issue — previously seen with MaxTurnsReached tasks, now confirmed to affect a broader set.

**Evidence**: Claude used logcat `TurnExecutionPhase: Executing tool` entries to reconstruct actual agent behavior for all 8 affected tasks. Codex independently flagged the same pattern ("Pattern A") from trace-side evidence.

**Root Cause (code-level)**:

Race condition between completion signal and trace flush. The exact sequence:

1. `Agent.run()` finishes → calls `trace.sessionStopped()` which **enqueues** `session_stopped` event and `run_summary.json` write to `FileTraceRecorder`'s async Channel (`FileTraceRecorder.kt:64-67`)
2. `handleAgentComplete()` runs → emits `TaskCompleted` event to logcat (`AgentSession.kt:340-348`) **before** the writer coroutine has processed the enqueued trace events
3. Runner's `LogcatCompletionMonitor` detects "Task completed" in logcat (`completion_monitor.py:58-66`)
4. Runner calls `stop_agent()` then **immediately** `force_stop()` (`native_agent_bridge.py:93-94`) — zero delay between them
5. `force_stop()` kills the Android process via `am force-stop`, terminating the writer coroutine mid-flight
6. `pull_trace_dir()` gets an incomplete `trace.jsonl` — missing the `session_stopped` event and `run_summary.json` artifact
7. `parse_trace()` can't find `session_stopped` → returns `turns_executed=0` (`trace_parser.py:54-72`)

The core bug: **`handleAgentComplete()` signals completion BEFORE trace is flushed, and `force_stop()` kills the process BEFORE the async writer finishes.**

`FileTraceRecorder` already flushes each event via `writer.flush()` per line (`FileTraceRecorder.kt:129`), but events enqueued via `trace.sessionStopped()` sit in the Channel until the writer loop picks them up. When `force_stop()` kills the process, the Channel drains partially or not at all.

**Fix** — single root-cause fix, no ad-hoc workarounds:

Add a `flush()` method to `FileTraceRecorder` that blocks until all queued events are written to disk, and call it in `handleAgentComplete()` BEFORE emitting `TaskCompleted`:

```kotlin
// FileTraceRecorder.kt — new flush mechanism
sealed class WriteOp {
    data class AppendLine(val line: String) : WriteOp()
    data class WriteBytes(...) : WriteOp()
    data class WriteUtf8(...) : WriteOp()
    data class Flush(val done: CompletableDeferred<Unit>) : WriteOp()  // NEW
}

suspend fun flush() {
    val done = CompletableDeferred<Unit>()
    channel.send(WriteOp.Flush(done))
    done.await()  // Blocks until writer loop processes all prior ops
}

// In writer loop:
is WriteOp.Flush -> op.done.complete(Unit)
```

```kotlin
// AgentSession.kt — handleAgentComplete()
private suspend fun handleAgentComplete(reason: AgentStopReason) {
    // ... existing code ...

    // FLUSH TRACE BEFORE signaling completion
    services.traceRecorder.flush()

    // NOW emit TaskCompleted — trace is guaranteed on disk
    emit(TaskCompleted(...))

    // ... rest of existing code ...
}
```

This guarantees that by the time the runner detects "Task completed" in logcat, ALL trace events (including `session_stopped` and `run_summary.json`) are already on disk. The `force_stop()` can safely kill the process — there's nothing left to write.

**Priority**: **P0** — must fix before next eval.

---

### CP2: Time Picker Element Index Confusion

**Affected**: SimpleCalendarAddOneEvent, SimpleCalendarAddOneEventTomorrow, SimpleCalendarAddOneEventRelativeDay. Contributed to SimpleCalendarAddRepeatingEvent, SimpleCalendarDeleteEvents.

**Problem**: On Android clock-face time pickers, the agent confuses **element_index** with the **hour value** it wants to set. The clock face renders hours (1-12) as clickable segments, but their accessibility element indices do NOT correspond to the hour numbers. The agent sees "I want hour 20" and clicks `element_index: 20`, which might be the OK button or a minute marker.

**Evidence** (from SimpleCalendarAddOneEventTomorrow trace):
- Turn 11: `click(idx=20)` with thought "Click on '20' in the time picker to set the hour to 20" — but idx 20 was not the hour 20 position
- Turn 12: `click(idx=20)` again with thought "The time is now set to 20:00. Click OK" — clicked same element twice with different intentions

**Fix**:
1. **System prompt tip** (under Calendar section — see **System Prompt Restructure** below): "For time pickers, ALWAYS switch to text/keyboard input mode (click the keyboard/edit icon at the bottom of the time picker dialog) and type the time value directly. Do NOT click numbers on the clock face — element indices do not correspond to hour values."
2. Consider auto-detecting time picker dialogs and injecting a "switch to text mode" hint in perception output.

**Priority**: **P1-Critical** — directly caused 3 failures, contributed to 2 more. Prompt fix is low-effort, high-impact.

---

### CP3: Cycle / Loop Detection Weakness

**Affected**: AudioRecorderRecordAudioWithFileName (12 shell loops), SimpleCalendarDeleteEvents (29 nav loops), SimpleCalendarAddRepeatingEvent (13 scroll/time loops), ExpenseAddMultipleFromGallery (20 gallery browse loops). Total: 85+ wasted turns across 4 tasks.

**Problem**: The agent repeats the same failing strategy 10-20+ times without pivoting to a fundamentally different approach.

**Patterns observed**:
- Shell search loop: `find /sdcard -name "*.m4a"` repeated 6 times → always empty
- Calendar nav loop: click header → open month picker → OK → repeat (5+ cycles)
- Time/scroll loop: scroll down → click → scroll up → repeat (7+ cycles)
- Gallery browse loop: swipe left → check properties → back → repeat (10+ cycles)

**Fix**:

Cycle detection IS implemented (`LoopDetectionPolicy.kt`) — it was added in the round7 postfix (183513 run). It successfully broke the AudioRecorder 23-turn save→warning→OK loop (30→9 turns). But the current thresholds are too permissive and several loop patterns slip through:

| Config | Current | Problem |
|--------|---------|---------|
| `cycleMinOccurrences` | 3 | Misses 2-state oscillations (dialog open/close) |
| `cycleMatchThreshold` | 0.85 | Too high — minor UI changes (progress, text) drop below threshold |
| `repeatedScreenWindow` | 3 | Too short — 5-7 turn loop sequences escape detection |
| `maxConsecutiveScrollActions` | 5 | OK for scrolls, but shell command loops are uncaught |

Additionally, the warning IS injected into the LLM prompt, but there's NO system prompt guidance telling the LLM how to respond to loop warnings. The LLM has to infer it should pivot.

Concrete changes needed:
1. **Tighten thresholds**: `cycleMinOccurrences: 2`, `cycleMatchThreshold: 0.75`, `repeatedScreenWindow: 5`
2. **Add action-sequence detection**: Track shell command repetitions (same command repeated 2+ times → warning), not just screen similarity
3. **System prompt guidance** (under General Tips): "When you see a loop/cycle warning, you MUST immediately try a fundamentally different approach. Do NOT repeat the same strategy."

**Priority**: **P1-Critical** — code exists but needs tuning + prompt support.

---

### CP4: False Positive Goal Claims (Self-Verification Gap)

**Affected**: ExpenseAddMultipleFromMarkor, SimpleCalendarAddOneEventTomorrow, (ExpenseDeleteDuplicates — incomplete but didn't explicitly claim success). Goal_claim_precision = 60%.

**Problem**: Agent calls `complete_task(success)` without verifying the saved data matches the goal requirements. In all cases, the agent completed the mechanical steps but made errors in specific data values:
- ExpenseAddMultipleFromMarkor: selected wrong categories (Food instead of Clothes), included "Reimbursable" tag in note field
- SimpleCalendarAddOneEventTomorrow: saved event with wrong time due to picker confusion

**Evidence**: Both Claude and Codex agree on this pattern. Code-level scorer checks confirm this is not only a trace issue:
- Expense addition compares `name/amount/category/note`; Markor can fail on both category and note mismatches.
- Calendar addition compares `start_ts/end_ts/title/location/description` (plus repeat fields for recurring tasks); `source/time_zone` are shown in logs but are not part of default comparison fields.

**Fix**:
1. System prompt tip (under **Completion** section — see **System Prompt Restructure** below): "Before calling complete_task(success), re-read the original goal and verify each requirement was met. Scroll to the saved entry and confirm all fields match exactly."
2. App-specific verification tips organized under each app section:
   - **Expense apps**: "After saving, verify the entry shows the correct name, amount, and category. If entering from a source file, verify category matches the source text exactly."
   - **Calendar apps**: "After saving, open the event to verify start time, end time, date, title, and description all match the goal."

**Priority**: **P1-Important** — improves goal_claim_precision from 60% toward 90%+.

---

### CP5: Time Format "Nh" Misinterpretation

**Affected**: SimpleCalendarAddOneEventRelativeDay (directly). Systemic risk for all tasks using "Nh" notation where N < 12.

**Problem**: Goal says "at 5h" meaning 05:00 (24-hour). Agent set 17:00. The "Nh" notation is standard in AndroidWorld tasks but the LLM interprets ambiguous hour < 12 as PM.

**Evidence**: Logcat for SimpleCalendarAddOneEventRelativeDay shows agent set 17:00 instead of 05:00. (Codex couldn't verify this because trace was missing.)

**Fix**:
1. System prompt tip (under **General** section): "In task descriptions, 'Nh' format means 24-hour time. '5h' = 05:00 (5 AM), '13h' = 13:00, '20h' = 20:00. Never add 12 to hours below 12."

**Priority**: **P1-Important** — low-effort prompt fix with clear impact.

---

### CP6: Calendar Date Navigation Failure

**Affected**: SimpleCalendarDeleteEvents (29 turns wasted), SimpleCalendarAddRepeatingEvent (partial).

**Problem**: Agent doesn't know how to navigate Simple Calendar Pro to a specific date. Keeps opening the month/year header picker (which just switches month view) instead of clicking the day number cell in the calendar grid.

**Evidence**: Logcat for SimpleCalendarDeleteEvents shows the agent repeating click header → month picker → OK → click header cycle for 29 turns straight, never reaching Oct 27.

**Fix**:
1. System prompt tip (under **Calendar** section): "In Simple Calendar Pro monthly view: to navigate to a specific date, click directly on the DAY NUMBER in the calendar grid. The header arrows change months. Do NOT use the header date to navigate to a specific day."

**Priority**: **P1-Important** — addresses navigation knowledge gap.

---

### CP7: Infra Failure — Calendar DB Initialization

**Affected**: SimpleCalendarDeleteEventsOnRelativeDay (both attempts), SimpleCalendarDeleteOneEvent (both attempts), SimpleCalendarAddOneEventTomorrow (attempt 0), SimpleCalendarAddRepeatingEvent (attempt 0).

**Problem**: AndroidWorld's `initialize_task()` fails with:
- "no such table: events" — Calendar Pro DB not initialized
- "No such file or directory" — DB path doesn't exist
- "initialize_task() is already called" — retry guard blocks recovery

**Fix**:
1. Pre-eval setup script: launch Simple Calendar Pro once to create its database
2. Fix `initialize_task()` idempotency — allow retry after failure
3. Validate DB existence before running calendar tasks

**Priority**: **P0-Infra** — blocks 3 tasks from even attempting. Must fix before next eval.

---

### CP8: Image Content Inaccessible (Capability Gap)

**Affected**: ExpenseAddMultipleFromGallery.

**Problem**: A11y-only perception cannot read text from images. The task requires reading expense data from `expenses.jpg`. Agent spent 30 turns browsing gallery but couldn't extract any data.

**Fix**:
1. **Task override in `eval/config/default.yaml`**: Add `ExpenseAddMultipleFromGallery: { perception_mode: hybrid }` to `task_overrides` — same pattern as BrowserDraw and BrowserMaze.
2. Agent should recognize inability within 3-5 turns rather than wasting 30.

**Priority**: **P1** — simple config change, same pattern as existing overrides.
---

### CP9: LLM Provider Error

**Affected**: BrowserDraw.

**Problem**: "SseException - 200: Provider returned error" on first LLM call. Hybrid perception payload may exceed provider limits.

**Fix**: Deferred — not prioritized for next round.

**Priority**: **Deferred** — single task, intermittent, not actionable now.

---

## 3. Proposed Changes by Priority

### P0: Fix Eval Infrastructure (before next eval)

| Change | Type | File(s) |
|--------|------|---------|
| Trace flush before completion signal (CP1 root fix) | Agent Code | `FileTraceRecorder.kt`, `AgentSession.kt` |
| Calendar DB pre-initialization in eval setup | Eval Script | Runner setup / pre-eval script |
| `ExpenseAddMultipleFromGallery` hybrid mode override | Eval Config | `eval/config/default.yaml` |

### P1: Fix Agent Cognition (highest pass-rate impact)

| Change | Type | Expected Impact |
|--------|------|-----------------|
| Time picker: always use text/keyboard input mode | System Prompt (Calendar) | +2-3 passes |
| "Nh" = 24-hour format interpretation | System Prompt (General) | +1 pass |
| Calendar day-cell navigation guidance | System Prompt (Calendar) | +1-2 passes |
| Tune cycle detection thresholds + add shell loop detection | Agent Code (`LoopDetectionPolicy.kt`) | +1-2 passes |
| Add loop warning response guidance to system prompt | System Prompt (General) | +0-1 passes |
| Pre-completion self-verification (per app type) | System Prompt (Completion + per app) | +1-2 passes |
| **System prompt restructure** ← organize all tips | System Prompt (all agent defs) | Enables above tips cleanly |

### Deferred

| Change | Type | Reason |
|--------|------|--------|
| LLM retry for provider errors | Agent Code | Single intermittent failure, low ROI |

### Projected Outcome

- Current: 6/20 = 30%
- After P0 (infra fixes only): 6/17 = 35% (3 infra tasks become runnable, Gallery gets hybrid mode)
- After P0 + P1: 10-13/20 = 50-65%

---

## 4. System Prompt Restructure

The current `StandaloneAgentDef` system prompt (`StandaloneAgentDef.kt`) has structural issues: Device Environment is sandwiched between Tips and Shell, Tips has only 2 generic items, and there's no logical place to add app-specific guidance. As we add more tips from this analysis, the prompt needs a clean structure.

### Current structure (problematic)

```
1. Your Job
2. Tool Calling
3. Open App
4. Own UI — Do NOT Interact
5. Core Loop
6. Execution Quality
7. Scroll vs Swipe
8. Tips              ← 2 generic items, no organization
9. Device Environment ← sandwiched in the middle
10. Shell Tool
11. ask_user
```

### Proposed structure

```
## Role                         — who you are, what you do
## Core Loop                    — observe → act → verify cycle
## Tools                        — all tool-related rules in one place
  ### Calling Conventions       — structured calls, batching, one action per turn
  ### mobile_action             — clicks, scrolls, swipes, own-UI avoidance
  ### open_app                  — direct launch, no drawer
  ### shell                     — when/how, loop limits
  ### ask_user                  — last resort
  ### complete_task             — self-verification before calling
## App Tips                     — reference: organized by app family
  ### Calendar                  — time picker text mode, day-cell nav, "Nh" format, event form preference
  ### Expense                   — category verification, amount clear:true, note field
  ### General                   — NumberPicker text input, loop warning response
## Device Environment           — reference: hardware/screen/date
```

Design rationale:
1. **Tools section consolidates ALL tool guidance**: mobile_action (clicks, scrolls, swipes, own-UI avoidance), shell, ask_user, open_app, complete_task (including self-verification). Everything tool-related in one place.
2. **App Tips + Device Environment → end**: Both are reference material, not behavioral rules. The agent reads HOW to work first, then WHAT to know last.
3. **complete_task subsection includes self-verification**: "Verify before calling" is a pre-condition of the tool, not a separate concept.
4. **Same restructure applies to PlannerAgentDef** (identical Tips section at lines 90-92).

---

## 5. Methodology Note

This analysis combines two independent analyses:
- **Claude**: Used `per_task.jsonl` + **logcat** as primary evidence. Logcat provided complete tool-call-level reconstruction for 8 tasks where traces were missing.
- **Codex**: Used `per_task.jsonl` + **trace data** as primary evidence. Had complete turn-by-turn analysis for tasks with intact traces (12 tasks), but limited/no analysis for 8 tasks with missing traces.

The logcat-based analysis proved essential for this run because the trace capture bug affected 40% of tasks. For future runs, fixing trace capture (P0) will make both approaches equally effective.

---

## 6. Resolved and Open Items

### Resolved in This Alignment Round

1. **"Evaluation gap" vs. trace issue**: Not equivalent. We observed both:
   - Instrumentation gap: missing/incomplete traces.
   - Real scoring-contract/verification gap: tasks with complete traces still claimed success but failed scorer checks.

2. **ExpenseAddMultipleFromMarkor primary failure**: scorer compares `name/amount/category/note`; both category and note mismatches are sufficient failure causes. This is a dual-error task, not a single-cause disagreement.

3. **SimpleCalendarAddOneEventTomorrow failure class**: scorer compares `start_ts/end_ts/title/location/description` (not `source/time_zone` by default). Most likely failure remains incorrect start/end time or date due picker interactions.

### Still Open

1. **ExpenseAddMultiple exact failure field(s)**: logcat shows substantial execution, but trace is truncated at `llm_request` and scorer warning is not field-specific for all rows. We still need fixed trace export (or DB diff dump) to determine whether amount formatting, note, category, or save semantics is the dominant failure mode.
