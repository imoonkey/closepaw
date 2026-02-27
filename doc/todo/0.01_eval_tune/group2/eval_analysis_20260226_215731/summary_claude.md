# Eval Group 2 Summary Analysis

**Run**: `20260226_215731`
**Model**: qwen3.5
**Tasks**: 20 (new tasks not in core or group_1)
**Pass Rate**: 14/20 = 70%
**Total Duration**: ~4,200s across all tasks

## Results Overview

| # | Task | Result | Turns | Failure Root Cause |
|---|------|--------|-------|--------------------|
| 1 | ExpenseDeleteDuplicates2 | FAIL | 30/30 | Reasoning + Execution gap |
| 2 | ExpenseDeleteMultiple | PASS | 12 | - |
| 3 | ExpenseDeleteSingle | PASS | 5 | - |
| 4 | FilesDeleteFile | PASS | 27 | - |
| 5 | MarkorAddNoteHeader | FAIL | 26 | Reasoning + Execution gap |
| 6 | MarkorChangeNoteContent | PASS | 17 | - |
| 7 | MarkorCreateFolder | FAIL | 30/30 | Execution gap |
| 8 | MarkorEditNote | FAIL | 30/30 | Execution + Context gap |
| 9 | OpenAppTaskEval | PASS | 3 | - |
| 10 | RecipeAddMultipleRecipes | FAIL | 30/30 | Orchestration + Turn budget gap |
| 11 | RecipeDeleteSingleRecipe | PASS | 6 | - |
| 12 | RetroCreatePlaylist | PASS | 28 | - (at-risk: 2 turns from limit) |
| 13 | SimpleCalendarEventsOnDate | FAIL | 30/30 | Navigation + Reasoning gap |
| 14 | SimpleCalendarNextEvent | PASS | 6 | - |
| 15 | SimpleSmsReply | PASS | 7 | - |
| 16 | SimpleSmsSendClipboardContent | PASS | 12 | - |
| 17 | SystemBluetoothTurnOff | PASS | 6 | - |
| 18 | SystemCopyToClipboard | PASS | 16 | - |
| 19 | TasksDueOnDate | PASS | 3 | - |
| 20 | TurnOffWifiAndTurnOnBluetooth | PASS | 8 | - |

## Turn Efficiency Distribution

- **Optimal (3 turns)**: OpenAppTaskEval, TasksDueOnDate
- **Efficient (5-8 turns)**: ExpenseDeleteSingle, RecipeDeleteSingleRecipe, SimpleCalendarNextEvent, SystemBluetoothTurnOff, SimpleSmsReply, TurnOffWifiAndTurnOnBluetooth
- **Moderate (12-17 turns)**: ExpenseDeleteMultiple, SimpleSmsSendClipboardContent, SystemCopyToClipboard, MarkorChangeNoteContent
- **High but passing (27-28 turns)**: FilesDeleteFile, RetroCreatePlaylist (at-risk)
- **MaxTurnsReached (30/30)**: ExpenseDeleteDuplicates2, MarkorCreateFolder, MarkorEditNote, RecipeAddMultipleRecipes, SimpleCalendarEventsOnDate

Average turns for PASS tasks: 10.4. All 5 MaxTurnsReached failures consumed the full 30-turn budget. MarkorAddNoteHeader is a special case: agent declared GoalAchieved at turn 26 but the scripted evaluator scored 0 (false completion).

---

## Common Failure Patterns

### Pattern 1: Text Prepend/Insert Operations (3 tasks affected)

**Tasks**: MarkorAddNoteHeader (FAIL), MarkorEditNote (FAIL), [MarkorChangeNoteContent (PASS, but only because it was replace-all, not insert)]

**Problem**: The agent cannot reliably insert text at specific positions in existing content. When tasked with "add text before existing content," the agent either replaces all content (MarkorAddNoteHeader) or cannot position the cursor correctly (MarkorEditNote). This is a fundamental limitation of the single-action-per-turn model combined with text editor accessibility challenges.

**Contrast**: MarkorChangeNoteContent (PASS, 17 turns) succeeded because the task was to replace content entirely, which doesn't require cursor positioning. This confirms that the failure is specifically about text *insertion at specific positions*, not text editing in general.

**Impact**: Any task requiring prepend, append-at-position, or insert-between will likely fail.

### Pattern 2: Scroll Loop Stuckness (2 tasks affected)

**Tasks**: ExpenseDeleteDuplicates2 (FAIL), [FilesDeleteFile (PASS, 27 turns -- slow due to navigation)]

**Problem**: When the agent needs to find a specific item in a scrollable list, it enters a repetitive scroll-up/scroll-down loop without clicking on the target even when it's visible on screen. In ExpenseDeleteDuplicates2, the agent correctly identified duplicates at turn 7 via scratchpad analysis but spent 23 more turns unable to act on them. The agent scrolls past targets without matching what's visible in the accessibility tree to its plan.

