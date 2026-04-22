# `delegate_task` Tool Analysis

> Analyst: Claude | Date: 2026-02-06
> Objective: max(agent success rate), min(token usage cost)

---

## 1. Our Current Implementation

### 1.1 Tool Schema (sent to LLM every turn)

```json
{
  "name": "delegate_task",
  "description": "Delegate ONE atomic UI action to a sub-agent.\n\nAvailable agents:\n- executor: Execute ONE atomic UI action on the current screen\n\n## Query Format (ATOMIC intents):\n- TAP: \"Tap on the 'Send' button\", \"Tap the first email in the list\"\n- SCROLL: \"Scroll down to reveal more items\", \"Scroll up\"\n- EXTRACT: \"Extract sender, subject from current email view\"\n- TYPE: \"Type 'hello' into the search field\"\n- BACK: \"Press back to return to previous screen\"\n\nBAD: \"Open app, navigate to settings, change theme\" (too many steps!)\nGOOD: \"Tap on the Settings icon\" (one atomic action)\n\nThe executor will ground your semantic intent to the actual UI element.",
  "parameters": {
    "properties": {
      "agent_name":       { "type": "string",  "description": "Name of sub-agent to run" },
      "query":            { "type": "string",  "description": "Complete instruction for the sub-agent" },
      "current_subgoal":  { "type": "string",  "description": "Optional current subgoal context" },
      "important_notes":  { "type": "array",   "description": "Optional short notes to preserve context", "items": {"type": "string"} },
      "agent_thought":    { "type": "string",  "description": "Brief reason for this delegation" }
    },
    "required": ["agent_name", "query"]
  }
}
```

**Token cost estimate**: ~250 tokens per turn (description + schema), regardless of whether the tool is called.

### 1.2 Planner System Prompt Delegation Guidance (~350 tokens)

Sections in planner system prompt that reference `delegate_task`:
- "Delegate all grounded UI execution to the executor agent via delegate_task" (role intro)
- "Call exactly one execution tool per turn (`delegate_task` or `app_control`)" (tool calling rules)
- "Call delegate_task(agent_name='executor', query='...') with ONE intent" (workflow step 3)
- "## CRITICAL: Atomic Delegation" (~120 tokens of examples)
- "## Writing Good Executor Queries" (~50 tokens of guidance)

### 1.3 How Executor Receives the Goal

`SubAgentRequest.toGoal()` builds:
```
Delegated query:
Tap on the first email in the inbox

Current subgoal:           (if provided)
Read all emails

Important notes:           (if provided)
- Gmail inbox is currently open
- There are 5 unread emails
```

### 1.4 Executor Return → Planner

Simple text string:
- Success: `"Sub-agent 'executor' completed:\n{answer}"`
- Failure: `"Sub-agent 'executor' reported failure.\n{answer}"`

### 1.5 Architecture Summary

```
Planner turn N:
  delegate_task(agent_name="executor", query="Tap on first email")
      ↓
  Executor (isolated history, shared scratchpad, maxTurns=5)
      ↓
  Returns: "Sub-agent 'executor' completed:\nTapped 'Meeting at 3pm' email"
      ↓
Planner turn N+1:
  Sees result as function_call_output → decides next action
```

---

## 2. Reference Implementations

### 2.1 DroidRun (Manager → Executor)

**Mechanism**: Code-level workflow orchestration via events — no LLM tool call.

| Aspect | Detail |
|--------|--------|
| **Delegation trigger** | Manager outputs `<plan>` in structured text; first line extracted as `current_subgoal` |
| **Delegation format** | `ExecutorInputEvent(current_subgoal=str)` — pure code, invisible to LLM |
| **Context to executor** | Via Jinja2 template: `instruction`, `app_card`, `device_state`, `plan`, `subgoal`, `progress_status`, `action_history`, `available_secrets`, `custom_variables` |
| **Executor prompt key line** | "EXECUTE THIS SUBGOAL: {{ subgoal }}" + "LITERAL EXECUTION RULE: Whatever the current subgoal says to do, do that EXACTLY." |
| **Return format** | `ExecutorResultEvent { action: Dict, outcome: bool, error: str, summary: str }` |
| **Manager receives** | Shared state updated with `action_history`, `summary_history`, `action_outcomes`, `error_descriptions` |
| **Manager prompt key line** | "If the first subgoal is not completed, please copy the previous round's plan or update the plan based on the completion of the subgoal." |

