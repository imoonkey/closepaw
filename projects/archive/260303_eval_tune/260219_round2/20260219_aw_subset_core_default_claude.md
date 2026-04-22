# Cog-Tune Report: aw_subset_core / default.yaml (Claude Analysis)

**Run**: `eval/results/20260218_203057`
**Model**: qwen3.5 (qwen/qwen3.5-plus-02-15) via OpenAI-compatible backend
**Config**: default.yaml, perception=accessibility_only, agent_mode=basic, max_turns=20
**Tasks**: 14 from `eval/config/aw_subset_core.txt`

## Overall Metrics

| Metric | Value |
|--------|-------|
| Success rate | 57.1% (8/14) |
| Timeout rate | 14.3% (2/14) |
| Tool failure rate | 15.7% |
| Goal claim precision | 85.7% (1 false positive) |
| Duration P50 | 158s |
| Duration P90 | 924s |

## Per-Task Results

| Task | Status | Turns | Tool Fails | Completion Reason | Root Causes |
|------|--------|-------|------------|-------------------|-------------|
| CameraTakePhoto | **PASS** | 4 | 0 | GoalAchieved | - |
| ClockTimerEntry | **PASS** | 7 | 0 | GoalAchieved | - |
| ContactsAddContact | **PASS** | 7 | 0 | GoalAchieved | - |
| MarkorCreateNote | **PASS** | 9 | 0 | GoalAchieved | - |
| SystemBluetoothTurnOnVerify | **PASS** | 12 | 0 | GoalAchieved | - |
| SystemWifiTurnOnVerify | **PASS** | 5 | 0 | GoalAchieved | - |
| SystemBrightnessMinVerify | **pass*** | 0 | 0 | timeout | V3 (flaky) |
| SystemWifiTurnOffVerify | **pass*** | 0 | 0 | timeout | V3 (flaky) |
| BrowserMultiply | **FAIL** | 13 | 1 | (no completion) | V1, R3, V4 |
| ExpenseAddSingle | **FAIL** | 20 | 2 | MaxTurnsReached | R1, R5, P3 |
| FilesMoveFile | **FAIL** | 20 | 8 | MaxTurnsReached | E3, E4, R4, P2 |
| RecipeAddSingleRecipe | **FAIL** | 13 | 0 | GoalAchieved (FP) | R2 |
| SimpleSmsSend | **FAIL** | 20 | 9 | MaxTurnsReached | E1, R5 |
| SystemBrightnessMaxVerify | **FAIL** | 20 | 3 | MaxTurnsReached | E2, P1 |

\* anomalous: bridge timeout but scored success (pre-existing state)

---

## Root Cause Classification

### Execution Failures

#### E1: Occluded/Overlapping Element Click Failures
**Affected**: SimpleSmsSend (9 tool failures), ExpenseAddSingle
**Evidence**: SimpleSmsSend T7/T8/T10/T15/T16/T17/T18 trace events

The a11y tree reports overlapping clickable elements (parent LinearLayout and child ImageView at the same coordinates). The click engine rejects clicks on elements it determines are "occluded." In SimpleSmsSend, the "confirm phone number" button is reported as:

```
[3] ImageView text='' click=True bounds=[954,296,1080,422] center=[1017,359]
[4] LinearLayout text='' click=True bounds=[996,296,1080,422] center=[1038,359]
```

Element 3 is blocked because element 4 overlaps it. Clicking element 4 also fails (click dispatched but no UI change). The agent exhausts 9+ turns attempting variations with no way forward.

**Impact**: Primary blocker for SimpleSmsSend. Agent loops between element 3 ("occluded") and element 4 ("no UI change") indefinitely.

**Proposed fix area**: Click engine occlusion detection (`tool/impl/` click logic). When an element is rejected as occluded, the engine should try clicking the occluding element directly, or fall back to coordinate-based tap at the original center.

---

#### E2: SeekBar/Slider Interaction Failure
**Affected**: SystemBrightnessMaxVerify (20 turns, 3 failures)
**Evidence**: SystemBrightnessMax T11-T20 trace events

The brightness SeekBar renders as a single a11y element with no value feedback:

```
[0] SeekBar text='Display brightness' click=False scrollable=False bounds=[42,149,1038,275]
```

The agent correctly identifies the element and attempts swipes across it (left-to-right, partial drags, slow drags). All swipesreport "success" but also "Screen content unchanged after swipe - may have reached scroll boundary." The agent has no way to verify the current value or confirm the action worked.

The agent spent 10 turns (T11-T20) trying different swipe strategies on this SeekBar with no feedback loop.

**Impact**: Slider-based tasks are unsolvable without a SeekBar interaction strategy.

