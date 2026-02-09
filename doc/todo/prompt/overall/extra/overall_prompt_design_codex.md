# Overall Prompt Re-Architecture (Codex)

## 0. Scope
目标：实现你在 `doc/todo/prompt/qi_overall_prompt_note.md` 里的 4 个核心诉求，且设计必须 grounded 在当前代码。

不做：本次文档不直接改代码；给出可落地改造方案、分阶段迁移和验证标准。

---

## 1. Ground Truth (基于代码，不基于旧文档)

### 1.1 当前每轮请求真实结构
- `instructions` 来自 agent role 的 system prompt：`app/src/main/kotlin/com/moonkey/androidagent/agent/definition/PlannerAgentDef.kt:18`、`app/src/main/kotlin/com/moonkey/androidagent/agent/definition/ExecutorAgentDef.kt:19`
- `input` 由 history + 当前 user context 组成：`app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt:40`
- `tools` 由 registry 按 allowed tools 过滤生成：`app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt:220`
- 请求最终组装在 OpenAI client：`app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAILLMClient.kt:303`

### 1.2 “当前屏幕”如何进入 prompt
- 每轮开头先抓屏（pre-turn）：`app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt:132`
- user message 由 `PromptUtils` 拼：`app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/prompt/PromptUtils.kt:36`
- screen JSON 来自 `Perceptor.toPromptJson()`：`app/src/main/kotlin/com/moonkey/androidagent/perception/Perceptor.kt:79`

### 1.3 关键事实：历史里没有“过去几轮完整 screen JSON”
- history 仅包含：初始 goal、assistant text、tool call、tool output。
- 当前轮 user context 是临时 append，不会写回 history：`app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt:92`

结论：你第 4 点（保留 last N full_screen_state）当前**尚未实现**。

### 1.4 tool 结果当前仍带“屏幕观察摘要”
- tool result 写入 history 前会拼接 observation：`app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt:631`
- `ToolObservation.ScreenState` 仍是一等类型：`app/src/main/kotlin/com/moonkey/androidagent/tool/ToolSpec.kt:159`
- 多个 UI tool 执行器内部也会抓 post-action screen：
  - `app/src/main/kotlin/com/moonkey/androidagent/tool/action/ClickExecutor.kt:55`
  - `app/src/main/kotlin/com/moonkey/androidagent/tool/action/SwipeExecutor.kt:155`
  - `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/OpenAppTool.kt:203`

结论：你第 3 点目前只做到“没把完整树塞进 history”，但仍在塞 summary + 保留 observation 通道。

---

## 2. 现状问题与任务映射

### Task 0: 你想要的 turn prompt 解剖结构
现状是“有这个方向，但不是显式分层对象”。`PromptUtils` 把 screen/todos/scratchpad/reminders揉成一段字符串（`PromptUtils.kt:56`）。

### Task 1: Prompt building 代码 KISS 化
目前拼装逻辑分散在：
- `AgentTurnRunner.buildPromptContext()`
- `PromptUtils.buildUserMessage()`
- `Turn.buildInputItems()`
并且 observation 捕获路径分散（tool 内 + runner fallback），造成认知成本偏高。

### Task 2: system reminder 最小化
当前 reminder 来源有三条：loop、step budget、todo summary（`PromptUtils.kt:87` + `AgentTurnRunner.kt:649`）。

### Task 3: tool 不再返回 screen state
当前 function_call_output 里还有 `Screen after action: ...`（`AgentTurnRunner.kt:642`）。

### Task 4: 保留 last N full_screen_state
当前没有显式 window；只保留“当前屏幕 + 历史里的 tool summary”。

---

## 3. 设计原则
1. KISS：prompt 组装单入口、单方向数据流。  
2. 明确边界：`history`、`memory`、`screen_window` 三层分离。  
3. 先保功能再减复杂度：先把 LLM 输入收敛，再逐步裁剪 observation 管线。  
4. Token-aware：N 屏窗口要有预算守卫，避免硬塞导致 context 爆炸。

---

## 4. Target Prompt Shape (V2)

每轮严格按以下顺序构建：

1. `instructions`
- 来自 AgentDef，但精简到角色边界 + tool calling 基本规则。

2. `tools`
- 保持当前按角色过滤。

3. `input`
- `history`：goal + assistant/tool-call/tool-output（不含旧 screen dump）
- `memory`（当前轮 user item）
  - todos
  - scratchpad keys
  - current_subgoal（如有）
- `screen_window`（当前轮 user item）
  - `screen_0` = current full screen state
  - `screen_1..N-1` = 最近 N-1 个 full screen state（默认 N=3）