**Code evidence**:
- Manager prompts: `.reference/mobile_agent/droidrun/droidrun/config/prompts/manager/system.jinja2`
- Executor prompts: `.reference/mobile_agent/droidrun/droidrun/config/prompts/executor/system.jinja2`
- Orchestration: `.reference/mobile_agent/droidrun/droidrun/agent/droid/droid_agent.py:658-838`
- Subgoal extraction: `.reference/mobile_agent/droidrun/droidrun/agent/manager/prompts.py:68-96`

### 2.2 MobileAgent V3 (Manager → Executor → Reflector)

**Mechanism**: Shared state pattern (`InfoPool`) — no LLM tool call.

| Aspect | Detail |
|--------|--------|
| **Delegation trigger** | Manager outputs `### Plan ###` and `### Current Subgoal ###` in structured text |
| **InfoPool fields** | `instruction`, `plan`, `current_subgoal`, `progress_status`, `action_history` (last 5), `summary_history`, `action_outcomes`, `error_descriptions`, `additional_knowledge`, `important_notes` |
| **Context to executor** | Via prompt template: instruction, plan, progress_status, current_subgoal, guidelines, action_history |
| **Plan format** | `{'name': 'brief description', 'info': 'detailed execution info'}` — info field is notable |
| **Return format** | Executor: `{thought, action, description}`. Reflector: `{outcome (A/B/C), error_description, progress_status}` |
| **Reflector role** | Compares before/after screenshots; updates progress; categorizes outcome as A (success), B (wrong page), C (no change) |
| **Error escalation** | After N consecutive failures (`error_flag_plan`), Manager replans |

**Code evidence**:
- InfoPool: `.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/os_world_v3/mm_agents/mobileagent_v3/mobile_agent_modules.py:279-310`
- Manager prompts: same file, lines 339-437
- Executor prompts: same file, lines 548-620
- Reflector prompts: same file, lines 640-686
- Orchestration: `.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/os_world_v3/mm_agents/mobileagent_v3/mobile_agent.py:252-474`

### 2.3 Minitap (Cortex → Executor, Planner → Orchestrator)

**Mechanism**: LangGraph state graph with 6 specialized agents.

| Aspect | Detail |
|--------|--------|
| **Delegation trigger** | Cortex produces `structured_decisions` JSON string |
| **Cortex → Executor format** | JSON: `[{"action": "tap", "target": {"resource_id": "...", "bounds": {...}, "text": "..."}}]` |
| **Executor role** | "You are the hands, Cortex is the brain" — pure execution, no reasoning |
| **Target specification** | Multi-fallback: `resource_id` + `resource_id_index` + `bounds` + `text` + `text_index` |
| **Orchestrator role** | Tracks subgoal status (NOT_STARTED → PENDING → SUCCESS/FAILURE), triggers replanning |
| **Planner guidance** | Detailed: good/bad examples, replanning rules, cross-app patterns, video recording patterns |
| **Return format** | Tool execution success/failure messages via `ToolWrapper.on_success_fn/on_failure_fn` |
| **Subgoal tracking** | Explicit: `SubgoalStatus` enum with `complete_subgoals_by_ids()`, `fail_current_subgoal()` |
| **Key design** | Cortex can mark subgoals complete AND issue new actions in same turn |

**Code evidence**:
- Graph: `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/graph/graph.py:100-160`
- Cortex prompt: `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/cortex/cortex.md`
- Executor prompt: `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/executor/executor.md`
- Planner prompt: `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/planner/planner.md`
- Orchestrator: `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/orchestrator/orchestrator.py:34-117`

### 2.4 AutoDev (Planner → Executor)

**Mechanism**: Planner issues typed semantic tool calls, executor converts to grounded actions.

