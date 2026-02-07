# `write_todos` Tool — Cross-Implementation Analysis

> Analyst: Claude | Date: 2026-02-06
> References: gemini-cli `write_todos`, codex `update_plan`, Android Agent `write_todos`

---

## 1. Implementation Comparison

### 1.1 Overview Table

| Dimension | Android Agent (`write_todos`) | Gemini CLI (`write_todos`) | Codex (`update_plan`) |
|-----------|-------------------------------|----------------------------|-----------------------|
| **Description token cost** | ~170 tokens | ~500 tokens | ~30 tokens (bulk in system prompt) |
| **Status values** | 4: pending, in_progress, completed, cancelled | 4: same | 3: pending, in_progress, completed |
| **Item field name** | `description` | `description` | `step` |
| **Extra params** | `agent_thought` | none | `explanation` |
| **Output to LLM** | JSON `{todos: [...], count: N}` | `"Successfully updated the todo list. The current list is now:\n1. [status] desc"` | `"Plan updated"` |
| **Step length guidance** | None | None | "5-7 words each" in system prompt |
| **"Don't repeat" rule** | No | No | Yes: "Do not repeat the full contents of the plan after an update_plan call" |
| **Quality examples** | None | When-to-use/not-use examples in description | High/low quality step examples in system prompt |
| **Methodology** | None | 7-step methodology in description | Behavioral rules in system prompt |
| **System prompt integration** | Brief mention: "Use write_todos to track progress" | Integrated into "Plan" workflow step | Dedicated `## Planning` section + `## update_plan` section |
| **Context injection** | Full todo list in user message + summary reminder in `<system_reminder>` | Todo list rendered in UI, not re-injected into context | Harness renders; not re-injected |

### 1.2 Gemini CLI — Detailed Analysis

**File:** `packages/core/src/tools/write-todos.ts`

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

1. **Very verbose description** (~500 tokens): includes a full "Methodology for using this tool" (7 steps), "Task state definitions" section, positive example (React logo creator with 8 steps + reasoning), and negative example (test loop without todo). This is sent with **every** API call since tool schemas are part of the request.

2. **No extra parameters**: no `agent_thought` or `explanation` field — the model writes thought inline in its response text.

3. **Human-readable output**: Returns `"Successfully updated the todo list. The current list is now:\n1. [pending] Initialize React project\n..."` — this is friendly but adds tokens to every subsequent turn via history.

4. **System prompt integration**: The system prompt mentions `write_todos` in the "Plan" workflow step:
   > "For complex tasks, break them down into smaller, manageable subtasks and use the `write_todos` tool to track your progress."

5. **UI rendering**: The current `in_progress` task is shown above the input box. Full list toggleable via `Ctrl+T`. The tool is an optional feature (`useWriteTodos` config flag).

**Pros:**
- Exhaustive description ensures model knows when/how to use tool even without system prompt reinforcement
- Positive and negative examples in description directly steer model behavior
- 7-step methodology is prescriptive
- `cancelled` status supports dynamic replanning

**Cons:**
- ~500 token description = significant cost per turn (description is sent every turn)
- Redundancy: the description duplicates guidance that could be in the system prompt (and is less expensive there since system prompt is cached in many APIs)
- Human-readable output adds token overhead to history
- No `explanation` field — model can't communicate *why* the plan changed structurally
- No step length guidance — can lead to verbose, paragraph-length todo items

### 1.3 Codex — Detailed Analysis

**Files:** `codex-rs/core/src/tools/handlers/plan.rs`, `codex-rs/core/prompt.md`

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

1. **Ultra-minimal description** (~30 tokens): just states what the tool does and the one-in_progress constraint. All behavioral guidance lives in the **system prompt**.

2. **`explanation` parameter**: Optional string for rationale when changing the plan. The code comment says: "it gives the model a structured way to record its plan that clients can read and render. So it's the _inputs_ to this function that are useful to clients."

3. **Minimal output**: Returns just `"Plan updated"` — the harness already displays the plan. The system prompt explicitly says: "Do not repeat the full contents of the plan after an `update_plan` call — the harness already displays it."

4. **Only 3 statuses**: No `cancelled`. Simplifies schema and reduces model choice space.

5. **Step length constraint** in system prompt: "1-sentence steps (no more than 5-7 words each)" — critical for controlling token costs of plan items.

6. **Quality examples** in system prompt: 3 high-quality examples and 3 low-quality examples showing the contrast. High quality plans have specific, actionable 5-7 word steps. Low quality plans are too vague.

7. **Behavioral rules** in system prompt:
   - "Before running a command, consider whether or not you have completed the previous step, and make sure to mark it as completed before moving on to the next step."
   - "Sometimes, you may need to change plans in the middle of a task: call `update_plan` with the updated plan and make sure to provide an `explanation` of the rationale when doing so."
   - "There should always be exactly one `in_progress` step until everything is done."
   - When to use list (6 criteria)

