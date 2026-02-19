# Round 3 Full Analysis - False Positive Audit & Root Cause Deep-Dive

Run: `eval/results/20260219_124436`
Model: `qwen3.5` (qwen/qwen3.5-plus-02-15)
Config: `default.yaml`, accessibility_only, no screenshot, max_turns=20
Companion doc: `20260219_aw_subset_core_default_step_review_codex.md` (step-level reasonableness)

---

## Executive Summary

| Metric | Reported | Corrected (excl. FP) |
|--------|----------|---------------------|
| **Scripted success rate** | 64.3% (9/14) | **35.7% (5/14)** |
| **False positives identified** | — | **4 of 9** successes |
| **Genuine successes** | — | 5 |
| **Genuine failures** | 5 | 5 (unchanged) |
| **Goal claim precision** | 88.9% (8/9 claims) | — |
| **Tool failure rate** | 1.86% | — |

**After auditing all 14 runs including successes, the corrected success rate drops from 64.3% to 35.7%.**

---

## Part 1: False Positive Audit (All 9 Scripted Successes)

### Summary Table

| # | Task | Turns | FP Risk | Verdict | Key Evidence |
|---|------|-------|---------|---------|-------------|
| 2 | CameraTakePhoto | 3 | **LOW** | Genuine | Shutter clicked; new thumbnail element appeared in a11y tree post-click |
| 3 | ClockTimerEntry | 7 | **LOW** | Genuine | Digits 1-6-3-5 entered; a11y tree shows 00h 16m 35s from initial 00h 00m 00s |
| 4 | ContactsAddContact | 10 | **LOW** | Genuine | "No contacts yet" at start; full create flow; "Added Feb 19, 2026" in detail view |
| 7 | MarkorCreateNote | 9 | **LOW** | Genuine | Correct filename + content typed; Save clicked; text persists in 5 post-save trees |
| 9 | SimpleSmsSend | 9 | **LOW** | Genuine | Message typed, sent; cleared input + sent bubble + timestamp in final tree |
| 10 | SystemBluetoothTurnOn | 11 | **HIGH** | **FALSE POSITIVE** | BT already ON at start; agent toggled OFF then back ON via ping-pong |
| 11 | SystemBrightnessMax | 4 | **HIGH** | **FALSE POSITIVE** | Brightness already at 100%; agent took zero brightness-modifying actions |
| 12 | SystemBrightnessMin | 20 | **HIGH** | **FALSE POSITIVE** | `initialize_task` pre-set brightness=1; MaxTurnsReached; agent never changed anything |
| 14 | SystemWifiTurnOn | 7 | **MEDIUM** | **FALSE POSITIVE** | Wi-Fi already ON; agent toggled OFF then ON via accidental ping-pong |

### Detailed False Positive Analyses

#### FP-1: SystemBrightnessMaxVerify (Task 11) — **HIGH**

- **What happened**: Agent navigated to Display settings, saw "Brightness level | 100%", declared success: *"The brightness is already at 100%."*
- **Why it's false**: Brightness was pre-set at max. Agent never touched the brightness slider. The scripted scorer checks `brightness == max`, which passed on pre-existing state.
- **Evidence**: Agent's own answer says "already at 100%". Duration 19.4s (vs suite p50 of 69.6s) — trivially fast because no real work was done.
- **Systemic issue**: `perform_emulator_setup: false` — emulator state not properly randomized before task.

#### FP-2: SystemBrightnessMinVerify (Task 12) — **HIGH**

- **What happened**: Agent fumbled for 20 turns across quick settings and Settings app, never completed (MaxTurnsReached). But `scripted_score=1.0`.
- **Why it's false**: The task class `SystemBrightnessMinVerify` runs `adb shell settings put system screen_brightness 1` in `initialize_task` — brightness was pre-set to minimum before the agent started. Scorer checks `brightness == 1`, which passed on the precondition.
- **Evidence**: Quick settings slider swipes returned "Screen content unchanged" (already at min). Agent explicitly stated goal was to go to min but never succeeded in any brightness-modifying action. Completion reason is MaxTurnsReached, contradicting `scripted_score=1.0`.
- **Systemic issue**: "Verify" task variants pre-set the goal state. Scorer doesn't distinguish "agent achieved goal" from "precondition undisturbed."

