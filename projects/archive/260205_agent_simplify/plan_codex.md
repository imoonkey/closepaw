# Agent 模块简化执行计划（Codex）

日期：2026-02-05
目标：在不改变外部行为的前提下，降低 `agent/` 的结构复杂度与维护成本。

## 当前状态（2026-02-05）

已完成：

1. 去掉 profile/A-B test 配置链（`SessionConfig`/`AgentConfig`/`Agent`/`AgentTurnRunner`/`SessionAgentRunner` 全链路）。
2. `TurnToolPolicy` 改成固定单策略，不再通过 profile mode 分支。
3. prompt user-context 构建去除 profile 参数。
4. 移除 profile 注册相关文件和测试。

剩余建议（继续奥卡姆剃刀）：

1. 把 `AgentRegistry` 退化为“单 executor 常量 + 查找函数”（当前只有一个 sub-agent，registry 抽象收益低）。
2. 评估把 `TurnToolPolicy` 内联进 `AgentTurnRunner`（若后续仍只有一套策略）。
3. 评估把 `ArbitrationTrace` 的几个短 model 并入 `AgentTrace`（减少跨文件跳转）。

## 执行原则

1. 小步提交，每步可编译、可测试。
2. 优先删代码和删抽象，再做重构。
3. 先低风险（类型/样板/重复），后高风险（主循环拆分）。
4. 保持 `Agent` 对外接口稳定（`run/pause/resume/stop` 不变）。

## Phase 1（低风险，先拿收益）

### 1.1 清理 `Turn` 数据与路径重复
- 变更：
  - 移除 `TurnResult.parseErrors`。
  - 抽出 `prepareTurnRequest()`，复用 `run()` 和 `runStreaming()` 的公共逻辑。
  - 迁移 `ChatModel` 解析到独立 `ModelResolver`（先保留原映射逻辑）。
- 影响文件：
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt`
  - 新增 `app/src/main/kotlin/com/moonkey/androidagent/llm/ModelResolver.kt`（建议）
- 验证：
  - `./gradlew test --tests "*TurnToolFilteringTest"`

### 1.2 删除轻量包装抽象
- 变更：
  - `ContextPolicy` 从 profile 中移除。
  - `RetryPolicy` 改为 profile 内直接布尔字段（如 `allowTransientNetworkRetry`）。
- 影响文件：
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/context/ContextPolicy.kt`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/RetryPolicy.kt`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/profile/CognitionProfile.kt`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/profile/BuiltinCognitionProfiles.kt`
- 验证：
  - `./gradlew test --tests "*CognitionProfileRegistryTest"`

## Phase 2（中风险，收敛抽象边界）

### 2.1 单实现接口改为具体类
- 变更：
  - `PromptAssembler`、`ContextPackager`、`TurnToolPolicy`、`CognitionProfileRegistry` 先去接口化。
  - 命名从 `DefaultXxx` 收敛为 `Xxx`。
- 影响文件：
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentRuntime.kt`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/**/*.kt`
- 验证：
  - `./gradlew test --tests "*TurnToolPolicyTest" --tests "*ContextPackagerTest" --tests "*CognitionProfileRegistryTest"`

### 2.2 统一观察模型
- 变更：
  - agent 侧不再维护 `Observation`，改用 `ToolObservation`。
  - 删除 `toObservation()` 转换层。
- 影响文件：
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentObservation.kt`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTrace.kt`
- 验证：
  - `./gradlew test`

## Phase 3（中高风险，拆主流程）

### 3.1 拆分 `AgentTurnRunner`
- 目标结构：
  - `TurnPerceptionExecutor`：采屏、loop 检测、step reminder
  - `TurnThinkingExecutor`：prompt/input 构建、LLM streaming
  - `TurnActionExecutor`：tool 执行、post-action 观察、history/事件写入
- 约束：
  - `executeTurn()` 保持输入输出不变（`TurnExecutionResult`）。
  - 每个协作者 < 250 行。
- 影响文件：
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt`
  - 新增 2~3 个 `agent/` 内部协作类。
- 验证：
  - `./gradlew test --tests "*Agent*"`
  - `./gradlew assembleDebug`

### 3.2 拆分 `AgentTrace`
- 变更：
  - 提取 `TraceDataFactory`（纯数据构建）和 `TraceArtifactFactory`（artifact 清单）。
  - `AgentTrace` 只保留 metrics + emit orchestration。
- 影响文件：
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTrace.kt`
  - 新增 `TraceDataFactory.kt`、`TraceArtifactFactory.kt`（建议）
- 验证：
  - `./gradlew test`

## Phase 4（收口与一致性）

### 4.1 Prompt 构建单入口
- 变更：
  - 在 `AgentPromptBuilder` 内新增统一入口：返回 system prompt + user context。
  - `ContextPackager` 的 reminder 拼装并入该入口（或反向并入，但只留一个入口）。
- 影响文件：
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentPromptBuilder.kt`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/context/ContextPackager.kt`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt`
- 验证：
  - `./gradlew test --tests "*ContextPackagerTest"`

### 4.2 角色来源统一
- 变更：
  - 统一 `AgentRole` / `AgentExecutionRole`，保留一个主类型。
  - prompt 选择使用主类型，工具集推断仅作为 fallback。
- 影响文件：
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentConfig.kt`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/AgentRole.kt`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/prompt/PromptAssembler.kt`
- 验证：
  - `./gradlew test`

## 建议的里程碑验收

1. M1（Phase 1 完成）
- 无行为变化。
- `Turn` 与 profile 结构更轻。

2. M2（Phase 2 完成）
- 抽象层数明显减少。
- 观察模型与策略类边界更清晰。

3. M3（Phase 3 完成）
- `AgentTurnRunner.kt` 与 `AgentTrace.kt` 均 < 400 行。
- 主流程可读性显著提升。

4. M4（Phase 4 完成）
- prompt/context 链路单入口。
- 角色语义单一来源。

## 最终验证命令

- `./gradlew clean assembleDebug lint test`
