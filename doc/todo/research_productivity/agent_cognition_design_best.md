# Agent Cognition 设计方案（Best Design）

> Date: 2026-02-04  
> Scope: 综合 `agent_success_research_{1,2,3}.md` 与 3 份 review，形成统一落地方案

## 1. 命名决策（替代 "success"）

### 推荐名称
使用 **Cognition（认知）** 作为模块名与概念名。

- 模块路径：`app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/`
- 设计名：`Agent Cognition Layer`

### 为什么是 Cognition
- 比 `success` 更准确：该层负责“如何思考与决策”，不是“结果好坏”。
- 比 `brain` 更专业：`brain` 偏口语、拟人化过强。
- 比 `core` 更清晰：`core` 语义太宽，容易和 runtime/core infra 混淆。

### 命名映射（旧词 -> 新词）
- `Success Hub` -> `Cognition Hub`
- `SuccessProfile` -> `CognitionProfile`
- `successProfileId` -> `cognitionProfileId`
- `SuccessTracer` -> `CognitionTracer`

---

## 2. 设计定位

采用 Design 3 的 **Lab vs Factory** 思想，结合 Design 2 的完整架构与 Design 1 的分阶段落地：

- **Factory（稳定层）**：`AgentRuntime`, `AgentTurnRunner`, tool execution, session state
- **Lab（认知层）**：prompt、context packaging、turn policy、trace artifact、profile 实验切换

目标是：让“认知策略迭代”不再侵入主循环。

---

## 3. 目标与非目标

### 目标
1. Prompt/上下文/决策策略单入口管理。
2. 支持 baseline/variant 的 profile 切换，不改主循环代码。
3. 每轮可复现：能看到模型收到的完整输入。
4. 首阶段保持行为等价，先提可维护性与可观测性。

### 非目标
1. 不改 tool protocol。
2. 不新增 loop 类型。
3. 不在首阶段引入外部模板引擎或热加载系统。

---

## 4. 目标架构

```text
app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/
├── profile/
│   ├── CognitionProfile.kt
│   ├── CognitionProfileRegistry.kt
│   └── BuiltinCognitionProfiles.kt
├── prompt/
│   ├── PromptAssembler.kt
│   ├── PlannerPromptTemplate.kt
│   ├── ExecutorPromptTemplate.kt
│   └── SharedPromptRules.kt
├── context/
│   ├── ContextPackager.kt
│   └── ContextPolicy.kt
├── policy/
│   ├── TurnPolicyEngine.kt
│   └── RetryPolicy.kt
├── trace/
│   └── CognitionTracer.kt
└── metrics/
    └── RunMetrics.kt
```

---

## 5. 核心接口（建议）

```kotlin
enum class AgentRole {
    PLANNER,
    EXECUTOR
}

data class CognitionProfile(
    val id: String,
    val promptVariant: String,
    val contextPolicy: ContextPolicy,
    val retryPolicy: RetryPolicy,
    val turnPolicyMode: TurnPolicyMode
)

interface PromptAssembler {
    fun build(
        role: AgentRole,
        context: PromptBuildContext,
        profile: CognitionProfile
    ): String
}
```

```kotlin
interface ContextPackager {
    fun buildTurnInput(
        role: AgentRole,
        profile: CognitionProfile,
        raw: RawTurnData
    ): PackagedTurnInput
}
```

```kotlin
interface TurnPolicyEngine {
    fun arbitrateToolCalls(
        toolCalls: List<ToolCallRequest>,
        profile: CognitionProfile
    ): ToolArbitrationResult
}
```

---

## 6. 与现有代码的接入点

1. `AgentRuntime.kt`  
   负责装配 `CognitionProfile`、`PromptAssembler`、`ContextPackager`。

2. `AgentTurnRunner.kt`  
   用 `ContextPackager` 产出输入；用 `TurnPolicyEngine` 处理多 tool 冲突/完成条件。

3. `Turn.kt`  
   删除角色规则拼接逻辑，改为接收已构建 `systemPrompt`。

4. `ExecutorAgent.kt`  
   `systemPrompt` 统一改由 `PromptAssembler`/模板提供。

5. `AgentTrace` 或独立 `CognitionTracer`  
   记录完整 prompt 与 input items artifact。

---

## 7. 分阶段实施（最佳落地顺序）

### Phase A（低风险，必须先做）
Prompt 集中化 + `PromptAssembler`，行为不变。

验收：
1. 所有 prompt 文本可在 `agent/cognition/prompt/` 定位。
2. baseline 行为与当前版本一致。

### Phase B（高价值）
完整输入可观测性。

新增 artifact：
1. `turn_{n}_full_prompt.txt`
2. `turn_{n}_llm_input_items.json`
3. `run_summary.json`（可先最小版）