**Proposed fix area**:
1. **Tool**: Add a specialized `set_seekbar_value` action or normalize swipe behavior for SeekBar elements (use `ACTION_SET_PROGRESS` accessibility action if available).
2. **Perception**: If the SeekBar exposes `rangeInfo` in the raw a11y node, surface the current value in the sanitized tree.
3. **Context/Prompt**: Teach agent to use Settings search + "Brightness level" text to verify post-action state.

---

#### E3: Long-Press on File Cards in Documents UI
**Affected**: FilesMoveFile (2 failed long-presses at T8 and T18)
**Evidence**: FilesMoveFile T8 a11y tree

```
[11] CardView text='holiday_photos.jpg | 21 B | Oct 15, 2023' click=True long_click=False
```

The Documents UI file card reports `long_clickable=false` in the a11y tree, even though long-pressing IS the standard gesture for selecting files. The click engine's `ACTION_LONG_CLICK` dispatch finds "No long-clickable node" and the fallback `gesture_long_press` also fails ("dispatched, no UI change").

**Impact**: Complete blocker for file move/copy operations that require selection.

**Proposed fix area**: When `gesture_long_press` is dispatched but shows no UI change, try a time-based long-hold gesture (press-and-hold for 800ms+) as a fallback. Documents UI may respond to touch events but not a11y long-click actions.

---

#### E4: Buttons Near Screen Edge Click Failures
**Affected**: FilesMoveFile (T14, T16 - "Just once" and "Always" buttons)
**Evidence**: FilesMoveFile T11 a11y tree

```
[8] Button text='Just once' click=True bounds=[631,2174,853,2209] center=[742,2191]
[9] Button text='Always' click=True bounds=[853,2174,1033,2209] center=[943,2191]
```

These buttons are at the very bottom edge of the screen (y=2174-2209, screen height=2209). Clicks at these coordinates consistently fail with "ACTION_CLICK returned false" and gesture taps show no change. The buttons are likely partially cut off or touch events at the extreme screen edge are unreliable.

**Impact**: Agent gets permanently stuck in "Open with" dialog.

**Proposed fix area**: For elements at screen extremes (within ~50px of edge), try scrolling the containing view to bring the element more centrally, or use `system_button: back` to dismiss dialogs.

---

### Reasoning Failures

#### R1: Horizontal RecyclerView Category Scrolling
**Affected**: ExpenseAddSingle (turns 6-20)
**Evidence**: ExpenseAddSingle T6 a11y tree, T6-T7 swipe results

The Pro Expense category picker is a horizontal RecyclerView showing 5 categories (Food, Income, Housing, Social, Entertainment). "Health Care" exists but requires horizontal scrolling. The agent tried `swipe left` and `swipe right` on element 8 but both got "scroll boundary reached" warnings.

```
[8] RecyclerView text='' click=False bounds=[21,762,1059,882]
[9-18] CardView items: Food, Income, Housing, Social, Entertainment
```

After failing to scroll, the agent looped: re-entering the expense name and amount 3 times, trying random clicks, but never finding "Health Care."

**Root problem**: The swipe gesture targets the RecyclerView center but the horizontal scroll doesn't engage. The RecyclerView may require targeted drag gestures (press-drag-release) rather than general swipes.

**Proposed fix area**:
1. **Prompt/Policy**: When a category/option isn't visible in a horizontal list, explicitly instruct the agent to try clicking on items near the edge to trigger scroll, or use coordinate-based drag gestures.
2. **Tool**: For RecyclerView elements, detect horizontal scroll capability and implement proper horizontal drag gestures.

---

#### R2: Form Field Stuffing Instead of Scrolling (False Positive)
**Affected**: RecipeAddSingleRecipe (GoalAchieved but scripted_score=0)
**Evidence**: RecipeAddSingleRecipe T7-T11 trace, T7 a11y tree

The Broccoli recipe form has visible fields: Title, Categories, Description, Source. Additional fields (Servings, Prep Time, Ingredients, Directions) are below the scroll fold. The agent's two scroll attempts (T7-T8) both failed with "scroll boundary reached."

Unable to find the additional fields, the agent dumped ALL recipe data into the Description field at T11:
```
"input_text": "An ideal recipe for experimenting...\n\nServings: 3-4 servings\nPreparation Time: 2 hrs\n\nIngredients: as desired\n\nDirections: Toss chopped romaine..."
```

Then it clicked SAVE and declared GoalAchieved. The scripted verifier checked individual fields and found them empty, scoring 0.