- `current_turn_question`（当前轮 user item）
  - 固定一句：下一步最优 action 是什么

说明：`memory` 和 `screen_window` 作为“当前轮附加输入项”，不写回 history。

---

## 5. 针对 4 个任务的落地方案

### 5.1 Task 1/0：重构 Prompt Assembler（主改造）
新增单入口：`TurnPromptAssembler`。

建议结构：
- `agent/cognition/prompt/TurnPromptAssembler.kt`
- `agent/cognition/prompt/ScreenWindowState.kt`
- `agent/cognition/prompt/PromptRenderers.kt`

`AgentTurnRunner` 只做：
- capture pre-turn snapshot
- 更新 `ScreenWindowState`
- 调 `TurnPromptAssembler.build(...)`
- 把返回的 `inputItems/system/tools` 交给 `Turn`

`Turn` 只做 LLM IO，不再负责 prompt 结构策略。

### 5.2 Task 2：reminder 最小化
策略改为：
- 删除 todo reminder（因为 todo 已在 memory block）
- 删除常规 turn budget warning
- 只保留 `CRITICAL` 级提醒：
  - 强 loop
  - 最终 turn 限额
- 不再使用 `<system_reminder>` 标签，改 plain text 一行

### 5.3 Task 3：tool 输出去 screen observation（对 LLM）
Phase A（低风险，建议先做）
- `FunctionCallOutput.content` 仅保留 tool meta 结果（success/failure + reason）
- 去掉 `formatToolResult()` 中 observation 拼接

Phase B（可选，进一步 KISS）
- 弱化/移除 `ToolObservation` 作为 tool contract
- post-action screenshot/tree 仅用于 trace（如果需要），不进 LLM 历史

### 5.4 Task 4：last N full_screen_state
- 在 runner state 内新增 ring buffer（N=3，先 hardcode）
- 每轮 pre-turn snapshot 推入 buffer
- prompt 中输出 `screen_0..screen_2`
- token guard：
  - 永远保留 `screen_0`
  - `screen_1/2` 按预算裁剪（超预算时降级为 compact form）

---

## 6. Token 现实（抽样证据）

抽样 run：`debug-output/run_20260208_202016`

- 单轮 user_context 体积常见 15KB~21KB（峰值 22KB+）
- 全 run user_context 总计约 `225,935` bytes
- 全 run llm_input_items 总计约 `368,550` bytes
- `function_call_output` 在 llm_input_items 中出现 `171` 次
- `Screen after action:` 在 llm_input_items 中出现 `104` 次

含义：
- 过去屏幕状态信息目前主要通过 tool output summary“残留”在 history 中；
- 一旦按 Task 3 去掉它，Task 4 的 screen window 必须一起上，否则模型会丢失短期视觉记忆。

---

## 7. 分阶段实施计划

### Phase 1 (结构重排，不改行为)
1. 引入 `TurnPromptAssembler`，迁移现有拼装逻辑。  
2. 保持提醒与 tool output 行为不变。  
3. 验证：prompt 内容等价（快照测试）。

### Phase 2 (Task 2 + Task 4)
1. 加 `ScreenWindowState(N=3)` 并接入 prompt。  
2. reminder 缩减到 critical-only。  
3. 验证：多轮任务里能引用前 1-2 屏信息。

### Phase 3 (Task 3)
1. function_call_output 去 observation 文本。  
2. 可选：裁剪 ToolObservation 合同，只保留 trace 侧使用。  
3. 验证：成功率不降，token 使用下降。

---

## 8. 测试与验收

必须新增/更新测试：
1. Prompt 顺序测试：`instructions/tools/input(history→memory→screen_window→question)`。  
2. Screen window 测试：仅保留 last 3，顺序正确。  
3. Reminder 测试：默认无 reminder，仅 critical 时出现。  
4. Tool output 测试：`function_call_output` 不含 `Screen after action`。  
5. 集成回归：Planner/Executor、Standalone 各跑一条多步任务。

---

## 9. 关键风险与对策

1. 风险：去 observation 后短期状态丢失。  
对策：Task 3 与 Task 4 绑定上线，不能拆开。

2. 风险：N=3 full screens 导致 token 激增。  
对策：token guard + 只保证 `screen_0` 全量。

3. 风险：system prompt 过度删减导致行为漂移。  
对策：先删 reminder，再逐步精简 system prompt，分 run 对比。

---

## 10. 推荐默认参数

- `SCREEN_WINDOW_SIZE = 3`  
- `SCREEN_WINDOW_TOKEN_BUDGET`：先按经验给固定上限（例如 8k~10k token 等价字符），后续基于 trace 再调  
- `REMINDER_MODE = CRITICAL_ONLY`

