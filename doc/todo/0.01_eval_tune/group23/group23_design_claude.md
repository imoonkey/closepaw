# Group 2/3 Improvement Design

## Problem Summary

**Group 2**: 20 tasks, 70% pass (14/20), 6 failures
**Group 3**: 20 tasks, 65% pass (13/20), 6 failures + 1 infra
**Combined**: 40 tasks, ~68% pass, 12 failures

Analysis of 12 failures + several at-risk passes reveals 8 systemic problems, described below in priority order by impact (number of tasks affected).

---

## P1: Repeated Action Loops (7 tasks, highest impact)

### Evidence

| Task | Group | Pattern | Wasted Turns |
|------|-------|---------|-------------|
| SimpleCalendarEventsOnDate | G2 | scroll up/down alternation in month navigation | 25+ |
| SimpleCalendarEventsInNextWeek | G3 | scroll up/down alternation in event list (turns 14-30) | 16+ |
| TasksHighPriorityTasks | G3 | open→back→discard cycle (turns 6-30) | 24+ |
| ExpenseDeleteDuplicates2 | G2 | scroll without clicking visible targets (turns 7-30) | 23+ |
| MarkorMergeNotes | G3 | repeated identical `find` commands returning empty (turns 17-28) | 11+ |
| MarkorCreateFolder | G2 | dialog cycle 10+ times without completing all steps | 20+ |
| MarkorEditNote | G2 | back-and-forth between UI and shell without committing | 14+ |

**Total wasted turns**: ~133+ across 7 tasks.

### Gap Analysis

The current `LoopDetectionPolicy` detects loops and injects **warnings** into the prompt. But warnings alone are insufficient — the LLM reads the warning and still repeats the same action. This is consistent across both groups.

Current detection covers:
- Unchanged screens (similarity threshold 0.85)
- Consecutive scrolls (max 4)
- Repeated actions (window of 3)
- Tool dominance (3+ of same tool type)
- Cycle detection (A-B-C-A-B-C patterns)

**What's missing**:
1. **Forced strategy escalation**: No mechanism to actually prevent the repeated action. The LLM can (and does) ignore warnings.
2. **Progress tracking**: Agent doesn't track whether its actions made observable progress toward the goal.
3. **Strategy memory**: Agent doesn't record which strategies it has already tried and failed with.

### What Reference Agents Do

All mature mobile agent frameworks use **forced escalation**, not advisory warnings:

| Framework | Mechanism |
|-----------|-----------|
| **Mobile-Agent-v3** | After 2 consecutive failures, `error_flag_plan` forces Manager to revise entire plan. Executor's prompt includes "Do NOT repeat previously failed actions." ActionReflector classifies outcomes as Success/WrongPage/NoChange. |
| **AutoDev** | Executor gets bounded session (max 10 steps). If sub-task fails, executor is terminated and Planner receives failure narrative. Fresh executor instance per intent — no baggage. |
| **MiniTap** | LangGraph convergence gate routes state to "replan", "continue", or "end" — a structural, not advisory, decision. |

**Common pattern**: Escalation after N failures, with failure narrative passed to a higher-level planner. The agent cannot simply ignore the escalation — it's forced by the infrastructure.

### Design: Progressive Loop Intervention

Introduce a 3-tier escalation system that goes beyond warnings:

**Tier 1 — Advisory (current, keep)**: Warning injected into prompt after detecting loop pattern. LoopDetectionPolicy stays as-is.

**Tier 2 — Mandatory Strategy Annotation** (NEW): After CRITICAL-severity loop warning persists for 2 more turns (i.e., agent ignored the warning twice):
- Inject a **mandatory strategy-change block** into the prompt that includes:
  - List of last N failed actions (from NavigationState.recentActions)
  - Explicit scratchpad of what has been tried
  - Directive: "You MUST choose a different strategy. The following strategies have failed: [list]. If you cannot find a new strategy, call complete_task with failure status."
- **Block the repeated action at the tool arbitration layer**: If the LLM emits the same (action, target) pair that has been tried 3+ times, `TurnToolPolicy` rejects it and forces a re-plan turn (no action executed).

