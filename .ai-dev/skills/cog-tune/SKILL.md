---
name: cog-tune
description: Analyze Android Agent cognition using debug-run traces/replay artifacts and eval results, then propose and implement improvements to prompts, tool definitions, context packing (todo/scratchpad/history), and multi-agent coordination. Use when a debug run feels wrong, when eval metrics regress, when tuning context engineering for generalizable gains, or when reviewing LLM input/output and tool usage; produce both a report and code/doc changes.
---

# Cog Tune

## Overview

Improve agent cognition by inspecting debug-run traces, eval metrics/results, screenshots/screen observations, LLM inputs/outputs, and tool calls, then applying generalizable prompt/context/tool changes backed by evidence.

## Quick Debug (lightweight entry)

For simple "agent did something wrong" cases that don't need full eval analysis, use this abbreviated flow:

1. **Run debug session**: `./scripts/setup.sh && ./scripts/debug-run.sh "goal"`
2. **Turn-by-turn check**: For each turn, compare the screenshot (`turn_N.png`) against the log (`turn_N_log.txt`):
   - Does the agent perceive the target element? (Perception)
   - Does it choose the right action? (Reasoning)
   - Does the action succeed? (Execution)
   - Does it observe the result correctly? (Observation)
3. **Quick diagnostics**:
   ```bash
   grep -E "click|type|scroll|swipe|back|home" debug-output/run_*/agent.log
   grep "ActionResult\|ToolCallResult" debug-output/run_*/agent.log
   grep "ERROR\|Exception" debug-output/run_*/agent.log
   ```
4. **Fix and verify**: Apply targeted fix, then re-run `./scripts/setup.sh && ./scripts/debug-run.sh "goal"`.

If the issue is unclear after this quick pass, proceed to the full workflow below.

## Full Workflow

### 1. Set scope and guardrails

- Clarify the failing behavior, target runs, and target metric(s).
- If tuning from eval, define baseline run and comparison run up front.
- Prefer generalizable changes; avoid one-off hacks for a single task.
- Require evidence from traces and/or eval artifacts: show which step(s), run IDs, and metrics support each proposed change.

### 2. Prepare evidence data

**Virtualenvs** — two separate venvs, use the right one:
- `eval/.venv/bin/python` — for all scripts under `eval/`
- `inspection_tool/.venv/bin/python` — for all scripts under `inspection_tool/`

Pick one or both entry points:

- **Debug-run entry**:
  - Use latest run or a provided run directory under `debug-output/`.
  - Run a debug session with specific config when needed:
    - `./scripts/debug-run.sh "goal"` (default: basic mode, accessibility-only)
    - `./scripts/debug-run.sh --pro "goal"` (planner+executor mode)
    - `./scripts/debug-run.sh --hybrid "goal"` (a11y + screenshot perception)
    - `./scripts/debug-run.sh --main-model <model> --executor-model <model> "goal"`
  - If derived replay files are missing/outdated, compile them:
    - `inspection_tool/.venv/bin/python inspection_tool/replay_compiler.py <run_dir>/trace`
  - Optional helper: `python3 .ai-dev/skills/cog-tune/scripts/prepare_cog_review.py --latest`
    - Produces a `cognition_review.md` with per-step artifact paths.
  - Token/context budget analysis:
    - `inspection_tool/.venv/bin/python inspection_tool/a11y_token_stats.py --run-dir <run_dir>`
    - Outputs CSV with raw vs. sanitized a11y tree token counts per turn. Use for diagnosing **Context** root causes (context window overflow, prompt bloat).

