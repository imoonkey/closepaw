# AW Core Eval Deep Analysis (Claude Code)

## Scope

- Eval run: `eval/results/20260218_145836`
- Task list: `eval/config/aw_subset_core.txt`
- Model: `qwen/qwen3.5-plus-02-15`
- Results: 13 task instances, 14 attempts, 7/13 scripted success (53.8%) -- **actual 6/13 (46%) after ContactsAddContact false positive**
- Method: subagent step-by-step trace inspection (steps.jsonl, tool_call_args, tool_result, llm_response_text, screenshots)

---

## Cross-cutting finding: Inline pseudo-tool call (P0)

**Affected runs**: MarkorCreateNote, FilesMoveFile, ContactsAddContact, SimpleSmsSend (4/14 attempts)

The qwen3.5 model frequently emits tool calls as **plain text** in its response body instead of structured tool calls. The tool arbitration layer sees `tool_calls=0` and treats the turn as a text-only completion, marking `is_complete=true`.

Evidence:

| Run | Turn | LLM response text (inline pseudo-tool) | Result |
|---|---|---|---|
| MarkorCreateNote | 3 | `mobile_action{"action":"type","input_text":"2023_01_26_wise_yacht","element_index":1,...}` | Task failed - filename never typed |
| FilesMoveFile | 4 | `write_todos{"todos":[...]}` (narrates next step but emits is_complete=true) | Task failed - file never moved |
| ContactsAddContact | 4 | `mobile_action{"action":"type","input_text":"Hugo","element_index":11,...}` | Task "succeeded" but with risky pattern |
| SimpleSmsSend | 4 | `open_app{"app_name":"SMS",...}` | Task failed - on launcher, claimed done |