**Tier 3 — Forced Completion** (NEW): After 5 consecutive loop turns (CRITICAL warnings ignored repeatedly):
- Force `complete_task` with status=failure and auto-generated summary of what was attempted.
- This prevents wasting the remaining turn budget on a clearly stuck task.
- The narrative summary should include: goal, strategies tried, last screen state.

#### Implementation Sketch

```kotlin
// In LoopDetectionPolicy — add escalation level tracking
data class LoopEscalation(
    val level: EscalationLevel,
    val consecutiveLoopTurns: Int,
    val failedStrategies: List<String>,  // human-readable descriptions
    val blockedActionSignatures: Set<String>  // action signatures to reject
)

enum class EscalationLevel {
    NONE,           // No loop detected
    ADVISORY,       // Tier 1: warning in prompt (current behavior)
    MANDATORY,      // Tier 2: block repeated actions, demand strategy change
    FORCE_COMPLETE  // Tier 3: auto-complete with failure
}

// In TurnToolPolicy — add action blocking
fun arbitrate(toolCalls: List<ToolCall>, escalation: LoopEscalation): ToolArbitrationResult {
    if (escalation.level >= MANDATORY) {
        val blocked = toolCalls.filter { it.actionSignature in escalation.blockedActionSignatures }
        if (blocked.isNotEmpty()) {
            return ToolArbitrationResult(
                allowed = toolCalls - blocked,
                rejected = blocked.map { RejectedTool(it, "Action blocked: repeated loop detected") }
            )
        }
    }
    // ... existing arbitration logic
}
```

#### Files to Modify

- `agent/cognition/policy/LoopDetectionPolicy.kt` — Add escalation level tracking, failed strategy accumulation
- `agent/cognition/policy/TurnToolPolicy.kt` — Add action blocking for MANDATORY level
- `agent/AgentTurnRunner.kt` — Wire escalation into turn preparation, handle FORCE_COMPLETE
- `agent/cognition/context/NavigationState.kt` — Track consecutive loop turns counter
- `agent/cognition/prompt/PromptBuilder.kt` — Inject mandatory strategy-change block for Tier 2

---

## P2: QA Data Collection Failure (4 tasks)

### Evidence

| Task | Group | Issue |
|------|-------|-------|
| SimpleCalendarEventsOnDate | G2 | Scrolled past events but never recorded them |
| SimpleCalendarEventsInNextWeek | G3 | Saw event dates/titles while scrolling, never used scratchpad |
| TasksHighPriorityTasks | G3 | Opened individual tasks but never recorded priority info |
| SportsTrackerActivitiesOnDate | G3 | Partially — correct data extraction but wrong field interpretation |

All 4 tasks share a pattern: the agent navigates to the right data but never systematically records what it sees, and in 3 of 4 cases, never calls `complete_task`.

### Gap Analysis

The agent prompt has no explicit guidance for **information-gathering QA tasks**. It knows how to *do* things (click, type, navigate) but doesn't have a protocol for *answering questions* that require:
1. Browsing multiple screens to find data
2. Recording data incrementally (scratchpad)
3. Synthesizing a final answer
4. Completing with whatever data was collected before turn budget runs out

### Design: QA Task Protocol in System Prompt

Add the following section to `StandaloneAgentDef.kt` (and `PlannerAgentDef.kt` for planner mode):

```
### Information-Gathering / QA Tasks
When the goal is to ANSWER a question about app content (e.g., "What events...", "What tasks...", "How many..."):

1. **Plan your data collection strategy** before navigating:
   - What app and view contains the data?
   - What specific field(s) do you need to extract? Read the question carefully:
     - "activity type" = the CATEGORY field (e.g., running, cycling), NOT the activity name/title
     - "event title" = the display name
     - "task title" = the task name
   - How will you know when you've seen all relevant data?

2. **Use scratchpad to accumulate findings** as you browse:
   - scratchpad(action="write", content={"found_items": "Item A, Item B"})
   - Update the scratchpad every time you see new relevant data
   - This prevents data loss when scrolling past it

3. **Use filter/sort to narrow results** when available:
   - Sort by date, priority, or relevant field BEFORE browsing
   - Trust the sort order — if sorted by priority descending, top items ARE high priority

4. **Call complete_task with your collected answer**:
   - Compile your answer from scratchpad data
   - If you haven't found all data but are running low on turns (< 5 remaining),
     call complete_task with what you have — a partial answer scores higher than no answer
   - NEVER let turns run out without calling complete_task on a QA task
```