8. **Explicit separation from Plan Mode**: "Plan Mode is a collaboration mode... Separately, `update_plan` is a checklist/progress/TODOs tool."

**Pros:**
- ~30 token description = minimal per-turn cost (system prompt is often cached by API providers)
- `explanation` field provides structured change rationale
- "5-7 words per step" constraint keeps token costs low
- "Don't repeat plan" instruction prevents token waste
- High/low quality examples calibrate model behavior
- Output is just "Plan updated" — minimal history bloat

**Cons:**
- No `cancelled` status — less flexible for dynamic replanning
- Relies heavily on system prompt — if system prompt is truncated, guidance is lost
- `step` field name is less descriptive than `description` for mobile UI actions
- No when-to-use examples in the description itself

---

## 2. Current Android Agent Implementation Assessment

### 2.1 What's Working Well

1. **`agent_thought` parameter** — consistent with all other tools, supports reasoning traces
2. **`cancelled` status** — valuable for mobile tasks where plans change frequently
3. **Validation** — proper in_progress count enforcement
4. **Todo reminder** in `<system_reminder>` — summarizes actionable items without full list repetition
5. **Full todo list in user message** — necessary since our model doesn't have a separate rendering channel

### 2.2 Current Issues

1. **Description is mid-weight (~170 tokens) but lacks key behavioral guidance**: no step length constraint, no quality examples, no methodology. It's a middle ground that gets neither the token efficiency of Codex nor the behavioral richness of Gemini.

2. **JSON output adds unnecessary tokens to history**: Returning `{"todos":[...],"count":3}` means the full todo state is stored in history as a function_call_output. Since we also inject todos into the user message each turn, this is redundant.

3. **No "don't repeat plan" instruction**: The model may echo the full todo list after updating it, wasting tokens.

4. **No step length guidance**: Steps can become verbose paragraphs, costing tokens in both the todo context block and reminder.

5. **System prompt mentions are too brief**: Just "Use `write_todos` and `scratchpad` to track progress" — no guidance on when to use, how to write good steps, or behavioral constraints.

6. **Missing `explanation` parameter**: When the plan changes mid-task, there's no structured way to record why (unlike Codex).

### 2.3 Token Budget Analysis

Per turn, the `write_todos` tool costs:
- **Tool description** (in tools array): ~170 tokens (every turn)
- **Todo context block** (in user message): variable, ~5-15 tokens per item
- **Todo reminder** (in `<system_reminder>`): ~30-50 tokens
- **Function call output** (in history): ~10-20 tokens per item (from JSON output)

With 5 todo items, that's roughly **170 + 50 + 40 + 75 = ~335 tokens per turn** attributable to todos. Over 20 turns, that's ~6,700 tokens.

If we reduce description to ~50 tokens and output to ~10 tokens, we save ~130 tokens/turn = ~2,600 tokens over 20 turns (a ~39% reduction in todo-related token cost).

---

## 3. Improvement Plan

### 3.1 Slim the Tool Description (HIGH IMPACT)

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

**Proposed** (~65 tokens):
```
Update the task plan. Pass the FULL list (replaces previous). Each item has a description and status. At most one item can be in_progress at a time. Do not use for simple single-action tasks.
```

**Rationale**: Follow the Codex pattern — move behavioral details to the system prompt where they benefit from prompt caching. Keep the description to the essential contract (replacement semantics, one-in_progress constraint, when not to use).

### 3.2 Add `explanation` Parameter

Add an optional `explanation` string parameter (like Codex) in addition to keeping `agent_thought`.

```json
"explanation": {
  "type": "string",
  "description": "Rationale when changing the plan (e.g., adding/removing/reordering steps)"
}
```

**Rationale**: `agent_thought` is a brief per-call reasoning trace. `explanation` is specifically for plan change rationale and is useful for:
- Debugging plan evolution
- Context for the model when it reviews its own history
- User visibility into why the plan changed

**Alternative**: Repurpose `agent_thought` for this, noting in the description that it should explain plan changes. This avoids schema bloat. **Recommended: go with this alternative** — just update the `agent_thought` description to: "Brief reason for this update. When changing the plan, explain what changed and why."

### 3.3 Minimize Tool Output (HIGH IMPACT)

**Current**: Returns full JSON `{"todos":[...],"count":N}` — redundant since todos are injected into context each turn.

