# BrowserMaze - Cog Tune Analysis (Codex)

## Task Overview
- run_folder: `20260225_225734`
- task: `BrowserMaze`
- attempts: 1

## Attempt 0 (`aw_20260225_225734_BrowserMaze_3_0`)
- bridge_status: `completed`
- task_status: `failure`
- scripted_success: `False`
- agent_completion_reason: `None`
- duration_sec: `189.3558264169842`
- turns_executed: `0`; tool_calls: `0`; tool_failures: `0`
- trace: `missing`

### Turn-by-turn
- 无可用 trace；无法恢复 turn 级动作。

## Codex Diagnosis
- 结论: 失败；0 turn、无 trace，属于执行/观测链路缺失。
- Root Cause Buckets: Observation, Evaluation gap
- Evidence:
  - `per_task` 显示 `turns_executed=0`，`artifact_paths.trace_dir=null`。
  - runner 仅看到初始化与最终 scoring，无 agent 行为证据。
- Suggested Changes:
  - 在桥接层要求“无 trace/0 turn”直接标记为 infra_failure 并自动重试。
  - 补充 run 启动探针：session_started 未落盘时立即 fail-fast。