**Root problem**: Two issues:
1. ScrollView scroll didn't work (the ScrollView at index 3 ends at y=1517, and Source field touches that boundary, suggesting content exists below)
2. Agent chose to stuff data into Description rather than attempting alternative scroll strategies or acknowledging failure

**Proposed fix area**:
1. **Policy**: Add "never declare GoalAchieved if required fields were not filled individually" guidance.
2. **Tool**: When swipe fails with "boundary reached" on a ScrollView, try scrolling with different start/end coordinates or using `ACTION_SCROLL_FORWARD` accessibility action.
3. **Prompt**: Teach agent that "scroll boundary reached" warnings mean the gesture failed, not that there's no more content.

---

#### R3: ask_user in Non-Interactive Eval Context
**Affected**: BrowserMultiply (T9)
**Evidence**: BrowserMultiply T9 tool call/result

When stuck on Google sign-in after Chrome's first-run flow, the agent called `ask_user`:
```
"message": "Chrome is showing a Google sign-in page instead of the task.html file..."
```

The tool returned: "User did not respond within the timeout." The agent then spent 4 more turns pressing back/home without recovering.

**Proposed fix area**: **Policy** - disable or deprioritize `ask_user` tool, or add prompt guidance: "Do not use ask_user. Always attempt to solve the task autonomously."

---

#### R4: Invalid Action Type in Tool Call
**Affected**: FilesMoveFile (T11)
**Evidence**: FilesMoveFile T11 tool_call_args

The agent sent `action: "system_button"` inside the `mobile_action` tool:
```json
{"action": "system_button", "button": "back"}
```

This is an invalid action type. The valid types are: click, long_press, type, swipe. The error message explicitly says so. The agent should have used the separate `system_button` tool.

**Impact**: 1 wasted turn due to tool schema confusion.

**Proposed fix area**: **Prompt** - clarify tool schema boundaries more explicitly, or add action validation in the prompt context.

---

#### R5: Looping Without Progress Detection
**Affected**: ExpenseAddSingle (3 full loops of data entry), SimpleSmsSend (2 restart cycles)
**Evidence**: ExpenseAddSingle T3-T5/T10-T12/T18-T19, SimpleSmsSend T3-T10/T12-T20

Both tasks show the agent repeating the same sequence of actions multiple times:

- **ExpenseAddSingle**: Typed name + amount 3 times, each time failing to find Health Care category and restarting
- **SimpleSmsSend**: Created new conversation and typed phone number 2 times, each time failing to confirm the number

No strategy change between loops. The agent makes the same decisions each time.

**Proposed fix area**:
1. **Policy**: Add loop detection in context/policy - if the agent is on the same screen and has attempted the same action 3+ times, force a strategy change.
2. **Prompt**: "If an action fails twice, do NOT repeat it. Try a fundamentally different approach."

---

### Perception Failures

#### P1: SeekBar Value Not Exposed in A11y Tree
**Affected**: SystemBrightnessMaxVerify
**Evidence**: T11 a11y tree (above in E2)

The SeekBar element has `text='Display brightness'` but no numeric value. The raw Android accessibility node may have `rangeInfo` (min/max/current) but this is not surfaced in the sanitized tree.

**Proposed fix area**: `perception/` - extract `RangeInfo` from `AccessibilityNodeInfo` and include it in the sanitized tree output (e.g., `"range": {"min": 0, "max": 255, "current": 128}`).

---

#### P2: Missing long_clickable on Documents UI File Cards
**Affected**: FilesMoveFile
**Evidence**: T8 a11y tree (above in E3)

The Documents UI CardView reports `long_clickable=false` even though it responds to long-press gestures in the actual UI. This is a framework-level a11y tree inaccuracy.

**Proposed fix area**: Not directly fixable in agent code. Workaround: use time-based gesture long-press as fallback (E3 fix).

---

#### P3: Horizontal RecyclerView Partial Content Visibility
**Affected**: ExpenseAddSingle
**Evidence**: T6 a11y tree

Only 5 of N category items are visible. The a11y tree accurately reflects what's on screen, but doesn't indicate "more items available via scroll." The agent has no signal that Health Care exists.

**Proposed fix area**: **Prompt** - add guidance: "Lists and grids may contain more items than visible. Always try scrolling to find items not in view."

---

### Evaluation / Infrastructure Issues

#### V1: Chrome First-Run Setup Derails Browser Tasks
**Affected**: BrowserMultiply
**Evidence**: BrowserMultiply T5-T9 (Terms, Sync, Google sign-in flow)

Chrome is not pre-configured on the eval emulator. Opening a local HTML file through Chrome triggers a 3-5 turn first-run flow (Terms of Service -> Sync prompt -> Google sign-in). This consumes turns and can lead to dead ends (Google sign-in page with no way back to the file).

