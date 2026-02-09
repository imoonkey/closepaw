# `write_todos` Tool — Cross-Implementation Analysis

> Analyst: Claude | Date: 2026-02-06
> References: gemini-cli `write_todos`, codex `update_plan`, AutoDev `update_todos`, DroidRun (prompt-driven `<plan>`), Minitap (subgoal system), MobileAgent-v3 (InfoPool planning), Android Agent `write_todos`

---

## 1. Implementation Comparison

### 1.1 Master Overview Table

| Dimension | Android Agent | Gemini CLI | Codex | AutoDev | DroidRun | Minitap | MobileAgent-v3 |
|-----------|--------------|------------|-------|---------|----------|---------|----------------|
| **Tool name** | `write_todos` | `write_todos` | `update_plan` | `update_todos` | *(no tool)* | *(no tool)* | *(no tool)* |
| **Mechanism** | Structured tool | Structured tool | Structured tool | Structured tool | XML tags in LLM output | Agent-internal subgoal model | Shared InfoPool + free-text plan |
| **Desc tokens** | ~170 | ~500 | ~30 | ~300 | 0 (prompt-driven) | 0 (agent-internal) | 0 (prompt-driven) |
| **Statuses** | 4: pending, in_progress, completed, cancelled | 4: same | 3: no cancelled | 3: pending, in_progress, completed | N/A (text) | 4: NOT_STARTED, PENDING, SUCCESS, FAILURE | N/A (numbered list) |
| **Item fields** | description, status | description, status | step, status | content, status, **priority**, **id** | free text | id, description, status, completion_reason, timestamps | numbered text |
| **Extra params** | agent_thought | none | explanation | none | N/A | N/A | N/A |
| **Output to LLM** | JSON `{todos:[], count}` | Human-readable list | `"Plan updated"` | *(not found)* | parsed from response | not returned to LLM | parsed from response |
| **Who has it** | Planner + Standalone | All agents | All agents | Planner only | Manager agent | Planner agent (internal) | Manager agent |
| **Step length guidance** | None | None | "5-7 words" | None | None | "purpose-driven, not too granular" | None |

### 1.2 Code Agent Implementations (Gemini CLI, Codex)

#### 1.2.1 Gemini CLI (`write_todos`)

**File:** `.reference/code_agent/gemini-cli/packages/core/src/tools/write-todos.ts`

**Schema sent to LLM:**
```json
{
  "name": "write_todos",
  "description": "<~500 token description with methodology + examples>",
  "parameters": {
    "type": "object",
    "properties": {
      "todos": {
        "type": "array",
        "items": {
          "type": "object",
          "properties": {
            "description": { "type": "string" },
            "status": { "type": "string", "enum": ["pending","in_progress","completed","cancelled"] }
          },
          "required": ["description","status"]
        }
      }
    },
    "required": ["todos"]
  }
}
```

**Key design decisions:**

1. **Very verbose description** (~500 tokens): 7-step methodology, "Task state definitions", positive example (React logo creator with 8 steps + reasoning), negative example (test loop). Sent **every** API call.
2. **No extra parameters**: no `agent_thought` or `explanation` — model writes thought inline.
3. **Human-readable output**: Returns `"Successfully updated the todo list. The current list is now:\n1. [pending] ..."` — friendly but adds history tokens.
4. **System prompt integration**: Mentioned in "Plan" workflow step: "use the `write_todos` tool to track your progress."

**Pros:** Exhaustive description steers model; positive/negative examples; prescriptive methodology; `cancelled` status.
**Cons:** ~500 token/turn cost; redundant with system prompt; no `explanation` field; no step length guidance.

#### 1.2.2 Codex (`update_plan`)

**Files:** `.reference/code_agent/codex/codex-rs/core/src/tools/handlers/plan.rs`, `prompt.md`

**Schema sent to LLM:**
```json
{
  "name": "update_plan",
  "description": "Updates the task plan.\nProvide an optional explanation and a list of plan items, each with a step and status.\nAt most one step can be in_progress at a time.",
  "parameters": {
    "type": "object",
    "properties": {
      "explanation": { "type": "string" },
      "plan": {
        "type": "array",
        "items": {
          "type": "object",
          "properties": {
            "step": { "type": "string" },
            "status": { "type": "string", "description": "One of: pending, in_progress, completed" }
          },
          "required": ["step","status"]
        }
      }
    },
    "required": ["plan"]
  }
}
```

