# 每个 Turn 发给 OpenAI 的 Prompt 结构（含真实运行样本）

> Last updated: 2026-02-06  
> 主样本：`debug-output/run_20260205_150035`（时间 2026-02-05）

## 1. 先看总结构

每个 turn 发给 OpenAI Responses API 的请求，本质是三段：

1. `instructions`：system prompt（Planner 或 Executor 角色）
2. `input`：按顺序拼好的 `ResponseInputItem` 列表（历史 + 本轮屏幕上下文）
3. `tools`：本轮可用函数工具 schema（按 agent 角色过滤）

代码入口：

- 组装请求：`app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAILLMClient.kt:303`
- 参数写入：`app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAILLMClient.kt:311`
- turn 输入构建：`app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt:40`
- prompt 构建：`app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt:211`

请求骨架（概念化）：

```json
{
  "model": "gpt-5.2",
  "instructions": "...system prompt...",
  "input": [
    {"role": "user", "content": "Goal: ..."},
    {"type": "function_call", "name": "...", "arguments": "..."},
    {"type": "function_call_output", "output": "..."},
    {"role": "user", "content": "Current screen state ... Available tools ..."}
  ],
  "tools": [
    {"type": "function", "name": "complete_task", "parameters": {...}},
    ...
  ]
}
```

## 2. Planner 主代理：一轮是怎么拼出来的

### 2.1 初始历史

Agent 启动时先把用户目标塞进 history：

- `ResponseItem.Message(role="user", content="Goal: ...")`
- 代码：`app/src/main/kotlin/com/moonkey/androidagent/agent/Agent.kt:57`

所以首轮的 `input_items` 至少有两项：

1. Goal message
2. 本轮屏幕上下文 user message

真实样本（首轮）：

- `debug-output/run_20260205_150035/turn_001_n1_log.txt:308`
- `debug-output/run_20260205_150035/turn_001_n1_log.txt:360`
- `debug-output/run_20260205_150035/trace/artifacts/llm_input_items/8_turn_1_llm_input_items.json`

### 2.2 system prompt 来源

主代理是 Planner 角色，默认使用 Planner 模板：

- 角色设定：`app/src/main/kotlin/com/moonkey/androidagent/session/SessionAgentRunner.kt:59`
- 模板选择：`app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/prompt/PromptUtils.kt:32`
- Planner 模板：`app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/prompt/PlannerPromptTemplate.kt`

真实样本（Planner system prompt=2423 chars）：

- `debug-output/run_20260205_150035/turn_005_n3_log.txt:279`
- 完整文本：`debug-output/run_20260205_150035/trace/artifacts/llm_system_prompt/140_turn_5_system.txt`

### 2.3 user context（你看到的“当前屏幕 JSON”）来源

`PromptUtils.buildUserMessage()` 生成 user message，基本结构固定为：

1. `Current screen state (N elements)` + JSON
2. `Available tools: ...`
3. `What action should I take next to achieve the goal?`
4. 可选 `<system_reminder>`（loop warning / turn budget / todo / scratchpad）

代码：

- `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/prompt/PromptUtils.kt:43`
- JSON 生成：`app/src/main/kotlin/com/moonkey/androidagent/perception/Perceptor.kt:79`
- 元素上限：`app/src/main/kotlin/com/moonkey/androidagent/perception/Perceptor.kt:19`

真实样本（含 loop reminder）：

- `debug-output/run_20260205_150035/trace/artifacts/llm_user_context/141_turn_5_user_context.txt:613`
- `debug-output/run_20260205_150035/trace/artifacts/llm_user_context/141_turn_5_user_context.txt:618`

### 2.4 tools 列表如何决定

Planner 的 allowed tools：

- `app_control`
- `write_todos`
- `scratchpad`
- `delegate_task`
- `complete_task`

代码：`app/src/main/kotlin/com/moonkey/androidagent/session/SessionAgentRunner.kt:30`

tools schema 由注册表动态生成：

