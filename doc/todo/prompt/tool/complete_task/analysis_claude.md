# `complete_task` Tool — Cross-Reference Analysis & Improvement Plan

> Analyst: Claude (Opus)
> Date: 2026-02-06
> Scope: Tool schema, tool description, and how system prompts reference this tool

---

## 1. Current Implementation

### 1.1 Tool Schema

```json
{
  "name": "complete_task",
  "description": "Call this when you have finished working on the task.\n\nParameters:\n- status: \"success\" if the goal was achieved, \"failure\" if it cannot be completed\n- answer: The response to return to the user (always required). For failures, include the reason here.\n\nAlways provide a helpful answer even when failing - explain what you tried and why it didn't work.",
  "parameters": {
    "type": "object",
    "properties": {
      "status": {
        "type": "string",
        "enum": ["success", "failure"],
        "description": "Whether the task succeeded or failed"
      },
      "answer": {
        "type": "string",
        "description": "The answer or result to return to the user"
      }
    },
    "required": ["status", "answer"],
    "additionalProperties": false
  }
}
```

### 1.2 System Prompt References

| Agent | What it says about `complete_task` |
|-------|-----------------------------------|
| **Planner** | "When the overall goal is achieved, call complete_task(status="success", answer="…"). If blocked, call complete_task(status="failure", answer="…") with partial progress." |
| **Executor** | "Never call complete_task together with another action in the same turn." / "Call complete_task(status="success", answer="…") after verifying the goal on screen." / "Call complete_task(status="failure", answer="…") if blocked (include the blocker)." / Per-query-type guidance (TAP→complete, EXTRACT→complete, etc.) |
| **Standalone** | "Call complete_task(status="success", answer="…") when goal is achieved." / "If blocked, call complete_task(status="failure", answer="…") with blocker details." |

### 1.3 Implementation Code (`CompleteTaskTool.kt`)

- Validates: status ∈ {success, failure}, answer non-blank
- Output: `"Task completed successfully.\n\nAnswer: <answer>"` or `"Task failed.\n\nAnswer: <answer>"`
- Returns data map: `{completed: true, success: bool, answer: string}`
- No side effects — pure signal tool

---

## 2. Reference Implementations

### 2.1 AutoDev (android_world fork)

**Mechanism**: Two separate tools — `finish_task(success: bool)` + `answer(text: str)`

**`finish_task` definition** (`autodev/planner_tools.py:203-215`):
```python
def finish_task(success: bool):
    """
    Declare that the agent considers the task complete and wishes to terminate.
    If success=True, the agent believes it has achieved the required goal.
    If success=False, the agent is unable to complete the task.
    This will signal the environment to end the episode.
    """
```

**`answer` definition** (`autodev/planner_tools.py:176-189`):
```python
def answer(text: str):
    """
    Provide a natural-language answer or final statement to the user or system.
    This is not a UI action — it represents verbal or textual reasoning output.
    The executor does not process this; the agent returns it as the final answer.
    """
```

**System prompt guidance** (`autodev/prompts.py:194-205`):
```
=== COMPLETION ===
- Update todos after executor reports
- For multi-item tasks: Verify ALL items extracted AND ALL items processed in target app
- Verify all todos completed AND verified in app state before finish_task()
- For count/search tasks: Call answer(text="[formatted answer]") first, THEN finish_task()
- NEVER finish if todos incomplete or unverified
- NEVER finish if goal requires multiple items but only one was processed
- NEVER finish if goal asks for count/answer without calling answer() first
```

**Key detail**: The `success` parameter of `finish_task` is **not actually used** — actual success is evaluated externally by `task.is_successful(env)`.

| Aspect | Detail |
|--------|--------|
| Params | `finish_task(success: bool)`, `answer(text: str)` |
| Required calls | Q&A tasks: answer() → finish_task(). Action tasks: finish_task() only |
| Status values | success=True / False (but unused) |
| Answer extraction | Separate `answer()` tool |
| Completion gate | Strong: "NEVER finish if todos incomplete" |

---

### 2.2 DroidRun

**Mechanism**: Different per agent type.

#### CodeAct Agent — `complete(success, reason)`

