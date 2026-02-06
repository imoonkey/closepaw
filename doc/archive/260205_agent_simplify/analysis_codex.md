# Agent 模块简化分析（Codex）

日期：2026-02-05
范围：`app/src/main/kotlin/com/moonkey/androidagent/agent`

## 2026-02-05 进展更新（single-path 化）

已完成一轮“减少实体 + 去可选配置”落地，核心变化：

1. 删除 cognition profile / A-B test 路径
- 删除：`app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/profile/CognitionProfiles.kt`
- 删除测试：`app/src/test/kotlin/com/moonkey/androidagent/agent/cognition/profile/CognitionProfileRegistryTest.kt`
- 去除配置字段：
  - `SessionConfig.cognitionProfileId`
  - `AgentConfig.cognitionProfileId`

2. 主循环变成固定策略（无 profile 分支）
- `AgentTurnRunner` 不再注入 `CognitionProfile`。
- Loop detection 使用默认策略直接启用。
- tool arbitration 固定为“优先非 complete_task 的单工具执行”。
- transient network recoverable 判定不再依赖 profile 开关。

3. 简化上下文构建 API
- `AgentPromptBuilder.buildUserContext(...)` 去掉 `profile` 参数。
- todo/scratchpad reminder 逻辑直接按会话状态决定，不再走 profile 开关。

4. trace 去掉 profile/mode 噪音字段
- `session_started` 不再写 `cognition_profile_id`。
- arbitration trace 不再写 `policy_mode`。

5. 保留 prompt 文件分离
- 按当前约束，`planner/executor` prompt 继续独立存放在：
  - `agent/cognition/prompt/PlannerPromptTemplate.kt`
  - `agent/cognition/prompt/ExecutorPromptTemplate.kt`

验证：
- `./gradlew :app:testDebugUnitTest` 通过
- `./gradlew :app:assembleDebug` 通过

## 概览

- 文件数：36
- 代码行数：3293
- 超过 400 行文件：2
- 主要复杂度集中在：`AgentTurnRunner.kt`（553 行）、`AgentTrace.kt`（474 行）

## Findings（按简化优先级）

### High

1. `AgentTurnRunner.executeTurn()` 职责过载
- 位置：`app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt:56`
- 现状：一个函数串行做了感知、loop 检测、步数预算、LLM 流式调用、工具仲裁、工具执行、观察采集、history 写入、trace、事件分发、错误分类。
- 影响：阅读和测试成本高；任何小改动都容易引发回归；不符合“一个模块一个变化原因”。
- 简化方向：拆成三个协作者（Perception / Thinking / ActionExecution），`executeTurn()` 只编排。

2. `AgentTrace` 混合“事件语义 + artifact 编排 + JSON 构建”
- 位置：`app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTrace.kt:23`
- 现状：每个 trace 事件都手工拼 `buildJsonObject`，同时处理 redaction/storeText/artifacts。
- 影响：样板重复多，新增事件成本高，改字段容易漏同步。
- 简化方向：提取 `TraceDataFactory`（只负责 data），`AgentTrace` 只负责 emit + artifact 组合。

3. 观察模型重复（`Observation` vs `ToolObservation`）
- 位置：
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentObservation.kt:8`
  - `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolSpec.kt:159`
- 现状：两套几乎同构模型，并通过 `toObservation()` 做转换。
- 影响：额外心智负担和转换代码，类型边界并没有带来真实隔离价值。
- 简化方向：在 agent 层直接使用 `ToolObservation`（必要时用 typealias/adapter 过渡）。

### Medium

4. 单实现接口偏多，制造间接层
- 位置：
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/prompt/PromptAssembler.kt:11`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/context/ContextPackager.kt:20`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/TurnToolPolicy.kt:23`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/profile/CognitionProfileRegistry.kt:3`
- 现状：当前都只有默认实现在用。
- 影响：文件和类型数量增加，调试跳转路径变长。
- 简化方向：先改为 concrete class；真出现第二实现再抽接口。

5. 角色概念有两套定义
- 位置：
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentConfig.kt:13`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/AgentRole.kt:3`
- 现状：`AgentExecutionRole` 与 `AgentRole` 表达接近语义（planner/executor），来源不同（配置 vs 工具集推断）。
- 影响：语义可能漂移；后续引入新角色时需要双点维护。
- 简化方向：统一到一个角色源（优先 `AgentExecutionRole`），工具集推断作为 fallback。

6. `Turn` 的非流式与流式路径存在重复
- 位置：`app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt:57`、`app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt:106`
- 现状：两条路径都有“构建 input/tools/model + 处理响应”逻辑；注释和实际使用存在偏差（主流程只用 streaming）。
- 影响：行为差异风险上升，测试要覆盖两套逻辑。
- 简化方向：保留一个主路径（streaming），`run()` 退化为适配器或移除（先迁移测试）。

7. 模型名映射硬编码在 `Turn`
- 位置：`app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt:205`
- 现状：`when` 枚举所有模型名。
- 影响：每次加模型要改 agent 核心文件，维护点不集中。
- 简化方向：抽 `ModelResolver`（集中在 llm 层），`Turn` 只依赖 resolver。

8. `TurnResult.parseErrors` 基本是死字段
- 位置：`app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt:282`
- 现状：当前构造固定 `parseErrors = null`。
- 影响：数据结构含无效字段，误导调用方。
- 简化方向：删除字段，若未来需要再通过 `sealed error` 单独建模。

9. 轻量策略对象过度包装
- 位置：
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/context/ContextPolicy.kt:3`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/RetryPolicy.kt:3`
- 现状：单值 enum/单字段 data class。
- 影响：看起来“可扩展”，实际没有策略分歧，增加抽象噪音。
- 简化方向：先落回布尔或常量；真实分支出现时再升级为策略对象。

10. Prompt 组装链路分散
- 位置：
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentPromptBuilder.kt:43`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/context/ContextPackager.kt:32`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt:141`
- 现状：system prompt / user context / reminder 拼装分散在 3 处。
- 影响：改 prompt 需要跨文件追逻辑，容易漏。
- 简化方向：定义单一入口 `buildTurnContext(...)`，统一输出 system + user context。

## 建议先不动的部分

1. `AgentEventDispatcher`
- 位置：`app/src/main/kotlin/com/moonkey/androidagent/agent/AgentEventDispatcher.kt:11`
- 原因：虽方法多，但每个方法职责简单，且事件契约清晰。

2. `Agent` facade
- 位置：`app/src/main/kotlin/com/moonkey/androidagent/agent/Agent.kt:12`
- 原因：当前是稳定对外入口；可暂留以降低外部改动面。

## 简化收益预估

- 目标代码行数：3293 -> 2600~2800（约 -15%~-21%）
- 超大文件：2 -> 0
- 核心回路修改时需触达文件数：预计从 5~7 个降到 2~3 个
- 测试关注点：从“整段流程回归”转为“分组件单测 + 少量集成测”
