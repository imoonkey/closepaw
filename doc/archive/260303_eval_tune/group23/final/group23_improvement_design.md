# Group 2/3 Eval Improvement Design

## Context

**Group 2**: 20 tasks, 70% pass (14/20), 6 failures
**Group 3**: 20 tasks, ~62% pass (13/21), 6 failures + 1 infra
**Combined**: ~67% pass, 12 failures (10 of which are MaxTurnsReached)

This document is the aligned design for fixing the systemic problems identified across both groups. Changes are organized by priority.

---

## 1. Anti-Loop Escalation (7 tasks affected, highest impact)

### Problem

10/12 failures end with MaxTurnsReached. The agent repeats the same failing actions (scroll cycles, dialog loops, identical shell commands) for 15-25 turns despite loop warnings being injected into the prompt. The LLM reads the warnings and ignores them.

Current `LoopDetectionPolicy` detects loops and emits warnings. `AgentTurnRunner.prepareTurn()` injects warnings as text into the observation. That's the end of it — the agent can (and does) emit the same action again.

Affected tasks: SimpleCalendarEventsOnDate, SimpleCalendarEventsInNextWeek, TasksHighPriorityTasks, ExpenseDeleteDuplicates2, MarkorMergeNotes, MarkorCreateFolder, RetroSavePlaylist.

### Design

Upgrade from advisory-only to a **3-tier escalation** that uses existing infrastructure (NavigationState, ScreenSignature similarity, action signatures):

**Tier 1 — Advisory (existing, keep as-is)**: `LoopDetectionPolicy.detect()` returns `LoopWarning` with CRITICAL/WARNING severity. Warning text is injected into the observation by `buildWarnings()`. No code changes here.

**Tier 2 — Block + Demand Strategy Change**: When the agent ignores CRITICAL-level warnings for 2 consecutive turns:
- **Block the repeated action**: `TurnToolPolicy.arbitrateToolCalls()` rejects tool calls whose action signature matches any in the blocked set. The blocked set is populated from `NavigationState.recentActions` entries that recurred during the loop.
- **Inject stronger prompt directive**: Replace the warning text with a mandatory strategy-change block listing failed actions and demanding a different approach.
- Progress detection: leverage existing `ScreenSignature.similarityTo()` — if similarity between pre-turn and previous-turn signature is >= 0.85, the action made NO_CHANGE. No new classification layer needed.

**Tier 3 — Forced Failure Completion Call**: After 5 consecutive loop turns (all with CRITICAL warnings), inject a synthetic `complete_task(status="failure", answer="...")` tool call with a narrative summary (reuse `ExecutorStepPolicy.buildNarrativeSummary()` pattern).  
Important: do **not** directly return `TurnOutcome.Complete` from loop policy, because runtime maps that to `GoalAchieved`.

### State Tracking

Add to `NavigationState`:

```kotlin
data class NavigationState(
    val recentSignatures: List<ScreenSignature> = emptyList(),
    val consecutiveScrollActions: Int = 0,
    val recentActions: List<String> = emptyList(),
    // NEW
    val consecutiveLoopTurns: Int = 0,
    val blockedActions: Set<String> = emptySet()
)
```

In `LoopDetectionPolicy`, return an escalation level alongside the warning:

```kotlin
data class LoopDetectionResult(
    val warning: LoopWarning?,
    val escalation: EscalationLevel
)

enum class EscalationLevel { NONE, ADVISORY, BLOCK, FORCE_COMPLETE }
```

Escalation logic (inside `LoopDetectionPolicy`):
- ADVISORY: current behavior (any loop heuristic triggers)
- BLOCK: `consecutiveLoopTurns >= 2` AND current warning is CRITICAL
- FORCE_COMPLETE: `consecutiveLoopTurns >= 5`

`consecutiveLoopTurns` increments when a CRITICAL warning fires and screen similarity >= 0.85 (no progress). Resets to 0 when similarity drops below threshold (genuine screen change).

### Action Blocking in TurnToolPolicy

```kotlin
fun arbitrateToolCalls(
    toolCalls: List<ToolCallRequest>,
    blockedActions: Set<String> = emptySet()
): ToolArbitrationResult {
    // Before existing logic: filter out blocked actions
    val unblocked = if (blockedActions.isNotEmpty()) {
        toolCalls.filter { classifyAction(it) !in blockedActions }
    } else {
        toolCalls
    }
    // ... existing arbitration on unblocked list
}
```

### Wiring in AgentTurnRunner

`prepareTurn()` already calls `loopDetectionPolicy.detect()` and builds warnings. Extend it:
1. Compute `LoopDetectionResult` (warning + escalation).
2. Update `NavigationState.consecutiveLoopTurns` and `blockedActions`.
3. If BLOCK: pass `blockedActions` to `planningPhaseRunner` → `TurnToolPolicy`.
4. If FORCE_COMPLETE: skip LLM planning for this turn, execute a synthetic `complete_task(status="failure")` call via normal execution path, then finish.

### Files to Modify