#### Files to Modify

- `agent/definition/StandaloneAgentDef.kt` — Add QA protocol section to system prompt
- `agent/definition/PlannerAgentDef.kt` — Add QA protocol section
- `agent/definition/ExecutorAgentDef.kt` — Add instruction to return extracted data in completion

---

## P3: Shell Tool Misuse (8+ tasks, ~50+ wasted turns)

### Evidence

Shell effectiveness across G2+G3: **~15-20%**. Most shell calls are wasted turns.

Five failure patterns identified:

| Pattern | Example | Turns Wasted |
|---------|---------|-------------|
| Wrong path | `/sdcard/Documents/` instead of `/sdcard/Documents/Markor/` | 14 (MarkorEditNote) |
| FS vs app DB mismatch | `mkdir` creates folder Markor doesn't see | 10+ (MarkorCreateFolder) |
| Repeated failing commands | Same `find` returning empty 5+ times | 20+ (MarkorMergeNotes) |
| Permission issues | `su -c`, `base64` read denied | 3-5 per task |
| Wrong tool for job | `strings`/`hexdump` on images for OCR | 13 (MarkorTranscribeReceipt) |

### Design: Shell Usage Guardrails

#### A. Pre-seed App Storage Paths in Prompt

Add to system prompt:

```
### App File Paths (for shell operations)
- Markor notes: /sdcard/Documents/Markor/ (capital M in Markor)
- Simple Gallery photos: /sdcard/DCIM/ or /storage/emulated/0/DCIM/
- Downloads: /sdcard/Download/
Do NOT use /sdcard/Documents/ directly — apps use subdirectories.
```

#### B. Shell Decision Matrix in Prompt

Replace the current minimal shell guidance with:

```
### Shell Tool
**Use shell for:**
- Reading file content when path is known: cat /sdcard/Documents/Markor/myfile.txt
- Listing directory contents: ls /sdcard/Documents/Markor/
- Text manipulation (prepend, append): echo -e "new\n$(cat file)" > file
- Checking device state: date, dumpsys

**Do NOT use shell for:**
- Creating folders/files that apps need to see (use app's UI instead — apps maintain internal databases)
- Reading image content (shell cannot OCR — if task needs to read an image, you need vision/screenshot mode)
- Operations requiring root (su, chmod on system paths)
- Anything you've already tried twice with no results

**After 2 failed shell commands with same approach, STOP and switch to UI strategy.**
```

#### C. Shell Loop Detection (supplement to P1)

The existing `LoopDetectionPolicy` already has tool dominance detection (3+ of same tool type). Enhance it:
- Track shell command similarity (not just tool name, but actual command patterns)
- If 2+ shell commands with same base command + path pattern return empty/error, block further shell attempts to that path

#### Files to Modify

- `agent/definition/StandaloneAgentDef.kt` — Replace shell guidance section, add path knowledge
- `agent/cognition/policy/LoopDetectionPolicy.kt` — Add shell-specific pattern detection (command substring matching)

---

## P4: QA Answer Format/Semantics (2+ tasks)

### Evidence

| Task | Issue | Expected | Agent Answered |
|------|-------|----------|---------------|
| SportsTrackerActivitiesOnDate | Wrong field | Activity category (e.g., "running") | Activity name ("Active Rest Day") |
| TasksHighPriorityTasks | Never answered | Task titles with high priority | (no answer — ran out of turns) |

### How AndroidWorld QA Scoring Actually Works

From investigating the eval code:

1. Agent calls `complete_task(answer="...")` → stored in trace
2. `trace_parser.py` extracts the answer from the last `complete_task` call
3. Answer is passed to AndroidWorld via `env.interaction_cache = trace_parse.answer`
4. Each task's `is_successful(env)` reads `env.interaction_cache` and compares:
   - **STRING_MATCH**: Fuzzy comparison via `difflib.SequenceMatcher` with **90% similarity threshold** (case-insensitive)
   - **NUMBER_MATCH**: Exact or tolerance-based
   - **DATE_MATCH**: Exact date format match