**Definition** (`droidrun/tools/android/adb.py`):
```python
async def complete(self, success: bool, reason: str = "") -> None:
    """Mark the task as finished.
    Args:
        success: Indicates if the task was successful.
        reason: Reason for failure/success
    """
```
- **Requires reason for failures** (`if not reason: raise ValueError(...)`)
- Sets `self.finished = True`, creates `CodeActEndEvent`

#### Manager Agent — XML tag in natural language

**Format** (`manager/system.jinja2`):
```xml
<request_accomplished success="true">
  message confirming what was accomplished
</request_accomplished>
```
- Parsed via regex; also supports `<answer>` as alias
- Defaults to success=True if attribute missing (backward compat)

#### Scripter Agent — Implicit completion

- No explicit signal; if LLM returns a message without a `<python>` code block, it's treated as completion
- Always `success=True` — no failure mechanism

| Agent | Signal | Success/Failure | Answer |
|-------|--------|----------------|--------|
| CodeAct | `complete(success, reason)` | bool | reason string |
| Manager | `<request_accomplished success="...">` | "true"/"false" attr | tag content |
| Scripter | No code block in response | Always success | Message text |

---

### 2.3 Minitap (mobile-use)

**Mechanism**: No explicit complete_task tool. Structural completion via subgoal tracking.

**Flow**:
1. **Cortex agent** proposes: `complete_subgoals_by_ids: ["sg1", "sg2"]` + `goals_completion_reason: "..."`
2. **Orchestrator agent** validates: `completed_subgoal_ids: [...]` + `reason: "..."`
3. **Convergence gate** checks: `all_completed(state.subgoal_plan)` → "end"

**Key design**: Evidence-based completion — Cortex must observe success on screen before marking a subgoal complete. From the prompt: "Never mark a goal complete 'in advance'. Only complete based on observed evidence."

**Output extraction**: After graph terminates, an optional `Outputter` agent extracts structured output. Falls back to last agent thought.

| Aspect | Detail |
|--------|--------|
| Explicit tool | None |
| Completion signal | All subgoals reach SUCCESS status |
| Failure handling | FAILURE status → triggers replanning, not termination |
| Answer extraction | Separate Outputter agent post-completion |
| Required reasoning | `goals_completion_reason` (mandatory) |

---

### 2.4 MobileAgent V3 / V2 / Mobile-Agent-E

**Mechanism**: Multiple, varies by variant.

#### V3 (os_world) — Planner "Finished" marker + "answer" action

- Planner marks `current_subgoal = "Finished"` → converts to `{"action": "done"}`
- Q&A tasks: `{"action": "answer", "text": "..."}` terminates immediately
- Stores `finish_thought` (reasoning for completion)

#### V3 (android_world) — `status` action

```json
{"action_type": "status", "goal_status": "complete"}
{"action_type": "status", "goal_status": "infeasible"}
```

#### Mobile-Agent-E — Finish flags

```python
finish_flag ∈ {"success", "max_iteration", "max_consecutive_failures",
               "max_repetitive_actions", "abnormal"}
```
- Distinguishes voluntary completion from forced termination
- Stores `finish_thought` for reasoning

#### V2 — Simple "Stop" action

- Single "Stop" action, no success/failure distinction

| Variant | Signal | Status values | Answer | Thought |
|---------|--------|---------------|--------|---------|
| V3 os_world | "Finished" / "done" | None (binary done/not-done) | "answer" action | finish_thought |
| V3 android_world | "status" action | complete / infeasible | "answer" action | — |
| Mobile-Agent-E | "Finished" | 5 finish_flags | — | finish_thought |
| V2 | "Stop" | None | — | — |

---

### 2.5 Eval Baselines (android_world + MobileWorld)

| Agent | Signal | Format | Status Values |
|-------|--------|--------|---------------|
| **T3A/M3A** | `status` action | `{"action_type":"status","goal_status":"complete"}` | complete / infeasible |
| **T3A/M3A** | `answer` action | `{"action_type":"answer","text":"..."}` | N/A (separate) |
| **SeeAct** | `TERMINATE` text | Converts to `status` w/ `goal_status="task_complete"` | task_complete |
| **Qwen3VL** | `terminate` tool | `{"action":"terminate","status":"success/failure"}` | success / failure |
| **GELAB** | `COMPLETE` action | `action:COMPLETE\treturn:text` | N/A |