**Proposed**: Return just `"Plan updated (N items)."` (like Codex's `"Plan updated"`).

**Rationale**: The full todo list is already:
1. Injected into the user message context block
2. Summarized in the `<system_reminder>` todo reminder

Repeating it in the function_call_output is pure waste. Saves ~5-15 tokens per item per turn in history.

### 3.4 Add Step Quality Guidance to System Prompt (HIGH IMPACT)

Add to both Planner and Standalone system prompts:

```
## Todo List (write_todos)
Use write_todos for multi-step tasks to track progress. Keep each item to ONE short sentence.
Do not repeat the full todo list after updating it — it is already shown in context.
Mark the current step in_progress before starting it. Mark it completed when done before moving on.

Good steps: "Open Gmail app", "Extract sender from first email", "Navigate back to inbox"
Bad steps: "Open the Gmail application on the device and wait for it to load completely" (too verbose)
```

**Rationale**: Codex's system prompt has extensive planning guidance (high/low quality examples, behavioral rules, "don't repeat" instruction). Our system prompt says almost nothing about how to use todos well. This is the highest-leverage change: it steers model behavior without adding per-turn schema cost.

### 3.5 Keep `cancelled` Status

Unlike Codex (3 statuses), keep all 4 statuses. Mobile tasks are more dynamic — the agent frequently discovers that planned UI paths are unavailable (app changed, element not found, etc.), and `cancelled` communicates this clearly vs. silently dropping items.

### 3.6 Optional: Add Merge Semantics

Currently both our implementation and all references use full-replacement semantics. This means to update one item's status, the model must re-emit the entire list. With 5+ items, this is wasteful.

**Option A (conservative)**: Keep full replacement. Simpler, matches all references.
**Option B (incremental)**: Add an optional `updates` parameter for partial updates by index.

**Recommendation**: Keep Option A for now. Full replacement is what all references use and keeps the contract simple. If profiling shows the model generating >7 items frequently, revisit.

---

## 4. Summary of Changes

| Change | Type | Token Impact | Effort |
|--------|------|-------------|--------|
| Slim tool description (~170→~65 tokens) | Tool schema | -105 tokens/turn | Low |
| Minimize output ("Plan updated (N items).") | Tool handler | -30-100 tokens/turn (in history) | Low |
| Enhance `agent_thought` description for plan change rationale | Tool schema | +5 tokens (one-time) | Low |
| Add planning guidance to system prompts | System prompt | +80 tokens (cached by API) | Medium |
| Keep `cancelled` status | No change | 0 | None |

**Estimated net savings**: ~135-205 tokens/turn → ~2,700-4,100 tokens over 20 turns

---

## 5. Proposed New Tool Description

```
Update the task plan. Pass the FULL list (replaces previous).
At most one item can be in_progress at a time.
Do not use for simple single-action tasks.

Statuses: pending, in_progress, completed, cancelled.
```

## 6. Proposed System Prompt Addition (for Planner and Standalone)

```
## Planning with write_todos
- Use write_todos to break multi-step tasks into short, actionable items.
- Keep each item to ONE short sentence (e.g., "Open Gmail app", "Extract sender info").
- Do NOT repeat the full todo list after updating — it is already shown in your context.
- Mark the current step in_progress before working on it. Mark it completed when done.
- When the plan changes, use agent_thought to explain what changed and why.
- Do not use write_todos for tasks that need only 1-2 actions.
```

## 7. Code Evidence Locations

| Reference | File | Key Content |
|-----------|------|-------------|
| **Gemini CLI tool** | `.reference/code_agent/gemini-cli/packages/core/src/tools/write-todos.ts` | Full tool class + WRITE_TODOS_DESCRIPTION constant |
| **Gemini CLI system prompt** | `.reference/code_agent/gemini-cli/packages/core/src/prompts/snippets.ts:403-410` | "use the `write_todos` tool to track your progress" |
| **Gemini CLI docs** | `.reference/code_agent/gemini-cli/docs/tools/todos.md` | User-facing documentation |
| **Codex tool handler** | `.reference/code_agent/codex/codex-rs/core/src/tools/handlers/plan.rs` | `handle_update_plan()`, returns "Plan updated" |
| **Codex tool schema** | `.reference/code_agent/codex/codex-rs/core/src/tools/handlers/plan.rs` | `PLAN_TOOL` LazyLock static |
| **Codex system prompt** | `.reference/code_agent/codex/codex-rs/core/prompt.md:52-121` | `## Planning` section + high/low quality examples |
| **Codex system prompt** | `.reference/code_agent/codex/codex-rs/core/prompt.md:267-275` | `## update_plan` section |
| **Codex plan mode separation** | `.reference/code_agent/codex/codex-rs/core/templates/collaboration_mode/plan.md:11-15` | "update_plan is a checklist/progress/TODOs tool" |
| **Codex protocol types** | `.reference/code_agent/codex/codex-rs/protocol/src/plan_tool.rs` | `UpdatePlanArgs`, `PlanItemArg`, `StepStatus` |
| **My tool impl** | `app/src/main/kotlin/.../tool/impl/WriteTodosTool.kt` | Current tool spec + handler |
| **My state** | `app/src/main/kotlin/.../session/TodoState.kt` | `TodoState`, `toPromptContext()` |
| **My context injection** | `app/src/main/kotlin/.../agent/cognition/prompt/PromptUtils.kt:107-133` | `buildTodoReminder()` |