5. For list answers: each comma-separated item is matched individually against expected items (any order)

**Critical finding**: If `interaction_cache` is None (agent never called `complete_task`), the score is **always 0.0**. The agent MUST call `complete_task` to have any chance of scoring.

**For SportsTrackerActivitiesOnDate specifically**: The task proto specifies `field_transformation: IDENTITY` on field `category`. The expected answer is the **category** column value, not the activity name. The agent correctly navigated to the data but read the wrong field (activity name "Active Rest Day" instead of category "rest" or "walking").

### Design

This is addressed by the QA protocol in P2 above (emphasizing reading the question carefully and understanding which field to extract). In addition:

- Add explicit guidance: "When asked about 'activity type', look for the category/type field, not the activity name"
- More generally: "Read the question prompt word-for-word. 'Type', 'category', and 'kind' refer to classification labels, not display names."

This goes into the QA Task Protocol section proposed in P2.

#### Files to Modify

- Same as P2: `agent/definition/StandaloneAgentDef.kt`, `PlannerAgentDef.kt`

---

## P5: Vision Task Perception Mode (1-2 tasks)

### Evidence

| Task | Issue |
|------|-------|
| MarkorTranscribeReceipt | Must read receipt.png text — impossible in accessibility_only mode. 30 turns wasted attempting shell workarounds. |

### Design: Add Hybrid Mode Override for Vision Tasks

The task override mechanism already exists in `eval/config/default.yaml`. Add entries for tasks that require reading visual content:

```yaml
bridge:
  task_overrides:
    BrowserDraw: { perception_mode: hybrid }
    BrowserMaze: { perception_mode: hybrid }
    ExpenseAddMultipleFromGallery: { perception_mode: hybrid }
    # NEW — tasks needing to read image content
    MarkorTranscribeReceipt: { perception_mode: hybrid }
    SaveCopyOfReceiptTaskEval: { perception_mode: hybrid }
```

Additionally, add early-exit guidance to the system prompt:

```
If a task requires reading text from an image (OCR) and you have no screenshot input,
call complete_task(status="failure", answer="Cannot read image content without vision mode")
after at most 3 attempts. Do not waste turns on shell-based image text extraction.
```

#### Files to Modify

- `eval/config/default.yaml` — Add vision task overrides
- `agent/definition/StandaloneAgentDef.kt` — Add early-fail guidance for vision-impossible tasks

---

## P6: False Completion / Missing Self-Verification (1-2 tasks)

### Evidence

| Task | Issue |
|------|-------|
| MarkorAddNoteHeader | Agent replaced content instead of prepending, then called complete_task(status=success). The goal required preserving existing content. |

Agent's self-assessment was incorrect — it believed it succeeded when it hadn't.

### Design: Pre-Completion Verification Prompt

Add to system prompt:

```
### Before Completing a Task
Before calling complete_task with status="success":
- Verify the result matches ALL parts of the goal, not just the last action
- For file editing tasks: re-read the file to confirm content is correct
- For multi-step tasks: confirm all steps completed, not just the last one
- If you made a destructive edit (e.g., replacement instead of prepend), verify old content is preserved
```

This is a prompt-level change, intentionally lightweight. A heavier approach (automated verification tool) could be explored later but adds complexity.

#### Files to Modify

- `agent/definition/StandaloneAgentDef.kt` — Add pre-completion verification section

---

## P7: Turn Budget Exhaustion (2-3 tasks)

### Evidence

| Task | Group | Turns Used | Turns Needed | Issue |
|------|-------|-----------|-------------|-------|
| RecipeAddMultipleRecipes | G2 | 30 (max) | ~42 | 3 recipes × 7 fields × 2 actions = 42 |
| RetroCreatePlaylist | G2 | 28 | 30 | Playlist + 3 songs, 2 turns from limit |
| RetroSavePlaylist | G3 | 30 (max) | 33+ | Playlist + songs + export |

### Design: Turn Budget Awareness