- `app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt:221`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRegistry.kt:132`

真实样本（TOOLS 5）：

- `debug-output/run_20260205_150035/turn_005_n3_log.txt:347`

## 3. Executor 子代理：每轮 request 有什么不同

`delegate_task` 会启动一个隔离子 agent；它自己的 goal 不是原始用户目标，而是“被委托语句”。

### 3.1 子代理 goal 如何构造

`SubAgentRequest.toGoal()` 会拼成：

- `Delegated query:`
- `Current subgoal:`（可选）
- `Important notes:`（可选）

代码：`app/src/main/kotlin/com/moonkey/androidagent/agent/subagent/SubAgentRunner.kt:263`

真实样本（executor turn-1 的 input item[0]）：

- `debug-output/run_20260205_150035/trace/artifacts/llm_input_items/33_turn_1_llm_input_items.json`

### 3.2 Executor 的 system/tools

Executor 使用 executor prompt 模板：

- `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/prompt/ExecutorPromptTemplate.kt`
- 完整 prompt 样本：`debug-output/run_20260205_150035/trace/artifacts/llm_full_prompt/32_turn_1_full_prompt.txt`

Executor allowed tools：

- `mobile_action`
- `app_control`
- `scratchpad`
- `complete_task`

代码：`app/src/main/kotlin/com/moonkey/androidagent/agent/subagent/SubAgentRunner.kt:69`

真实样本（TOOLS 4）：

- `debug-output/run_20260205_150035/turn_007_n2_log.txt:354`

### 3.3 Executor 的典型两步

同一 delegated task，常见是：

1. 第 1 轮发 `mobile_action`
2. 第 2 轮发 `complete_task`

真实样本：

- 第 1 轮工具调用：`debug-output/run_20260205_150035/trace/artifacts/llm_tool_calls/34_turn_1_tool_calls.json`
- 第 2 轮完成调用：`debug-output/run_20260205_150035/trace/artifacts/llm_tool_calls/49_turn_2_tool_calls.json`

## 4. 为什么 input_items 会越来越长

`Turn.buildInputItems()` 会把 history 的三类 item 全部拼进去：

1. `Message`
2. `FunctionCall`
3. `FunctionCallOutput`

代码：`app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt:40`

所以你会看到：

- turn1：`input_items=2`
- turn3：`input_items=6`
- turn5：`input_items=10`

真实样本（同一 run）：

- `debug-output/run_20260205_150035/trace/trace.jsonl` 中 `type="llm_request"` 的 `input_items`

## 5. 动态提醒是怎么插入 prompt 的

提醒来自两条策略线：

1. LoopDetectionPolicy：屏幕不变/动作重复时注入 loop warning
2. ExecutorStepPolicy：接近上限注入 `TURN BUDGET WARNING`，到上限注入 `FINAL TURN WARNING`

代码：

- loop 检测：`app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/LoopDetectionPolicy.kt:30`
- step 预算：`app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/ExecutorStepPolicy.kt:29`
- 注入 reminder：`app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt:653`

真实样本：

- loop warning：`debug-output/run_20260205_150035/trace/artifacts/llm_user_context/141_turn_5_user_context.txt:618`
- turn budget warning：`debug-output/run_20260205_162356/trace/artifacts/llm_user_context/116_turn_4_user_context.txt:660`
- final turn warning：`debug-output/run_20260205_162356/trace/artifacts/llm_user_context/131_turn_5_user_context.txt:750`

Todo/Scratchpad reminder 样本（另一条真实 run）：

- `debug-output/run_20260204_214908/trace/artifacts/llm_user_context/468_turn_12_user_context.txt:468`
- `debug-output/run_20260204_214908/trace/artifacts/llm_user_context/468_turn_12_user_context.txt:472`

## 6. OpenAI 调用前最后一步：日志与 trace

你当前有两套“看大脑”的入口：

1. 运行时 log（可快速看）
2. trace artifacts（可看完整 prompt）

### 6.1 运行时 log 打印了什么

`LlmLogger.logInput()` 会打印：

- system prompt 长度和内容
- input items 列表（摘要）
- tools 列表（摘要）

代码：`app/src/main/kotlin/com/moonkey/androidagent/llm/LlmLogger.kt:12`

### 6.2 trace artifacts 存了什么

每个 `llm_request` 事件都会落盘：

- `llm_system_prompt/*.txt`
- `llm_user_context/*.txt`
- `llm_full_prompt/*.txt`
- `llm_input_items/*.json`
- `llm_history/*.json`

代码：`app/src/main/kotlin/com/moonkey/androidagent/trace/AgentTrace.kt:138`

主样本目录：

- `debug-output/run_20260205_150035/trace/trace.jsonl`
- `debug-output/run_20260205_150035/trace/artifacts/`

## 7. 一句话总结你的 agent 每轮“怎么想”

每轮都在做同一件事：

- 用 role-specific system prompt 定义“思考边界”（Planner 或 Executor）
- 用 history + 当前屏幕 JSON + 可选提醒构成当前语境
- 在当前可用 tool 集合内产出一个函数调用
- 执行后把结果写回 history，进入下一轮

在你给的真实 run（2026-02-05 15:00）里，这个闭环非常清晰：Planner 逐轮委托 Executor 做原子动作，Executor 通常 1 次 action + 1 次 complete，然后 Planner 基于结果继续推进，直到 `complete_task` 收束。
