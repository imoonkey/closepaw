# Round 4 Eval Analysis — Common Problems

**Run ID**: `20260305_151648` | **Date**: 2026-03-05 | **Model**: qwen3.5
**Tasks**: 9 evaluated | **Pass Rate**: 1/9 (11%)

## Scorecard

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| RecipeDeleteDuplicateRecipes3 | 0.0 | 50/50 | TurnExhaustion |
| RetroPlaylistDuration | 0.0 | 45/45 | TurnExhaustion |
| SportsTrackerActivitiesCountForWeek | 0.0 | 30/30 | FalseCompletion |
| SportsTrackerLongestDistanceActivity | 1.0 | 30/30 | Success (fragile) |
| SportsTrackerTotalDistanceForCategoryOverInterval | 0.0 | 30/30 | ReasoningError (REGRESSED from 1.0) |
| TasksCompletedTasksForDate | 0.0 | 30/30 | NavigationFailure |
| TasksDueNextWeek | 0.0 | 6/30 | FalseCompletion |
| TasksHighPriorityTasksDueOnDate | 0.0 | 9/30 | ReasoningError (REGRESSED from 1.0) |
| TasksIncompleteTasksOnDate | 0.0 | 3/30 | ReasoningError |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| Success (fragile) | 1 | SportsTrackerLongestDistanceActivity |
| FalseCompletion | 2 | SportsTrackerActivitiesCountForWeek, TasksDueNextWeek |
| ReasoningError | 3 | SportsTrackerTotalDistanceForCategoryOverInterval, TasksHighPriorityTasksDueOnDate, TasksIncompleteTasksOnDate |
| NavigationFailure | 1 | TasksCompletedTasksForDate |
| TurnExhaustion | 2 | RecipeDeleteDuplicateRecipes3, RetroPlaylistDuration |

## Previous Round Comparison

| Fix Applied | Status | Impact |
|-------------|--------|--------|
| P1: Date-label tip | **Partially Working** | TasksIncompleteTasksOnDate now computes date map (R3: zero filtering → R4: correct filtering). But still 0.0 due to no scroll. |
| P1: QA scratchpad guardrail | **Partially Working** | TasksDueNextWeek used scratchpad. But agent contradicted its own scratchpad (counted 13, submitted 14). |
| P2: Shell restriction to files only | **No Regressions Observed** | Shell was restricted; agent stopped using `date` command. SportsTracker regression caused by premature shell pivot, not by restriction blocking useful commands. |
| P2: Repetitive-task guidance (write_todos) | **Counterproductive** | RecipeDeleteDuplicateRecipes3: 9 write_todos calls wasted 18% of turns (9/50) with zero action. |
| P2: Retro batch-select tip | **Partially Followed** | Agent attempted long-press selection but hit duplicate "More options" grounding error. |
| P2: max_turns=50 for Recipe | **Marginal** | 8 deletions (R4) vs ~4 (R3). Still insufficient for 10+ duplicates. |
| P2: max_turns=45 for Retro | **Ineffective** | Extra turns wasted on failed batch attempts. |

### Regressions (2)

- **TasksHighPriorityTasksDueOnDate** (1.0→0.0): qwen3.5 natural variance. Agent gave up at turn 9 (R3: persisted 21 turns). Priority radio buttons still have no a11y labels. Not caused by prompt changes.
- **SportsTrackerTotalDistanceForCategoryOverInterval** (1.0→0.0): Agent pivoted to shell after checking only 2/5 tracks (R3: checked 4+). Wasted 5 turns on failed shell `find` commands. Natural variance + premature shell pivot.

## Common Problems

### P1: Tasks App — Premature Completion Without Scroll — HIGH — 3 tasks

**Affected**: TasksDueNextWeek, TasksIncompleteTasksOnDate, TasksCompletedTasksForDate

**Root Cause**: The agent answers enumeration/count questions from a single viewport without scrolling through the full task list. TasksIncompleteTasksOnDate completed in 3 turns without any scroll. TasksDueNextWeek completed in 6 turns with one scroll. TasksCompletedTasksForDate never found the completed-tasks view at all. The date-label tip worked (agent now does date computation), but the scroll-before-answer gap is the primary remaining blocker.

**Proposed Fix**:
1. Add QA enumeration rule to system prompt: "Before answering any 'list all' or 'how many' question, scroll the entire list at least once. Record items as you go in scratchpad."
2. Add Tasks app tip for completed tasks: "To see completed tasks, open the overflow menu and look for 'Show completed' or a filter toggle."

---

### P2: Tasks App Priority Perception Gap — MED — 1 task

**Affected**: TasksHighPriorityTasksDueOnDate (REGRESSED)

