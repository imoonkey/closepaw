# SimpleCalendarEventOnDateAtTime -- Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 11 | **Reason**: Error (repeated action loop at turn 11) | **Duration**: 101s
**Goal**: What is on my schedule for October 27 2023 at 8:00pm in Simple Calendar Pro? Answer with the titles only. If there are multiples titles, format your answer in a comma separated list.

## Root Cause
**Category**: Perception + Execution
**Summary**: Two compounding failures. First, the calendar monthly grid cells expose NO text labels in the a11y tree (all empty View elements), causing the agent to click the wrong date cell (landed on Oct 20 instead of Oct 27). Second, when the agent opened the date picker dialog and attempted to change the day from 22 to 27 using the `type` action on a NumberPicker EditText, the value did not change -- NumberPicker widgets require scroll gestures, not keyboard input. The agent then pressed OK on the unchanged date (Oct 22), returned to the wrong date, and entered a loop trying to re-open the date picker via the "October" header, which was blocked by anti-loop POLICY_REJECTION on turns 8, 9, and 10.

## Turn-by-Turn Analysis

### Turn 1
- **Action**: `open_app { app_name: "Simple Calendar Pro" }`
- **Result**: Success -- app launched, monthly calendar view visible
- **Assessment**: Good

### Turn 2
- **Action**: `click { element_index: 39 }` -- agent_thought: "Click on October 27, 2023 (Friday in the 4th week of October)"
- **Result**: Navigated to daily view showing **October 20 (Fri)** with event "Workshop on Budget Planning | 08:22 - 08:37"
- **Assessment**: **WRONG DATE**. The monthly calendar grid cells are all empty View elements with no text/desc attributes (indexes 13-54 are all `text: ""`, `class: View`). The agent guessed element 39 was Oct 27 based on positional reasoning, but it was actually Oct 20. This is the fundamental perception problem: Simple Calendar Pro's monthly grid does not expose date numbers through accessibility.

### Turn 3
- **Action**: `click { element_index: 15 }` -- right arrow to navigate forward from Oct 20
- **Result**: Navigated to **October 21 (Sat)** -- event "Call with Alice | 00:40 - 01:40"
- **Assessment**: OK -- in daily view, forward arrow advances by 1 day. Agent needs 7 more forward clicks to reach Oct 27 from Oct 20. This is an inefficient recovery strategy.

### Turn 4
- **Action**: `click { element_index: 15 }` -- right arrow again
- **Result**: Navigated to **October 22 (Sun)** -- visible via date header
- **Assessment**: OK -- still advancing day by day, 5 more days to go

### Turn 5
- **Action**: `click { element_index: 12 }` -- clicked "October" month header
- **Result**: **Opened date picker dialog** with NumberPicker spinners showing Oct / 22 / 2023, with Cancel and OK buttons
- **Assessment**: Good strategic pivot -- the agent recognized that clicking the month header could open a date picker for direct navigation to Oct 27. This is more efficient than clicking the forward arrow 5 more times.

### Turn 6 (Critical Failure)
- **Action**: `type { input_text: "27", element_index: 11, clear: true }` -- agent_thought: "Type 27 into the day field to change from October 22 to October 27"
- **Result**: Action reported success, but the day value **did not change**. The EditText (element 11, showing "22") is part of a NumberPicker widget.
- **Assessment**: **EXECUTION FAILURE**. Android NumberPicker EditText fields do not respond to standard `setText()` via accessibility. They require scroll gestures (swipe up/down on the NumberPicker) to change values. The `type` action completed without error but had zero effect. The agent had no way to detect this from the tool result.

### Turn 7
- **Action**: `click { element_index: 17 }` -- click "OK" on date picker
- **Result**: Date picker dismissed. Calendar navigated to **October 22 (Sun)** -- events visible: "Appointment for Annual Report | 14:34 - 15:34" and "Workshop on Campaign | 19:13 - 19:58"
- **Assessment**: The agent believed it had navigated to Oct 27, but the day field didn't change. OK confirmed Oct 22 instead. The agent didn't verify the current date header before proceeding.

### Turns 8, 9, 10 (Loop)
- **Action**: All three turns attempted `click { element_index: 12 }` -- clicking "October" header to reopen date picker
- **Result**: All three rejected by POLICY_REJECTION (anti-loop detection)
- **Assessment**: The agent correctly identified it needed to try again with the date picker, but the anti-loop system blocked the identical action. The agent failed to try alternative approaches (forward arrow, swipe on calendar, or using a different action to reopen the picker).

### Turn 11
- **Action**: Forced `complete_task { status: "failure" }` -- "detected repeated action loop at turn 11"
- **Result**: Task terminated with score 0.0
- **Assessment**: Anti-loop escalation correctly stopped the stuck agent

## What Failed

1. **Unlabeled calendar grid cells**: The monthly view's day cells expose zero text content in accessibility, making date selection a blind guess. The agent misidentified element 39 as Oct 27 when it was actually Oct 20.

2. **NumberPicker type incompatibility**: The `type` action does not work on Android NumberPicker EditText fields. The agent typed "27" but the value remained "22". The tool reported success (the click/text dispatch didn't throw an error), creating a false-positive that misled the agent.

3. **No verification after date picker**: The agent pressed OK without confirming the date had actually changed. After returning to the daily view, it didn't check the date header ("October 22 (Sun)") before proceeding.

4. **Loop exit failure**: When POLICY_REJECTION blocked the repeated "October" header click 3 times in a row, the agent couldn't adapt. It never tried alternative strategies:
   - Click the forward arrow (element 15) to advance day-by-day
   - Scroll/swipe the day NumberPicker instead of typing
   - Use a swipe gesture on the calendar view

## Comparison to SimpleCalendarEventsOnDate (group23, score 1.0)

The related task SimpleCalendarEventsOnDate (Oct 24) succeeded in 17 turns. Key differences:
- That task used **14 turns of forward/click navigation** to reach the target date (brute force but reliable)
- This task tried to be efficient with the date picker (good strategy) but was defeated by the NumberPicker type incompatibility
- That task never encountered the NumberPicker at all -- pure arrow-click navigation

## Recommendations

### P0: NumberPicker scroll support
- Detect NumberPicker widgets in the a11y tree and advise the agent (or system prompt) to use scroll/swipe actions instead of type. NumberPickers require `AccessibilityNodeInfo.ACTION_SCROLL_FORWARD` / `ACTION_SCROLL_BACKWARD`, not `ACTION_SET_TEXT`.
- Alternative: Implement a specialized `set_number_picker_value` tool that properly manipulates NumberPicker state.

### P1: Calendar grid cell labeling
- The a11y tree sanitizer should attempt to extract day numbers from calendar View cells, possibly by correlating position in the grid with the month header. Without labels, the agent cannot navigate the monthly grid reliably.
- Fallback: Add system prompt guidance that calendar grid cells lack labels and the agent should prefer day-by-day navigation or the date picker.

### P2: Post-action verification for pickers
- After typing into any picker/spinner and pressing OK, inject a verification hint: "Check the date header to confirm the date changed as expected."
- This would have caught the Oct 22 vs Oct 27 mismatch in Turn 7.

### P3: Anti-loop escape strategies
- When POLICY_REJECTION fires, the agent should receive explicit guidance about alternative actions it could try, rather than just blocking the repeated action. The current system blocks but does not redirect.