---

## 3. Cross-Reference Comparison

### 3.1 Feature Matrix

| Feature | Ours | AutoDev | DroidRun CodeAct | Minitap | MobileAgent V3 | Eval Baselines |
|---------|------|---------|-------------------|---------|-----------------|----------------|
| Single tool | ✓ | ✗ (2 tools) | ✓ | ✗ (structural) | ✗ (varies) | Mixed |
| Status enum | success/failure | bool | bool | N/A | complete/infeasible | varies |
| Answer in same call | ✓ | ✗ (separate) | ✓ (reason) | N/A | ✗ (separate) | ✗ (separate) |
| Requires answer always | ✓ | ✗ | ✗ (only on failure) | N/A | ✗ | ✗ |
| Agent thought/reasoning | ✗ | ✗ | ✗ | ✓ (goals_completion_reason) | ✓ (finish_thought) | ✗ |
| Evidence-based guidance | ✗ | ✓ (in system prompt) | ✗ | ✓ (in tool prompt) | ✗ | ✗ |
| Premature completion guard | ✗ | ✓ (NEVER finish if...) | ✗ | ✓ (convergence gate) | ✗ | ✗ |
| "Infeasible" status | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ (M3A/T3A) |

### 3.2 Pros and Cons

| Approach | Pros | Cons |
|----------|------|------|
| **Ours (unified)** | Simple, single tool call. Answer always provided. Low token cost. | No agent reasoning captured. No premature-completion guard in tool description. |
| **AutoDev (2 tools)** | Clean separation of answer vs signal. Strong completion guard in prompt. | 2-step process for Q&A = more error surface. Agent forgets to call answer() before finish. success param unused. |
| **DroidRun CodeAct** | Requires reason for failures (good debugging). | Different mechanisms per agent type = inconsistency. Scripter has no failure path. |
| **Minitap (structural)** | Evidence-based, replanning on failure, never premature. | Very complex, graph-based — not applicable to tool-call architecture. |
| **MobileAgent V3** | finish_thought captures reasoning. Multiple finish_flags for diagnostics. | Fragmented across variants. "Finished" marker is brittle string matching. |
| **Eval baselines** | Minimal, standard. "infeasible" useful for evaluation. | No answer in completion signal (separate tool). |

---

## 4. Failure Mode Analysis

Common agent failure modes related to task completion:

| Failure Mode | Frequency | Root Cause | Mitigated By |
|--------------|-----------|------------|--------------|
| **Premature completion** | HIGH | Agent calls complete_task before verifying result on screen | AutoDev's "NEVER finish if…" guard; Minitap's evidence-based rule |
| **Vague answer** | MEDIUM | Agent says "Done" without specifics | Requiring structured answer content |
| **Missing Q&A answer** | MEDIUM | For info-retrieval tasks, agent completes without providing the actual answer | AutoDev's separate answer() + "NEVER finish without answer()" |
| **Looping instead of failing** | MEDIUM | Agent doesn't call failure when stuck | Better "failure" guidance (when to give up) |
| **Wrong status** | LOW | Agent reports success when task actually failed | Evidence-based completion verification |

---

## 5. Improvement Proposal

### 5.1 Changes to Tool Description (Recommended)

**Current** (~47 tokens):
```
Call this when you have finished working on the task.

Parameters:
- status: "success" if the goal was achieved, "failure" if it cannot be completed
- answer: The response to return to the user (always required). For failures, include the reason here.

Always provide a helpful answer even when failing - explain what you tried and why it didn't work.
```

**Proposed** (~80 tokens, +33 tokens):
```
Signal task completion. Call ONLY after verifying the outcome on screen.

- status: "success" if the goal is visibly achieved, "failure" if blocked or infeasible.
- answer: Concise result for the user.
  - Success: state what was accomplished and any requested data.
  - Failure: what was tried, what blocked progress, and any partial results.

Do NOT call this before confirming the action's effect on screen.
```

