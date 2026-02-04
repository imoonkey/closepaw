# Agent Success Hub Design (Codex)

> Date: 2026-02-04  
> Goal: 把影响 success 的核心逻辑集中起来，支持快速实验与回归比较

## 1. 背景与问题

你现在的 infra 已经很好（planner/executor、history hygiene、trace、planning tools 都在）。
但“success 调参面”还分散在多个文件，导致迭代成本高。

### 当前分散点（基于代码）

| 维度 | 当前位置 | 问题 |
|---|---|---|
| Planner 基础 prompt | `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentRuntime.kt` | 默认 prompt 和策略耦合在 runtime |
| Planner/Executor 角色规则 | `app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt` | 规则由 `hasDelegate/hasMobileAction` 推断，难做版本化 |
| Executor prompt | `app/src/main/kotlin/com/moonkey/androidagent/agent/subagent/ExecutorAgent.kt` | 与其它 prompt 不在同一体系 |
| Delegation tool 描述 | `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/DelegateTaskTool.kt` | 也是 prompt-like 文本，但不在统一入口 |
| Context 注入策略 | `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentPromptBuilder.kt` + `TurnInputBuilder.kt` | 逻辑分散，难快速切换实验策略 |
| 成功相关执行策略 | `AgentTurnRunner`（多 tool 取首个、completion defer） | 策略不可配置，实验要改 runtime |
| 可观测性 | `AgentTrace.llmRequest()` | 已有 system/user/history 分件，但缺“完整最终输入快照” |

## 2. 设计目标

1. 单入口管理“success 相关行为”（prompt + context + turn 策略）。
2. 不改主循环架构（保持现有 ReAct + 子 agent）。
3. 支持 baseline/variant 并行，方便快速 A/B。
4. 每轮可复现：看到“模型实际收到的完整输入”。

## 3. 方案总览: Success Hub

新增一个轻量模块（不替换现有 session/tool/loop）：

```text
app/src/main/kotlin/com/moonkey/androidagent/agent/success/
├── profile/
│   ├── SuccessProfile.kt
│   ├── SuccessProfileRegistry.kt
│   └── BuiltinProfiles.kt
├── prompt/
│   ├── PromptAssembler.kt
│   ├── PlannerSystemPrompt.kt
│   ├── ExecutorSystemPrompt.kt
│   ├── TurnRulesPlanner.kt
│   ├── TurnRulesExecutor.kt
│   └── ToolPromptHints.kt
├── context/
│   ├── ContextPackager.kt
│   └── ContextPolicy.kt
├── policy/
│   ├── TurnPolicyEngine.kt
│   └── RetryPolicy.kt
└── metrics/
    ├── RunMetrics.kt
    └── TraceRunSummarizer.kt
```

核心思路：
- `PromptAssembler.build(role, context, visibleTools, profile)` 成为唯一 prompt 出口。
- `TurnPolicyEngine` 托管 success 关键决策（如多工具冲突、completion gate）。
- `SuccessProfile` 把“实验参数”集中，不再散落在 runtime 常量中。

## 4. 关键设计细节

### 4.1 Prompt 集中化（先做，无行为变化）

对齐你在 `prompt_refactor.md` 的方向，保留 Kotlin 字符串模板：
- 迁移文本源自：`AgentRuntime.kt`、`Turn.kt`、`ExecutorAgent.kt`、`DelegateTaskTool.kt`。
- `Turn.kt` 不再拼 role rules，只负责调用 LLM。
- `ExecutorAgent.definition.systemPrompt` 改为从 `PromptAssembler`/模板提供。

建议接口：

```kotlin
enum class AgentRole { PLANNER, EXECUTOR }

data class PromptBuildContext(
    val goal: String,
    val screenJson: String,
    val toolNames: Set<String>,
    val todosText: String,
    val scratchpadText: String,
    val backend: LLMBackendType
)

interface PromptAssembler {
    fun build(role: AgentRole, context: PromptBuildContext, profile: SuccessProfile): String
}
```

### 4.2 Context 策略集中化

把“给 LLM 什么上下文”抽到 `ContextPackager`：
- 输入：snapshot、history、todos、scratchpad、profile。
- 输出：`systemPrompt` + `userContext` + `inputItems`。

这样后续可快速试验：
- history 压缩阈值
- screen JSON 裁剪策略
- 是否把 tool result 做短摘要再入 history

### 4.3 Turn 成功策略可配置

把现在硬编码在 `AgentTurnRunner` 的关键逻辑抽成 `TurnPolicyEngine`：
- 多 tool call 冲突时的仲裁
- `complete_task` 与 action 同时出现时的处理
- stuck/重复动作策略（后续可加）

先保持默认与当前行为一致，确保回归稳定。

### 4.4 Profile 驱动实验

新增 `SuccessProfile`（baseline + variants）：

```kotlin
data class SuccessProfile(
    val id: String,
    val promptVariant: String,
    val contextPolicy: ContextPolicy,
    val retryPolicy: RetryPolicy,
    val turnPolicyMode: TurnPolicyMode
)
```

`SessionConfig` 可加：
- `successProfileId: String = "baseline_v1"`
- `experimentTag: String? = null`

这样每次实验只换 profile，不改 runtime 主逻辑。

### 4.5 Trace 可观测性增强（fast iterate 核心）

在现有 `AgentTrace` 基础上加三类 artifact：
- `turn_{n}_full_prompt.txt`：最终 system prompt（角色规则已合成）
- `turn_{n}_llm_input_items.json`：完整 input items（history + user context）
- `run_summary.json`：关键统计（成功率、平均 turn、失败类型、tool error 率）

这比只看碎片（system/user/history 分件）更适合做 prompt regression。

## 5. 代码接入点

| 改动点 | 接入方式 |
|---|---|
| `AgentRuntime` | 构造 `SuccessProfile` + `PromptAssembler` + `ContextPackager` |
| `AgentTurnRunner` | 用 `ContextPackager` 产出输入；用 `TurnPolicyEngine` 做结果仲裁 |
| `Turn` | 删除 `buildSystemPrompt()`，只接收已构造 prompt |
| `ExecutorAgent` | prompt 来源改为 Success Hub 模板 |
| `AgentTrace` | 增加 full prompt / input items / run summary artifact |

## 6. 迭代工作流（面向 research）

1. 新建/修改一个 profile（只改 `agent/success/`）。
2. `scripts/debug-run.sh` 跑固定任务集。
3. 用 trace 比较 baseline vs variant 的 `run_summary.json`。
4. 保留提升版本，回收无效分支。

## 7. 分阶段实施

### Phase A（1-2 天，低风险）
- Prompt 文本迁移到 `agent/success/prompt/*`
- `PromptAssembler` 单入口
- 行为保持 100% 不变

### Phase B（1 天，中低风险）
- `TurnPolicyEngine` 抽离
- 默认策略与现状一致

### Phase C（1 天，高收益）
- Full prompt + full input items trace artifact
- 增加 run summary 统计

### Phase D（持续）
- 引入 2-3 个 profile 做真实 A/B（例如 planner query 风格、completion gate 严格度）

## 8. 非目标（当前不做）

- 不新增新的 loop 类型
- 不改 tool protocol
- 不做 asset/jinja 热加载（先 Kotlin 模板，后续再升级）

## 9. 验收标准

1. 所有 success 相关 prompt 文本在 `agent/success/prompt/` 可定位。
2. 每轮 trace 能直接看到完整 prompt 与完整 input items。
3. 切换 profile 不需要改 `AgentRuntime/Turn` 业务代码。
4. baseline 行为与当前版本一致（主要回归：planner/executor 正常跑通）。

