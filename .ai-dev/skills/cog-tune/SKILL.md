---
name: cog-tune
description: Analyze Android Agent cognition using debug-run traces and replay artifacts, then propose and implement improvements to prompts, tool definitions, context packing (todo/scratchpad/history), and multi-agent coordination. Use when a debug run feels wrong, when tuning context engineering for generalizable gains, or when reviewing LLM input/output and tool usage; produce both a report and code/doc changes.
---

# Cog Tune

## Overview

Improve agent cognition by inspecting debug-run traces, LLM inputs/outputs, and tool calls, then applying generalizable prompt/context/tool changes backed by evidence.

## Workflow

### 1. Set scope and guardrails

- Clarify the failing behavior and the target runs.
- Prefer generalizable changes; avoid one-off hacks for a single task.
- Require evidence from traces: show which step(s) and artifacts support each proposed change.

### 2. Prepare replay data

- Use latest run or a provided run directory under `debug-output/`.
- If derived replay files are missing/outdated, compile them:
  - `python3 inspection_tool/replay_compiler.py <run_dir>/trace`
- Optional helper: `python3 .ai-dev/skills/cog-tune/scripts/prepare_cog_review.py --latest`
  - Produces a `cognition_review.md` with per-step artifact paths.

### 3. Inspect cognition step-by-step

Use `trace/derived/steps.jsonl` plus artifacts in `trace/artifacts/`:

- **World**: `screenshot`, `raw_a11y_tree`, `sanitized_a11y_tree`
- **Mind**: `llm_system_prompt`, `llm_user_context`, `llm_full_prompt`, `llm_input_items`, `llm_history`, `llm_tool_calls`
- **Act/Observe**: `tool_call_args`, `tool_result`, `tool_observation_screen`

Look for mismatches between:
- Screen state vs. what the model believed
- Tool call args vs. available UI elements
- History/todo/scratchpad vs. chosen action
- Planner vs. executor handoff (delegation summaries)

### 4. Classify root cause

Bucket issues before changing prompts:
- **Perception** (missing/incorrect a11y data)
- **Context** (missing/overloaded system/user context)
- **Reasoning** (bad choice despite correct inputs)
- **Execution** (tool call failure or wrong target)
- **Observation** (post-action state not captured)
- **Orchestration** (multi-agent handoff gaps)

### 5. Apply changes (minimal, generalizable)

Possible change areas:

- **Prompt assembly**: `agent/cognition/prompt/PromptAssembler.kt`, `AgentPromptBuilder.kt`
- **Context packing**: `agent/cognition/context/ContextPackager.kt`
- **Policies**: `agent/cognition/policy/*`
- **Tool schemas**: `tool/ToolSpec.kt`, tool impls under `tool/impl/`
- **Delegation**: `tool/impl/DelegateTaskTool.kt`, `agent/subagent/*`

Use `rg` to locate prompt or tool definition text before edits.

### 6. Validate and generalize

- Re-run at least one additional task to avoid overfitting.
- If improvement is narrow, document the limitation and keep change small.
- If uncertainty remains, propose instrumentation/trace improvements instead of guessing.

## Output format

Provide a report with:

- **Summary**: what went wrong, and evidence (step IDs, artifact paths)
- **Root cause**: category + reasoning
- **Proposed changes**: concise list with impacted files
- **Patch**: actual code/doc updates
- **Verification plan**: runs to re-test

## Project references

- Cognition roadmap: `doc/todo/cognition/design_proposal.md`
- Trace/replay design: `doc/todo/tracking/final_design_codex.md`
- Debug workflow: `scripts/agent_process_visual_debug.md`
- Replay compiler: `inspection_tool/replay_compiler.py`

## External best practices

See `references/llm_best_practices.md` for prompt/tool/multi-agent guidance and sources.