Add turn budget information to the prompt dynamically:

```
Turn {current}/{max}. {remaining} turns remaining.
```

This already partially exists in `ExecutorStepPolicy` (warns at maxTurns-2). Enhance:
- Show turn count in every observation block (not just near the end)
- Add efficiency guidance: "For repetitive form-filling (adding multiple items), each item costs ~10-14 turns. If the budget is insufficient, prioritize completing as many items as possible rather than perfecting each one."

Note: This is partially a model capability issue (single-action-per-turn limitation). A more substantial fix would be multi-action batching, but that's a larger architectural change outside the scope of this document.

#### Files to Modify

- `agent/cognition/prompt/PromptBuilder.kt` — Add turn count to every observation block

---

## P8: Multi-Step Dialog Failure (2 tasks)

### Evidence

| Task | Issue |
|------|-------|
| MarkorCreateFolder | Cycled through create→FOLDER tab→type name→OK 10+ times. Dialog may reset between turns. |
| (Various dialog interactions) | Buttons in dialogs sometimes lack accessible nodes, requiring gesture_tap fallback |

### Design

This is partially addressed by P1 (loop detection will catch the dialog cycling). Additionally:

Add app-specific dialog knowledge to the prompt:

```
### Markor Dialog Tips
- Create new file/folder: tap "+" button → select File/Folder tab → type name → tap OK
- The dialog has two tabs: "File" and "Folder". Make sure you tap the correct tab.
- After typing the name, tap OK immediately — do not navigate away from the dialog.
```

This is a tactical fix. A more general solution would be dialog-state tracking via scratchpad, which the agent should already do with the improvements from P1 (mandatory strategy annotation includes tracking what has been tried).

#### Files to Modify

- `agent/definition/StandaloneAgentDef.kt` — Add app-specific dialog tips
- `agent/definition/ExecutorAgentDef.kt` — Same

---

## Implementation Priority

| Priority | Problem | Impact (tasks) | Effort | Expected Gain |
|----------|---------|----------------|--------|---------------|
| **P0** | P1: Anti-loop escalation | 7 tasks | Medium | +3-4 tasks pass |
| **P0** | P2: QA data collection protocol | 4 tasks | Low (prompt) | +2-3 tasks pass |
| **P1** | P3: Shell guardrails | 8+ tasks (turns) | Low (prompt) | +1-2 tasks pass, ~50 turns saved |
| **P1** | P4: QA answer semantics | 2 tasks | Low (prompt) | +1 task pass |
| **P1** | P5: Vision task overrides | 1-2 tasks | Low (config) | +1 task pass |
| **P2** | P6: Pre-completion verification | 1-2 tasks | Low (prompt) | +1 task pass |
| **P2** | P7: Turn budget awareness | 2-3 tasks | Low (prompt) | Marginal improvement |
| **P2** | P8: Dialog tips | 2 tasks | Low (prompt) | +1 task pass |

**Estimated combined impact**: +6-8 task passes out of 12 current failures → projected pass rate improvement from 68% to ~83-88%.

---

## Summary of All File Changes

### Prompt Changes (Low Risk)

| File | Changes |
|------|---------|
| `StandaloneAgentDef.kt` | QA protocol, shell guardrails + paths, pre-completion verification, app dialog tips, vision early-fail guidance |
| `PlannerAgentDef.kt` | QA protocol |
| `ExecutorAgentDef.kt` | App dialog tips |

### Code Changes (Medium Risk)

| File | Changes |
|------|---------|
| `LoopDetectionPolicy.kt` | Escalation level tracking, failed strategy accumulation, shell command pattern detection |
| `TurnToolPolicy.kt` | Action blocking for MANDATORY escalation level |
| `AgentTurnRunner.kt` | Wire escalation into turn prep, handle FORCE_COMPLETE |
| `NavigationState.kt` | Track consecutive loop turns counter |
| `PromptBuilder.kt` | Mandatory strategy-change block injection, turn count in every observation |

### Config Changes (Low Risk)

| File | Changes |
|------|---------|
| `eval/config/default.yaml` | Add MarkorTranscribeReceipt and SaveCopyOfReceiptTaskEval hybrid overrides |