#### FP-3: SystemBluetoothTurnOnVerify (Task 10) — **HIGH**

- **What happened**: Agent navigated to Bluetooth settings, found "Device name" section visible (BT ON indicator). Repeatedly clicked "Use Bluetooth" toggle, causing ON→OFF→ON→ON→ON→OFF→ON ping-pong over 6 turns. Final state ON.
- **Why it's false**: Bluetooth was already ON when the agent reached the settings page. The "Device name" section was visible from first observation (only appears when BT is enabled). Agent turned BT OFF then accidentally back ON. Net state change = zero.
- **Evidence**: Pre-toggle a11y tree shows "Device name | sdk_gphone64_arm64" (BT ON indicator). Post turn-5 tree shows "Bluetooth will turn on to pair" subtitle (BT OFF). Agent recovered via "Pair new device" which auto-enables BT.
- **Systemic issue**: A11y tree doesn't expose Switch `checked` state → agent can't determine toggle position → blind toggling.

#### FP-4: SystemWifiTurnOnVerify (Task 14) — **MEDIUM**

- **What happened**: Agent navigated to Settings > Internet. First observation showed "Searching for networks..." (Wi-Fi ON). Agent clicked Wi-Fi row, accidentally toggling OFF, then clicked again toggling back ON. Connected to AndroidWifi.
- **Why it's false**: Wi-Fi was already ON at start. The previous task (SystemWifiTurnOffVerify #12) failed to turn Wi-Fi off (disconnect ≠ off), leaving Wi-Fi enabled for this task. Agent's toggle ping-pong (ON→OFF→ON) shows it didn't understand the initial state.
- **Risk mitigating factor**: The final state is genuinely ON+connected, and the agent did mechanically toggle from an OFF state (that it created itself). Rated MEDIUM rather than HIGH.
- **Systemic issue**: Sequential task state dependency — previous task's failure contaminated this task's precondition.

---

## Part 2: Failure Root Cause Analysis (5 Failures + 1 Semantic Failure)

### Summary Table

| # | Task | Turns | Failure Mode | Primary Root Cause | Category |
|---|------|-------|--------------|--------------------|----------|
| 1 | BrowserMultiply | 20 | MaxTurnsReached | Chrome first-run overhead + scratchpad per-click overhead | Orchestration |
| 5 | ExpenseAddSingle | 20 | MaxTurnsReached | Category RecyclerView swipe failures + destructive form dismissals | Execution + Reasoning |
| 6 | FilesMoveFile | 6 | LLM 401 Error | Infra: qwen3.5 API auth failure + env: Podcasts folder missing | Infrastructure |
| 8 | RecipeAddSingleRecipe | 20 | MaxTurnsReached | Keyboard occludes ScrollView, blocking lower form fields | Perception + Execution |
| 13 | SystemWifiTurnOff | 13 | Semantic failure | "Disconnect" ≠ "Turn off Wi-Fi" — agent satisficed | Reasoning |

### Detailed Failure Analyses

#### F-1: BrowserMultiply — Orchestration Failure

**Goal**: Open task.html, click button 5 times, compute product of displayed numbers, enter into form.

**What went wrong**:
1. **Chrome first-run loop** (turns 4-9): Chrome's "Accept & continue" and "No thanks" dialogs consumed 6 turns. A failed "No thanks" click navigated back to Files, forcing a full re-open sequence. Cost: 4 wasted turns.
2. **Scratchpad overhead**: Each button click required 2 turns (click + scratchpad write). For 5 clicks = 10 turns, consuming half the budget.
3. **Turn exhaustion**: After collecting 4 of 5 numbers, the page transitioned to the form. Agent spent final 2 turns confused by the UI change, never entering the product.

