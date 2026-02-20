# Cog-Tune Analysis: Eval Run 20260219_163232

## Eval Summary

| Metric | Value |
|--------|-------|
| Run | `20260219_163232` |
| Model | qwen3.5 (qwen/qwen3.5-plus-02-15) |
| Tasks | 3 |
| Success Rate | 33% (1/3) |
| Perception | accessibility_only (no screenshots) |
| Max Turns | 30 |

### Per-Task Results

| Task | Status | Turns | Duration | Key Issue |
|------|--------|-------|----------|-----------|
| ExpenseAddSingle | FAILURE | 26* | 184s | Tool schema confusion + scripted validation fail |
| FilesMoveFile | FAILURE | 29* | 228s | Wrong UX strategy + dialog loop |
| RecipeAddSingleRecipe | SUCCESS | 17 | 123s | Clean execution |

> *per_task.jsonl reports `turns_executed: 0` for both failures despite traces showing 26/29 turns. This is a harness reporting bug.

---

## Root Cause Classification

### Verdict: COGNITION (not Execution)

Both failures are caused by LLM reasoning errors, not action execution failures. Action execution (click, long_press, scroll) worked correctly when given valid parameters. No `/action-debug` flow needed for this run.

---

## Failure 1: FilesMoveFile

**Goal**: Move `holiday_photos.jpg` from Podcasts to DCIM within sdk_gphone_x86_64 storage.

### Timeline

| Turn | Action | Target | Result | Notes |
|------|--------|--------|--------|-------|
| 1 | open_app | "Files" | OK | |
| 2 | mobile_action (click) | documentsui | OK | Navigating sidebar/storage |
| 3 | mobile_action (?) | documentsui | OK | |
| 4 | mobile_action (?) | documentsui | OK | Navigating to Podcasts |
| 5 | mobile_action (?) | documentsui | OK | |
| 6 | mobile_action (?) | documentsui (39 elems) | OK | |
| 7 | **long_press** elem 16 | `holiday_photos.jpg` | OK | Intended: select file for context menu |
| 8 | **click** elem 7 | `More options` (toolbar) | OK | **WRONG TARGET** - opened "Open with" dialog |
| 9 | mobile_action `system_button` | back | **FAIL** | Invalid action type in mobile_action |
| 10-14 | various clicks | package: android (10 elems) | OK/mixed | Stuck on "Open with" dialog |
| 15-16 | clicks | documentsui | OK | Briefly returned to file list |
| 17-24 | clicks, system_button | package: android | OK/mixed | Loop: system dialogs |
| 25-28 | clicks | documentsui + android | OK | Still trying to move file |
| 29 | - | - | CUT OFF | Max turns reached mid-LLM-request |

### Root Cause Breakdown

#### 1. Wrong UX Strategy (Cognition - Planning) [PRIMARY]

Turn 7-8 sequence:
- Turn 7: `long_press` on element 16 (`holiday_photos.jpg`) - intended to select the file
- Turn 8: Click on element 7 (`More options` in the toolbar at bounds `[954,128,1080,254]`)

The agent thought this would bring up a move/copy menu. Instead, it triggered an **"Open with" intent chooser** showing:
- Retro Music
- VLC
- YouTube Music
- "Just once" / "Always" buttons

The agent should have:
- After long-pressing to select, looked for action bar changes (Cut/Copy/Move icons)
- OR used the per-item three-dot menu on the file row itself
- OR used click (not long_press) to open the file, then used the share/move option

**Evidence**: Turn 8 pre-state a11y tree shows `More options` at index 7 is an `ImageView` in the top toolbar (center: [1017, 191]) - this is the global overflow menu, not a per-file context action.

#### 2. Tool Schema Confusion (Cognition - Tool Use) [SECONDARY]

Turn 9 generated:
```json
{"action": "system_button", "button": "back", ...}
```
as a `mobile_action` call. Error:
```
Error: Validation failed: Unknown action: 'system_button'. Valid: click, long_press, scroll, swipe, type
```

The system prompt says: *"Prefer at most ONE screen-affecting action per turn (mobile_action, open_app, system_button, wait)"* - listing `system_button` as a separate tool. But the LLM wraps it inside `mobile_action.action`.

#### 3. Failed Recovery Loop (Cognition - Adaptation)

After the "Open with" dialog appeared (turns 9-28), the agent:
- Tried clicking ScrollView (index 0) to dismiss
- Repeatedly cycled between `package: android` and `com.google.android.documentsui`
- Never successfully moved the file
- Hit max_turns (30) on turn 29

---

## Failure 2: ExpenseAddSingle

**Goal**: Add expense "Therapy Sessions, $307.01, Health Care" to Pro Expense app.

### Timeline