- `NavigationState.kt` — Add `consecutiveLoopTurns`, `blockedActions`
- `LoopDetectionPolicy.kt` — Return `LoopDetectionResult` with escalation level
- `TurnToolPolicy.kt` — Accept `blockedActions` parameter in `arbitrateToolCalls()`
- `AgentTurnRunner.kt` — Wire escalation into `prepareTurn()`, handle FORCE_COMPLETE

---

## 2. QA Data Collection Protocol (4 tasks affected)

### Problem

For information-gathering tasks ("What events...", "What tasks..."), the agent navigates to the right data but never records it. In 3/4 cases, the agent never calls `complete_task`, so `interaction_cache` is None and the score is automatically 0.0.

Additionally, SportsTrackerActivitiesOnDate: the agent read the activity **name** instead of the **category** field. AndroidWorld's eval expects comma-separated values fuzzy-matched at 90% similarity against expected field values.

Affected tasks: SimpleCalendarEventsOnDate, SimpleCalendarEventsInNextWeek, TasksHighPriorityTasks, SportsTrackerActivitiesOnDate.

### Design

Add a QA protocol section to the system prompt in `StandaloneAgentDef.kt`:

```
### Information-Gathering / QA Tasks
When the goal asks you to ANSWER a question about app content (e.g., "What events...", "What tasks...", "How many..."):

1. **Identify the exact field** the question asks about:
   - "activity type" or "what activities" = the CATEGORY/TYPE label (e.g., "running"), NOT the display name
   - "event title" = the title/name field
   - "how many" = count the items and answer with a single number

2. **Use scratchpad to accumulate findings** as you browse:
   - scratchpad(action="write", content={"found_items": "Item A, Item B"})
   - Update every time you see new relevant data on screen

3. **Call complete_task with your collected answer**:
   - Format: comma-separated list matching the goal's requested format
   - If running low on turns (< 5 remaining), call complete_task with what you have — partial answer > no answer
   - NEVER let turns run out without calling complete_task on a QA task
```

### Files to Modify

- `StandaloneAgentDef.kt` — Add QA protocol section to `systemPrompt`

---

## 3. Shell Guardrails (8+ tasks, ~50 wasted turns)

### Problem

Shell effectiveness ~15-20%. Five failure patterns: wrong paths, filesystem-vs-app-DB mismatch, repeated failing commands, permission errors, wrong tool for job (e.g., `strings` on images for OCR).

Shell is described as a normal tool in the prompt with minimal guardrails. Agents overuse it in Markor tasks especially, wasting 10-17 turns per task on failed shell commands.

### Design

#### A. Prompt: Replace shell guidance in StandaloneAgentDef

Replace the current 4-line shell section with:

```
### Shell Tool
**Use shell for:**
- Reading file content: cat /sdcard/Documents/Markor/myfile.txt
- Listing directories: ls /sdcard/Documents/Markor/
- Checking device state: date

**Do NOT use shell for:**
- Creating folders/files that apps need to see — apps maintain internal databases, shell-created items won't appear
- Reading image content — shell cannot OCR images
- Operations requiring root (su, chmod)
- Anything you've already tried twice with no results

**Known app storage paths:**
- Markor: /sdcard/Documents/Markor/
- Downloads: /sdcard/Download/

**After 2 failed shell commands, switch to UI strategy.**
```

#### B. Runtime: Shell-Specific Loop Detection

Enhance `LoopDetectionPolicy` to detect shell command repetition. Add to the existing `detect()` method:
- Track recent shell commands (not just tool name, but command content)
- If 2+ shell commands with similar base command + path return empty/error, contribute to loop escalation

This is a natural extension of the existing tool dominance detection (which already counts `shell` as a tool type). The enhancement compares shell command content, not just tool name.

**OPEN QUESTION**: Codex proposes a hard shell budget (3 per task) enforced at runtime. Claude proposes pattern-based blocking (block after 2 same-pattern failures). **See Discussion section below.**

### Files to Modify

- `StandaloneAgentDef.kt` — Replace shell section in prompt
- `LoopDetectionPolicy.kt` — Add shell command content tracking to existing detection

---

## 4. Vision Task Overrides (1-2 tasks)

### Problem

MarkorTranscribeReceipt requires reading text from `receipt.png`. In `accessibility_only` mode, the agent cannot see image content. 30 turns wasted on futile shell workarounds.

### Design

Add to `eval/config/default.yaml`:

```yaml
task_overrides:
  BrowserDraw: { perception_mode: hybrid }
  BrowserMaze: { perception_mode: hybrid }
  ExpenseAddMultipleFromGallery: { perception_mode: hybrid }
  # NEW
  MarkorTranscribeReceipt: { perception_mode: hybrid }
```

Add early-exit guidance to system prompt:

```
If a task requires reading text from an image and you have no screenshot input,
call complete_task(status="failure", answer="Cannot read image without vision mode")
after at most 2 failed attempts. Do not waste turns on shell-based image extraction.
```

### Files to Modify

