# Agent Core Simplicity — 对齐评审

**状态：** 最终版（由 CLAUDE + CODEX 对齐，共 2 轮）
**范围：** `app/src/main/kotlin/com/moonkey/androidagent/agent/`（24 个文件，约 3.2k 行代码）

---

## 摘要

Agent core 实现了一个 ReAct loop（观察 -> 决策 -> 执行 -> 观察）。整体架构合理，多个设计模式运用得当（精简的 TurnRunnerState、清晰的 TurnOutcome sealed class、范围恰当的 AgentStopReason、经过启发式剪枝后稳健的 LoopDetectionPolicy）。

复杂性的主要来源在于：runtime 并未强制执行三个 agent prompt 中都描述的"每轮最多一个屏幕操作"不变量。这一不匹配导致了 tool arbitration、操作后 snapshot 链式处理、完成判定延迟以及下一轮状态追踪等方面的级联复杂性——外加一个具体的正确性 bug。

复杂性的第二个来源是两套并行的角色定义机制之间的重复代码（顶层的 AgentDef 和 sub-agent 的 AgentDefinition/AgentRegistry）。

除此之外，还存在一些残留状态、死代码和仅使用一次的 DTO。

---

## 发现

### HIGH

#### H-1: Runtime 未强制执行每轮一个屏幕操作的不变量

**来源：** CODEX（独有发现）

三个 agent prompt 都教导"每轮最多执行一个改变屏幕的操作，然后进行观察"：
- `PlannerAgentDef.kt:28-37`
- `ExecutorAgentDef.kt:29-40`
- `StandaloneAgentDef.kt:31-41`

但 runtime 与此矛盾：
- `TurnToolPolicy.kt:33-84` 保留所有屏幕操作 tool，而非只保留一个
- `TurnExecutionPhaseRunner.kt:44-64` 顺序执行所有被选中的 tool
- `TurnExecutionPhaseRunner.kt:108-127` 在 tool 之间刷新 snapshot（这段代码的存在仅仅是因为允许了多屏幕操作轮次）

**正确性 bug：** `TurnExecutionPhaseRunner.kt:45` 在执行前就计算了 `actionForNextTurn`。如果前面的 tool 执行失败（`TurnExecutionPhaseRunner.kt:59-62`），runner 仍然返回预先计算的签名（`TurnExecutionPhaseRunner.kt:64`）。`AgentTurnRunner.kt:113-116` 将其存入下一轮状态，`NavigationState.kt:22-40` 将其记录为已发生的操作。这会污染 loop detection。

**KISS 不变量：** 每轮允许：任意纯认知/记忆类 tool + 最多一个屏幕操作 tool。不允许 `complete_task` 与屏幕操作 tool 出现在同一轮中。

#### H-2: 双重 agent 角色定义系统

**来源：** 双方均发现（CODEX 范围更广）

顶层角色：`definition/AgentDef.kt`、`AgentDefRegistry.kt`、`PlannerAgentDef.kt`、`ExecutorAgentDef.kt`、`StandaloneAgentDef.kt`

Sub-agent 角色：`subagent/SubAgentRunner.kt:29-39`（`AgentDefinition`）、`SubAgentRunner.kt:79-99`（`AgentRegistry`）、`SubAgentRunner.kt:61-74`（从 ExecutorAgentDef 逐字复制数据）

消费端分裂：`SessionAgentRunner.kt:51-77` 使用 AgentDef；`SessionAgentRunner.kt:129-147` 和 `DelegateTaskTool.kt:18-35` 使用 AgentDefinition/AgentRegistry。

这意味着 prompt/tool/角色的所有权分布在两个 registry 和两个定义类型中。任何变更都必须同时在两处进行推理。

对齐方向：围绕一个角色模型进行统一，并从该角色模型本身派生 delegation 能力。不要用一个新的硬编码 `if (mode == PRO)` 分支替代重复的定义系统。

#### H-3: ExecutorStepPolicy 混合了不相关的关注点，并携带死行为

**来源：** 双方

一个类承担三项职责：
- 接近轮次上限时发出警告（计算了但被丢弃——`WarnApproaching` 没有消费者）
- 活跃运行中的最后一轮警告（`AgentTurnRunner.kt:220-226,234-243`）
- Sub-agent 停止后的叙述性摘要（`SubAgentRunner.kt:177-193`）

命名为 "Executor" 但所有 agent 都在使用。`WarnApproaching` 实际上是死代码。`narrativeSummaryOnLimit` 参数在所有调用点都为 `true`。

---

### MEDIUM

