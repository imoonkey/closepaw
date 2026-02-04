# Agent Cognition Logging + Replay 设计（Codex）

> Date: 2026-02-04  
> Author: Codex  
> Status: Proposal

## 1. 目标（面向 research 迭代）

围绕 `debug-run.sh` 建立一个高信噪比调试闭环：

1. 每个 step 都能回放 `World`（screenshot + a11y tree）和 `Mind`（LLM 完整输入 + 输出 + tool calls）。
2. main agent / subagent 有明确层级关系，可一键跳转 parent-child。
3. logging 全局统一（不是散落 `Log.d` + 临时文本）。
4. 可持续扩展：先满足 cognition research，再支持后续 profiling/ablation。

## 2. 现状问题

当前已经有 `TraceRecorder` + `AgentTrace` + `inspection_tool/trace_viewer`，但 research 阶段仍有关键缺口：

1. 事件粒度偏“流水账”，缺少稳定的 step 语义对象（难以 walk-through）。
2. subagent 依赖 sessionId 命名约定做关联，没有显式 parent link。
3. 事件上下文字段不统一，logcat / trace / artifact 之间关联成本高。
4. viewer 是 event-centric，不是 cognition-step-centric。

## 3. 设计原则（简洁但有效）

1. 单一事实源：`trace.jsonl` 仍是唯一真相日志。
2. 强上下文：每条事件必须携带统一 `ctx`（run/agent/turn/step）。
3. 原子回放单元：以 `step` 为核心，不以原始 event 为核心。
4. 多层输出：
   - `Human logs`（logcat，便于现场看）
   - `Structured trace`（可重放）
   - `Derived replay index`（后处理加速查看）
5. 兼容演进：schema 升级到 v2，但保留 v1 可读路径。

## 4. Trace V2：统一事件模型

### 4.1 事件 Envelope

```json
{
  "v": 2,
  "run_id": "20260204_145501",
  "seq": 128,
  "ts_ms": 1760223345123,
  "event": "llm.request",
  "ctx": {
    "session_id": "root-session",
    "agent_id": "agent_root",
    "agent_role": "planner",
    "parent_agent_id": null,
    "delegation_call_id": null,
    "turn_id": "turn-6",
    "turn_number": 6,
    "step_id": "turn-6.step-1",
    "step_index": 1
  },
  "data": { "model": "gpt-5.2", "input_items": 23 },
  "artifacts": []
}
```

### 4.2 `ctx` 必填字段

1. `session_id`
2. `agent_id`（每个 Agent runtime 唯一）
3. `agent_role`（planner/executor/...）
4. `parent_agent_id`（main 为空）
5. `delegation_call_id`（子代理由哪个 tool call 触发）
6. `turn_id`, `turn_number`
7. `step_id`, `step_index`

## 5. 事件分类（最小闭环）

### 5.1 生命周期

1. `agent.started`
2. `agent.stopped`
3. `turn.started`
4. `turn.completed`
5. `step.started`
6. `step.completed`

### 5.2 World（屏幕）

1. `screen.pre`（LLM 前）
2. `screen.post`（tool 后）

Artifact 至少包含：
1. `raw_a11y_tree`
2. `sanitized_a11y_tree`
3. `screenshot`

### 5.3 Mind（认知）

1. `llm.request`
2. `llm.response`
3. `tool.call`
4. `tool.result`
5. `delegation.started`
6. `delegation.completed`

`llm.request` 必须含完整输入 artifacts：
1. `system_prompt.txt`
2. `user_context.txt`
3. `full_prompt.txt`
4. `llm_input_items.json`
5. `history.json`

`llm.response` 必须含：
1. `assistant_text.txt`（可空）
2. `tool_calls.json`（可空）

## 6. Subagent 链接模型（关键）

在 `AgentConfig` 增加 trace 上下文字段：

1. `agentId: String`
2. `agentRole: AgentRole`
3. `parentAgentId: String?`
4. `delegationCallId: String?`

`delegate_task` 执行时：

1. parent 发 `delegation.started`，记录 `delegation_call_id`。
2. child `agent.started` 带上 `parent_agent_id + delegation_call_id`。
3. child 结束后 parent 发 `delegation.completed`（成功/失败 + summary）。

