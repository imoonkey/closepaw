# Click Redesign Post-Implementation Eval Analysis

## Run Info

- **Run**: `eval/results/20260218_235445`
- **Tasks**: `eval/config/aw_subset_core.txt` (14 tasks)
- **Config**: `default.yaml`, model=qwen3.5
- **Code**: Click redesign Phase 1 implemented (single dispatchGesture, no retry, occlusion=warning)

## Overall Results

| Metric | Round 1 (pre-redesign) | Round 2 (post-redesign) | Delta |
|--------|----------------------|------------------------|-------|
| Success rate | 57.1% (8/14) | **28.6% (4/14)** | **-28.5%** |
| Tool failure rate | — | 3.3% (5/152) | — |

### Per-Task Comparison

| Task | Round 1 | Round 2 | Recording | Change | Root Cause |
|------|---------|---------|-----------|--------|------------|
| BrowserMultiply | FAIL | FAIL | MaxTurnsReached | same | Click no-op (Files/Chrome items) |
| CameraTakePhoto | SUCCESS | FAIL | GoalAchieved, scripted=false | **regression** | Gesture no-op on shutter |
| ClockTimerEntry | SUCCESS | FAIL | GoalAchieved, scripted=false | **regression** | Reasoning (started timer) |
| ContactsAddContact | SUCCESS | FAIL | MaxTurnsReached | **regression** | FAB click no-op |
| ExpenseAddSingle | FAIL | FAIL | MaxTurnsReached | same | FAB click no-op |
| FilesMoveFile | FAIL | INFRA | — | same | ADB timeout |
| MarkorCreateNote | SUCCESS | SUCCESS | GoalAchieved, scripted=true | same | — |
| RecipeAddSingleRecipe | FAIL | FAIL | MaxTurnsReached | same | Click no-ops (SAVE, fields) |
| SimpleSmsSend | FAIL | **SUCCESS** | GoalAchieved, scripted=true | **improved** | Occlusion warning fixed it |
| SystemBluetoothTurnOn | SUCCESS | **FALSE POS** | completion=null, turns=0 | **regression** | Settings items don't respond to gesture |
| SystemBrightnessMax | FAIL | **FALSE POS** | completion=null, turns=0 | **not improved** | Settings items don't respond to gesture |
| SystemBrightnessMin | FAIL | FAIL | MaxTurnsReached | same | SeekBar + Settings items |
| SystemWifiTurnOff | SUCCESS | **FALSE POS** | UserRequested, 1 turn | **regression** | Manually stopped, state already correct |
| SystemWifiTurnOn | SUCCESS | **FALSE POS** | UserRequested, 1 turn | **regression** | Manually stopped, state already correct |

### Corrected Success Rate

Excluding false positives (4 tasks where scripted check passed trivially):

| | Real successes | Real tasks | Rate |
|---|---|---|---|
| Round 2 | 2 (MarkorCreateNote, SimpleSmsSend) | 14 | **14.3%** |
| Round 1 | 8 | 14 | 57.1% |

**Net impact**: -42.8% real success rate. The click redesign caused a severe regression.

---

## False Positive Analysis

### Tasks recorded as "success" but actually failed

**SystemBluetoothTurnOnVerify**: `per_task.jsonl` says `turns_executed: 0, tool_calls: 0` but trace has **10 turns** of tool calls. Agent clicked "Connected devices" (element 9, LinearLayout, center 540,1116) **3 times** — all dispatched "Success" — but a11y tree is **byte-for-byte identical** before and after each click. Settings main page never navigated. `scripted_score: 1.0` because bluetooth was already on.

**SystemBrightnessMaxVerify**: `per_task.jsonl` says `turns_executed: 0, tool_calls: 0` but trace has **15 turns**. Agent clicked "Search settings" (element 3, ViewGroup, center 540,659) multiple times — all "Success" — but search never activated. Type "display" into search field → `Error: No text-input node at (540,659)`. Clicked on scrollable content → "Screen content unchanged". `scripted_score: 1.0` because brightness was already at max.