**Key design decisions:**

1. **Ultra-minimal description** (~30 tokens). All guidance in system prompt (cacheable).
2. **`explanation` parameter**: Structured rationale for plan changes.
3. **Minimal output**: `"Plan updated"`. System prompt: "Do not repeat the full contents of the plan after an update_plan call."
4. **Only 3 statuses**: No `cancelled`.
5. **Step length constraint** in system prompt: "1-sentence steps (no more than 5-7 words each)".
6. **Quality examples**: 3 high-quality + 3 low-quality plan examples in system prompt.
7. **Behavioral rules** in system prompt: mark step completed before moving on; `explanation` when changing plan; always exactly one `in_progress`.

**Pros:** Minimal per-turn cost; `explanation` field; step length constraint; quality examples; "don't repeat" rule.
**Cons:** No `cancelled` status; relies on system prompt caching.

---

### 1.3 Mobile Agent Implementations

#### 1.3.1 AutoDev / android_world (`update_todos`)

**File:** `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/todo_list.py`

**The only mobile agent with an explicit todo tool.** Schema:

```json
{
  "name": "update_todos",
  "description": "<~300 token description>",
  "parameters": {
    "type": "object",
    "properties": {
      "todos": {
        "type": "array",
        "items": {
          "type": "object",
          "properties": {
            "content": { "type": "string", "minLength": 1 },
            "id": { "type": "string" },
            "priority": { "type": "string", "enum": ["high", "medium", "low"] },
            "status": { "type": "string", "enum": ["pending", "in_progress", "completed"] }
          },
          "required": ["content", "status", "priority", "id"]
        }
      }
    },
    "required": ["todos"]
  }
}
```

**Tool description (full text from `TODO_LIST_DESCRIPTION`):**
```
Use this tool to create and manage a structured task list for your current task session.
This helps you track progress, organize complex tasks, and demonstrate thoroughness to the user.
Without this, you will run into endless loops.

## When to Use This Tool
Use this tool proactively in these scenarios:
1. Complex multi-step tasks - When a task requires 3 or more distinct steps or actions
2. Non-trivial and complex tasks - Tasks that require careful planning or multiple operations
3. User explicitly requests todo list
4. User provides multiple tasks (numbered or comma-separated)
5. After receiving new instructions - Immediately capture user requirements as todos
6. When you start working on a task - Mark it as in_progress BEFORE beginning work.
   Ideally you should only have one todo as in_progress at a time
7. After completing a task - Mark it as completed and add any new follow-up tasks

## When NOT to Use This Tool
Skip using this tool when:
1. There is only a single, straightforward task
2. The task is trivial and tracking it provides no organizational benefit
3. The task can be completed in less than 3 trivial steps
4. The task is purely conversational or informational
```

**System prompt planning section** (from `prompts.py:30-45`):
```
2. **PLAN**: Create a todo list using update_todos() for any task with:
   - Multiple items or steps
   - Sequential operations
   - Data extraction and reuse
   - Multi-app workflows

=== PLANNING STRATEGY ===
- Break complex tasks into atomic subgoals
- For multi-item tasks: list each item separately
- For sequential workflows: list steps in order
- Include specific values (names, dates, amounts) in todo descriptions
- Mark todos complete only after verifying in screenshot/result
- Update todos as you discover new requirements
```

**Unique features:**
- **`priority` field** (high/medium/low): Allows ordering execution by importance.
- **`id` field**: Each item has a unique identifier for tracking across updates.
- **"Without this, you will run into endless loops"**: Strong behavioral nudge to always use the tool for complex tasks.
- **Completion verification requirement**: "Mark todos complete only after verifying in screenshot/result" — critical for mobile agents where UI state must be visually confirmed.
- **Planner-only**: Executor doesn't have the tool — consistent with planning being a planner responsibility.

**Pros:**
- `priority` field enables strategic execution ordering (do high-priority items first)
- `id` field enables stable tracking across plan updates
- Strong nudge ("endless loops") ensures model uses the tool
- Verification requirement aligns with mobile agent reality
- Detailed when-to-use/not-use guidance