- `eval/config/default.yaml` — Add `MarkorTranscribeReceipt` override
- `StandaloneAgentDef.kt` — Add early-fail guidance

---

## 5. Pre-Completion Verification (1-2 tasks)

### Problem

MarkorAddNoteHeader: agent replaced content instead of prepending, then declared success. The prompt says "verify EACH requirement" but this is buried in the `complete_task` section and not prominent enough.

### Design

Strengthen the existing `complete_task` guidance in the prompt:

```
### complete_task
- Before calling complete_task(status="success"), re-read the original goal and verify EACH requirement.
- For text editing tasks: re-read the file/note to confirm both new AND old content are present.
- For multi-step tasks: verify all steps, not just the last one.
- If unsure, use shell to read the file content and confirm before completing.
```

This replaces the current 2-line `complete_task` guidance in `StandaloneAgentDef.kt`.

### Files to Modify

- `StandaloneAgentDef.kt` — Expand `complete_task` section

---

## 6. Turn Budget Visibility (2-3 tasks)

### Problem

RecipeAddMultipleRecipes needs ~42 turns (30 available). RetroSavePlaylist needs ~33 turns. The agent doesn't know its turn budget until the final-turn warning at turn 28+.

### Design

Add turn count to every observation in `PromptBuilder.buildObservationText()`:

```kotlin
// At the top of the observation text, after warnings:
appendLine("Turn $turnNumber/$maxTurns")
```

This requires passing `turnNumber` and `maxTurns` to `PromptBuilder.buildInputItems()`. Both are already available in `AgentTurnRunner.executeTurn()`.

### Files to Modify

- `PromptBuilder.kt` — Add turn counter to observation text
- `AgentTurnRunner.kt` — Pass turn info to prompt builder

---

## Discussion / Open Questions

### Q1: Shell Budget — Hard Cap vs Pattern-Based?

**Codex position**: Hard cap of 3 shell calls per task, enforced at runtime. Exceeding the cap blocks the tool call and injects a warning.

**Claude position**: Pattern-based detection — block shell after 2 failed commands with similar pattern. No hard cap.

**Analysis**: A hard cap is simpler but can punish legitimate multi-shell workflows (e.g., `ls` to find path, then `cat` to read file, then `cat` to verify — that's 3 calls for a valid workflow). Pattern-based is more surgical but harder to implement reliably.

**Proposed resolution**: Use **pattern-based blocking as primary control**, and add a **high safety ceiling** (e.g., max 6 shell calls per task) as a fail-safe only.  
Rationale: pattern-based blocking avoids penalizing legitimate short shell workflows, while a high ceiling guarantees bounded damage when pattern detection misses.

### Q2: History Compression During Loops

**Codex proposes**: When loop is detected, compress recent failed action sequences into a 3-5 line summary to reduce "inertia pollution" from 80+ history items.

**Claude position**: Not addressed.

**Analysis**: This is a real issue — long failure histories reinforce the failed strategy. However, the anti-loop escalation (Section 1) addresses the root cause: if we block repeated actions at Tier 2 and force-complete at Tier 3, the history never grows to 80+ items of the same failure.

**Proposed resolution**: Defer history compression. If Tier 2/3 escalation works, the problem is eliminated at the source. If eval results still show history-driven inertia even with escalation, revisit.

### Q3: Eval/Trace Observability

**Codex proposes**: Add trace fields for perception mode, loop detector hit count, shell budget state, max_same_action_streak. Add these to per-task analysis scripts.

**Analysis**: Good idea, low risk, helps future debugging. But it's orthogonal to the core improvements and can be done independently.

**Proposed resolution**: Track as a separate follow-up task. Not blocking for the main improvements.

---

## Implementation Order

1. **Config** (5 min): `eval/config/default.yaml` — Add `MarkorTranscribeReceipt` hybrid override
2. **Prompt changes** (30 min): `StandaloneAgentDef.kt` — QA protocol, shell guardrails, pre-completion verification, vision early-fail
3. **Turn budget visibility** (15 min): `PromptBuilder.kt` + `AgentTurnRunner.kt` — Turn counter in observations
4. **Anti-loop escalation** (core work): `NavigationState.kt`, `LoopDetectionPolicy.kt`, `TurnToolPolicy.kt`, `AgentTurnRunner.kt`
5. **Shell loop detection** (extension of #4): `LoopDetectionPolicy.kt` — Shell command content tracking

## Verification

Minimum validation set (one task per problem category):
- `MarkorTranscribeReceipt` — Vision override (#4)
- `TasksHighPriorityTasks` — Loop escalation (#1) + QA protocol (#2)
- `SimpleCalendarEventsInNextWeek` — Loop escalation (#1) + QA protocol (#2)
- `MarkorMergeNotes` — Shell guardrails (#3) + loop escalation (#1)
- `SportsTrackerActivitiesOnDate` — QA field semantics (#2)

Target: Group 2+3 scripted success rate from ~67% to ~83%+ (at least +6 task passes out of 12 failures).