**SystemWifiTurnOffVerify**: `agent_completion_reason: "UserRequested"`, 1 turn (only opened Settings). `scripted_score: 1.0` because wifi was already off.

**SystemWifiTurnOnVerify**: Same pattern. 1 turn, manually stopped, wifi already on.

---

## Issue Classification

### I1: Silent Click No-Op — CRITICAL REGRESSION

**Affected tasks**: BrowserMultiply, ContactsAddContact, ExpenseAddSingle, CameraTakePhoto, RecipeAddSingleRecipe, SystemBluetoothTurnOn, SystemBrightnessMax

**Pattern**: `dispatchGesture` returns `ActionResult.Success` but the target View does not respond. The tool reports "Success: Tapped (x,y) via gesture_tap" but the screen is identical on the next turn.

**A11y tree proof (SystemBluetoothTurnOn)**:
- Element 9: `"Connected devices | Bluetooth, pairing"`, class=LinearLayout, clickable=true, bounds=[0,1001,1080,1232], center=(540,1116)
- Tapped at (540,1116) on turns 4, 8, 10 — all "Success"
- A11y tree before turn 4 and after turn 10: **identical**. Settings main page never navigated.

**Affected element types**:
| Element type | App | dispatchGesture | ACTION_CLICK (old code) |
|---|---|---|---|
| LinearLayout (Settings item) | Settings | NO EFFECT | Worked (round 1) |
| ViewGroup (Search bar) | Settings | NO EFFECT | Worked (round 1) |
| FAB button at y>2000 | Contacts | NO EFFECT | Worked (round 1) |
| FAB button at y>2100 | Pro Expense | NO EFFECT | Likely worked |
| File item in list | Files | NO EFFECT | Unknown |
| Chrome setup button | Chrome | NO EFFECT | Unknown |
| Camera shutter | Camera | NO EFFECT | Likely worked |
| SAVE button | Broccoli | NO EFFECT | Unknown |

**Root cause**: `dispatchGesture` injects a raw `MotionEvent` at coordinates. Some Views (especially those in RecyclerView-backed lists, or certain Material Design widgets) don't have a `View.OnTouchListener` at the programmatic level — they rely on `View.performClick()` which is what `ACTION_CLICK` through the accessibility framework calls. The gesture dispatch hits the correct coordinates but the View's touch handling chain doesn't trigger the click callback.

**Severity**: **CRITICAL** — this is the dominant cause of failure. At least 7 of 14 tasks are affected.

### I2: Occlusion Handling — IMPROVED

**Affected tasks**: SimpleSmsSend (now fixed)

**Evidence**:
- SimpleSmsSend Turn 14: `Warning: Element center likely occluded; using offset point` — Tapped (955,359) → SUCCESS, element responded
- SystemBrightnessMin Turn 20: `Warning: Element may be occluded; clicking center anyway` — Tapped correctly

The new resolver never hard-fails for occlusion. This directly fixed the SimpleSmsSend Send button that was the biggest round 1 click bug.

**Severity**: RESOLVED (positive change)

### I3: LLM Tool Confusion — `system_button` via `mobile_action`

**Affected tasks**: SimpleSmsSend (1x), ExpenseAddSingle (1x), SystemBrightnessMin (2x)

**Pattern**: The LLM sends `{"action": "system_button", "button": "back"}` to the `mobile_action` tool instead of using the separate `system_button` tool. Result: `Error: Validation failed: Unknown action: 'system_button'. Valid: click, long_press, type, swipe`

This accounts for all 5 recorded tool_failures in the run (5/152 = 3.3%).

**Is this a click implementation bug?** No. Prompt/LLM reasoning issue.

**Severity**: LOW — agent typically recovers by using the correct tool on the next turn.

### I4: Reasoning Failures (Not Click-Related)