**Contributing factors**:
- No loop detection: agent doesn't recognize when it's repeating the same action with no state change
- No element-to-plan matching: agent doesn't systematically compare visible elements against its known targets
- Late adoption of filtering: agent tried the filter feature at turn 29 (1 turn remaining) instead of using it early

### Pattern 3: Multi-Step Dialog Execution Failure (2 tasks affected)

**Tasks**: MarkorCreateFolder (FAIL), MarkorEditNote (FAIL)

**Problem**: Some app operations require a precise sequence of actions within a dialog (e.g., create button -> FOLDER tab -> type name -> OK). The agent breaks this sequence because each step is a separate turn, and the dialog state may shift between turns. In MarkorCreateFolder, the agent cycled through the create dialog 10+ times without completing all 4 steps in sequence.

**Contributing factors**:
- No dialog-state tracking: agent doesn't record which step of the dialog it completed
- Dialog may dismiss or reset between turns
- Agent restarts the sequence from scratch rather than continuing from where it left off

### Pattern 4: Turn Budget Exhaustion for Complex Tasks (2 tasks affected)

**Tasks**: RecipeAddMultipleRecipes (FAIL), RetroCreatePlaylist (PASS at 28 turns, at-risk)

**Problem**: Tasks requiring repetitive form-filling across multiple items consume turns at a rate of ~2 turns per field (click to focus + type). RecipeAddMultipleRecipes needed ~42 turns for 3 recipes (7 fields x 2 actions x 3 = 42), far exceeding the 30-turn budget. RetroCreatePlaylist barely passed at 28 turns. The agent executed perfectly with zero wasted turns -- this is purely a budget constraint.

**Root cause**: The single-action-per-turn architecture makes form-filling inherently expensive. Each text field requires a minimum of 2 actions (click + type), and complex forms multiply this quickly.

### Pattern 5: Calendar/Date Navigation (1 task affected)

**Tasks**: SimpleCalendarEventsOnDate (FAIL)

**Problem**: Navigating backward through months in Simple Calendar Pro proved impossible within 30 turns. The agent could not determine the correct UI path (swipe or arrow buttons) to go from the current month to October 2023. The calendar's accessibility tree makes date cells ambiguous.

**Contrast**: SimpleCalendarNextEvent (PASS, 6 turns) required no backward navigation, only viewing current/upcoming events. This shows the failure is specifically about backward date navigation, not calendar interaction in general.

### Pattern 6: False Completion / Self-Evaluation Failure (1 task affected)

**Tasks**: MarkorAddNoteHeader (FAIL -- GoalAchieved but scripted_score=0)

**Problem**: The agent declared the task complete despite not meeting the core requirement (prepending text). It replaced content instead of prepending, then submitted GoalAchieved. This is the most concerning failure type because the agent's self-assessment was wrong -- it believed it succeeded.

---

## Cross-Cutting Observations

### 1. Shell vs UI Strategy Selection

The agent sometimes attempts shell commands as fallbacks but uses them inconsistently:
- **MarkorCreateFolder**: Tried `mkdir -p` but Markor doesn't recognize filesystem-created folders
- **MarkorEditNote**: Tried shell editing but couldn't find the correct file path
- **MarkorAddNoteHeader**: Used `cat` to read the file (correct) but didn't use shell to construct the prepended content (missed opportunity)

Shell commands are powerful for content manipulation but require knowing the app's storage paths and whether the app's internal state will reflect filesystem changes.

### 2. Tool API Confusion

In ExpenseDeleteDuplicates2 (turn 20), the agent tried to use `system_button: back` as a parameter inside the `mobile_action` tool, causing a validation error. This shows the agent sometimes confuses the parameters of separate tools, particularly `mobile_action` vs `system_button`.

### 3. Scratchpad Underutilization

The scratchpad was underused for tracking intermediate state:
- ExpenseDeleteDuplicates2: Identified duplicates in scratchpad but didn't use it to track visible elements for matching
- MarkorCreateFolder: Didn't track which step of the dialog flow was completed
- SimpleCalendarEventsOnDate: Didn't record which navigation approaches were tried and failed

### 4. Recovery Strategy Gaps

When an approach isn't working, the agent tends to retry the same strategy repeatedly rather than pivoting:
- MarkorCreateFolder: Looped through the create dialog 10+ times
- SimpleCalendarEventsOnDate: Kept trying similar navigation approaches for 30 turns
- ExpenseDeleteDuplicates2: Scroll loop for 23 turns without trying filter until turn 29

---

## Recommendations

### High Priority (addresses multiple failures)

#### R1: Text Prepend via Shell