**Proposed fix**: Pre-configure Chrome on the eval emulator (accept ToS, skip sign-in) as part of emulator setup. Or use `am start -a android.intent.action.VIEW -d file:///...` intent to bypass the file manager.

---

#### V2: ANR (App Not Responding) Dialogs
**Affected**: SystemBrightnessMinVerify (T2, T4)
**Evidence**: T2 and T4 show `pkg=android elements=3` with agent clicking "Wait" button

The Settings app showed ANR dialogs during the BrightnessMin task. The agent spent 2 turns clicking "Wait" to dismiss them. This is likely an emulator performance issue from the previous task.

**Proposed fix**: Add longer settle delays between tasks, or detect and auto-dismiss ANR dialogs in the platform layer.

---

#### V3: Anomalous Success on Timeout (False Positive Success)
**Affected**: SystemBrightnessMinVerify, SystemWifiTurnOffVerify
**Evidence**: per_task.jsonl shows bridge_status=timeout, turns_executed=0, scripted_success=true

Both tasks timed out at ~920s with 0 reported turns (though BrightnessMin trace shows 6 actual turns). The scripted verifier scored them as success, meaning the goal state was already satisfied before/during the run.

- **SystemBrightnessMinVerify**: Brightness was likely already at min from the previous BrightnessMax test (which ran the slider but may have set it to min by accident)
- **SystemWifiTurnOffVerify**: Wi-Fi may have been turned off by a side effect of the preceding SystemWifiTurnOn test

These are false positive successes - they don't reflect agent capability.

**Proposed fix**: Reset device state between tasks (brightness to 50%, Wi-Fi to default). Or mark timeout results as failures regardless of scripted score.

---

#### V4: Per-Task Turn Count Reporting Bug
**Affected**: BrowserMultiply
**Evidence**: per_task.jsonl reports `turns_executed: 0` but trace.jsonl contains 13 turns with tool calls

The bridge reports 0 turns executed for BrowserMultiply, which is incorrect. The trace file clearly shows 13 turns. Also, SystemBrightnessMinVerify reports 0 turns but has ~6 turns in the trace.

**Proposed fix**: Investigate the turn counting logic in `eval/aw_bridge/native_agent_bridge.py` - the bridge may not be reading the trace correctly, or the agent status query returns stale data after timeout.

---

## Priority Summary

### P0 - Blocking Multiple Tasks
1. **E1 (Occluded elements)**: Fix click engine occlusion handling - directly impacts SimpleSmsSend and likely other apps with overlapping a11y nodes
2. **R5 (Loop detection)**: Add loop detection/break policy - impacts ExpenseAddSingle, SimpleSmsSend, and resilience in general

### P1 - Blocking Individual Tasks
3. **E2+P1 (SeekBar)**: Surface SeekBar rangeInfo in a11y tree + add set_progress action - blocks all slider tasks
4. **E3+P2 (Long-press fallback)**: Add time-based long-press gesture as fallback - blocks file management tasks
5. **R2 (False positive prevention)**: Add policy against declaring success when required fields are empty - RecipeAddSingleRecipe

### P2 - Improvements
6. **V1 (Chrome setup)**: Pre-configure Chrome in eval emulator
7. **R1+P3 (Horizontal scroll)**: Better horizontal RecyclerView scrolling + prompt guidance
8. **R3 (ask_user)**: Disable/deprioritize ask_user in autonomous agent mode
9. **E4 (Edge buttons)**: Handle clicks near screen boundary
10. **V3+V4 (Eval harness)**: Fix turn counting, reset state between tasks

### P3 - Nice to Have
11. **R4 (Action type confusion)**: Better tool schema clarity in prompt
12. **V2 (ANR handling)**: Auto-dismiss ANR dialogs

---

## Affected Files (Proposed Changes)

| Area | Files | Issues |
|------|-------|--------|
| Click engine | `tool/impl/` (ClickActionHandler or similar) | E1, E4 |
| Long-press | `tool/impl/` (LongPressHandler or similar) | E3 |
| SeekBar perception | `perception/` (a11y tree sanitizer) | P1 |
| SeekBar tool | `tool/impl/` or `tool/ToolSpec.kt` | E2 |
| Loop detection | `agent/cognition/policy/` | R5 |
| Prompt assembly | `agent/cognition/prompt/PromptAssembler.kt` | R1, R3, R4, P3 |
| Goal completion policy | `agent/cognition/policy/` | R2 |
| Eval harness | `eval/aw_bridge/native_agent_bridge.py` | V3, V4 |
| Eval setup | eval emulator config | V1, V2 |