**ClockTimerEntry**: Agent entered digits 1,6,3,5 for 16:35. Then clicked START (element 15) despite goal saying "Do not start the timer." Answer explicitly states "timer is now running."

**CameraTakePhoto**: Agent clicked shutter (element 6) — "Success" — declared goal achieved in 4 turns. Scripted check found no photo saved. Likely a combination of I1 (gesture no-op on shutter) and premature goal declaration without verifying camera capture state.

**Severity**: MEDIUM — not click-related for ClockTimerEntry; partially click-related for CameraTakePhoto.

### I5: SeekBar / Slider Interaction Limitation

**Affected task**: SystemBrightnessMin

SeekBar elements don't respond to gesture dispatch or swipe. Pre-existing limitation, not caused by redesign.

**Severity**: LOW for click redesign scope.

---

## Click Dispatch Statistics

All click/long_press dispatches across all tasks:

| Result | Count | Pattern |
|--------|-------|---------|
| `Success: Tapped (x,y) via gesture_tap` | ~80 | Normal dispatch |
| `Success: Long pressed (x,y) for Xms via swipe_to_self` | ~8 | Long press working |
| `Warning: Element center likely occluded; using offset point` | 1 | Occlusion handled |
| `Warning: Element may be occluded; clicking center anyway` | 1 | Occlusion fallback |
| Hard dispatch failure (`ActionResult.Failure`) | **0** | No failures |

**Key finding**: Zero hard dispatch failures. 100% dispatch success rate. The problem is exclusively **silent no-ops** — gesture reaches Android's input pipeline, `dispatchGesture` callback reports success, but the target View's click handler never fires.

---

## Phase 2 Decision Analysis

The Phase 2 trigger condition says: "If there are real dispatch failures (dispatch false, cancellation, timeout) at meaningful frequency."

**Strict reading**: Zero dispatch failures → Phase 2 not triggered.

**Reality**: The trigger condition is wrong. The failure mode is not "dispatch fails" but "dispatch succeeds, view ignores it." This is a fundamentally different problem that Phase 2's node-based fallback WOULD fix (since `node.performAction(ACTION_CLICK)` calls `View.performClick()` directly through the accessibility framework).

### Recommendation

The Phase 1 gesture-only approach is a net negative. It fixed 1 task (SimpleSmsSend via occlusion improvement) but broke ~5 tasks that previously worked via ACTION_CLICK.

**Option A (recommended)**: Reverse the dispatch order:
1. Try `ACTION_CLICK` via a11y node first (resolves element from `element_index` → a11y node → `performAction`)
2. If ACTION_CLICK fails (node not found, action rejected), fall back to `dispatchGesture` at center coordinates
3. Keep the improved TargetResolver (occlusion=warning, never fail) for the gesture fallback path
4. Single attempt, no retry chain

This preserves the design's simplicity (no retry, no jitter, no UiChangeDetector) while restoring compatibility with Views that need ACTION_CLICK. It also keeps the occlusion handling improvement from Phase 1.

**Option B**: Keep gesture-first but add a single ACTION_CLICK fallback when the LLM reports the screen didn't change (detected by LLM, not by UiChangeDetector). This is essentially Phase 2 as designed.

**Option C**: Use element class/type to decide: LinearLayout/ViewGroup items in lists → ACTION_CLICK first. Standard buttons → gesture first. More complex but targeted.

---

## Code Review Summary

The implementation faithfully follows the aligned design. No bugs found in the click/long_press executor code itself:
- TargetResolver: correctly returns points with occlusion warnings
- ClickExecutor: single dispatch, no retry, clean outcome mapping
- LongPressExecutor: swipe-to-self pattern works correctly
- ActionOutcome: clean sealed interface

**One design gap**: Missing `LongPressExecutorTest` (design verification gate requires it).

**The regressions are caused by the design decision to use gesture-only dispatch, not by implementation bugs. The design assumption that dispatchGesture is functionally equivalent to ADB `input tap` is incorrect for many Android Views.**