**Turn budget**: 12/20 productive (60%), 8/20 wasted (40%).

**Fix**: (1) Pre-configure Chrome in eval setup to skip first-run. (2) Don't use scratchpad per-click — numbers fit in conversation context. (3) Consider max_turns > 20 for multi-step browser tasks.

#### F-2: ExpenseAddSingle — Execution + Reasoning Failure

**Goal**: Add expense "Therapy Sessions", $307.01, category "Health Care", note "I may repeat this".

**What went wrong**:
1. **Category "Health Care" unreachable**: The horizontal RecyclerView showed Food/Income/Housing/Social/Entertainment. "Health Care" required scrolling. 5 swipe attempts ALL failed ("screen content unchanged") or accidentally dismissed the form.
2. **Destructive form dismissal**: Swipe-right on the narrow category RecyclerView triggered back navigation TWICE, losing all typed data. Agent re-entered name + amount 3 times.
3. **Tool parameter error**: Turn 18 used `text_index` (an a11y tree field) instead of `element_index`.
4. **Wrong note**: Agent typed "This is the psychology therapy fund" instead of "I may repeat this" — goal text hallucination after 20 turns of accumulated context.

**Turn budget**: 4/20 productive (20%), 16/20 wasted (80%).

**Fix**: (1) Loop detection after 2 consecutive failed swipes. (2) Guard against swipe-to-back on narrow RecyclerViews. (3) Preserve goal text verbatim — don't paraphrase/hallucinate.

#### F-3: FilesMoveFile — Infrastructure + Environment Failure

**Goal**: Move `holiday_photos.jpg` from Podcasts to DCIM.

**What went wrong**:
1. **LLM 401 error** at turn 6 killed the session. The qwen3.5 API returned "User not found" on the 6th API call (first 5 succeeded).
2. **Podcasts folder doesn't exist**: Device storage shows Alarms/Android/Audiobooks/DCIM/Documents/Download/Movies/Music — no Podcasts. Task was unachievable regardless of the auth error.

**Fix**: (1) Add LLM API retry with backoff for transient 401/5xx. (2) Ensure eval task setup creates required file/folder structure.

#### F-4: RecipeAddSingleRecipe — Perception + Execution Failure

**Goal**: Add recipe "Chicken Caesar Salad Wrap" with 6 fields (title, description, servings, time, ingredients, directions).

**What went wrong**:
1. **Keyboard occlusion**: After typing into Description, the soft keyboard consumed ~692px of screen height, shrinking the ScrollView from 1934px to 1242px. Lower form fields (Servings, Time, Ingredients, Directions) became invisible and unreachable.
2. **6 failed scroll attempts**: Agent tried 6 swipe variants across turns 7-12 and 19-20, all returning "screen content unchanged" — the keyboard-shrunken viewport had no scrollable content below.
3. **No keyboard dismissal**: Agent never tried `system_button(back)` or tapping outside to dismiss the keyboard. This simple fix was never discovered.
4. **Premature save**: Agent gave up and saved incomplete recipe (turn 14) with only title+description, then re-entered edit mode (turns 15-16). This accidentally revealed lower fields when keyboard was not open.

**Fields filled**: 3/6 (title, description, servings). Missing: time, ingredients, directions. No final save.

**Fix**: (1) Keyboard-aware scrolling: dismiss keyboard before attempting to scroll forms. (2) After 2 consecutive "unchanged" swipes, try alternative strategy. (3) Prioritize "dismiss keyboard → scroll → focus next field → type" pattern for multi-field forms.

#### F-5: SystemWifiTurnOff — Reasoning (Semantic) Failure

**Goal**: "Turn off Wi-Fi."