| Turn | Action | Result | Notes |
|------|--------|--------|-------|
| 1 | open_app "Pro Expense" + write_todos | OK | Good planning |
| 2-11 | mobile_actions (clicks, typing) | OK | Navigating and entering expense details |
| 12 | mobile_action `system_button` | **FAIL** | Same bug: `system_button` not valid in mobile_action |
| 13 | mobile_action | OK | Recovered with different approach |
| 14-19 | mobile_actions | OK | Continued expense entry |
| 20 | click with `index: 20` | **FAIL** | Wrong param: should be `element_index` |
| 21 | mobile_action | OK | Recovered with correct param |
| 22-26 | mobile_actions | OK | Completed and saved expense |

### Root Cause Breakdown

#### 1. Tool Schema Confusion (Cognition - Tool Use)

Same issue as FilesMoveFile turn 9:
```json
{"action": "system_button", "button": "back",
 "agent_thought": "Go back to try a different approach..."}
```
Recovery: Turn 13 succeeded with alternative approach.

#### 2. Parameter Name Error (Cognition - Tool Schema)

Turn 20:
```json
{"action": "click", "index": 20, ...}
```
Error: `click requires one of: element_index, text, or x/y coordinates`

The LLM used `"index"` instead of `"element_index"`. Recovery: Turn 21 used correct naming.

#### 3. Scripted Validation Failure (Cognition - Task Understanding) [PRIMARY]

Despite 26 turns with 2 recoverable errors and apparent completion, the scripted check returned `scripted_score: 0.0`. This means either:
- Wrong category selected (Health Care may not have been correctly chosen)
- Amount entered incorrectly
- Expense not saved
- Wrong UI flow for category selection

**Note**: The agent's trace shows it struggled around category selection (turn 12 error was during category flow: "Go back to try a different approach - maybe the category is selected differently").

---

## Common Pattern: `system_button` in `mobile_action`

Both tasks hit the same bug: the LLM generates `mobile_action(action="system_button")` instead of calling the separate `system_button` tool.

**System prompt says**:
```
Prefer at most ONE screen-affecting action per turn (mobile_action, open_app, system_button, wait)
```

The LLM interprets `system_button` as an action type within `mobile_action` rather than a standalone tool. This confusion is caused by:
1. The system prompt listing tools by name without clearly separating them
2. `mobile_action` accepting `action` as a parameter, which the LLM confuses with tool names
3. The prompt mentions `system_button(button="enter")` syntax, but this is tool call syntax, not action type syntax

### Recommended Fix

Option A: Add `system_button` as a valid `mobile_action` action type and handle internally (consolidate tools).

Option B: Add explicit disambiguation in system prompt:
```
IMPORTANT: system_button is a SEPARATE tool, not a mobile_action action type.
- mobile_action actions: click, long_press, scroll, swipe, type
- system_button tool: back, home, enter, recent
Never use action="system_button" inside mobile_action.
```

---

## harness Bug: turns_executed=0 for Failed Tasks

per_task.jsonl reports:
- ExpenseAddSingle: `turns_executed: 0, tool_calls: 0`
- FilesMoveFile: `turns_executed: 0, tool_calls: 0`

But trace artifacts show 26 and 29 turns respectively. This is a reporting bug in the eval bridge/runner - likely the counter isn't being updated for tasks where `agent_completion_reason: null`.

---

## Action Debug Assessment

**Not warranted for this run.** Both failures stem from LLM cognition issues:
- Wrong UX planning (FilesMoveFile)
- Tool schema confusion (both)
- Parameter naming errors (ExpenseAddSingle)
- Task understanding gaps (ExpenseAddSingle)

All action executions (click, long_press, scroll, swipe) succeeded when given valid parameters. No false success pattern detected (action_accepted matching ui_changed on all valid actions).

---

## Recommended Improvements (Priority Order)

### P0: Fix `system_button` vs `mobile_action` confusion
- Either merge system_button into mobile_action as valid action type
- Or add explicit disambiguation in system prompt
- This affected 100% of failed tasks

### P1: Investigate ExpenseAddSingle category selection
- Agent struggled with Health Care category (turn 12 error triggered during category flow)
- Need to check: does the app have "Health Care" as a category? Is the UX path discoverable?
- Read turn 11-13 screenshots/a11y trees to understand what the agent saw

### P2: Improve Files app file-move strategy
- Add app-specific guidance for file operations in Files/DocumentsUI
- Correct approach: long_press to select → use action bar (Cut/Move) → navigate to destination → Paste
- The "More options" in toolbar is NOT the per-file context menu

### P3: Fix per_task.jsonl turns_executed reporting
- Eval harness bug: turns_executed=0 for completed-but-failed tasks
- Check runner.py / bridge code for where this counter is populated

### P3: Add parameter validation hints in system prompt
- Explicitly list required parameters: `element_index` (not `index`)
- Or add parameter aliases in tool schema validation
