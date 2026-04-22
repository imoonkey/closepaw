# Agent Core 简化 — 对齐改进计划

**状态：** 最终版（由 CLAUDE + CODEX 对齐，共 2 轮）

---

## 排序原则

先修复 runtime 不变量，再合并重复结构，最后清理残余。不要从死代码移除或提取开始——当前的问题是围绕一个 loop 存在过多薄弱的抽象，而这个 loop 本应由少数硬不变量来约束。

---

## P0: 强制每轮最多一个屏幕操作

**为何优先：** 这是最主要的简化杠杆。它既能消除意外复杂性，也能修复一个真实的正确性问题（下一轮操作状态可能描述了一个从未执行的操作）。

### 变更

1. **TurnToolPolicy.kt** — 选择逻辑变为：任意纯认知/记忆类 tool + 最多一个屏幕操作 tool。不允许 `complete_task` 与屏幕操作 tool 出现在同一轮中。

2. **TurnExecutionPhaseRunner.kt** — 返回*实际执行*的操作签名，而非仅仅被规划的操作。操作后 snapshot 捕获每轮只发生一次（在单个屏幕操作之后），而非在链式操作之间执行。

3. **AgentTurnRunner.kt** — 下一轮状态从实际运行的内容派生。

### 预期删除

- TurnToolPolicy 中的多屏幕 arbitration 逻辑
- TurnExecutionPhaseRunner 中的多操作 snapshot 链式处理
- 可能描述未执行操作的预计算 actionForNextTurn

### 验收标准

- 没有任何代码路径在一轮中执行两个屏幕操作 tool
- Loop detection 只看到实际执行过的操作
- Prompt 与 runtime 规则一致

---

## P1: 将 ExecutorStepPolicy 拆分为两个关注点

**为何排在此处：** 操作不变量清理完毕后，这是一个直接的分离操作。

### 变更

1. 将 `ExecutorStepPolicy` 替换为：
   - 一个小型 `TurnBudgetCheck` helper："这是最后一个允许的轮次吗？"返回布尔值。
   - 一个独立的 `DelegationSummaryFormatter`：在被委派的 executor 达到上限时构建叙述性摘要。

2. 移除 `ExecutorStepDecision.WarnApproaching`（计算了但从未呈现）。

3. 移除 `narrativeSummaryOnLimit` 参数（在所有调用点都为 `true`）。将该行为硬编码。

4. 从 "Executor" 重命名，因为它适用于所有 agent。

### 主要文件

- `cognition/policy/ExecutorStepPolicy.kt`
- `AgentTurnRunner.kt`
- `subagent/SubAgentRunner.kt`

### 验收标准

- 没有"计算了但未使用"的决策状态
- 最后一轮警告从一个调用点即可清晰理解
- 生成 delegation summary 不需要实例化 policy 对象

---

## P1: 统一 Agent 角色定义为一个模型

**为何排在此处：** 跨顶层和被委派 agent 的结构性清理。应在更小的清理工作之前完成，以避免同样的修复做两遍。

### 变更

1. 将 `AgentDef`（abstract class）+ `AgentDefinition`（sub-agent data class）替换为一个统一的角色模型，拥有：
   - 角色名称
   - System prompt
   - 允许的 tool
   - 执行角色 / model bucket
   - 是否可作为 sub-agent 被调用

2. `SessionAgentRunner` 的会话启动和 `DelegateTaskTool` 的 registry 条目都从同一来源派生。

3. 删除 `requiresDelegationToolRegistration`。从选定的顶层角色定义本身（例如通过角色属性或解析后的 tool 集合）注册 delegation 能力，而非硬编码 `mode == PRO`。

### 预期删除

- `ExecutorAgent` 桥接对象
- 重复的 `AgentRegistry` / `AgentDefinition` 类型
- `AgentDef.id`（未使用）
- `AgentRegistry.getAll()`（未使用）

### 主要文件

- `definition/AgentDef.kt`、`AgentDefRegistry.kt`、`PlannerAgentDef.kt`、`ExecutorAgentDef.kt`、`StandaloneAgentDef.kt`
- `subagent/SubAgentRunner.kt`
- `session/SessionAgentRunner.kt`
- `tool/impl/DelegateTaskTool.kt`

### 验收标准

- Executor 的 prompt/tool 所有权仅存在于一处
- 顶层和被委派 agent 的启动从同一定义来源读取
- 统一角色模型之外不再存在基于 mode 的 delegation 特殊分支

---

## P2: 死代码与残留状态移除

**为何在此时：** 在不变量强制和结构统一之后，这些变成了简单的删除操作。

### 变更

1. **NavigationState** — 移除 `consecutiveScrollActions`、`recentActions`（启发式逻辑移除后已死）。移除 `ScreenSignature.fingerprint`（未使用；只有 `tokens` 被消费）。移除 `LoopWarningSeverity.CRITICAL`（从未被发出）。如果 WARNING 是唯一的值，考虑完全移除 severity enum。

