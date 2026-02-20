# Eval Run 20260220_162433 — Cross-Task Click Analysis

## Config
- Model: qwen3.5 (qwen/qwen3.5-plus-02-15)
- Perception: accessibility_only
- Max turns: 30
- Executor cascade: gesture-first (post FLAG_NOT_TOUCHABLE fix)

## Results Overview
- **9/14 success (64.3%)**
- Successes: CameraTakePhoto, ContactsAddContact, ExpenseAddSingle, MarkorCreateNote, RecipeAddSingleRecipe, SimpleSmsSend, SystemBluetoothTurnOn, SystemWifiTurnOff, SystemWifiTurnOn
- Failures: BrowserMultiply, ClockTimerEntry, FilesMoveFile, SystemBrightnessMax, SystemBrightnessMin

## Root Cause Classification

### RC1: DocumentsUI Click Non-Response [Execution] — BrowserMultiply, FilesMoveFile

**Symptom**: gesture_tap dispatches to correct coordinates on clickable elements, tool reports success, but DocumentsUI doesn't respond. Pre/post a11y trees are IDENTICAL.

**Evidence**:
- BrowserMultiply turn 2: click element_index 13 (CardView `task.html | 2.23 kB | 16:25`, clickable=true, bounds [64,678,529,1279], center (296,978)) → `Success: Tapped (296,978) via gesture_tap` → post-tree is identical to pre-tree, Downloads still shown
- BrowserMultiply turns 3,4,10,13,18,22,26: repeated attempts via click, long_press on same element — UI never changes
- FilesMoveFile turns 5,6,18: click on Podcasts folder (element_index 24, center (540,1665)) → UI never navigates into folder
- FilesMoveFile turns 11,13,15,22,30: long_press on files/folders → no selection mode activated

**Contrast with working clicks**: Camera shutter, Clock numpad, Contacts FAB, Expense FAB, Settings menus, toggle switches ALL respond correctly to gesture_tap. All these are in apps OTHER than DocumentsUI.

**Hypothesis**: DocumentsUI uses custom touch handling (RecyclerView with DirectoryFragment) that may not respond to accessibility-service-injected gesture events the same way as other apps. The gesture physically lands at the right coordinates and the accessibility framework reports success, but the gesture doesn't translate to the app's click listener. This is a known pattern with RecyclerView item touches vs. dispatchGesture.

**Impact**: 2 tasks (14.3% of total) — both MaxTurnsReached

### RC2: Eval Script False Failure [Evaluation Gap] — ClockTimerEntry

**Symptom**: Agent correctly entered all 4 digits (1,6,3,5), timer display shows `00h 16m 35s` exactly matching goal, agent called complete_task with correct answer, but eval reports scripted_success=false.

**Evidence**:
- Turn 3: click element_index 3 (Button "1") → display `00h 00m 01s` ✓
- Turn 4: click element_index 8 (Button "6") → display `00h 00m 16s` ✓
- Turn 5: click element_index 5 (Button "3") → display `00h 01m 63s` ✓
- Turn 6: click element_index 7 (Button "5") → display `00h 16m 35s` ✓
- Turn 7: complete_task → "Timer has been set to 0 hours, 16 minutes, and 35 seconds."
- Backspace label updated after each digit (confirms digit reached the app)
- Goal: "0 hours, 16 minutes, and 35 seconds. Do not start the timer." — MATCHES

**Hypothesis**: Eval verification script may check timer value via a different mechanism (adb shell settings, app state dump) that doesn't reflect entered-but-unstarted timer state. Or the verification expects the timer to be in a specific UI state (e.g., "ready to start" screen vs. numpad entry screen).

**Impact**: 1 task (7.1% of total) — false failure inflating error rate

### RC3: Swipe Precision on SeekBar [Execution] — SystemBrightnessMax

**Symptom**: Swipe gestures on brightness slider reach 98% but not 100%.

**Evidence**:
- Multiple swipe attempts from left to right edge: (100,420)→(980,420), (60,420)→(1020,420), (950,420)→(1020,420)
- Agent acknowledged "98%" in completion answer
- SeekBar y-coordinate at 420 appears correct (from quick settings panel)
- Even a final micro-swipe (950→1020) didn't reach 100%