#### M-1: NavigationState 携带已移除的启发式逻辑

**来源：** 双方

`consecutiveScrollActions` 和 `recentActions` 每轮都在计算，但生产代码中从未被读取。它们的消费者（滚动刷屏检测、操作重复检测）已按照 `LoopDetectionPolicy.kt:13` 的设计被有意移除。`ScreenSignature.fingerprint` 每轮都通过 hash 计算，但从未使用——`similarityTo()` 只消费 `tokens`。`LoopWarningSeverity.CRITICAL` 从未被发出或被分支判断引用。

#### M-2: Observation 渲染存在重复，且有时序耦合

**来源：** CODEX

当前屏幕被渲染了两次：
- `PromptBuilder.kt:111-178` 用于 LLM prompt
- `TurnPlanningPhaseRunner.kt:173-205` 用于 history

`TurnPlanningPhaseRunner.kt:84-86` 记录了排序依赖：必须先构建 prompt，再记录 history，否则当前屏幕会被重复记录。正确性依赖于调用顺序——这是一种时序耦合的坏味道。

#### M-3: Turn flow 被仅使用一次的 DTO 碎片化

**来源：** 双方

多个交接信封仅服务于单一调用者：
- `PreTurnContext`（含死字段 `appTier`）
- `PreparedTurn`
- `PlanningPhaseOutput`
- `TurnExecutionResult`
- `CompletionDecision`

**待讨论：** 其中一些（如 `PlanningPhaseOutput`）命名了阶段之间的契约，可能有助于可读性。具体哪些 DTO 确实造成了困惑，哪些起到了文档化流程的作用？

#### M-4: Event emission 分散在 dispatcher 和原始 emitter 之间

**来源：** 双方

`AgentEventDispatcher` 集中处理了约 10 种 event 类型，但 `TurnExecutionPhaseRunner.kt:148-179` 和 `SubAgentRunner.kt:207-226` 绕过了它。"agent event 从哪里来"实际上并未真正集中化。

解决方向：向 dispatcher 添加缺失的方法（它已经覆盖了大部分场景）。

#### M-5: Tool 参数解释存在重复

**来源：** CODEX

`ActionDescriptionFormatter.kt:20-124` 和 `ActionSignature.kt:20-94` 都独立解码 mobile_action 的参数变体（文本目标、bounds、坐标、element-index、子类型）。任何 schema 变更都需要更新两个 JSON parser。

---

### LOW

#### L-1: 死代码清扫

**来源：** 双方（Claude 更详细）

- `AgentDef.id` — 无生产代码使用
- `AgentRegistry.getAll()` — 无生产代码使用
- `PreTurnContext.appTier` — 赋值但从未被读取
- `LoopWarningSeverity.CRITICAL` — 从未被发出
- `ScreenSignature.fingerprint` — 计算但未使用
- `Agent.kt:69-75,173-190` 中重复的 pause/resume 状态发射

#### L-2: 双重取消信号

**来源：** Claude

`Agent.kt` 同时拥有 `CompletableDeferred<AgentStopReason>` 和 `AtomicBoolean(stopRequested)`。统一为单一 deferred 是可行的，但存在生命周期方面的微妙之处：`SessionAgentRunner` 拥有外部 deferred，`Agent` 拥有内部 flag。Claude 的评审中对这一权衡的阐述不够充分。

**待讨论：** 在所有 pause/resume/shutdown 路径下，统一是否安全？

**对齐立场：** 将此项从主简化序列中推迟。仅在 P0/P1 完成后重新审视，且在修改之前必须先进行一次明确的 pause/resume/shutdown 状态审计。

#### L-3: Turn.kt 文本恢复的复杂性

**来源：** Claude

130 行防御性解析代码，用于从 LLM 文本中恢复 tool call。包含多条恢复路径（object-wrapped、inline markers、balanced JSON、markdown fence stripping）。在使用现代 function-calling 模型的情况下，这些可能已经是残留代码。

**解决方案：** 添加遥测来衡量触发率。经过一个 eval 周期后，移除从未触发的路径。不要在没有数据支撑的情况下推测性地移除。

#### L-4: 硬编码的魔法延迟

**来源：** Claude（由 CODEX 完善）

`TurnExecutionPhaseRunner.kt:42`（`delay(200)`）和 `:216`（`delay(500)`）是魔法数字。它们服务于不同目的（执行前节奏控制 vs. 操作后观察稳定）。解决方案：命名它们并添加注释。只有在语义确实匹配的情况下才与 `config.uiSettleDelayMs` 统一。