| Aspect | Detail |
|--------|--------|
| **Delegation trigger** | Planner calls typed tools: `tap(intent)`, `scroll(intent)`, `type_text(text, intent)`, etc. |
| **Key difference** | Each action type has its own planner-level tool — NOT a generic `delegate_task` |
| **Query conversion** | `tool_call_to_query()` converts typed tool call to natural language for executor |
| **Executor budget** | Up to 10 steps per planner call (multi-turn executor) |
| **Executor reporting** | `report(notes)` — subtask done; `extracted_data(data)` — return structured data |
| **Shared state** | Scratchpad + todo list (shared across planner/executor) |
| **Planner tools** | `tap(intent)`, `scroll(intent)`, `swipe_coords(...)`, `gesture(intent)`, `type_text(text, intent)`, `open_app(app_name)`, `go_back()`, `clear_text()`, `answer(text)`, `finish_task(success)`, `transcribe_screen()`, `update_todos()`, `createItem()`, `fetchItem()` |
| **Executor tools** | `click(x,y)`, `scroll(direction,x,y)`, `swipe_coords(...)`, `input_text(text,x,y,clear)`, `navigate_back()`, `navigate_home()`, `open_app(app_name)`, `wait()`, `transcribe_screen()`, `report(notes)`, `extracted_data(data)`, etc. |

**Code evidence**:
- Orchestration: `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev_agent.py:546-1054`
- Planner tools: same repo, `agents/autodev/planner_tools.py`
- Executor tools: same repo, `agents/autodev/executor_tools.py`
- Prompts: same repo, `agents/autodev/prompts.py`

### 2.5 MobileWorld Planner-Executor

**Mechanism**: Planner selects action + semantic target, Executor only grounds coordinates.

| Aspect | Detail |
|--------|--------|
| **Executor role** | Coordinate grounding only — one prediction per action |
| **Delegation scope** | Very narrow: only GUI actions (click, long_press, double_tap, drag) delegate |
| **Not relevant** | Too narrow to compare meaningfully with our `delegate_task` |

---

## 3. Comparative Analysis

### 3.1 Delegation Granularity Spectrum

```
Narrow ←──────────────────────────────────────────────→ Wide

MobileWorld     Our impl     DroidRun     AutoDev     Minitap
(coords only)   (1 atomic    (1 subgoal   (10-step    (multi-action
                 action)      literal      executor)    structured
                              execution)               decisions)
```

Our `delegate_task` sits on the narrower end — strictly ONE atomic action. This is a good position for:
- **Success rate**: Atomic actions are more likely to succeed
- **Debuggability**: Easy to trace cause-effect
- **Token cost**: Executor finishes in 1-3 turns

But it means the planner needs MORE turns to accomplish complex goals, increasing total planner tokens.

### 3.2 Context Passing Comparison

| What's passed to executor | Ours | DroidRun | MobileAgent V3 | AutoDev |
|---------------------------|------|----------|-----------------|---------|
| Query/subgoal text | ✓ | ✓ | ✓ | ✓ |
| Original user instruction | ✗ | ✓ | ✓ | ✗ (in sys prompt) |
| Full plan | ✗ | ✓ | ✓ | ✗ |
| Progress status | ✗ | ✓ | ✓ | ✗ |
| Recent action history | ✗ | ✓ | ✓ (last 5) | ✗ |
| App-specific guidance | ✗ | ✓ (app_card) | ✓ (additional_knowledge) | ✗ |
| Current subgoal (separate) | Optional | N/A (IS subgoal) | ✓ | ✗ |
| Important notes | Optional | ✗ | ✓ | ✗ |
| Device state/screen | Via fresh perception | ✓ | Via screenshot | Via screenshot |

**Key insight**: Our executor operates with the LEAST context among multi-agent implementations. The executor system prompt even says "Read the query - it's your ONLY context." This is intentional for token efficiency but may hurt success rate when queries are ambiguous.

### 3.3 Return Format Comparison

| What planner receives back | Ours | DroidRun | MobileAgent V3 | AutoDev |
|---------------------------|------|----------|-----------------|---------|
| Success/failure boolean | ✓ (in text) | ✓ (outcome) | ✓ (A/B/C) | ✓ (report) |
| Description of what happened | ✓ | ✓ (summary) | ✓ (description) | ✓ (notes) |
| Error details | ✓ (in text) | ✓ (error) | ✓ (error_description) | ✗ |
| Action executed | ✗ | ✓ (action dict) | ✓ (action JSON) | ✗ |
| Structured outcome category | ✗ | ✗ | ✓ (A/B/C) | ✗ |
| Extracted data (typed) | ✗ | ✗ | ✗ | ✓ (extracted_data) |

**Key insight**: Our return format is unstructured text. The planner must infer what happened from prose. DroidRun and MobileAgent V3 provide structured outcome info that makes it easier for the planner to decide next steps.

