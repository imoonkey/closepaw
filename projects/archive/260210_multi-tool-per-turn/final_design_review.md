# Multi-Tool Per Turn: Final Design Review

## 1. Review Scope
Reviewed docs under `doc/todo/0.03_multi-tool-per-turn/`:
- `design_1.md`
- `design_2.md`
- `design_review_gemini.md`
- `qi_note.md`

## 2. Final Decision (TL;DR)
Adopt **Design 1 as the mainline**: minimal invasive change, keep runtime simple, no new scheduler framework.

Absorb key advantages from Design 2:
- Prompt updates should explicitly teach multi-tool-per-turn behavior.
- Use explicit execution semantics: cognitive tools can batch with one UI tool, `complete_task` handled as an end-of-turn signal.
- Safety default: unknown tools should be treated conservatively as screen-affecting.
- Add lightweight observability for selected/dropped calls.

## 3. Why This Direction
- The real bottleneck is LLM + UI transition, not in-memory state writes.
- Current issue is policy-level dropping, not executor capability.
- Parallelizing state tools now adds concurrency cost with low practical gain.
- We still keep a clean upgrade path to parallel state execution later if needed.

## 4. Final Design

### 4.1 Tool Classification (Policy-Level)
Add a classification property on `ToolName` (Design 1 style), but with safer mapping:

- **Screen-affecting (`true`)**:
  - `mobile_action`
  - `open_app`
  - `system_button`
  - `wait`
  - `delegate_task` (conservative: sub-agent may mutate UI)
  - `unknown` (safe fallback)
- **Non-screen (`false`)**:
  - `write_todos`
  - `scratchpad`
  - `complete_task` (completion signal, not UI mutation)

This keeps classification colocated with tool identity, without introducing extra policy abstractions.

### 4.2 Arbitration Rule (Core)
Replace single-tool hard gate with:
1. Keep **all non-screen non-completion** tools.
2. Keep **at most one screen-affecting** tool (first one).
3. Keep `complete_task` **only if no screen-affecting tool is selected**.

Result:
- No unnecessary dropping of cognitive tools.
- Preserve one-UI-action safety invariant.
- Preserve completion deferral when UI action is pending.

### 4.3 Execution Order
Keep execution sequential (KISS):
1. Cognitive tools first (`write_todos`, `scratchpad`)
2. Screen-affecting tool last (if any)
3. Completion decision after tool execution

Reason:
- Avoid concurrency complexity today.
- Keep loop-detection/action-signature behavior stable by making UI action the last concrete action in the turn.

### 4.4 Completion Semantics
Update completion rule from:
- old: `isComplete && !hasNonCompletionTool`

To:
- new: `isComplete && !hasScreenAction`

Meaning:
- `complete_task + write_todos/scratchpad` can complete in same turn.
- `complete_task + mobile/open_app/...` defers completion to next turn.

### 4.5 Prompt Updates (From Design 2, simplified)
Update tool-calling sections in:
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/ExecutorAgentDef.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/PlannerAgentDef.kt` (if applicable to its allowed tools)

Suggested wording:
- You may call multiple tools in one turn.
- Prefer at most one screen-affecting action per turn.
- You may combine non-screen-affecting tools (`write_todos`, `scratchpad`) with that action.
- Use `complete_task` only when no further screen action is required in this turn.

Also remove/replace strict legacy phrasing like “Execute ONE UI action per turn” where it blocks multi-tool cognitive batching semantics.

### 4.6 Observability
Keep existing arbitration trace model with minimal change:
- Move away from single `selectedTool` reliance.
- Prefer list/count-based fields (`selectedToolCalls`, `selectedToolCount`, dropped names).
- Warning messages should report kept vs dropped tool names.

No need to introduce a brand-new trace event schema in this phase.

## 5. Explicitly Out of Scope (Now)
- No concurrent execution for state tools.
- No full scheduler/planner framework.
- No broad refactor replacing `TurnToolPolicy` with a new subsystem.
- No aggressive trace model rewrite.

## 6. Concrete File-Level Impact
Primary:
- `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolName.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/TurnToolPolicy.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/trace/ArbitrationTrace.kt`

Prompt updates:
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/ExecutorAgentDef.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/PlannerAgentDef.kt`

Tests:
- `app/src/test/kotlin/com/moonkey/androidagent/agent/cognition/policy/TurnToolPolicyTest.kt`

## 7. Test Plan (Must Have)
1. Multiple cognitive tools are all kept.
2. Multiple screen tools: first kept, rest dropped.
3. Mixed cognitive + one screen: all cognitive + screen kept.
4. `complete_task` alone: completes.
5. `complete_task` + cognitive only: completes.
6. `complete_task` + screen: completion deferred.
7. Unknown tool is treated as screen-affecting.
8. Selected order is cognitive-first then screen.

## 8. Risk Notes
- `delegate_task` classification is the main nuance.
  - Final recommendation uses conservative classification as screen-affecting to avoid hidden UI side-effects in the same turn.
- If future profiling shows real gain, we can add optional concurrent execution for state tools behind the same arbitration contract.

## 9. Final Recommendation
Proceed with a **Design-1-led implementation** plus three Design-2 upgrades:
1. Prompt clarity update.
2. Conservative unknown/delegate safety classification.
3. Better kept/dropped observability.

This gives immediate behavior improvement with low implementation risk and keeps the architecture easy to reason about.