**Changes explained**:

| Change | Rationale | Source |
|--------|-----------|--------|
| "Call ONLY after verifying the outcome on screen" | Guards against premature completion — the #1 failure mode. Borrowed from Minitap's evidence-based rule and AutoDev's "NEVER finish if unverified". | Minitap, AutoDev |
| "visibly achieved" | Reinforces screen-verification requirement. The word "visibly" is concise but specific. | Minitap |
| "blocked or infeasible" | Covers both "I tried and failed" and "this task is impossible" without adding a third enum value. Keeps binary status simple. | MobileAgent V3 android_world |
| "Concise result" | Guides answer length — avoids both vague "Done" and excessive verbosity. Token-efficient. | — |
| "state what was accomplished and any requested data" | For Q&A tasks, the agent must include the actual answer (e.g., "3 emails" not just "counted the emails"). Addresses the missing-Q&A-answer failure mode. | AutoDev |
| "what was tried, what blocked progress, and any partial results" | Structure for failure answers — helps debugging and lets the user recover. More specific than "explain what you tried". | DroidRun (reason required) |
| "Do NOT call this before confirming the action's effect on screen" | Belt-and-suspenders for premature completion. Short, imperative, hard to miss. | Minitap, AutoDev |
| Removed "Parameters:" header | The parameter descriptions are in the schema itself. Duplicating them in the description wastes tokens. | — |

### 5.2 Changes to Parameter Schema (Recommended)

Add `agent_thought` parameter for consistency with all other tools:

```json
"agent_thought": {
  "type": "string",
  "description": "Brief reasoning for why the task is being completed now"
}
```

**Rationale**:
- Every other tool (`mobile_action`, `app_control`, `write_todos`, `scratchpad`, `delegate_task`) has `agent_thought`
- `complete_task` is the most important tool to have reasoning on — it's the final decision
- MobileAgent V3/E stores `finish_thought` for this exact purpose
- Minitap requires `goals_completion_reason`
- Cost: ~15 schema tokens + whatever the agent writes (but this is the LAST tool call, so marginal cost)
- Benefit: debuggability, forces the agent to articulate WHY it's done

### 5.3 Changes NOT Recommended

| Considered Change | Why NOT |
|-------------------|---------|
| **Add "infeasible" as third status** | Adds decision complexity for marginal benefit. LLMs already struggle with binary decisions in edge cases. "failure" with a good answer covers the infeasible case. The eval baselines need it for scoring but our agent doesn't. |
| **Separate `answer()` tool** (AutoDev style) | Two-step completion increases error surface (agent forgets to call answer first). Our unified design is more reliable and token-efficient. AutoDev's own system prompt needs extensive guardrails to prevent this exact issue. |
| **Structured output schema** (Minitap style) | Over-engineering for a tool-call agent. If structured output is needed, the answer field can contain JSON. Adding schema complexity increases prompt tokens for all calls. |
| **Multiple finish flags** (Mobile-Agent-E style) | Our system already handles forced termination externally (ExecutorStepPolicy, LoopDetectionPolicy). The agent doesn't need to self-report these conditions. |
| **Implicit completion** (DroidRun Scripter style) | Unreliable — needs explicit signal for robust loop control. |

### 5.4 System Prompt Changes (Recommended, separate from tool)

These are improvements to how system prompts reference `complete_task`. Not part of the tool schema change, but work synergistically.

#### Planner — add completion checklist

Current:
```
- When the overall goal is achieved, call complete_task(status="success", answer="...").
- If blocked, call complete_task(status="failure", answer="...") with partial progress.
```

Proposed (add after existing lines):
```
- Before calling complete_task, verify: (1) all todos completed, (2) outcome confirmed on screen.
- For information/count tasks: include the specific answer (e.g., "3 unread emails") in the answer field.
```

#### Executor — no changes needed

The executor system prompt already has good per-query-type completion guidance.

#### Standalone — add verification line

Current:
```
- Call complete_task(status="success", answer="...") when goal is achieved.
```

Proposed:
```
- Call complete_task(status="success", answer="...") after verifying the goal on screen.
```

---

## 6. Token Cost Analysis