### 3.4 Token Cost Comparison

| Component | Ours | DroidRun | MobileAgent V3 |
|-----------|------|----------|-----------------|
| Tool description cost (per planner turn) | ~250 tokens | 0 (no tool) | 0 (no tool) |
| Executor context cost (per delegation) | ~50 tokens (query) | ~500+ tokens (full template) | ~400+ tokens (InfoPool) |
| Return cost (per delegation) | ~30-50 tokens | ~80 tokens (structured) | ~60 tokens (structured) |

**Key insight**: Our approach trades per-delegation context cost (low) for per-turn tool schema cost (high). DroidRun/MobileAgent V3 trade per-turn schema cost (zero, since delegation is code-level) for higher per-delegation context.

---

## 4. Pros and Cons Summary

### 4.1 Our Implementation

**Pros:**
1. Clean LLM-native abstraction — planner explicitly reasons about delegation via tool call
2. Strict atomic enforcement — reduces executor failure rates
3. Isolated executor history — prevents context bloat
4. Shared scratchpad — enables data persistence without inflating delegation payload
5. `agent_thought` parameter — good for debugging and tracing
6. Flexible schema — `important_notes` and `current_subgoal` allow context passing when needed

**Cons:**
1. **Tool description bloat**: ~250 tokens per turn for a tool that is called on almost every planner turn — the examples and format guidance are redundant with system prompt
2. **`agent_name` waste**: Always `"executor"` — wastes ~10 tokens per call + schema overhead for unused flexibility
3. **Executor context starvation**: Query-only context can be insufficient for ambiguous situations
4. **Unstructured return**: Planner must parse prose to determine what happened
5. **Duplicate guidance**: Atomic delegation rules appear in BOTH system prompt AND tool description
6. **No outcome categorization**: Planner can't easily distinguish "wrong page" from "no change" from "partial success"

### 4.2 DroidRun

**Pros:**
1. Rich executor context (full plan, progress, action history, app cards)
2. Zero token cost for delegation mechanism (code-level)
3. Structured return format with action/outcome/error/summary
4. `<add_memory>` tag for important observations
5. Literal execution rule makes executor behavior predictable