**What went wrong**: Agent navigated to Settings > Internet > Wi-Fi detail page. Could not find a dedicated Wi-Fi on/off toggle in the a11y tree (the toggle switch inside the Wi-Fi row was not separately indexed). After 10 turns of searching, agent pivoted to clicking the connected "AndroidWifi" network and pressing "Disconnect" — then claimed success.

**Semantic gap**: "Disconnect from a network" ≠ "Turn off Wi-Fi radio". After disconnecting, Wi-Fi radio remained ON (still scanning). Scripted scorer checks `wifi_enabled == false`, which failed → `scripted_score=0.0`.

**Agent said**: *"Successfully disconnected from WiFi."* — explicitly wrong framing.

**Fix**: (1) Agent needs to distinguish "disconnect" from "disable radio" in system settings tasks. (2) A11y tree should surface the Wi-Fi toggle switch as a separate element. (3) Agent could use the quick settings Wi-Fi tile as an alternative toggle path.

---

## Part 3: Cross-Cutting Patterns

### Pattern 1: "Verify" Task False Positives (3 of 4 Verify tasks)

Four tasks use the `*Verify` variant pattern (SystemBrightnessMax**Verify**, SystemBrightnessMin**Verify**, SystemBluetoothTurnOn**Verify**, SystemWifiTurnOn**Verify**). Three of four were false positives where the goal state was pre-satisfied.

**Root cause**: `perform_emulator_setup: false` combined with sequential task execution means emulator state bleeds between tasks. Verify tasks that pre-set state in `initialize_task` will always pass the scorer regardless of agent behavior.