- **Eval entry (`eval/results/`)**:
  - Select a run directory: `eval/results/<timestamp>/`
  - Summarize metrics:
    - `eval/.venv/bin/python eval/analysis/summarize.py --run-dir <eval_run_dir>`
  - Compare against baseline run when available:
    - `eval/.venv/bin/python eval/analysis/compare_runs.py --base <base_eval_run_dir> --new <eval_run_dir>`
  - Use `per_task.jsonl` to identify candidate failures/regressions (for example: `scripted_success=false`, high `tool_failures`, `MaxTurnsReached`, long `duration_sec`).
  - For selected tasks, follow `artifact_paths.trace_dir` and inspect traces step-by-step like debug runs.
  - When traces are missing but logcat exists, check `eval/aw_bridge/completion_monitor.py` for the completion/error patterns it matches — this helps diagnose trace capture infra bugs vs. actual agent failures.

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
- **Execution** (tool call failure or wrong target) — use `./scripts/action-test.sh` to isolate and reproduce action-level failures independently of the agent
- **Observation** (post-action state not captured)
- **Orchestration** (multi-agent handoff gaps)
- **Evaluation gap** (metric selection/run config mismatch, flaky task set, or benchmark harness artifact) — use `eval/aw_bridge/setup_task_only.py --task <TaskName>` to run task setup in isolation and manually verify the environment state

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
- Re-run a relevant eval subset when tuning from eval. Task config files in `eval/config/`:
  - `aw_fullset.txt` — complete task set
  - `aw_subset_core.txt` — core subset for baseline
  - `aw_subset_group_1.txt`, `aw_subset_group_2.txt` — 20 tasks each, non-overlapping, for incremental testing
  - `aw_subset_smoke.txt` — 5-task quick smoke test
- Run eval: `eval/.venv/bin/python eval/aw_bridge/runner.py --tasks-file eval/config/<task_file>`
- Parallel runner (`eval/aw_bridge/parallel_runner.py`) exists but is WIP — not validated for production use yet.
- Recompute/compare metrics:
  - `eval/.venv/bin/python eval/analysis/summarize.py --run-dir <eval_run_dir>`
  - `eval/.venv/bin/python eval/analysis/compare_runs.py --base <base_eval_run_dir> --new <eval_run_dir>`
- If improvement is narrow, document the limitation and keep change small.
- If uncertainty remains, propose instrumentation/trace improvements instead of guessing.

## Output format

**Per-task analysis**: Follow the template in `assets/per_task_analysis_template.md`.

**Common problems summary** (when synthesizing for /autotune): Follow `.ai-dev/skills/autotune/assets/common_problems_template.md`.

**Overall report** (when doing a full analysis, not per-task):

- **Summary**: what went wrong, and evidence (step IDs, artifact paths, run IDs, metric deltas)
- **Root cause**: category + reasoning
- **Proposed changes**: concise list with impacted files
- **Patch**: actual code/doc updates
- **Verification plan**: debug runs and/or eval runs to re-test

## Project references

- Cognition roadmap: `doc/todo/cognition/design_proposal.md`
- Trace/replay design: `doc/todo/tracking/final_design_codex.md`
- Debug workflow guide: `doc/dev/visual_debug_guide.md`
- Debug run script: `scripts/debug-run.sh`
- Action test harness: `scripts/action-test.sh`
- Replay compiler: `inspection_tool/replay_compiler.py`
- Token stats analyzer: `inspection_tool/a11y_token_stats.py`
- Eval harness: `eval/README.md`
- Eval architecture: `doc/main/eval/eval.md`
- Eval runner: `eval/aw_bridge/runner.py`
- Eval bridge config: `eval/aw_bridge/native_agent_bridge.py` (agent_mode, perception_mode, platform_mode, excluded_tools, model selection)
- Eval completion monitor: `eval/aw_bridge/completion_monitor.py`
- Eval preflight / snapshot policy: `eval/aw_bridge/runner_preflight.py`
- Eval per-task lifecycle: `eval/aw_bridge/runner_execution.py`
- Eval task setup (standalone): `eval/aw_bridge/setup_task_only.py`
- Eval summarizer: `eval/analysis/summarize.py`
- Eval run comparator: `eval/analysis/compare_runs.py`

## External best practices

See `references/llm_best_practices.md` for prompt/tool/multi-agent guidance and sources.