**Cons:**
1. No LLM reasoning about delegation — planner just writes a plan, code extracts first step
2. Manager must include `<plan>` in every response (even if plan hasn't changed)
3. Tight coupling between manager output format and executor input
4. First-line-of-plan extraction is brittle

### 4.3 MobileAgent V3

**Pros:**
1. Reflector agent provides systematic outcome evaluation (A/B/C)
2. `{'name': ..., 'info': ...}` plan format carries execution-relevant details
3. Error escalation threshold triggers replanning automatically
4. Before/after screenshot comparison for outcome verification

**Cons:**
1. 3 LLM calls per step (manager + executor + reflector) — expensive
2. Shared state (`InfoPool`) can become stale
3. No explicit delegation mechanism — reliance on shared state is fragile
4. Large context per executor call (~400+ tokens of context)

### 4.4 Minitap

**Pros:**
1. Cortex produces structured action JSON — very precise execution
2. Multi-fallback target specification (resource_id → bounds → text)
3. Orchestrator tracks subgoal lifecycle explicitly
4. Cortex can complete subgoals and issue actions in same turn — efficient

**Cons:**
1. 6 agents per step is very expensive
2. LangGraph complexity adds engineering overhead
3. Structured decisions format is rigid

### 4.5 AutoDev

**Pros:**
1. Typed planner tools (`tap(intent)`, `scroll(intent)`) give structure to delegation
2. 10-step executor budget allows complex subgoal completion
3. `extracted_data()` provides typed data return
4. `transcribe_screen()` for reading content — specialized extraction tool

**Cons:**
1. Many planner tools increase schema token cost
2. 10-step executor can get stuck and waste budget
3. Tight coupling between planner tool types and executor capabilities

---

## 5. Improvement Proposals

### Proposal 1: Slim the Tool Description (HIGH IMPACT — Token Savings)

**Problem**: The tool description carries ~250 tokens of examples and formatting guidance that duplicates what's in the planner system prompt.

**Current description** (13 lines, ~250 tokens):
```
Delegate ONE atomic UI action to a sub-agent.

Available agents:
- executor: Execute ONE atomic UI action on the current screen

## Query Format (ATOMIC intents):
- TAP: "Tap on the 'Send' button", "Tap the first email in the list"
- SCROLL: "Scroll down to reveal more items", "Scroll up"
- EXTRACT: "Extract sender, subject from current email view"
- TYPE: "Type 'hello' into the search field"
- BACK: "Press back to return to previous screen"

BAD: "Open app, navigate to settings, change theme" (too many steps!)
GOOD: "Tap on the Settings icon" (one atomic action)

The executor will ground your semantic intent to the actual UI element.
```

**Proposed description** (3 lines, ~50 tokens):
```
Delegate ONE atomic UI action to the executor. The query should be a single semantic intent (e.g., tap, scroll, extract, type, back). The executor grounds your intent to the actual UI element and executes it.
```

**Rationale**: The planner system prompt already contains the full "## CRITICAL: Atomic Delegation" section with good/bad examples, query format guidance, and writing tips. The tool description doesn't need to repeat this. Tool descriptions are included in EVERY LLM call; system prompt guidance is more appropriate for behavioral instructions.

**Token savings**: ~200 tokens × number of planner turns. For a 15-turn task, that's ~3,000 tokens saved.

**Risk**: LOW. The planner system prompt has comprehensive delegation guidance already.

### Proposal 2: Default `agent_name` to "executor" (LOW IMPACT — Token Savings + UX)

**Problem**: `agent_name` is always `"executor"` in current use. Making it required wastes:
- Schema tokens: `agent_name` property definition (~25 tokens per turn)
- Call tokens: `"agent_name": "executor"` (~5 tokens per call)

**Proposed changes**:
- Remove `agent_name` from `required` array
- Add `"default": "executor"` to schema
- Update description: `"Name of sub-agent to run (default: executor)"`
- In code: `val agentName = params.optString("agent_name", "executor").trim()`

**Token savings**: ~5 tokens per call × calls per task. Marginal but free.

**Risk**: VERY LOW. If we add agents later, the parameter still exists and can be explicitly set.

### Proposal 3: Add `intent_type` Parameter (MEDIUM IMPACT — Success Rate)

**Problem**: The executor must infer intent type from natural language query. When queries are ambiguous (e.g., "Check the search field"), the executor may misinterpret.

**Proposed change**: Add an optional `intent_type` enum parameter:
```json
"intent_type": {
  "type": "string",
  "enum": ["tap", "scroll", "extract", "type", "back", "wait", "other"],
  "description": "Type of action (helps executor ground faster)"
}
```

**Evidence**: AutoDev uses typed planner tools (`tap(intent)`, `scroll(intent)`) which serves the same purpose. MobileAgent V3's executor prompt has "Atomic Actions" section. Both show that action-type hints improve grounding accuracy.

**Token cost**: +~40 tokens schema, +~10 tokens per call. Total per task: ~40 + (10 × calls) ≈ 150 tokens for a 10-delegation task.

**Risk**: MEDIUM. May over-constrain planner for edge cases. The `"other"` fallback mitigates this.

**Recommendation**: DEFER. The token cost may not justify the improvement. Test without this first.

### Proposal 4: Improve Return Format (HIGH IMPACT — Success Rate)

**Problem**: Executor returns unstructured text like `"Sub-agent 'executor' completed:\nTapped 'Meeting at 3pm' email"`. The planner must parse this prose. Compare with DroidRun's structured `{action, outcome, error, summary}`.

**Proposed changes to `SubAgentResult` → tool output**:

Current:
```
Sub-agent 'executor' completed:
Tapped 'Meeting at 3pm' email
```

Proposed:
```
executor result: success
Tapped 'Meeting at 3pm' email
```

For failures:
```
executor result: failed
Could not find 'Send' button on current screen. Visible elements: Inbox, Compose, Menu.
```

**Rationale**: 
- Remove verbose prefix `"Sub-agent 'executor' completed:\n"` (wastes tokens in history)
- Add clear `success`/`failed` signal on first line (easier for LLM to parse)
- Keep answer as free text (structured JSON would be overkill and harder for LLM to produce)

**Token savings**: ~5 tokens per return × returns per task. Also reduces history accumulation.

**Risk**: LOW. Simple format change that improves parseability.

### Proposal 5: Consolidate Delegation Guidance in System Prompt (HIGH IMPACT — Token Savings + Clarity)

**Problem**: Delegation guidance is fragmented across:
1. Planner system prompt: "## CRITICAL: Atomic Delegation" (~120 tokens), "## Writing Good Executor Queries" (~50 tokens), workflow steps (~40 tokens)
2. Tool description: Query format examples (~130 tokens), BAD/GOOD examples (~40 tokens)
3. Tool parameter descriptions: "Complete instruction for the sub-agent" (~8 tokens)

This creates redundancy and wastes tokens.

**Proposed change**: Move ALL behavioral guidance to the planner system prompt. Tool description becomes minimal (see Proposal 1). This means:
- System prompt carries the FULL guidance (already mostly there)
- Tool description is a one-liner explaining what the tool does
- Parameter descriptions remain descriptive but brief

**Combined with Proposal 1**, the total prompt restructuring saves ~200 tokens per turn.

### Proposal 6: Richer `current_subgoal` Usage Guidance (MEDIUM IMPACT — Success Rate)

**Problem**: `current_subgoal` and `important_notes` are optional and the planner rarely uses them. But DroidRun and MobileAgent V3 always pass the full plan context to their executors, and their executors benefit from it.

**Proposed change**: Add explicit guidance in planner system prompt:
```
## Context for Executor
When delegating, provide context that helps the executor succeed:
- current_subgoal: Name the broader step this action belongs to (helps executor understand purpose)
- important_notes: Pass 1-2 facts the executor needs (e.g., "target element may be off-screen", "app is in dark mode")
Use these when the query alone might be ambiguous.
```

**Token cost**: ~60 tokens added to system prompt (one-time). ~15 tokens per call when used.

**Risk**: LOW. Doesn't change schema, just usage guidance. The LLM may or may not follow it.

### Proposal 7: Remove Duplicate Guidance from System Prompt (MEDIUM IMPACT — Token Savings)

**Problem**: The planner system prompt has BOTH high-level workflow guidance AND detailed examples that overlap with the tool description. After Proposal 1 moves all guidance to the system prompt, we should also deduplicate within the system prompt itself.

**Current duplication**:
- "Delegate all grounded UI execution to the executor agent via delegate_task" (intro)
- "Call delegate_task(agent_name='executor', query='...') with ONE intent" (workflow)
- "## CRITICAL: Atomic Delegation" (full section with examples)
- "## Writing Good Executor Queries" (guidance section)

**Proposed consolidation**: Merge into one clean section:
```
## Delegation Rules
- Each delegate_task call = ONE atomic action (tap, scroll, extract, type, back)
- Query format: specific, actionable, names the target element
  GOOD: "Tap on the 'Inbox' label"
  BAD: "Navigate to inbox and read emails" (multiple steps!)
- Provide current_subgoal when the action is part of a multi-step sequence
- Provide important_notes when executor needs extra context (e.g., element may be off-screen)
```

**Token savings**: ~100 tokens from deduplication.

---

## 6. Prioritized Implementation Plan

| # | Proposal | Impact | Token Δ | Risk | Priority |
|---|----------|--------|---------|------|----------|
| 1 | Slim tool description | High (saves ~200 tok/turn) | -3000/task | Low | **P0** |
| 5 | Consolidate guidance in system prompt | High (clarity + savings) | -200/turn combined with P1 | Low | **P0** |
| 4 | Improve return format | High (success rate) | -5/return | Low | **P0** |
| 7 | Deduplicate system prompt guidance | Medium (saves ~100 tok) | -100 | Low | **P1** |
| 6 | Richer current_subgoal guidance | Medium (success rate) | +60 (system) | Low | **P1** |
| 2 | Default agent_name to executor | Low (token savings) | -5/call | Very Low | **P2** |
| 3 | Add intent_type parameter | Medium (success rate) | +150/task | Medium | **DEFER** |

### Recommended Execution Order

**Phase 1 (P0 — do together):**
1. Slim tool description to ~50 tokens (Proposal 1)
2. Consolidate all delegation guidance in system prompt (Proposal 5)
3. Improve return format prefix (Proposal 4)

**Phase 2 (P1 — after validating Phase 1):**
4. Deduplicate system prompt delegation sections (Proposal 7)
5. Add current_subgoal usage guidance (Proposal 6)

**Phase 3 (P2/DEFER):**
6. Default agent_name (Proposal 2) — easy but marginal
7. intent_type parameter (Proposal 3) — needs A/B testing

---

## 7. Proposed Tool Description (After Phase 1)

```kotlin
override val description: String =
    """
    Delegate ONE atomic UI action to the executor agent.
    The query should be a single semantic intent (tap, scroll, extract, type, back).
    The executor grounds your intent to the actual UI element and executes it.
    """.trimIndent()
```

**From ~250 tokens → ~50 tokens.** All examples and format guidance moved to system prompt.

---

## 8. Proposed Planner System Prompt Delegation Section (After Phase 1+2)

Replace the current fragmented sections with:

```
## Delegation
You do NOT perform UI actions directly. Use delegate_task for all screen interactions.

### Rules
- ONE atomic action per delegate_task (tap, scroll, extract, type, back)
- Query must be specific and name the target: "Tap on the 'Inbox' label", not "Go to inbox"
- After receiving result, check success/failure, store data in scratchpad if needed, then continue

### Writing Good Queries
- Include element identifier: text, desc, or resource_id
- State what you expect: "Tap 'Send' button to submit the form"
- If ambiguous, use current_subgoal and important_notes to provide context

### Examples
GOOD: "Tap on the first email in the inbox"
GOOD: "Extract sender and subject from the currently open email"
GOOD: "Scroll down to find the 'Settings' option"
BAD:  "Open Gmail, read all emails, summarize them" — too many steps!

### Failure Recovery
When executor reports failure:
1. Don't repeat the same approach
2. Try alternative: search/filter/back/scroll to new position
3. Use scratchpad to note what failed and why
```

**Token estimate**: ~180 tokens (vs current ~350 tokens spread across multiple sections). Net savings: ~170 tokens.

---

## 9. Proposed Return Format (After Phase 1)

```kotlin
// In IsolatedSubAgentRunner.run(), change the output formatting:

// Success:
"executor result: success\n${completion.answer}"

// Failure:
"executor result: failed\n${completion.answer}"

// Timeout:
"executor result: timeout\nReached step limit. ${narrativeSummary ?: ""}"
```

Removes verbose `"Sub-agent 'executor' completed:\n"` and `"Sub-agent 'executor' reported failure.\n"` prefixes. Adds clear machine-parseable first line.

---

## 10. Ideas NOT Recommended (and Why)

### 10.1 Typed Planner Tools (AutoDev pattern)
Replace `delegate_task` with `tap(intent)`, `scroll(intent)`, `type(text, intent)`, etc.

**Why not**: Each additional tool adds ~80 tokens to the schema. 6 typed tools = ~480 tokens vs our ~50 tokens (after slimming). The generic `delegate_task` + query is more token-efficient and equally expressive.

### 10.2 Reflector Agent (MobileAgent V3 pattern)
Add a third agent that evaluates executor outcomes with before/after screenshots.

**Why not**: Adds an entire LLM call per delegation. Our planner already evaluates outcomes by observing the new screen state. The cost/benefit ratio is poor for our atomic delegation granularity.

### 10.3 Structured JSON Decisions (Minitap pattern)
Have the planner output structured action JSON for the executor.

**Why not**: Defeats the purpose of the planner-executor split. The planner should reason at the semantic level; the executor grounds to UI elements. Structured decisions tie the planner to implementation details.

### 10.4 Rich Context Passing (DroidRun pattern)
Pass full plan, progress status, action history to executor.

**Why not**: For atomic actions (1-3 executor turns), the query alone is sufficient in most cases. The token cost of passing full context on every delegation (~400 tokens) exceeds the benefit. Our `important_notes` parameter already allows selective context passing. If success rate data shows the executor struggling with context, this can be revisited.

---

## Appendix: Token Budget Analysis

Assuming 15-turn planner task with 12 delegations:

| Component | Current | After Phase 1 | Delta |
|-----------|---------|---------------|-------|
| Tool description (×15 turns) | 3,750 | 750 | **-3,000** |
| System prompt delegation sections | 350 | 180 | **-170** |
| Return format (×12 delegations) | 480 | 360 | **-120** |
| **Total prompt tokens saved** | | | **-3,290** |

This is a ~3.3K token reduction per task with NO expected degradation in success rate (guidance is preserved, just reorganized).
