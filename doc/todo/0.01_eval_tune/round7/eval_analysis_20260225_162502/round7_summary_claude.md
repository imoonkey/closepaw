# Round 7 — Quick5 Evaluation Summary (Claude Analysis)

## Overview

| Metric | Value |
|---|---|
| Model | qwen3.5 (qwen/qwen3.5-plus-02-15) |
| Perception | accessibility_only (no screenshots) |
| Total Tasks | 5 |
| Max Turns | 30 per task |
| Eval Run | 20260225_162502 |
| Config | eval/config/aw_quick5.txt |
| **Pass Rate** | **2/5 (40%)** |
| Duration p50 | 199.0s |
| Duration p90 | 226.9s |
| Tool Failure Rate | 2.9% (2/70 total tool calls) |
| Goal Claim Precision | 100% (2/2 GoalAchieved were true successes) |
| Infra Failure Rate | 0% |

### Context: Infra Fix Applied This Round

The completion monitor (`eval/aw_bridge/completion_monitor.py`) had a critical bug: sessions killed by `fresh_session=true` emitted `AgentService: Session completed ... reason: USER_STOPPED`, which the monitor treated as a real task completion. This caused tasks to terminate after 0 turns with `bridge_status=completed`.

**Fix**: Filter out `USER_STOPPED` lines from the completion pattern. Also removed the `AgentSession: Emitted event: SessionCompleted` pattern (lacks reason field, so the filter can't distinguish teardown from real completion).

**Impact**: Infra failure rate dropped from 20% to 0%, and tasks that previously showed 0 turns (AudioRecorder, Contacts) now actually executed. Pass rate improved from 20% (round 6 initial) to 40%.

## Results by Task

| # | Task | Turns | Completion | Score | Root Cause Category |
|---|------|-------|------------|-------|---------------------|
| 1 | AudioRecorderRecordAudio | 30 | MaxTurnsReached | 0.0 | Cognition: Infinite loop (save -> warning dialog -> OK, 23 turns) |
| 2 | **ClockStopWatchRunning** | **4** | **GoalAchieved** | **1.0** | **Success: Optimal path** |
| 3 | **ContactsNewContactDraft** | **11** | **GoalAchieved** | **1.0** | **Success: Clean execution** |
| 4 | ExpenseAddMultipleFromMarkor | 30 | MaxTurnsReached | 0.0 | Perception: A11y text truncation; never left Markor |
| 5 | SimpleCalendarAddOneEvent | 30 | MaxTurnsReached | 0.0 | Cognition: 16 turns on date navigation; 2 actions from success |

## Failure Distribution by Root Cause

| Root Cause Category | Count | Tasks |
|---|---|---|
| **Cognition: Loop / No Progress Detection** | 1 | AudioRecorderRecordAudio |
| **Perception: A11y Text Truncation** | 1 | ExpenseAddMultipleFromMarkor |
| **Cognition: Inefficient Navigation + Turn Budget** | 1 | SimpleCalendarAddOneEvent |
| **Success** | 2 | ClockStopWatchRunning, ContactsNewContactDraft |

## Cross-Cutting Analysis

### 1. Loop Detection Remains the Biggest Cognition Gap (2/3 failures)

**AudioRecorderRecordAudio** entered a 23-turn cycle: press save -> warning dialog ("records deleted or moved") -> press OK -> records list (all 00:00) -> navigate back -> press save again. The model (qwen3.5) showed zero awareness of the repetition across 8+ full iterations of the exact same 3-screen cycle.

**ExpenseAddMultipleFromMarkor** similarly cycled for 26 turns through scroll/swipe/mode-switch actions in Markor trying to reveal truncated text, never recognizing the futility or pivoting to an alternative strategy (shell read, open a different app).

Both failures share the same meta-pattern: the agent lacks a mechanism to detect "I've seen this screen state before and my action didn't change it." This was identified in round 6 but remains unaddressed.

### 2. A11y Text Truncation is a Hard Perception Ceiling

ExpenseAddMultipleFromMarkor's a11y tree consistently exposed only ~60 characters of the `my_expenses.txt` file content: `"name|amount_dollars|category_name|note\nEducational|$296.94|D"`. This happened in both EditText (edit mode) and WebView/TextView (view mode). No amount of scrolling changed the exposed text.

**Compounding factor**: `screenshot_attached: false` for all 30 turns. Screenshots would have allowed the LLM to visually read the rendered file content, but were never sent in `accessibility_only` perception mode.

**Compounding factor 2**: The agent tried `find /sdcard -name "my_expenses.txt" 2>/dev/null` via shell but the command was rejected for containing the `>` redirect operator. It never retried without the redirect, missing its best path to read the file content.

### 3. Calendar Date Navigation is Systematically Expensive

SimpleCalendarAddOneEvent spent 16 of 30 turns (53%) navigating the calendar view from February 2026 to October 2023 before even opening the event creation form. Breakdown:
- 2 turns wasted going the wrong direction (right/forward instead of left/backward)
- 4 turns scrolling the year NumberPicker from 2026 to 2023
- 6 turns scrolling the month NumberPicker from January to October
- 2 turns confirming the selection
- 2 turns opening event creation on the correct date

The agent was then **exactly 2 actions from success** (type description + save) when it hit turn 30. All other fields were correctly filled.

**Better strategy**: Skip calendar view navigation entirely. Click "New Event" immediately, then use the date picker within the event form. This saves ~12 turns.

### 4. Successes Show the Agent's Strength: Simple, Single-App, Well-Structured UIs

**ClockStopWatchRunning** (4 turns, optimal): open_app -> click Stopwatch tab -> click Start -> complete_task. Clean execution with correct own-UI avoidance and precise element targeting.

**ContactsNewContactDraft** (11 turns, near-optimal): open_app -> create contact -> fill 4 fields -> complete_task. Used reliable click-then-type pattern (adds ~3 extra turns vs. theoretical minimum of 8). Zero failures. Strong screen comprehension — identified all needed fields and indices in a single reasoning pass.

Both successes share: single app, clear form-based UI, no date manipulation, no cross-app data transfer.

### 5. Comparison with Round 6 (extra20)

| Metric | Round 6 extra20 | Round 7 quick5 | Delta |
|---|---|---|---|
| Pass Rate | 5.0% (1/20) | 40.0% (2/5) | +35pp |
| Infra Failure Rate | 20% | 0% | -20pp |
| ASK_USER_BLOCKED | 30% | 0% | -30pp (excluded from task set) |
| Goal Claim Precision | 14% (1/7) | 100% (2/2) | +86pp |
| Tool Failure Rate | 1.3% | 2.9% | +1.6pp |

Key improvements since round 6:
- **P0-1 open_app resolver** fixed: No more 13-turn app resolution loops for Simple Calendar Pro
- **P0-2 ask_user eval block**: `ask_user` excluded from tool set (`excluded_tools: ask_user,write_todos`)
- **P1-5 write_todos reduction**: Tool excluded from eval, saving 2-5 turns per task
- **Completion monitor fix** (this round): Eliminated false-positive completions from USER_STOPPED sessions

Persistent issues from round 6:
- A11y text truncation for file content (ExpenseAddMultipleFromMarkor)
- Calendar date navigation inefficiency (SimpleCalendarAddOneEvent)
- No loop/stuck detection

## Priority Recommendations

### P0 — Fix Cycle Detection in Existing LoopDetectionPolicy (would fix 2/3 failures)

**Existing infrastructure**: Loop detection already exists and is fully wired:
- `LoopDetectionPolicy.detect()` → `LoopWarning?`
- `AgentTurnRunner.buildWarnings()` collects warnings
- `PromptBuilder.buildObservationText()` prepends warnings to the current turn's screen observation (before the a11y tree JSON)
- `NavigationState` tracks the last 10 `ScreenSignature`s (Jaccard similarity over element tokens)

**Why it didn't fire for AudioRecorder**: The existing detector only checks **consecutive** screen similarity — whether the last 3 screens are all similar to each other (Jaccard ≥ 0.90). AudioRecorder's loop is a **3-state cycle**:

```
Turn N:   Paused recording  (14 elements)  ← all different from each other
Turn N+1: Warning dialog     (9 elements)  ← Jaccard ≈ 0.0 to adjacent turns
Turn N+2: Records list      (27 elements)  ← so "unchanged for 3 turns" never fires
Turn N+3: Paused recording  (14 elements)  ← cycle repeats
```

Each consecutive pair has near-zero similarity, so `isStable(0.90)` returns false. The "same action repeated 3 times" check also misses because actions alternate: `click 11` → `click 7` → `click 5` → `click 11`.

**Fix**: Add **cycle detection** to `LoopDetectionPolicy` — check whether the current `ScreenSignature` matches any signature seen earlier in `recentSignatures` (not just consecutive ones). If the same screen appears ≥ 3 times within the last 10 turns, it's a multi-state cycle.

```kotlin
// Pseudocode — add to LoopDetectionPolicy.detect()
val current = latestSignatures.last()
val matchCount = state.recentSignatures.count { it.similarityTo(current) >= threshold }
if (matchCount >= 3) {
    return LoopWarning(
        message = "Cycle detected: this screen has appeared $matchCount times " +
                  "in the last ${state.recentSignatures.size} turns. " +
                  "Your actions are not making progress. Try a completely " +
                  "different approach or abandon this sub-goal.",
        severity = LoopWarningSeverity.CRITICAL
    )
}
```

- Impact: AudioRecorderRecordAudio (saves 20+ turns), ExpenseAddMultipleFromMarkor (saves 20+ turns)
- Files: `agent/cognition/policy/LoopDetectionPolicy.kt` (only file that needs change)
- Design constraint: KISS — single new check in existing `detect()`, reuses existing `ScreenSignature.similarityTo()`.

**Relationship with round6 P0 click false success design**: The P0 click false success design (`p0_click_false_success_design.md`) is **partially implemented** — infrastructure is in place (`UiChangeDetector`, `ActionOutcome.verified` field, `[unverified]` output formatting) but the **core fallback loop** in `PointActionExecutorCore.kt` was never wired. After P1 (multi-window perception) and P2 (click node overlap + semantic mismatch guard) were implemented, the remaining P0 gap is narrower but **still valid**: P2 catches wrong-node targeting, but P0 addresses a different scenario — the RIGHT node is found and `performAction(ACTION_CLICK)` returns true, yet the widget doesn't respond (only reacts to real touch events). This case can't be caught by P1/P2.

These two features operate at different levels:

| | P0 click false success | P0 cycle detection (this round) |
|---|---|---|
| **Level** | Execution (per-action) | Cognition (cross-turn) |
| **Trigger** | Single click has no observable UI change | Same screen reappears 3+ times in recent history |
| **Response** | Fallback from node_action to gesture_tap | Inject warning message to LLM |
| **Infra** | `UiChangeDetector.compare(pre, post)` | `ScreenSignature.similarityTo()` (already exists) |

**Recommendation**: The cycle detection fix is a single added check in `LoopDetectionPolicy.detect()` — highest priority, directly addresses 2/3 failures. The P0 click false success core loop can be completed as a follow-up.

### P1 — Perception Improvements (would fix 1/3 failures, improve robustness)

2. **Remove a11y text field length limit and element count limit**: The current truncation at ~60 chars loses critical content for file-reading tasks. Remove the text length cap entirely. Also raise the 80-element cap to a much larger number (e.g., 500). If specific cases later show a need for limits, revisit with a smarter mechanism.
   - Impact: ExpenseAddMultipleFromMarkor
   - Files: `perception/` (a11y tree sanitization)

3. **Relax shell command validation**: Remove most shell operator restrictions (`||`, `&&`, `>`, `|`, etc.). The current validation is too aggressive — it rejected `find ... 2>/dev/null`, blocking a viable file-read fallback. User approval mechanisms can be added later if needed; for now, release full shell capability.
   - Files: `tool/impl/` (shell tool validation)

### P2 — Navigation Efficiency (would fix 1/3 failures)

Add a dedicated **"Tips"** section to the system prompt — a standalone, structured block of app-specific navigation tips. Keep this section independent from the main prompt so it can be managed (added to, edited, or eventually loaded dynamically) without touching core prompt logic.

4. **Calendar navigation shortcut**: "For calendar apps, prefer creating events directly via the 'New Event' button and using date fields in the form, rather than navigating the calendar view to the target date first."
   - Impact: SimpleCalendarAddOneEvent (saves ~12 turns, would flip to success)
   - Files: `agent/cognition/prompt/`

5. **NumberPicker direct input**: "When faced with NumberPicker widgets, type the value directly into the editable text field rather than scrolling incrementally."
   - Impact: Any task using date/time pickers

### ~~P3 — Multi-App Task Planning~~ (DROPPED)

~~Turn budget allocation for multi-phase tasks.~~

Dropped: max turns is an artificial eval constraint. The agent should not be engineered around a fixed turn budget — it can be increased or removed in future configs.

## Aggregate Statistics

| Metric | Value |
|---|---|
| Total turns executed | 105 (across 5 task attempts) |
| Total tool calls | 105 |
| Total tool failures | 3 |
| Tool success rate | 97.1% |
| Tasks hitting MaxTurnsReached | 3 |
| Tasks declaring GoalAchieved (true) | 2 |
| Average turns per task | 21.0 |
| Average turns for successes | 7.5 |
| Average turns for failures | 30.0 |