For tasks requiring text insertion at specific positions (prepend, insert), use shell commands instead of UI text editing:
```
# Read current content, prepend new text, write back
current=$(cat /path/to/file.txt)
echo -e "New header text\n\n$current" > /path/to/file.txt
```
**Addresses**: MarkorAddNoteHeader, MarkorEditNote
**Implementation**: Add guidance in the system prompt or app_knowledge for text manipulation tasks to prefer shell-based approaches. Include Markor's default storage path (`/sdcard/Markor/` or `/sdcard/Documents/markor/`).

#### R2: Loop Detection and Early Pivot

Add detection for when the agent is performing the same type of action repeatedly (scrolling, dialog opening) without state change. After 3-4 identical actions, force the agent to reassess and try a different strategy.

**Addresses**: ExpenseDeleteDuplicates2, MarkorCreateFolder, SimpleCalendarEventsOnDate
**Implementation**: Add a turn-aware check in the orchestration layer or in the system prompt: "If you've performed the same action type 3+ consecutive times without progress, stop and try a different approach."

#### R3: Self-Verification Before Completion

Before calling `complete_task` / GoalAchieved, the agent should verify its work against the original task requirements. For content modification tasks, read the file and confirm both new and original content are present.

**Addresses**: MarkorAddNoteHeader (false completion)
**Implementation**: Add a pre-completion verification step in the system prompt: "Before declaring success, verify the result matches all task requirements. For file modifications, re-read the file. For UI state changes, confirm the state."

### Medium Priority (addresses specific failure categories)

#### R4: App-Specific Knowledge for Complex UI Flows

Pre-seed knowledge for apps with non-obvious interaction patterns:
- **Markor**: Folder creation dialog flow (create -> FOLDER tab -> type name -> OK). Default storage: `/sdcard/Markor/`.
- **Simple Calendar Pro**: Month navigation (swipe left/right on calendar grid, or use left/right arrows near month name). Date picker (click month name to jump to date).
- **Pro Expense**: Delete mechanism (long-press to select, then delete icon). Filter/search for locating specific items.

**Addresses**: MarkorCreateFolder, SimpleCalendarEventsOnDate, ExpenseDeleteDuplicates2
**Implementation**: Build an `app_knowledge` module keyed by package name that injects relevant tips when the app is opened.

#### R5: Multi-Step Dialog State Tracking

For dialogs requiring a precise sequence of steps, the agent should use the scratchpad to track which step it has completed and what the next step is. This prevents restarting the sequence from scratch.

**Addresses**: MarkorCreateFolder, MarkorEditNote
**Implementation**: Add scratchpad guidance: "When working through a multi-step dialog, write the step sequence and mark your current step. On the next turn, read the scratchpad to know which step to perform next."

#### R6: Dynamic Turn Budget or Turn-Aware Planning

For tasks that require many repetitive actions (multi-item form filling), the agent should estimate required turns early and either:
1. Request a higher turn budget (if supported), or
2. Adopt a faster strategy (shell commands, content providers, batch operations)

**Addresses**: RecipeAddMultipleRecipes, RetroCreatePlaylist (at-risk)
**Implementation**: Add turn-budget awareness to the planning phase. If `estimated_turns > 0.7 * max_turns`, switch to shell/intent-based approaches where available.

### Lower Priority (quality-of-life improvements)

#### R7: Filter/Search-First Strategy for List Operations

When the task requires finding specific items in a scrollable list, use the app's filter or search functionality first rather than scrolling.

**Addresses**: ExpenseDeleteDuplicates2
**Implementation**: Add guidance: "If you need to find a specific item in a list, look for a search or filter button before scrolling."

#### R8: Tool API Disambiguation

Clarify the boundaries between `mobile_action` and `system_button` tools in the system prompt to prevent parameter confusion.

**Addresses**: ExpenseDeleteDuplicates2 (turn 20 tool failure)
**Implementation**: Add a brief note in tool descriptions: "system_button is a separate tool -- do not pass system_button parameters inside mobile_action."

---

## Comparison with Previous Eval Groups

| Metric | Core (14 tasks) | Group 1 (20 tasks) | Group 2 (20 tasks) |
|--------|-----------------|--------------------|--------------------|
| Pass Rate | TBD | TBD | 70% (14/20) |
| MaxTurnsReached | TBD | TBD | 5 |
| False Completion | TBD | TBD | 1 |
| Avg Turns (PASS) | TBD | TBD | 10.4 |

*(Previous group data to be filled in when available.)*

## Priority Implementation Order

1. **R1 (Text Prepend via Shell)** + **R3 (Self-Verification)** -- Quick wins that address the most frustrating failure mode (false completion + text manipulation)
2. **R2 (Loop Detection)** -- Prevents the most common waste pattern across failures
3. **R4 (App Knowledge)** -- Medium effort, high reward for specific app interactions
4. **R5 (Dialog State Tracking)** -- Scratchpad enhancement with targeted guidance
5. **R6 (Turn Budget Awareness)** -- Longer-term architecture consideration
6. **R7-R8** -- Minor prompt/doc improvements