**Root cause**: Reasoning + Execution. The LLM outputs tool-call-like syntax embedded in natural language text. The tool parser requires structured function calls (via the LLM's native tool_use format) and does not extract tool calls from freeform text.

**Impact**: This is the #1 issue. 3 of 6 non-infra failures are directly caused by this pattern, plus 1 "success" is fragile.

**Proposed fix**:
1. **Text-to-tool recovery parser**: In `ToolArbitrationPolicy` or `ToolCallParser`, add a fallback that scans LLM response text for `tool_name{...}` patterns and converts them to structured tool calls before marking completion.
2. **Completion guardrail**: If `tool_calls=0` and response text matches `/(mobile_action|open_app|write_todos|scratchpad|system_button)\s*\{/`, do NOT allow `is_complete=true`; instead force a retry or parse the inline call.
3. **Prompt reinforcement**: Add explicit instruction in system prompt: "You MUST use the tool call format. Do NOT write tool calls as text in your response."

---

## Per-run detailed analysis

### 1) BrowserMultiply (`failure`, MaxTurnsReached, 20 turns)

**Goal**: Open task.html, click button 5 times, remember numbers, enter product.

**Step-by-step audit**:

| Turn | Tool | OK? | Exec OK? | Category | Evidence |
|---|---|---|---|---|---|
| 1 | write_todos | Y | Y | - | Good planning |
| 2 | open_app(Files) | Y | Y | - | Files opened |
| 3 | mobile_action(click task.html) | Y | Y | - | File selected |
| 4 | mobile_action(click Chrome) | Y | Y | - | App chooser handled |
| 5 | wait | Y | Y | - | Wait for Chrome load |
| 6 | mobile_action(click "Use without an account") | Y | **N** | Execution | `Click at (540,2093) failed after all attempts - dispatched, no UI change` |
| 7 | mobile_action(click "Continue as I") | Y | Y | - | Recovery from step 6 |
| 8 | mobile_action(click "No thanks") | Y | Y | - | Chrome setup dismissed |
| 9 | mobile_action(click task.html again) | Y | Y | - | Back to Files, re-opens |
| 10 | mobile_action(choose Chrome) | Y | Y | - | Second time opening with Chrome |
| 11 | mobile_action | Q | Y | Observation | Package context shows `com.android.chrome` vs earlier `com.moonkey.androidagent` |
| 12 | mobile_action(click "Click Me") | Y | Y | - | First button click, number shown |
| 13 | scratchpad(numbers: "9,10") | Y | Y | - | Records first 2 numbers |
| 14 | mobile_action(click "Click Me") | Y | Y | - | Third click |
| 15 | scratchpad(numbers: "9,10,5") | Y | Y | - | Records 3rd number |
| 16 | mobile_action(click at 541,443) | Y | **N** | Execution | `Click at (541,443) failed - dispatched, no UI change` - WebView button unresponsive |
| 17 | mobile_action(click at 540,1307) | N | **N** | Execution | `No clickable node at (540,1307)` - targeting non-clickable WebView container |
| 18 | mobile_action(click at 541,443) | Q | Y | Orchestration | Coordinate click "succeeds" (dispatched) but page transitions to form prematurely |
| 19 | scratchpad(numbers: "9,10,5") | Q | Y | Orchestration | Notes "page changed to form, need to restart" but only writes scratchpad, no action |
| 20 | mobile_action(click element 9) | N | Y | Reasoning | Clicks an element but agent_thought says "need to reload" - contradictory |

**Root causes**:
- **Execution**: WebView button clicks fail silently (ACTION_CLICK dispatched but no UI change). 3 failures at turns 6, 16, 17.
- **Reasoning**: After click failures, agent doesn't adapt strategy well. Uses scratchpad turns instead of acting. On turn 20, the thought says "reload page" but action clicks an arbitrary element.
- **Observation**: The "Click Me" button may have changed coordinates after page scroll/resize, but agent re-used stale coordinates.

**Proposed fixes**:
- WebView click hardening: detect "dispatched, no UI change" and try JavaScript-based click injection or alternative coordinates
- Failure recovery policy: limit consecutive scratchpad-only turns; force action after failed click
- Add post-click UI change validation: if no a11y tree change detected, retry with alternative strategy

---

### 2) FilesMoveFile (`failure`, GoalAchieved, 4 turns)

**Goal**: Move holiday_photos.jpg from Podcasts to DCIM in sdk_gphone storage.

| Turn | Tool | OK? | Exec OK? | Category | Evidence |
|---|---|---|---|---|---|
| 1 | write_todos + open_app(Files) | Y | Y | - | Good planning, Files opened |
| 2 | mobile_action(Show roots) | Y | Y | - | Storage roots displayed |
| 3 | mobile_action(click sdk_gphone64_arm64) | Y | Y | - | Navigated into storage |
| 4 | *(no tool - text only)* | **N** | N/A | Reasoning+Execution | LLM says "navigate to Podcasts" but outputs inline `write_todos{...}` as text. `is_complete=true`. |

**Key evidence** (turn 4 response text):
```
I can see both the Podcasts folder (element_index 34) and the DCIM folder (element_index 21)...
Let me first navigate to the Podcasts folder...

write_todos{"todos":[...{"description":"Locate holiday_photos.jpg in Podcasts folder","status":"in_progress"}...]}
```

**Root cause**: Inline pseudo-tool call pattern (see P0 above). LLM narrates correct next step but emits tool call as text. Arbitration sees 0 tool calls, marks complete.

---

### 3) MarkorCreateNote (`failure`, GoalAchieved, 3 turns)

**Goal**: Create note "2023_01_26_wise_yacht.md" with text "Ignorance is bliss."

| Turn | Tool | OK? | Exec OK? | Category | Evidence |
|---|---|---|---|---|---|
| 1 | open_app(Markor) | Y | Y | - | Markor opened |
| 2 | mobile_action(click new file FAB) | Y | Y | - | New file dialog appeared |
| 3 | *(no tool - text only)* | **N** | N/A | Execution | Inline `mobile_action{"action":"type","input_text":"2023_01_26_wise_yacht",...}` as text |

**Key evidence** (turn 3 response text):
```
I can see the file creation dialog. I need to:
1. Enter the filename "2023_01_26_wise_yacht" in the name field (index 1)
...
mobile_action{"action":"type","input_text":"2023_01_26_wise_yacht","element_index":1,"clear":true,...}
```

**Root cause**: Same P0 inline pseudo-tool call. The LLM correctly identifies the target and writes the correct tool call, but as text instead of structured format.

---

### 4) ContactsAddContact (`success` but risky, 4 turns)

**Goal**: Create contact Hugo Pereira, +13920741751.

| Turn | Tool | OK? | Exec OK? | Category | Evidence |
|---|---|---|---|---|---|
| 1 | open_app(Contacts) | Y | Y | - | Contacts opened |
| 2 | mobile_action(click "Create new contact") | Y | Y | - | Contact form opened |
| 3 | mobile_action(click First name field) | Y | Y | - | Field focused |
| 4 | *(no tool - text only)* | **N** | N/A | Execution | Inline `mobile_action{"action":"type","input_text":"Hugo",...}` as text |

**Key evidence** (turn 4 response text):
```
The First name field is now focused (index 11 shows "focused": true). Now I'll type "Hugo" into this field.

mobile_action{"action":"type","input_text":"Hugo","element_index":11,...}
```

**CONFIRMED FALSE POSITIVE**: scripted_success=true but agent never actually created the contact.

**Root cause**: Google Contacts Sync restored the contact from the cloud after `clear_app_data`.

Logcat evidence (timestamps from `aw_20260218_145836_ContactsAddContact_3_0/logcat.log`):
```
15:21:21.507  PACKAGE_DATA_CLEARED com.android.providers.contacts     ← clear_contacts() executed
15:21:25.090  FSA2_ContactsSyncAdapter: Sync started                   ← Google sync triggered by data clear
15:21:29.588  ContactSyncGrpc: Received non-null API-contact response  ← server returned contacts
15:21:35.766  FSA2_RawContactSyncer: before sync: 0, after sync down: 6  ← 6 contacts restored from cloud (including Hugo Pereira from prior run)
15:21:40.741  AgentSession: Task started                                ← agent starts AFTER sync completes
15:22:06.824  LLM turn 2: "already a contact named Hugo Pereira"       ← agent sees pre-existing contact
```

The emulator has a Google account logged in. A prior eval run created Hugo Pereira, which synced to Google Cloud. `clear_app_data` only wipes local DB; Google Contacts Sync immediately re-downloads all contacts from the cloud within ~14 seconds, well before the agent starts.

**Fix required in eval harness**:
1. Disable auto-sync before `clear_contacts()`: `adb shell settings put global auto_sync 0`
2. Or: remove Google account from eval emulator
3. Or: after `clear_app_data`, also call `adb shell pm clear com.google.android.gms` (nuclear option)

**Status: RESOLVED** — Created new `AndroidWorldAvd` emulator (API 33, Pixel 6, `google_apis`, no Google account). Without a logged-in Google account there is no cloud sync, so `clear_app_data` reliably wipes contacts. This eliminates the false positive. Future eval runs on this AVD are not affected by P6.

---

### 5) SimpleSmsSend (`failure`, GoalAchieved, 4 turns)

**Goal**: Send SMS via Simple SMS Messenger to +16597910719.

| Turn | Tool | OK? | Exec OK? | Category | Evidence |
|---|---|---|---|---|---|
| 1 | open_app("Simple SMS Messenger") | Y | **N** | Eval gap | App not found - not installed on emulator |
| 2 | open_app("Messages") | Q | Y | Reasoning | Opens Google Messages instead (wrong app) |
| 3 | system_button("home") | **N** | Y | Reasoning | Abandons Google Messages onboarding, goes to home - should have tried sending from there |
| 4 | *(no tool - text only)* | **N** | N/A | Reasoning+Execution | On launcher, inline `open_app{...}` as text, `is_complete=true` |

**Root causes**:
- **Evaluation gap**: "Simple SMS Messenger" not installed on the emulator. The task requires a specific app that doesn't exist.
- **Reasoning**: Agent correctly identifies Google Messages is wrong app, but instead of attempting to use it (which might still work for SMS), presses Home and falsely claims completion.
- **Execution**: Turn 4 inline pseudo-tool call pattern.

---

### 6) ClockTimerEntry (`timeout`, 0 turns, 916 seconds)

**Key data**:
- `bridge_status`: timeout
- `agent_completion_reason`: "volume_controller"
- `trace_dir`: null (no trace exists)
- `turns_executed`: 0, `tool_calls`: 0

**Root causes**:
- **Orchestration**: Agent session never started. The accessibility service likely wasn't ready when the bridge attempted to start the session, or the session startup failed silently. With 0 turns and no trace, the agent loop never ran.
- **Evaluation gap**: `completion_reason="volume_controller"` is clearly a mis-extraction - this string comes from Android system logs (volume key press handler), not from agent output. The reason extraction logic is matching system log entries instead of agent-specific completion markers.

---

### 7-8) RecipeAddSingleRecipe (2x `infra_failure`)

Both attempts fail identically:
```
Error type 3: Activity class {com.flauschcode.broccoli/com.flauschcode.broccoli.MainActivity} does not exist.
```

**Root cause**: **Evaluation gap** - Broccoli app is either not installed on the emulator or the activity class name in the task config is wrong. The retry doesn't help because the same infrastructure issue persists.

---

### 9-14) System* tasks (5x `success`)

All System tasks (Bluetooth, Brightness Max/Min, Wifi On/Off) complete in 3 turns with 2 tool calls each. Pattern: open_app(Settings) or system_button(home) -> navigate/toggle -> complete_task. Clean execution, no issues.

---

## Problem classification summary

### P0: Inline pseudo-tool call (4 runs affected, 3 failures)

**Affected**: MarkorCreateNote, FilesMoveFile, ContactsAddContact, SimpleSmsSend
**Category**: Execution (tool call format) + Reasoning (completion check)
**Fix priority**: Highest - single fix addresses multiple failures

### P1: WebView click reliability (1 run affected)

**Affected**: BrowserMultiply
**Category**: Execution
**Pattern**: `ACTION_CLICK: dispatched, no UI change` on WebView-hosted buttons
**Fix**: Fallback to gesture_tap with jitter, JavaScript click injection, or coordinate recalculation after scroll

### P2: Premature completion / over-optimistic is_complete (4 runs affected)

**Affected**: FilesMoveFile, MarkorCreateNote, SimpleSmsSend, ContactsAddContact
**Category**: Reasoning
**Pattern**: Agent marks `is_complete=true` when task is clearly unfinished
**Fix**: Minimum-action-chain validation for multi-step tasks; post-completion screen state verification against goal

### P3: Session/a11y readiness race condition (1 run affected)

**Affected**: ClockTimerEntry
**Category**: Orchestration
**Fix**: Bridge should poll for agent session readiness with exponential backoff rather than relying on fixed timeout

### P4: Completion reason mis-extraction (1 run affected)

**Affected**: ClockTimerEntry
**Category**: Evaluation gap
**Pattern**: `agent_completion_reason="volume_controller"` extracted from system logcat instead of agent logs
**Fix**: Filter completion reason extraction to agent-specific log tags only

### P5: Missing app / wrong activity mapping (3 runs affected)

**Affected**: RecipeAddSingleRecipe (2x), SimpleSmsSend
**Category**: Evaluation gap
**Fix**: Pre-flight check that required apps are installed; maintain app name -> package alias mapping; skip task gracefully if app missing

### P6: Google Cloud Sync defeats `clear_app_data` preconditions (1 run confirmed, likely more)

**Affected**: ContactsAddContact (confirmed false positive)
**Category**: Evaluation gap
**Pattern**: `clear_app_data` wipes local DB, but Google Contacts Sync immediately re-downloads data from cloud (6 contacts restored in 14 seconds). Any task that creates cloud-synced data (contacts, calendar, etc.) will persist across eval runs.
**Fix**: Disable auto-sync (`adb shell settings put global auto_sync 0`) before precondition reset, or use emulator without Google account.
**Status: RESOLVED** — New `AndroidWorldAvd` emulator has no Google account, eliminating cloud sync interference.

---

## Proposed changes (generalizable, priority-ordered)

### 1. Text-to-tool recovery parser (addresses P0)

**Impact**: 3 failures -> potential successes
**Files**: `agent/cognition/policy/ToolArbitrationPolicy.kt` or tool call parsing layer
**Change**: When LLM response has `tool_calls=0` but response text contains `toolName{...json...}` pattern, extract and execute as structured tool call. Add guardrail: if extracted tool call found, do NOT mark `is_complete=true`.

### 2. Completion pre-condition validator (addresses P2)

**Impact**: Prevents false GoalAchieved across all tasks
**Files**: `agent/cognition/policy/CompletionPolicy.kt` or equivalent
**Change**: Before allowing `is_complete=true`, verify that at least one "goal-relevant" tool was executed (e.g., for file move tasks, a move/copy action must exist in history). Cross-check screen state against goal keywords.

### 3. WebView click fallback chain (addresses P1)

**Impact**: BrowserMultiply and future web-based tasks
**Files**: `tool/impl/MobileActionTool.kt`, click execution code
**Change**: On "dispatched, no UI change" for WebView elements:
  1. Retry with +-5px coordinate jitter
  2. Try `dispatchGesture` with longer hold
  3. If still failing, attempt the click via `evaluateJavascript` on the WebView

### 4. Session readiness polling (addresses P3)

**Impact**: ClockTimerEntry and other timing-sensitive tasks
**Files**: `eval/aw_bridge/runner.py` or equivalent bridge code
**Change**: Replace fixed sleep with poll loop checking agent service connected + session active

### 5. Completion reason extraction hardening (addresses P4)

**Impact**: Accurate metrics for all runs
**Files**: `eval/aw_bridge/runner.py` (completion reason extraction)
**Change**: Only extract completion reason from tagged agent log lines (e.g., `AgentSession:` or `SessionManager:`), not from arbitrary logcat

### 6. App pre-flight check (addresses P5)

**Impact**: RecipeAddSingleRecipe, SimpleSmsSend
**Files**: `eval/aw_bridge/runner.py`, task config
**Change**: Before starting a task, verify all required packages are installed via `pm list packages`. If missing, mark as `skipped_missing_app` instead of failing with infra_failure.

### 7. Prompt reinforcement for structured tool calls (addresses P0, model-level)

**Impact**: All runs using qwen3.5
**Files**: `agent/cognition/prompt/PromptAssembler.kt` or system prompt template
**Change**: Add explicit instruction: "CRITICAL: Always use the tool_call function calling format. NEVER write tool calls as plain text in your response. If you need to call a tool, use the proper function call syntax."

---

## Verification plan

1. Re-run `aw_subset_core.txt` after implementing fix #1 (text-to-tool recovery) - expect MarkorCreateNote, FilesMoveFile to flip to success
2. Re-run BrowserMultiply after implementing fix #3 (WebView click fallback)
3. Compare metrics via `python3 eval/analysis/compare_runs.py --base eval/results/20260218_145836 --new <new_run>`
4. Target: scripted_success_rate >= 0.70 (from corrected baseline 0.46)
