# BrowserDraw - Cog Tune Analysis (Codex)

## Task Overview
- run_folder: `20260225_225734`
- task: `BrowserDraw`
- attempts: 1

## Attempt 0 (`aw_20260225_225734_BrowserDraw_2_0`)
- bridge_status: `completed`
- task_status: `failure`
- scripted_success: `False`
- agent_completion_reason: `Error`
- duration_sec: `136.46884483331814`
- turns_executed: `1`; tool_calls: `0`; tool_failures: `0`
- trace: `/Users/moonkey/workspace/android-agent-workspace/androidagent/eval/results/20260225_225734/artifacts/aw_20260225_225734_BrowserDraw_2_0/trace`
- trace_events: `7`; last_event: `session_stopped`
- session_stopped: reason=`Error`, turns_executed=`1`

### Turn-by-turn
| Turn | Foreground(PRE) | LLM | Tool Actions | Notes |
|---:|---|---|---|---|
| 1 | com.moonkey.androidagent | request_sent_no_response | (no tool call) | turn_error=LLM error: SseException - 200: Provider returned error |

## Codex Diagnosis
- 结论: 失败；首轮即 LLM provider 错误，未执行任何工具。
- Root Cause Buckets: Execution, Evaluation gap
- Evidence:
  - trace 出现 `turn_error: LLM error: SseException - 200: Provider returned error`。
  - 只有 turn-1，且无 tool_call。
- Suggested Changes:
  - 对 Browser task 增加 provider 级重试（至少 1 次）与降级模型。
  - 将此类错误单独记为 infra-like，避免污染 cognition 成功率判断。