**Root Cause**: The Tasks app priority radio buttons (indices 25-28 in task detail) have no a11y text labels. The agent cannot determine priority level. In R3, the agent persisted and inferred priority; in R4 it gave up after 9 turns.

**Proposed Fix**:
1. Add Tasks app tip: "Priority is shown as 4 radio buttons in the task detail view. Check the `checked` or `selected` state of each button to determine the priority level."
2. Consider adding a premature-completion guard: for QA tasks where the answer is empty/null, encourage re-verification if turns remain.

---

### P3: Premature Shell Pivot on SportsTracker — MED — 1 task

**Affected**: SportsTrackerTotalDistanceForCategoryOverInterval (REGRESSED)

**Root Cause**: Agent abandoned working UI Edit workflow after checking only 2 of 5 tracks and spent 5 turns on failed shell `find` commands. The "after 2 failed shell commands, switch to UI" rule was not followed. In R3, the agent balanced shell attempts with continued UI exploration; in R4 it over-relied on shell.

**Proposed Fix**:
1. Strengthen the 2-failed-shell-commands rule. Consider making it an enforceable limit (validate/block after 2 consecutive failed shell commands per session).
2. Add rule: "If you have a working UI approach and have not finished checking all items, complete the UI approach before trying alternatives."

---

### P4: TurnExhaustion on Structurally Hard Tasks — LOW — 2 tasks

**Affected**: RecipeDeleteDuplicateRecipes3, RetroPlaylistDuration

**Root Cause**: Both tasks are structurally beyond the model's throughput at current turn budgets. Recipe needs ~80 turns for 10+ duplicates at 4 turns/deletion. Retro has a persistent grounding issue with duplicate "More options" buttons that qwen3.5 cannot resolve. write_todos over-use wasted 18% of Recipe's budget.

**Proposed Fix**:
1. **RecipeDeleteDuplicateRecipes3**: Park. Even 50 turns insufficient. Would need batch-delete tool or 80+ turns.
2. **RetroPlaylistDuration**: Park as qwen3.5-blocked. 7 consecutive 0.0 runs, model cannot execute the multi-step batch workflow.
3. Fix write_todos waste: add to system prompt: "Always combine write_todos with a mobile_action in the same turn. Never spend a turn solely on write_todos."

---

## Next Steps

### Must Fix
1. **Scroll-before-answer rule** (P1): Add to system prompt QA section: "Before answering enumeration/count questions, scroll the entire list at least once." Targets: TasksDueNextWeek, TasksIncompleteTasksOnDate, TasksCompletedTasksForDate.
2. **Tasks completed-tasks tip** (P1): Add Tasks app tip for finding completed tasks view.
3. **Priority radio button tip** (P2): Add Tasks app tip about checking radio button checked/selected state.

### Should Fix
4. **Complete-UI-before-shell rule** (P3): Strengthen system prompt: "If a UI workflow is working, finish it before pivoting to shell." Targets: SportsTrackerTotalDistanceForCategoryOverInterval.
5. **write_todos waste prevention** (P4): Add: "Always combine write_todos with mobile_action in the same turn."
6. **Premature completion guard** (P2): For QA tasks with empty answers and remaining turns, add a re-verify prompt.

### Accept as Capability Gap
- **RecipeDeleteDuplicateRecipes3**: Park. 10+ duplicates at 4 turns/deletion exceeds any reasonable turn budget.
- **RetroPlaylistDuration**: Park as qwen3.5-blocked. 7 consecutive failures, persistent grounding error with duplicate UI elements.


# Qi Note
1. 我看了一眼 StandaloneAgentDef.kt，现在的prompt结构乱得跟屎山一样。需要仔细重构。有很多之前tuning round，为了解决一些task具体问题，往里面这塞一句，那补一句。一是逻辑不清晰，二是有些detail是overfit的。就这prompt都把agent搞糊涂了。参考 sop/adhoc/reference_analysis.md里的mobile agents，分别分析他们的system prompt段落结构，然后设计一个我的system prompt结构的refactor。另外我现在system prompt和tool prompt都和heavy，有很多重复。你看看reference里面都是怎么处理这个问题的。是tool description/params都写得很简单，把更多instruction放在system prompt里，还是每个tool都写得很详细？还是都写得很详细，有大量overlap？ 写doc/autotune/round_4/prompt_refactor/*_claude.md里。

2. 整理所有现在不能success的task到一个eval/config里。GPT-5.4今天刚发布，我想把一次都没成功过的task给GPT-5.4跑一下，用openai provider不要用openrouter provider。

3. 上面这些tip都太hacky了，不准备加。