**Recommendation**: Flag any result where `scripted_score=1.0` AND (`completion_reason=MaxTurnsReached` OR agent's answer contains "already"). Track Verify tasks separately in aggregate metrics.

### Pattern 2: Toggle Ping-Pong (Bluetooth, Wi-Fi)

Both SystemBluetoothTurnOn and SystemWifiTurnOn exhibited ON→OFF→ON ping-pong behavior. Root cause: the a11y tree does not expose Switch/Toggle `checked` state, so the agent cannot determine whether a toggle is ON or OFF. It clicks blindly and infers from secondary UI signals (which it also struggles to interpret).

**Recommendation**: Surface `checked`/`selected` state in sanitized a11y tree for Switch/Toggle widgets.

### Pattern 3: Keyboard Occlusion Blocking Form Scrolling (Expense, Recipe)

Both ExpenseAddSingle and RecipeAddSingleRecipe failed because the soft keyboard consumed screen space, preventing scroll to lower form fields. In both cases the agent never dismissed the keyboard.

**Recommendation**: Add keyboard dismissal heuristic: after 2 consecutive "screen content unchanged" swipes while any EditText has `focused: true`, automatically dismiss keyboard via back button before retrying.

### Pattern 4: Destructive Swipe-to-Back (Expense)

Horizontal swipes on narrow RecyclerViews can trigger back navigation, destroying form state. This happened twice in ExpenseAddSingle, forcing the agent to re-enter data from scratch.

**Recommendation**: Detect when a swipe causes a major screen transition (different package or significantly different element count) and warn/prevent this.

### Pattern 5: Turn Budget Exhaustion on Complex Tasks

Four tasks hit MaxTurnsReached (BrowserMultiply, ExpenseAddSingle, RecipeAddSingleRecipe, SystemBrightnessMin). Common patterns:
- No loop detection (same failing action repeated 3-6 times)
- Scratchpad/todo overhead consuming turns
- Recovery from accidental navigation costing 3-5 turns

**Recommendation**: (1) Implement loop detection — after 2 identical failing actions, force strategy change. (2) Budget-awareness: if only N turns remain and M actions are still needed, prioritize completion actions (e.g., save).

---

## Part 4: Corrected Metrics

### Scoring After FP Removal

| Task | Reported | Corrected | Reason |
|------|----------|-----------|--------|
| CameraTakePhoto | 1.0 | **1.0** | Genuine |
| ClockTimerEntry | 1.0 | **1.0** | Genuine |
| ContactsAddContact | 1.0 | **1.0** | Genuine |
| MarkorCreateNote | 1.0 | **1.0** | Genuine |
| SimpleSmsSend | 1.0 | **1.0** | Genuine |
| SystemBluetoothTurnOn | 1.0 | **0.0** | FP: BT already ON |
| SystemBrightnessMax | 1.0 | **0.0** | FP: already at 100% |
| SystemBrightnessMin | 1.0 | **0.0** | FP: pre-set by initialize_task |
| SystemWifiTurnOn | 1.0 | **0.0** | FP: Wi-Fi already ON |
| BrowserMultiply | 0.0 | 0.0 | — |
| ExpenseAddSingle | 0.0 | 0.0 | — |
| FilesMoveFile | 0.0 | 0.0 | — |
| RecipeAddSingleRecipe | 0.0 | 0.0 | — |
| SystemWifiTurnOff | 0.0 | 0.0 | — |

### Corrected Summary

| Metric | Reported | Corrected |
|--------|----------|-----------|
| Scripted success rate | 64.3% (9/14) | **35.7% (5/14)** |
| Real success (excl. FP) | — | **35.7%** |
| Goal claim precision | 88.9% (8/9) | **55.6% (5/9)** |
| FP count in successes | — | 4/9 (44.4%) |

### Comparison Across Rounds (Corrected)

| Metric | R1 (corrected) | R3 (corrected) | Delta |
|--------|----------------|-----------------|-------|
| Scripted success rate | 57.1% | 64.3% | +7.2% |
| Real success rate | 46% | 35.7% | **-10.3%** |
| Tool failure rate | 15.7% | 1.86% | **-13.8%** (major improvement) |
| Duration p50 | 158s | 69.6s | **-88.4s** (major improvement) |
| Duration p90 | 924s | 146.8s | **-777.2s** (major improvement) |

**Interpretation**: Tool reliability and speed improved dramatically from R1→R3. But real task success rate actually regressed, suggests the remaining failures are reasoning/perception problems, not execution problems. The low-hanging fruit (click reliability, latency) has been addressed; remaining issues require cognitive improvements.

---

## Part 5: Prioritized Action Items

### P0 — Eval Reliability (fix false positives in scoring)

1. **Auto-flag contradictory signals**: `scripted_score=1.0` + `completion_reason=MaxTurnsReached` → auto-flag as suspect
2. **Track Verify tasks separately**: Don't mix Verify-variant results with action-required variants in aggregate metrics
3. **Ensure precondition randomization**: For system toggle tasks (WiFi/BT/Brightness), verify starting state is opposite of goal via adb check before task starts

### P1 — Agent Cognitive (highest impact on real success rate)

4. **Loop detection**: After 2 identical failing actions (same tool + same target + same error), force strategy switch
5. **Keyboard dismissal heuristic**: Before scrolling forms, dismiss keyboard if any EditText has `focused: true`
6. **Toggle state awareness**: Surface Switch/Toggle `checked` state in sanitized a11y tree; or teach agent to read secondary indicators (e.g., "Device name" section visible = BT ON)
7. **Semantic precision**: Distinguish "disconnect network" from "disable radio" for system toggle tasks

### P2 — Robustness

8. **LLM API retry**: Add retry with exponential backoff for transient 401/5xx errors
9. **Destructive swipe guard**: Detect when swipe causes major screen transition and warn
10. **App name fuzzy matching**: `open_app("Simple SMS Messenger")` should match `"SMS Messenger"`
11. **Chrome first-run pre-configuration**: Skip Chrome setup dialogs in eval environment

### P3 — Efficiency

12. **Turn budget awareness**: When remaining_turns < needed_actions, prioritize save/submit
13. **Reduce scratchpad overhead**: For in-task memory, prefer conversation context over per-action scratchpad writes
14. **Pre-configure eval apps**: Skip first-run / onboarding dialogs for all eval-included apps