**Hypothesis**: SeekBar maximum position doesn't correspond to the rightmost pixel of its bounds. Android SeekBar has internal padding, and the effective thumb range is smaller than the view bounds. The last 2% may require precise gesture endpoint calculation or a dedicated `setProgress(max)` accessibility action.

**Impact**: 1 task (7.1% of total)

### RC4: LLM Tool Call Format Error [Reasoning] — SystemBrightnessMin

**Symptom**: On turn 3, qwen3.5 model output tool calls as plain text instead of structured function calls. No action was taken, session ended prematurely.

**Evidence**:
- Turn 3 LLM response: `has_text: true, tool_calls: 0, is_complete: true`
- The response text contains tool call syntax as plaintext:
  ```
  write_todos({"todos":[...],"agent_thought":"Found Display option..."})
  mobile_action({"action":"click","element_index":20,...})
  ```
- System interpreted this as task completion (is_complete=true) — no tool calls dispatched
- The "Display" option was visible at element_index 20 with text "Dark theme, font size, brightness"
- answer: null (no complete_task call was made)

**Hypothesis**: qwen3.5 model occasionally fails to emit structured tool calls and instead writes them as text. The `is_complete: true` flag combined with `tool_calls: 0` causes the session to terminate via GoalAchieved despite no actual task progress. This is a model-level instruction-following issue.

**Impact**: 1 task (7.1% of total) — session ended in 3 turns with zero brightness adjustment

## Successful Task Click Patterns

All 9 successful tasks used gesture_tap and gesture_swipe correctly:

| Task | Turns | Click Actions | All Clicks Worked? |
|------|-------|---------------|-------------------|
| CameraTakePhoto | 3 | 1 click (shutter) | ✓ |
| ContactsAddContact | 10 | 5 clicks + 3 types | ✓ |
| ExpenseAddSingle | 10 | 3 clicks + 3 types + 2 scrolls | ✓ |
| MarkorCreateNote | 8 | 3 clicks + 2 types | ✓ |
| RecipeAddSingleRecipe | 18 | 6 clicks + 5 types + 2 scrolls | ✓ |
| SimpleSmsSend | 9 | 4 clicks + 2 types (1 open_app error recovered) | ✓ |
| SystemBluetoothTurnOn | 6 | 4 clicks (Settings navigation + toggle) | ✓ |
| SystemWifiTurnOff | 4 | 2 clicks (quick settings WiFi toggle) | ✓ |
| SystemWifiTurnOn | 5 | 3 clicks (Settings→Network→WiFi toggle) | ✓ |

SimpleSmsSend notable: Turn 1 open_app "Simple SMS Messenger" failed (app name mismatch), agent recovered with "SMS Messenger" on turn 2. Good adaptive behavior.

## Key Distinction: DocumentsUI vs. Everything Else

The click success/failure split is 100% correlated with the target app:
- **DocumentsUI (Files)**: 0% click success — gestures report success but UI never responds
- **All other apps**: 100% click success — gestures work correctly

This is the single most impactful finding. If DocumentsUI clicks are fixed, 2 additional tasks would progress significantly (removing MaxTurnsReached), and the overall success rate could reach ~85%+.

## Actionable Fixes (Priority Order)

1. **DocumentsUI click dispatch** — Investigate why gesture_tap doesn't activate DocumentsUI RecyclerView item clicks. Consider falling back to node ACTION_CLICK for DocumentsUI, or using accessibility node `performAction(ACTION_CLICK)` directly. Test with `adb shell input tap` to determine if the issue is gesture injection or app-specific.

2. **Eval verification for ClockTimerEntry** — Inspect the eval script's timer verification logic. The task was completed correctly; the eval metric is wrong.

3. **SeekBar precision** — For slider-type controls, consider using the accessibility `ACTION_SET_PROGRESS` or `ACTION_SCROLL_FORWARD/BACKWARD` actions instead of rawswipe gestures. These are semantically correct and don't depend on pixel precision.

4. **LLM text-as-tool-call guard** — Add validation: if `is_complete=true` and `tool_calls=0` and no `complete_task` was called, flag as an anomaly rather than GoalAchieved. For qwen3.5 specifically, consider parsing assistant text for function-call–like patterns and re-prompting if found.