**Cons:**
- ~300 token description per turn (expensive)
- 4 required fields per item (`content`, `status`, `priority`, `id`) = more output tokens per item than competitors
- No `cancelled` status — only 3 states
- No step length guidance

#### 1.3.2 DroidRun (Prompt-Driven `<plan>` Tags)

**Files:** `.reference/mobile_agent/droidrun/droidrun/config/prompts/manager/system.jinja2`, `agent/manager/prompts.py`

**No explicit todo tool.** Planning is handled via XML tags in the ManagerAgent's LLM output:

```xml
<thought>Reasoning about what to do next</thought>
<plan>
1. first subgoal
2. second subgoal
...
</plan>
<add_memory>Important information to remember</add_memory>
<progress_summary>Cumulative progress description</progress_summary>
```

**State tracking** (from `state.py`):
```python
plan: str = ""              # Current plan (free text)
current_subgoal: str = ""   # First line of plan
previous_plan: str = ""     # For comparison
progress_summary: str = ""  # Cumulative progress
memory: str = ""            # Append-only facts
```

**Planning prompt instruction:**
> "Please update or copy the existing plan according to the current page and progress. Please pay close attention to the historical operations. Please do not repeat the plan of completed content unless you can judge from the screen status that a subgoal is indeed not completed."

**Key pattern:** The manager's LLM response is **parsed** for XML tags — `parse_manager_response()` extracts thought, plan, memory, current_subgoal. The first line of `<plan>` becomes `current_subgoal`.

**Pros:**
- Zero tool schema overhead — no tool tokens at all
- Free-form plan allows rich context in each step
- `<progress_summary>` enables stateless mode (no conversation history needed)
- `<add_memory>` separates factual memory from plan tracking

**Cons:**
- No structured status tracking — plan is just numbered text
- Parsing XML from LLM output is fragile (regex-based)
- No explicit "completed" marking — relies on LLM to remove finished steps
- No validation of plan format
- Planner can't easily be "reminded" of its plan since it's just text

#### 1.3.3 Minitap / mobile-use (Subgoal System)

**Files:** `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/planner/types.py`, `agents/planner/planner.py`, `agents/orchestrator/orchestrator.py`

**No explicit todo tool.** Uses an agent-internal subgoal model:

```python
class Subgoal(BaseModel):
    id: str                      # Unique identifier
    description: str             # What to do
    completion_reason: str|None  # Why it was completed
    status: SubgoalStatus        # NOT_STARTED, PENDING, SUCCESS, FAILURE
    started_at: datetime|None    # When started
    ended_at: datetime|None      # When ended
```

**Multi-agent architecture:**
1. **Planner** → Creates initial subgoal list with structured output
2. **Orchestrator** → Tracks completion, transitions between subgoals, triggers replanning
3. **Cortex** → Analyzes screen, marks subgoals complete based on visual evidence (`complete_subgoals_by_ids`)
4. **Executor** → Performs actions

**Planner system prompt guidelines** (from `planner.md`):
```
Guidelines for subgoals:
- Purpose-driven — each subgoal should have a clear purpose
- Sequential — subgoals are executed in order
- Not too granular — avoid splitting into too many tiny steps
- No loops — don't create subgoals that require iteration
```

**Replanning:**
- On failures, Planner revises plan while keeping completed subgoals intact
- Orchestrator sets `needs_replanning` flag if plan is unworkable

**Scratchpad tools** (`tools/scratchpad.py`): `save_note`, `read_note`, `list_notes` for persistent key-value memory.

**Pros:**
- Rich status model: NOT_STARTED → PENDING → SUCCESS/FAILURE with timestamps and completion_reason
- `completion_reason` provides auditability
- Timestamps enable performance analysis
- FAILURE status (not just "not completed") enables targeted error recovery
- Multi-agent verification: Cortex marks completion based on **visual evidence**, not just agent self-report
- Replanning preserves completed work

**Cons:**
- Not a tool — the LLM can't directly update the plan
- Adds architectural complexity (4 agents)
- Subgoal model is agent-internal, not in prompt context
- "Not too granular" guidance is vague

#### 1.3.4 MobileAgent-v3 (InfoPool Planning)

**File:** `.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/mobile_v3/utils/mobile_agent_e.py`

**No explicit todo tool.** Uses a shared `InfoPool` dataclass:

```python
@dataclass
class InfoPool:
    plan: str = ""                   # Current plan (numbered subgoals)
    completed_plan: str = ""         # Completed subgoals / historical operations
    progress_status: str = ""        # Current progress description
    current_subgoal: str = ""        # What to do now
    important_notes: str = ""        # Accumulated important info
    future_tasks: list = field(...)  # Deferred tasks
```

**Planning pattern:** Manager generates/updates a numbered plan:
```
### Plan ###
1. first subgoal
2. second subgoal
...
```

**Critical planning prompt instructions:**
> "If the first subgoal in plan has been completed, please update the plan in time according to the screenshot and progress to ensure that the next subgoal is always the first item in the plan."
> "Please do not repeat the plan of completed content unless you can judge from the screen status that a subgoal is indeed not completed."

**Completed operations tracking:**
```
### Historical Operations ###
Try to add the most recently completed subgoal on top of the existing historical operations.
Please do not delete any existing historical operation.
```

**Multi-agent coordination:**
- **Manager** → Creates/updates plan in `info_pool.plan`, moves completed items to `info_pool.completed_plan`
- **Executor** → Reads `info_pool.plan` + `info_pool.current_subgoal`, selects action
- **Reflector** → Evaluates action outcome, updates `info_pool.progress_status`
- **Notetaker** → Updates `info_pool.important_notes`

**Pros:**
- Dual-list pattern: active plan + completed history — avoids re-listing completed items
- `progress_status` tracked by a separate Reflector agent — independent assessment
- `important_notes` from Notetaker — separates knowledge from planning
- Error escalation: after N consecutive failures, Manager revises plan

**Cons:**
- Free-form text planning — no structured validation
- Response parsing (regex-based) is fragile
- No explicit status tracking per item — relies on positional semantics (first = current)
- Heavy prompt overhead for the plan update instruction

---

## 2. Cross-Cutting Analysis: What Mobile Agents Teach Us

### 2.1 The Planning Spectrum

Mobile agent implementations span a spectrum from **no-tool prompt-driven** to **structured tool-driven**:

```
More structured ◄─────────────────────────────────────────────► Less structured

AutoDev         Our Agent     Gemini CLI   Codex      Minitap       DroidRun    MobileAgent-v3
update_todos    write_todos   write_todos  update_plan (subgoal     (<plan>     (InfoPool
w/ priority,    w/ status     w/ status    w/ step,    model,       XML tags)   free text)
id fields       field         field        explanation  internal)
```

**Key insight:** Only 1 out of 4 mobile agents uses an explicit tool (AutoDev). The other 3 use prompt-driven or agent-internal planning. This suggests the mobile domain is split on whether a structured tool is worth the token overhead.

### 2.2 Unique Mobile Agent Insights (Not Found in Code Agents)

| Insight | Source | Relevance to Our Agent |
|---------|--------|----------------------|
| **`priority` field** (high/medium/low) | AutoDev | Could help prioritize steps when budget is tight, but adds token overhead per item. **Not recommended** — our turn budget already enforces urgency. |
| **`id` field** per item | AutoDev, Minitap | Enables stable tracking across updates. **Not recommended** for now — full replacement semantics makes IDs unnecessary since the LLM re-emits the whole list. Would matter if we add merge semantics. |
| **Verification before marking complete** | AutoDev | "Mark todos complete only after verifying in screenshot/result" — **should adopt**. Mobile-specific: UI state must be visually confirmed, not assumed. |
| **Dual-list: active + completed** | MobileAgent-v3 | Separates active plan from history. **Interesting but complex** — our current approach (single list with status) is simpler and the `<system_reminder>` already summarizes only actionable items. |
| **`completion_reason` field** | Minitap | Explains why a subgoal was marked complete. **Nice for debugging** but adds token cost. Could be folded into `agent_thought`. |
| **FAILURE status** (distinct from incomplete) | Minitap | Explicit failure marking enables targeted recovery. **Worth considering** — but our `cancelled` status partially covers this, and the agent can explain failure in `agent_thought`. |
| **"Endless loops" warning** | AutoDev | "Without this, you will run into endless loops" — strong behavioral nudge. **Should adopt** a softer version in our system prompt. |
| **Separate Reflector agent** for progress assessment | MobileAgent-v3 | Independent verification of progress. **Not applicable** — our agent is the one assessing its own progress. But the principle of verification-before-marking-complete is valid. |
| **"Not too granular" guidance** | Minitap | Prevents over-decomposition. **Should adopt** — aligns with step length guidance from Codex. |

