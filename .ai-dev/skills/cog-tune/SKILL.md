---
name: cog-tune
description: Analyze Android Agent cognition using debug-run traces/replay artifacts and eval results, then propose and implement improvements to prompts, tool definitions, context packing (todo/scratchpad/history), and multi-agent coordination. Use when a debug run feels wrong, when eval metrics regress, when tuning context engineering for generalizable gains, or when reviewing LLM input/output and tool usage; produce both a report and code/doc changes.
---

# Cog Tune

## Overview

Improve agent cognition by inspecting debug-run traces, eval metrics/results, screenshots/screen observations, LLM inputs/outputs, and tool calls, then applying generalizable prompt/context/tool changes backed by evidence.

## Workflow

### 1. Set scope and guardrails

- Clarify the failing behavior, target runs, and target metric(s).
- If tuning from eval, define baseline run and comparison run up front.
- Prefer generalizable changes; avoid one-off hacks for a single task.
- Require evidence from traces and/or eval artifacts: show which step(s), run IDs, and metrics support each proposed change.

### 2. Prepare evidence data

Pick one or both entry points:

- **Debug-run entry**:
  - Use latest run or a provided run directory under `debug-output/`.
  - Use inspection tool virtualenv for inspection scripts: `inspection_tool/.venv/bin/python ...`
  - If derived replay files are missing/outdated, compile them:
    - `inspection_tool/.venv/bin/python inspection_tool/replay_compiler.py <run_dir>/trace`
  - Optional helper: `python3 .ai-dev/skills/cog-tune/scripts/prepare_cog_review.py --latest`
    - Produces a `cognition_review.md` with per-step artifact paths.

- **Eval entry (`eval/results/`)**:
  - Select a run directory: `eval/results/<timestamp>/`
  - Use eval virtualenv for all eval scripts: `eval/.venv/bin/python ...`
  - Summarize metrics:
    - `eval/.venv/bin/python eval/analysis/summarize.py --run-dir <eval_run_dir>`
  - Compare against baseline run when available:
    - `eval/.venv/bin/python eval/analysis/compare_runs.py --base <base_eval_run_dir> --new <eval_run_dir>`
  - Use `per_task.jsonl` to identify candidate failures/regressions (for example: `scripted_success=false`, high `tool_failures`, `MaxTurnsReached`, long `duration_sec`).
  - For selected tasks, follow `artifact_paths.trace_dir` and inspect traces step-by-step like debug runs.

### 3. Inspect cognition step-by-step

Use `trace/derived/steps.jsonl` plus artifacts in `trace/artifacts/`:

- **World**: `screenshot`, `tool_observation_screen`, `raw_a11y_tree`, `sanitized_a11y_tree`
- **Mind**: `llm_system_prompt`, `llm_user_context`, `llm_full_prompt`, `llm_input_items`, `llm_history`, `llm_tool_calls`
- **Act/Observe**: `tool_call_args`, `tool_result`, `tool_observation_screen`

Always cross-check image evidence with a11y trees. If they disagree, document the mismatch explicitly and avoid conclusions based on only one modality.

Look for mismatches between:
- Screen state vs. what the model believed
- Tool call args vs. available UI elements
- History/todo/scratchpad vs. chosen action
- Planner vs. executor handoff (delegation summaries)
- Eval-level regressions vs. per-task cognition patterns (for example: lower success rate tied to repeated tool mis-targeting)

### 4. Classify root cause

Bucket issues before changing prompts:
- **Perception** (missing/incorrect a11y data)
- **Context** (missing/overloaded system/user context)
- **Reasoning** (bad choice despite correct inputs)
- **Execution** (tool call failure or wrong target)
- **Observation** (post-action state not captured)
- **Orchestration** (multi-agent handoff gaps)
- **Evaluation gap** (metric selection/run config mismatch, flaky task set, or benchmark harness artifact)

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
- Re-run a relevant eval subset (for example `eval/config/aw_subset_smoke.txt` or `eval/config/aw_subset_core.txt`) when tuning from eval.
- Recompute/compare metrics:
  - `eval/.venv/bin/python eval/analysis/summarize.py --run-dir <eval_run_dir>`
  - `eval/.venv/bin/python eval/analysis/compare_runs.py --base <base_eval_run_dir> --new <eval_run_dir>`
- If improvement is narrow, document the limitation and keep change small.
- If uncertainty remains, propose instrumentation/trace improvements instead of guessing.

## Output format

Provide a report with:

- **Summary**: what went wrong, and evidence (step IDs, artifact paths, run IDs, metric deltas)
- **Root cause**: category + reasoning
- **Proposed changes**: concise list with impacted files
- **Patch**: actual code/doc updates
- **Verification plan**: debug runs and/or eval runs to re-test

## Project references

- Cognition roadmap: `doc/todo/cognition/design_proposal.md`
- Trace/replay design: `doc/todo/tracking/final_design_codex.md`
- Debug workflow: `scripts/agent_process_visual_debug.md`
- Replay compiler: `inspection_tool/replay_compiler.py`
- Eval harness: `eval/README.md`
- Eval summarizer: `eval/analysis/summarize.py`
- Eval run comparator: `eval/analysis/compare_runs.py`

## External best practices

See `references/llm_best_practices.md` for prompt/tool/multi-agent guidance and sources.