2. **PreTurnContext** — 移除 `appTier` 字段（赋值后从未被读取）。

3. **TurnToolPolicy** — 将 `any` + `find` 的双遍历替换为单次 `find` + null 检查。

4. 更新断言已移除字段的测试（`NavigationStateTest.kt`）。

### 验收标准

- NavigationState 中的每个字段都被生产代码读取
- 不再存在"计算了但未使用"的决策状态或字段
- Loop detection 行为仍然有完整的测试覆盖

---

## P3: Observation 表示统一

**为何靠后：** 有价值但杠杆率低于修复不变量和定义。

### 变更

1. 提取每轮一个规范的 observation payload（屏幕状态只捕获一次）。
2. Prompt 渲染和 history 记录都从这个 payload 投影。
3. 消除"先构建 prompt"的调用顺序作为正确性前提的时序耦合。

### 主要文件

- `cognition/prompt/PromptBuilder.kt`
- `TurnPlanningPhaseRunner.kt`

### 验收标准

- Prompt 和 history 在描述同一屏幕时不会产生偏差
- 没有任何正确性注释依赖于 prompt 构建与 history 记录之间的调用顺序

---

## P3: Event Emission 整合

### 变更

1. 向 `AgentEventDispatcher` 添加缺失的 event 发射方法（例如 `actionExecuted()`、`approvalRequired()`）。
2. 从 `AgentTurnRunner` 和 `TurnExecutionPhaseRunner` 中移除原始 `eventEmitter` 透传。
3. 所有 agent 产生的 event 都经由 dispatcher 流转。

### 验收标准

- 新增 event 类型时，有一个显而易见的添加位置
- 不再有原始 eventEmitter lambda 与 dispatcher 并行传递

---

## P3: Tool 参数解码整合

### 变更

1. 创建一个共享的规范化 action-target decoder，供 `ActionDescriptionFormatter` 和 `ActionSignature` 使用。
2. 格式化和签名生成都消费解码后的结果。

### 验收标准

- mobile_action schema 变更时，只有一个显而易见的解码路径需要更新

---

## P4: 需要度量数据支撑的项目

### Turn.kt 文本恢复审计

添加遥测来追踪 `recoverToolCallFromText` 的触发率。经过一个 eval 周期后，移除从未触发的路径。不要在没有数据支撑的情况下推测性地移除——代码库支持多个后端。

### 魔法延迟常量

为 `TurnExecutionPhaseRunner.kt` 中的 `delay(200)` 和 `delay(500)` 命名并添加解释性注释。只有在执行前节奏控制和操作后稳定的语义确实匹配时，才与 `config.uiSettleDelayMs` 统一。

### 重命名 ExecutorStepPolicy

P1 拆分完成后，将剩余部分重命名为 `TurnBudgetPolicy` 或类似名称。"Executor" 前缀在所有 agent 都使用时具有误导性。

---

## 待讨论问题

1. **双重取消信号（Agent.kt）：** Claude 提议将 `CompletableDeferred` + `AtomicBoolean` 统一为单一 deferred。CODEX 指出所有权分布在 SessionAgentRunner（外部）和 Agent（内部）之间。在所有 pause/resume/shutdown 路径下，统一是否安全？**建议：** 暂时从对齐计划中推迟。仅在 P0/P1 完成后、如果生命周期所有权仍然显得不必要地分裂时再重新审视，且在修改前必须先进行一次明确的 pause/resume/shutdown 审计。

2. **SubAgentRunner 文件拆分：** Claude 提议拆分为 3 个文件（288 行中的 7 个类型）。CODEX 认为这是无概念精简的搅动。**建议：** 不纳入对齐计划。真正的修复是 P1 定义统一，它应当自然地缩减 SubAgentRunner。

3. **Turn DTO 存废：** 一些仅使用一次的 DTO（PlanningPhaseOutput、TurnExecutionResult）命名了阶段之间的契约。在 P0/P1 变更之后，重新评估哪些确实已死、哪些起到了文档化流程的作用。**建议：** 现在移除死字段（`appTier`）。保留起契约命名作用的 DTO，直到 turn loop 的形态稳定，然后只裁剪那些仍然不增加清晰度的。

---

## 总结

| 优先级 | 项目数 | 性质 | 风险 |
|--------|--------|------|------|
| P0 | 1 项 | 不变量强制 | 中等（行为变更） |
| P1 | 2 项 | 结构统一 | 中等（多文件重构） |
| P2 | 1 项 | 死代码移除 | 无 |
| P3 | 3 项 | 整合 | 低-中等 |
| P4 | 3 项 | 度量 + 命名 | 无-低 |

**执行顺序：** P0 -> P1 -> P2 -> P3 -> P4。每一层依赖于前一层的稳定。