### 2.3 Action Space Patterns

Across all mobile agents, the todo/plan tool is always **planner-side only**:

| Agent | Who has the planning tool | Who does UI actions |
|-------|--------------------------|-------------------|
| AutoDev | Planner | Executor |
| DroidRun | Manager (via prompt) | Executor |
| Minitap | Planner (internal) | Executor |
| MobileAgent-v3 | Manager (via prompt) | Executor |
| **Our Agent** | **Planner + Standalone** | **Executor** |

This is consistent with our design. The Executor never needs to plan — it only executes atomic actions.

---

## 3. Current Android Agent Implementation Assessment

### 3.1 What's Working Well

1. **`agent_thought` parameter** — consistent with all other tools, supports reasoning traces
2. **`cancelled` status** — only shared with Gemini CLI; valuable for mobile tasks where plans change frequently
3. **Validation** — proper in_progress count enforcement
4. **Todo reminder** in `<system_reminder>` — summarizes actionable items without full list repetition
5. **Full todo list in user message** — necessary since our model doesn't have a separate rendering channel
6. **Planner + Standalone only** — correctly excludes Executor, matching all mobile agent patterns

### 3.2 Current Issues

1. **Description is mid-weight (~170 tokens) but lacks key behavioral guidance**: no step length constraint, no quality examples, no methodology, no verification requirement. It's a middle ground that gets neither the token efficiency of Codex nor the behavioral richness of Gemini/AutoDev.

2. **JSON output adds unnecessary tokens to history**: Returning `{"todos":[...],"count":3}` means the full todo state is stored in history as a function_call_output. Since we also inject todos into the user message each turn, this is redundant. (Codex returns just "Plan updated"; Gemini returns human-readable text.)

3. **No "don't repeat plan" instruction**: The model may echo the full todo list after updating it, wasting tokens. (Codex explicitly prevents this; MobileAgent-v3 says "do not repeat the plan of completed content".)

4. **No step length guidance**: Steps can become verbose paragraphs. (Codex: "5-7 words"; Minitap: "not too granular".)

5. **System prompt mentions are too brief**: Just "Use `write_todos` and `scratchpad` to track progress" — no guidance on when to use, how to write good steps, or verification. (AutoDev has detailed `=== PLANNING STRATEGY ===`; Codex has `## Planning` with examples.)

6. **No verification-before-completion guidance**: Mobile-specific gap. AutoDev says "Mark todos complete only after verifying in screenshot/result." Our agent could mark steps complete without visual confirmation.

### 3.3 Token Budget Analysis

Per turn, the `write_todos` tool costs:
- **Tool description** (in tools array): ~170 tokens (every turn)
- **Todo context block** (in user message): variable, ~5-15 tokens per item
- **Todo reminder** (in `<system_reminder>`): ~30-50 tokens
- **Function call output** (in history): ~10-20 tokens per item (from JSON output)

With 5 todo items, that's roughly **170 + 50 + 40 + 75 = ~335 tokens per turn**. Over 20 turns = ~6,700 tokens.

If we reduce description to ~50 tokens and output to ~10 tokens, we save ~130 tokens/turn = ~2,600 tokens over 20 turns (**~39% reduction** in todo-related token cost).

---

## 4. Improvement Plan

### 4.1 Slim the Tool Description (HIGH IMPACT)

**Current** (~170 tokens):
```
Manage a todo list for tracking progress on complex tasks.

Use this when:
- Task requires multiple steps
- You need to track progress

Do NOT use for:
- Simple single-step tasks
- Q&A queries

Statuses:
- pending: Not started
- in_progress: Currently working (only ONE at a time)
- completed: Successfully done
- cancelled: No longer needed

Always pass the FULL list. This replaces the previous list.
```

**Proposed** (~85 tokens):
```
Update the task plan. Pass the FULL list (replaces previous).
Each item has description + status (pending, in_progress, completed, cancelled).
At most one item can be in_progress at a time.
Update todos when new requirements appear during execution.
Do not use for tasks that need only 1-2 actions.
```