| Component | Current Tokens | Proposed Tokens | Delta |
|-----------|---------------|-----------------|-------|
| Tool description | ~47 | ~80 | +33 |
| `agent_thought` param schema | 0 | ~15 | +15 |
| Planner system prompt addition | 0 | ~25 | +25 |
| Standalone system prompt change | ~12 | ~14 | +2 |
| **Total per request** | — | — | **+75** |

This is a ~75 token increase per LLM request. Given that `complete_task` guidance is critical for task success rate, and the tool schema is sent every turn, the ROI is high:

- **75 tokens × ~10 turns** = ~750 tokens/session additional cost
- At GPT-4o pricing (~$2.50/M input tokens) = ~$0.002/session
- If this prevents even 1 in 50 premature completions, the success-rate improvement far outweighs cost

---

## 7. Final Proposed Schema

```json
{
  "type": "function",
  "name": "complete_task",
  "description": "Signal task completion. Call ONLY after verifying the outcome on screen.\n\n- status: \"success\" if the goal is visibly achieved, \"failure\" if blocked or infeasible.\n- answer: Concise result for the user.\n  - Success: state what was accomplished and any requested data.\n  - Failure: what was tried, what blocked progress, and any partial results.\n\nDo NOT call this before confirming the action's effect on screen.",
  "parameters": {
    "type": "object",
    "properties": {
      "status": {
        "type": "string",
        "enum": ["success", "failure"],
        "description": "Whether the task succeeded or failed"
      },
      "answer": {
        "type": "string",
        "description": "The answer or result to return to the user"
      },
      "agent_thought": {
        "type": "string",
        "description": "Brief reasoning for why the task is being completed now"
      }
    },
    "required": ["status", "answer"],
    "additionalProperties": false
  },
  "strict": false
}
```

---

## 8. Summary of Recommendations

| # | Change | Type | Priority | Token Cost |
|---|--------|------|----------|------------|
| 1 | Rewrite tool description with verification gate and structured answer guidance | Tool description | **HIGH** | +33 tokens |
| 2 | Add `agent_thought` parameter | Tool schema | **MEDIUM** | +15 tokens |
| 3 | Add completion checklist to Planner system prompt | System prompt | **MEDIUM** | +25 tokens |
| 4 | Add "verify on screen" to Standalone system prompt | System prompt | **LOW** | +2 tokens |

**Not recommended**: third status value, separate answer tool, structured output schema, finish flags, implicit completion.

---

## Appendix A: Full Reference Evidence Locations

| Repo | File | Lines | What |
|------|------|-------|------|
| AutoDev | `agents/autodev/planner_tools.py` | 203-215 | `finish_task` definition |
| AutoDev | `agents/autodev/planner_tools.py` | 176-189 | `answer` definition |
| AutoDev | `agents/autodev/prompts.py` | 194-205 | Completion workflow prompt |
| AutoDev | `agents/autodev_agent.py` | 458-471 | `finish_task` execution |
| DroidRun | `tools/android/adb.py` | — | `complete(success, reason)` |
| DroidRun | `config/prompts/codeact/system.jinja2` | 6-7 | Completion instructions |
| DroidRun | `config/prompts/manager/system.jinja2` | 181-199 | `<request_accomplished>` format |
| Minitap | `agents/cortex/types.py` | 4-15 | `CortexOutput` (complete_subgoals_by_ids) |
| Minitap | `agents/cortex/cortex.md` | 22-23 | Evidence-based completion rule |
| Minitap | `agents/orchestrator/types.py` | 6-11 | `OrchestratorOutput` |
| Minitap | `graph/graph.py` | 39-57 | Convergence gate |
| MobileAgent V3 | `mobile_agent.py` | 326-330 | "Finished" processing |
| MobileAgent V3 | `m3a.py` | 43-49, 509-517 | `status` action |
| Mobile-Agent-E | `inference_agent_E.py` | 753-805 | Finish flags |
| Eval android_world | `agents/t3a.py` | 41-50, 413-421 | `status` + `answer` actions |
| Eval MobileWorld | `agents/utils/prompts.py` | 92-93 | `terminate` tool (Qwen3VL) |