这样 viewer 可稳定构建树：`parentAgentId` + `delegationCallId`，不依赖字符串解析 sessionId。

## 7. Artifact 目录规范

建议按 agent/turn/step 分层，避免命名冲突与后处理猜测：

```text
trace/
  meta.json
  trace.jsonl
  artifacts/
    agent_root/
      turn_006/
        step_001/
          screen_pre.jpg
          screen_pre_sanitized.json
          llm_full_prompt.txt
          llm_input_items.json
          llm_response_tool_calls.json
    agent_exec_02/
      turn_001/
        step_001/
          ...
```

## 8. 全局 Logging API（代码层）

保留 `TraceRecorder`，新增轻量封装：

1. `TraceContext`：run/agent/turn/step 上下文对象。
2. `TraceEmitter`：统一 `emit(event, ctx, data, artifacts)`。
3. `AgentLogger`：logcat wrapper，自动打 context 前缀。

目标是让 `AgentTurnRunner`, `ToolRouter`, `SubAgentRunner`, `LLMClient` 都走同一接口，而不是各写一套日志习惯。

## 9. 后处理（Post-processing）设计

新增 `inspection_tool/replay_compiler.py`，输入 `trace/` 输出：

1. `replay_index.json`
2. `agent_tree.json`
3. `steps.jsonl`

`steps.jsonl` 的每条就是 viewer 的渲染单元：

```json
{
  "step_id": "turn-6.step-1",
  "agent_id": "agent_root",
  "turn_number": 6,
  "world": {"pre": {...}, "post": {...}},
  "mind": {"llm_request": {...}, "llm_response": {...}, "tool": {...}},
  "links": {"parent_step_id": null, "child_agent_ids": ["agent_exec_02"]}
}
```

`debug-run.sh` 在 pull trace 后自动跑 compiler，做到“开箱即看”。

## 10. Visualizer V2（从头做，不 retrofit）

路径建议：`inspection_tool/replay_v2/`

UI 三栏：

1. 左：Agent Tree（main/sub 明确分色、可折叠）
2. 中：Step Timeline（按 turn/step）
3. 右：Detail Split
   - World：screenshot + a11y overlay + pre/post 切换
   - Mind：Prompt / InputItems / Response / ToolCall / ToolResult tabs

交互：

1. 点击 step 即切换右侧详情。
2. `←/→` 逐步播放。
3. `↑/↓` 在当前 agent 内跳 step。
4. “Jump to parent/child delegation” 快捷跳转。

## 11. 落地计划（建议 4 个 phase）

### Phase 1: Schema + Context（P0）

1. 增加 Trace V2 `ctx`。
2. 给 main/subagent 注入显式 parent link。
3. 加 `step.started/completed`。

### Phase 2: 完整采集（P0）

1. 保证每 step 都有 `screen.pre + llm.request + llm.response`。
2. 有 tool 时补 `tool.call + tool.result + screen.post`。
3. 完善 artifact 命名和路径规则。

### Phase 3: Replay Compiler（P1）

1. 生成 `steps.jsonl` 与 `agent_tree.json`。
2. 接入 `debug-run.sh` 自动编译。

### Phase 4: Viewer V2（P1）

1. 三栏 UI + 键盘导航。
2. parent-child 跳转。
3. world/mind 同步展示。

## 12. 验收标准

1. 从 `debug-run.sh` 输出目录，10 秒内可打开并看到 step timeline。
2. 任一步都能看到：`screen.pre` + `llm.input(full)` + `llm.response`。
3. 至少一个 delegation case 下，能从 planner step 跳到 subagent step，再跳回。
4. 不看 logcat，仅靠 replay viewer 能定位“模型为什么这么做”。

## 13. 关键取舍

1. 不做复杂 DB，继续 JSONL + artifacts（移动端最稳）。
2. 不在首版引入重量前端框架（先无构建链，纯静态站点）。
3. Redaction 继续保留，但建议增加 `trace_export_redacted`（导出时脱敏）与 `local_full_trace`（本地完整）双模式，避免影响 research 可观察性。