**Rationale**: Keep the description compact but include two high-value behavior rules directly in the tool schema ("update as you discover", "skip 1-2 action tasks"). This avoids adding a large planning block to system prompts while still improving behavior.

### 4.2 Enhance `agent_thought` for Plan Change Rationale

Rather than adding a separate `explanation` parameter (like Codex), repurpose `agent_thought` to serve double duty. Update its schema description:

**Current:** `"Brief reason for why this update is being performed"`
**Proposed:** `"Brief reason for this update. When changing the plan, explain what changed and why."`

**Rationale**: Avoids schema bloat while capturing the Codex `explanation` use case. Consistent with all other tools using `agent_thought`.

### 4.3 Minimize Tool Output (HIGH IMPACT)

**Current**: Returns full JSON `{"todos":[...],"count":N}`.

**Proposed**: Return `"Plan updated (N items)."` (like Codex's `"Plan updated"`).

**Rationale**: The full todo list is already:
1. Injected into the user message context block
2. Summarized in the `<system_reminder>` todo reminder

Repeating it in the function_call_output is pure waste. Saves ~5-15 tokens per item per turn in history.

### 4.4 Do NOT Add Large Planning Guidance to System Prompt

Do not add a new long `## Planning with write_todos` block to Planner/Standalone prompts. The extra prompt text offsets the token savings from section 4.1.

Instead, keep guidance minimal and put only the two highest-impact rules in the tool description itself:
- Update todos as you discover new requirements during execution.
- Do not use `write_todos` for tasks that need only 1-2 actions.

### 4.5 Keep `cancelled` Status (for now)

Unlike Codex (3 statuses) and AutoDev (3 statuses), keep all 4. Mobile tasks are dynamic; the agent often discovers dead-end paths (layout changed, element missing, unexpected dialog). `cancelled` preserves this as explicit state instead of silently removing items.

Open naming question: if we later want stricter semantics, `failed` might be clearer than `cancelled`. For now, keep `cancelled` unchanged.

### 4.6 Do NOT Add `priority` or `id` Fields

AutoDev's `priority` (high/medium/low) and `id` fields add 4 required fields per item. Analysis:

- **`priority`**: Our agent executes steps sequentially (not choosing between priorities), and the turn budget already creates urgency. Adding priority would add ~10 tokens per item with minimal behavioral benefit.
- **`id`**: Only useful for merge/incremental updates. With full-replacement semantics, the LLM re-emits the whole list anyway.

### 4.7 Defer Merge Semantics

Currently all structured implementations (ours, Gemini, Codex, AutoDev) use full-replacement. This means updating one item's status re-emits the entire list. 

**Option A (keep)**: Full replacement. Simpler. All references use this.
**Option B (future)**: Add optional merge mode with `id`-based updates.

**Recommendation**: Keep Option A now; do not add merge semantics in this iteration.

---

## 5. Summary of Changes

| Change | Type | Token Impact | Effort | Source |
|--------|------|-------------|--------|--------|
| Slim tool description (~170→~85 tokens) | Tool schema | **-85 tokens/turn** | Low | Codex pattern |
| Minimize output ("Plan updated (N items).") | Tool handler | **-30-100 tokens/turn** (in history) | Low | Codex pattern |
| Enhance `agent_thought` for plan change rationale | Tool schema | +5 tokens (one-time) | Low | Codex `explanation` |
| Fold minimal behavior guidance into tool description (2 lines) | Tool schema | +20 tokens/turn (vs ultra-minimal) | Low | Qi note decision |
| Keep `cancelled` status | No change | 0 | None | Mobile agent flexibility |
| Do NOT add `priority`/`id` fields | No change | 0 | None | Avoid AutoDev token bloat |

**Estimated net savings**: ~110-180 tokens/turn → **~2,200-3,600 tokens over 20 turns**

---

## 6. Proposed New Tool Description

```
Update the task plan. Pass the FULL list (replaces previous).
Each item has description + status (pending, in_progress, completed, cancelled).
At most one item can be in_progress at a time.
Update todos when new requirements appear during execution.
Do not use for tasks that need only 1-2 actions.
```

## 7. System Prompt Decision

No large `write_todos` planning block will be added to system prompts in this iteration.
Keep existing prompt text and rely on the tightened tool description + output minimization.

---

## 8. Code Evidence Locations

### Code Agent References

| Reference | File | Key Content |
|-----------|------|-------------|
| **Gemini CLI tool** | `.reference/code_agent/gemini-cli/packages/core/src/tools/write-todos.ts` | Full tool class + WRITE_TODOS_DESCRIPTION |
| **Gemini CLI system prompt** | `.reference/code_agent/gemini-cli/packages/core/src/prompts/snippets.ts:403-410` | write_todos in "Plan" workflow step |
| **Gemini CLI docs** | `.reference/code_agent/gemini-cli/docs/tools/todos.md` | User-facing documentation |
| **Codex tool handler** | `.reference/code_agent/codex/codex-rs/core/src/tools/handlers/plan.rs` | `handle_update_plan()`, returns "Plan updated" |
| **Codex tool schema** | `.reference/code_agent/codex/codex-rs/core/src/tools/handlers/plan.rs` | `PLAN_TOOL` LazyLock static |
| **Codex system prompt (planning)** | `.reference/code_agent/codex/codex-rs/core/prompt.md:52-121` | `## Planning` + high/low quality examples |
| **Codex system prompt (tool)** | `.reference/code_agent/codex/codex-rs/core/prompt.md:267-275` | `## update_plan` usage rules |
| **Codex plan mode separation** | `.reference/code_agent/codex/codex-rs/core/templates/collaboration_mode/plan.md:11-15` | "update_plan is a checklist/progress/TODOs tool" |
| **Codex protocol types** | `.reference/code_agent/codex/codex-rs/protocol/src/plan_tool.rs` | `UpdatePlanArgs`, `PlanItemArg`, `StepStatus` |

### Mobile Agent References

| Reference | File | Key Content |
|-----------|------|-------------|
| **AutoDev todo tool** | `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/todo_list.py` | `update_todos` tool + `TodoList` class + TODO_LIST_DESCRIPTION |
| **AutoDev planning prompt** | `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/prompts.py:30-45` | `=== PLANNING STRATEGY ===` section |
| **AutoDev agent integration** | `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/autodev_agent.py:472-479` | `update_todos` tool call handling |
| **DroidRun manager prompt** | `.reference/mobile_agent/droidrun/droidrun/config/prompts/manager/system.jinja2` | `<plan>` XML tag instructions |
| **DroidRun response parser** | `.reference/mobile_agent/droidrun/droidrun/agent/manager/prompts.py:8-106` | `parse_manager_response()` — extracts plan, thought, memory |
| **DroidRun state** | `.reference/mobile_agent/droidrun/droidrun/agent/droid/state.py:42-54` | `plan`, `current_subgoal`, `progress_summary` fields |
| **Minitap subgoal model** | `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/planner/types.py` | `Subgoal` class with status, timestamps, completion_reason |
| **Minitap planner prompt** | `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/planner/planner.md` | Subgoal guidelines: "purpose-driven, sequential, not too granular" |
| **Minitap orchestrator** | `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/orchestrator/orchestrator.py` | Subgoal completion tracking + replanning trigger |
| **Minitap scratchpad** | `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/tools/scratchpad.py` | `save_note`, `read_note`, `list_notes` |
| **MobileAgent-v3 InfoPool** | `.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/mobile_v3/utils/mobile_agent_e.py:6-46` | `InfoPool` dataclass (plan, completed_plan, progress_status) |
| **MobileAgent-v3 Manager** | `.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/mobile_v3/utils/mobile_agent_e.py:56-154` | Plan creation/update prompts + response parsing |
| **MobileAgent-v3 Executor** | `.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/mobile_v3/utils/mobile_agent_e.py:187-272` | Uses `plan` + `current_subgoal` from InfoPool |

### Our Implementation

| Reference | File | Key Content |
|-----------|------|-------------|
| **Tool impl** | `app/src/main/kotlin/.../tool/impl/WriteTodosTool.kt` | Current tool spec + handler |
| **State** | `app/src/main/kotlin/.../session/TodoState.kt` | `TodoState`, `toPromptContext()` |
| **Context injection** | `app/src/main/kotlin/.../agent/cognition/prompt/PromptUtils.kt:107-133` | `buildTodoReminder()` |
