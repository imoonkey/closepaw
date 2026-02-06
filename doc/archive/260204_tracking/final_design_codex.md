# Agent Cognition Logging & Replay Final Design (Codex)

> Date: 2026-02-04  
> Status: Final Design (for implementation)  
> Inputs: `logging_and_viz_design_claude.md`, `logging_and_viz_design_gemini.md`, `logging_replay_design_codex.md`, and two review docs.

## 1. 结论

采用 **Codex 的架构主线**（step-centric + derived replay index），结合 **Claude 的落地策略**（最小 Android 改动 + 实用 UI 组件）。

核心决策：

1. Android 端不做激进重构，先补齐 agent 层级元数据（parent/role/delegation link）。
2. Step 语义优先放在后处理阶段（`replay_compiler.py`），避免把复杂推断写死在 runtime。
3. Viewer v2 直接面向 step 数据（`steps.jsonl`），不是直接渲染 raw event 流。
4. 继续使用 `trace.jsonl + artifacts/`，不引入 DB。

## 2. 设计目标

1. debug-run 后可快速回放每个 step 的 World + Mind。
2. main/sub agent 关系明确，可跳转。
3. 保持现有 trace 能力和 redaction，兼容已有 trace 文件。
4. 支持后续 profiling、A/B 和认知实验迭代。

## 3. 方案合成（吸收各设计长处）

### 3.1 来自 Codex

1. 明确区分 `raw events` 与 `step replay units`。
2. 引入 `replay_compiler.py` 生成 `agent_tree.json` + `steps.jsonl`。
3. viewer 围绕 step 导航和 parent-child linking。

### 3.2 来自 Claude

1. Android 侧先做“手术式改动”：`parentSessionId`、`agentRole`。
2. 保留对旧 trace 的 fallback（`sessionId` 的 `::` 解析）。
3. UI 使用三栏模型：Agent Tree / Step Timeline / World+Mind Detail。

### 3.3 来自 Gemini

1. 保持全局 logging 的统一入口思想。
2. 明确 Zero-config 本地离线回放体验。

## 4. 日志与数据模型

### 4.1 Android Trace 基线

短期不替换 `TraceEventRecord` 结构，继续使用 v1 event；通过 `data` 扩展层级字段：

1. `agent_id`
2. `agent_role`
3. `parent_session_id`
4. `delegation_call_id`

写入时机：`session_started`。

### 4.2 Runtime 上下文字段（AgentConfig）

新增字段：

1. `agentId`
2. `agentRole`
3. `parentSessionId`
4. `delegationCallId`

说明：
- Phase 1 默认 `agentId == sessionId.value`，单 session 单 agent；后续多 agent 复用 session 时再扩展。

### 4.3 Step 语义

本阶段不强制新增 Android 的 `step.started/completed` 事件。  
Step 由 compiler 通过 turn 内事件模式推导：

1. `screen_captured` -> World(pre)
2. `llm_request + llm_response` -> Mind
3. `tool_call + tool_result (+ post artifacts)` -> Act/Observe

## 5. 后处理产物

在 trace 文件夹下新增：

1. `derived/replay_index.json`
2. `derived/agent_tree.json`
3. `derived/steps.jsonl`

### 5.1 `agent_tree.json`

每个节点包含：

1. `session_id`
2. `parent_session_id`
3. `agent_role`
4. `goal`
5. `status`

### 5.2 `steps.jsonl`

每行一个 step，最少包含：

1. `step_id`
2. `session_id`
3. `turn_number`
4. `events`（同一步的关键事件摘要）
5. `world`（pre/post artifact refs）
6. `mind`（llm req/resp + tool refs）

## 6. Visualizer v2

路径：`inspection_tool/replay_v2/`。

UI 三栏：

1. 左：Agent Tree（支持 parent/child）
2. 中：Step Timeline（按选中 agent 过滤）
3. 右：Detail
   - World: screenshot + a11y
   - Mind: prompt/input items/response/tool

交互：

1. 点击 step 切换详情。
2. 键盘 `←/→` 步进。
3. 点击 agent 节点过滤 timeline。

## 7. 实施计划

### Phase 1（P0，立即）

1. AgentConfig + SubAgentRunner 打通 parent/role/delegation 链接。
2. AgentTrace `session_started` 写入新字段。

### Phase 2（P0）

1. 新增 `replay_compiler.py`。
2. `debug-run.sh` pull trace 后自动编译 derived 文件。

### Phase 3（P1）

1. 新建 `replay_v2` viewer。
2. 接入 `agent_tree.json + steps.jsonl`。

### Phase 4（P1）

1. 细化 world overlay 与 mind tabs。
2. 增加 filter/jump/错误容错。

## 8. 验收标准

1. `debug-run.sh` 产物可直接打开 `replay_v2` 浏览 step。
2. 至少一个 delegation run 中可清晰区分 planner 和 executor。
3. 任一 step 可看到：屏幕状态 + LLM 完整输入 + 模型输出/tool call。
4. 对旧 trace（无新字段）viewer 仍能基本工作（降级模式）。

## 9. 非目标（本轮不做）

1. 不引入数据库或服务端。
2. 不在 Android 侧实现复杂 step 状态机。
3. 不做 live streaming replay（仅离线回放）。