### Phase C（实验能力）
引入 `CognitionProfile` 与 registry，支持 `cognitionProfileId` 切换。

### Phase D（策略解耦）
抽离 `TurnPolicyEngine`，把关键裁决逻辑从 runner 中移出并可配置化。

### Phase E（可选）
评测基线任务集与 profile 对比报表。

---

## 8. 质量门槛（Acceptance Criteria）

1. 切换 profile 不需要改 `AgentRuntime/Turn` 业务逻辑。
2. 每轮都可复现模型最终输入（system prompt + input items）。
3. Prompt 改动可通过单点 diff 评审。
4. 关键行为有回归保障（planner/executor 正常流、tool 调用仲裁稳定）。

---

## 9. 风险与约束

1. 过度抽象导致首期复杂度过高。  
   处理：严格按 Phase A -> B -> C 推进，不跨阶段合并。

2. 完整 trace 可能带来敏感信息落盘风险。  
   处理：在 trace 写入前加 redaction 策略（账号、token、隐私字段）。

3. profile 激增导致维护成本上升。  
   处理：只保留 baseline + 少量活跃 variant，定期清理。

---

## 10. 结论

最佳方案是：
- 架构上采用 **Cognition Hub**（Design 2 主体）  
- 落地上采用 **分阶段低风险迁移**（Design 1）  
- 原则上坚持 **Lab vs Factory**（Design 3）

并统一将原先 “success-*” 命名替换为 **cognition-***，使语义与模块职责一致。

---

## 11. Implementation Plan（执行计划，2026-02-04）

### Summary
按低风险迁移顺序执行：`Phase A -> B -> C -> D`。  
当前先落地 **Phase A（Prompt 集中化，行为等价）**，先保证回归稳定，再推进后续阶段。

### Affected Components
- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentRuntime.kt`：接入 Cognition 组装器
- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt`：改为使用组装后的 prompt
- `app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt`：移除角色规则拼接逻辑
- `app/src/main/kotlin/com/moonkey/androidagent/agent/subagent/ExecutorAgent.kt`：prompt 来源统一
- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTrace.kt`：后续扩展 full prompt/input items artifact
- 新增目录 `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/`（profile/prompt/context/policy/trace/metrics）
- 测试：`app/src/test/kotlin/com/moonkey/androidagent/agent/*` + 新增 `agent/cognition/*Test.kt`

### Phases

#### Phase 1: Prompt 集中化（先做）
1. 新建 `agent/cognition/prompt/`：
   - `PromptAssembler.kt`
   - `PlannerPromptTemplate.kt`
   - `ExecutorPromptTemplate.kt`
   - `SharedPromptRules.kt`
   - `AgentRole.kt`
2. 将当前 `AgentRuntime` 和 `Turn`/`ExecutorAgent` 的 prompt 文本迁移到模板，保持文本语义不变。
3. `Turn.kt` 不再 `buildSystemPrompt()`，只消费外部传入的最终 `systemPrompt`。
4. 补测试，验证 baseline prompt 内容和工具可见性行为不变。  
Risk: Low

#### Phase 2: 输入可观测性（第二步）
1. 在 trace 中新增 artifact：
   - `turn_{n}_full_prompt.txt`
   - `turn_{n}_llm_input_items.json`
   - `run_summary.json`（最小版）
2. 引入 redaction（token/账号等敏感字段脱敏）后再落盘。  
Risk: Medium

#### Phase 3: Profile 切换（第三步）
1. 新建 `CognitionProfile`、`CognitionProfileRegistry`、`BuiltinCognitionProfiles`。
2. 增加 `cognitionProfileId` 配置入口，`AgentRuntime` 按 profile 装配 prompt/context/policy。  
Risk: Medium

#### Phase 4: 策略解耦（第四步）
1. 抽离 `TurnPolicyEngine`，承接 `AgentTurnRunner` 里的多 tool 仲裁与完成条件裁决。
2. 增加行为回归测试矩阵（单 tool、多 tool、`complete_task` 混合）。  
Risk: Medium-High

### Risks
- 行为漂移：通过 Phase 1 的“文本等价 + 回归测试”控制。
- trace 泄露敏感数据：Phase 2 必做 redaction。
- 抽象过早：严格分阶段，不跨阶段合并。

### Testing Strategy
- Unit:
  - `PromptAssembler` 组装结果（planner/executor/local backend）
  - `TurnPolicyEngine` 仲裁规则
  - `CognitionProfileRegistry` 解析与默认回退
- Regression:
  - `TurnToolFilteringTest`
  - `AgentPromptBuilder` 相关用例（或迁移为 cognition prompt 测试）
- Verify:
  - `./gradlew assembleDebug test lint`
