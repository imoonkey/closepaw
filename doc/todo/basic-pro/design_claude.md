# Basic / Pro Execution Mode Design (Implementation Ready)

> **Author**: Claude + Codex (refined)
> **Last Updated**: 2026-02-06
> **Status**: Ready for implementation
> **Supersedes**: 2026-02-04 draft in the same file

---

## 1. Goals

为 Android Agent 提供两种可选执行模式，降低简单任务时延，同时保留复杂任务能力。

- **Basic Mode**: 单 Agent，直接执行 UI 操作（快）
- **Pro Mode**: Planner + Executor 双 Agent（稳，适合复杂多步）

---

## 2. Confirmed Organization Decision

已确认采用你建议的组织方式：**每个 agent 一个独立定义文件，差异全部并列收敛**。

- `PlannerAgentDef`（主规划）
- `ExecutorAgentDef`（委托执行）
- `StandaloneAgentDef`（单体直连执行）

三者放在 `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/`，每个文件集中定义该 agent 的 `systemPrompt + allowedTools + executionRole`，由 registry 选择，避免业务代码到处分支。

---

## 3. Codebase Reality Check (vs old draft)

旧稿中的以下假设已过时，必须修正后才能实施：

| Old Assumption | Current Reality | Implementation Decision |
|---|---|---|
| `protocol/SessionConfig.kt` 存在 | `SessionConfig` 在 `app/src/main/kotlin/com/moonkey/androidagent/protocol/Op.kt` | 在 `Op.kt` 修改 `SessionConfig` |
| 有 `CognitionProfile`/`PromptVariant` | 当前代码无 `agent/cognition/profile/` 目录 | 不引入该层；改用 `definition` 收敛差异 |
| 有 `PromptAssembler` | 当前入口是 `PromptUtils.buildSystemPrompt(...)` | 删除 role-switch 逻辑，主路径完全由 `AgentDef.systemPrompt` 提供 |
| 使用 `settings/UserPreferences.kt` | 当前是 `AppSettingsStore` + `AppSettingsState` | 在 `app/` 现有 settings 体系加 `agentMode` |
| `SessionAgentRunner` 依赖 profile id | 当前 `SessionAgentRunner` 直接构造 `AgentConfig` | runner 仅消费 `AgentDefRegistry`，不内联差异 |
| `AgentConfig` 命名含义偏泛 | 当前文件名与职责不够直观 | 一次性更名为 `AgentExecutionConfig`（不保留别名） |

---

## 4. Target Runtime Model

### 4.1 Mode Enum

在 `app/src/main/kotlin/com/moonkey/androidagent/protocol/Op.kt` 增加：

```kotlin
enum class AgentMode {
    BASIC,
    PRO
}
```

并在 `SessionConfig` 增加字段（默认 `PRO`，保持兼容）：

```kotlin
val agentMode: AgentMode = AgentMode.PRO
```

### 4.2 Unified `definition` Design (All Differences Live Here)

你提的方向作为主设计：**每个 agent 一个定义文件，集中放 prompt + allowedTools**，其余运行时代码不再写角色差异 if-else。

建议新增目录：

- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/`

建议结构：

- `AgentDef.kt`：抽象基类（或 sealed interface）
- `PlannerAgentDef.kt`
- `StandaloneAgentDef.kt`
- `ExecutorAgentDef.kt`
- `AgentDefRegistry.kt`：mode -> main agent def，及 delegated executor def

建议抽象（示意）：

```kotlin
internal abstract class AgentDef {
    abstract val id: String
    abstract val executionRole: AgentExecutionRole
    abstract val systemPrompt: String
    abstract val allowedTools: Set<String>
    abstract val requiresDelegationToolRegistration: Boolean
}
```

说明：
- 除了 `prompt`、`allowedTools`，至少还需要 `executionRole`（trace/分类）和 `requiresDelegationToolRegistration`（是否要注册 `delegate_task`）。
- 这些也属于“agent 差异”，应跟前两项一起放进 `definition`，避免散落。

#### 4.2.1 AgentDef Matrix

| AgentDef | Execution Role | Prompt Owner | Allowed Tools | requiresDelegationToolRegistration | Used By |
|---|---|---|---|---|---|
| `StandaloneAgentDef` | `STANDALONE` | `StandaloneAgentDef` | `mobile_action`, `app_control`, `scratchpad`, `write_todos`, `complete_task` | `false` | Basic 主 Agent |
| `PlannerAgentDef` | `PLANNER` | `PlannerAgentDef` | `app_control`, `write_todos`, `scratchpad`, `delegate_task`, `complete_task` | `true` | Pro 主 Agent |
| `ExecutorAgentDef` | `EXECUTOR` | `ExecutorAgentDef` | `mobile_action`, `app_control`, `scratchpad`, `complete_task` | `false` | `delegate_task` 子 Agent |

#### 4.2.2 Runtime Boundary (No Behavior If-Else Outside `definition`)

`SessionAgentRunner` 只做：

1. `AgentDefRegistry.mainFor(config.agentMode)` 取 main def  
2. 按 def 的 `requiresDelegationToolRegistration` 决定是否注册 `delegate_task`  
3. 把 def 的 `systemPrompt/allowedTools/executionRole` 注入 `AgentExecutionConfig`  

`SubAgentRunner` 只做：

1. `AgentDefRegistry.executor()` 取 executor def  
2. 用 def 的 `systemPrompt/allowedTools/executionRole` 构建子 agent  

除了 registry 选型点，业务代码不再出现“planner/standalone/executor 差异分支”。

### 4.3 Prompt Ownership

Prompt 不再由独立 `PromptTemplate + role switch` 组合，改为“由各自 `AgentDef` 拥有”：

- `PlannerAgentDef.systemPrompt`
- `StandaloneAgentDef.systemPrompt`
- `ExecutorAgentDef.systemPrompt`

---

## 5. Implementation Plan

### Phase 0: One-shot Refactor Policy (Hard Rule)

- 本改造采用一次性改造（big-bang），**不做渐进式迁移**。
- 不引入 `@Deprecated`、`typealias`、compat shim、双写逻辑。
- `AgentConfig` 直接重命名为 `AgentExecutionConfig`，并一次性全量替换引用。
- 若同一次 PR 无法完成全链路替换，则不合并。

### Phase 1: Protocol + Settings State

#### Files
- `app/src/main/kotlin/com/moonkey/androidagent/protocol/Op.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsState.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivityIntentPayload.kt` (optional but recommended)

#### Changes
- 在 `SessionConfig` 增加 `agentMode`。
- 在 `AppSettings` 增加 `agentMode` 持久化（新 key: `agent_mode`，默认 `PRO`）。
- `MainActivity.ensureSessionAndSend(...)` 创建 `SessionConfig` 时传入 `settingsState.agentMode`。
- 可选：增加 intent extra `agent_mode`（便于 `scripts/dev.sh` / `scripts/debug-run.sh` 指定模式）。

---

### Phase 2: `definition` Layer (Prompt + Tools + Role in one place)

#### Files
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/AgentDef.kt` (NEW)
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/PlannerAgentDef.kt` (NEW)
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt` (NEW)
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/ExecutorAgentDef.kt` (NEW)
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/AgentDefRegistry.kt` (NEW)
- `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/prompt/PromptUtils.kt` (REMOVE role-based system prompt builder)

#### Changes
- 新建三份 `AgentDef` 实现，每份文件里同时定义该 agent 的：
  - `systemPrompt`
  - `allowedTools`
  - `executionRole`
  - `requiresDelegationToolRegistration`
- `AgentDefRegistry` 统一维护 mode 到 definition 的映射。
- 主流程只走 `AgentDef.systemPrompt`，不保留 role-based fallback。

---

### Phase 3: Session Runtime Branching (Definition-driven)

#### Files
- `app/src/main/kotlin/com/moonkey/androidagent/session/SessionAgentRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/AgentDefRegistry.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/subagent/SubAgentRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentExecutionConfig.kt` (rename from `AgentConfig.kt`)

#### Changes
- `SessionAgentRunner.start(...)` 通过 `AgentDefRegistry.mainFor(config.agentMode)` 获取 definition：
  - 仅根据 definition 判断是否注册 `delegate_task`
  - 直接把 definition 的 prompt/tools/role 注入 `AgentExecutionConfig`
- `SubAgentRunner` 使用 `AgentDefRegistry.executor()`，不再内嵌 executor tools/prompt 常量。
- 结果：运行时代码不再维护“agent 差异配置”，差异收敛在 `definition/`。

---

### Phase 4: UI Exposure

#### Files
- `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/SettingsSheet.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/SettingsDropdowns.kt` (recommended)
- `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsState.kt`

#### Changes
- 在 Settings 增加 `Execution Mode` 选择器（Basic / Pro）。
- 选择值写入 `AppSettingsStore`，作为后续会话默认模式。

MVP 取舍：
- 本阶段先做 Settings 入口，保证可用且改动最小。
- Chat 输入框旁的每条消息临时切换（quick toggle）不在本次范围。

---

## 6. Prompt Content Boundaries

### Planner Prompt
- 不做低层 UI grounding。
- 使用 `delegate_task` 驱动 executor。
- 负责任务分解、进度与失败恢复策略。

### Executor Prompt
- 接受单条 delegated query。
- 一次原子动作后尽快 `complete_task`。
- 不进行跨步骤规划。

### Standalone Prompt
- 直接面向用户目标，端到端推进。
- 可直接调用 `mobile_action` 与 `app_control`。
- 多步任务可用 `write_todos`，但不使用 `delegate_task`。

---

## 7. Verification Plan

### Unit Tests (required)

1. `app/src/test/kotlin/com/moonkey/androidagent/agent/cognition/prompt/PromptUtilsTest.kt`
- 删除/重写 `buildSystemPrompt(role)` 相关测试（该分支将被移除）。

2. `app/src/test/kotlin/com/moonkey/androidagent/agent/definition/AgentDefRegistryTest.kt` (NEW)
- `BASIC -> StandaloneAgentDef`。
- `PRO -> PlannerAgentDef`。

3. `app/src/test/kotlin/com/moonkey/androidagent/agent/definition/AgentDefTest.kt` (NEW)
- `StandaloneAgentDef` prompt/tools/role 正确。
- `PlannerAgentDef` prompt/tools/role 正确。
- `ExecutorAgentDef` prompt/tools/role 正确。

4. `app/src/test/kotlin/com/moonkey/androidagent/session/AgentSessionTest.kt`
- 覆盖 `SessionConfig(agentMode=...)` 不破坏既有 session 生命周期。

### Manual Tests (required)

1. Basic 模式下执行“tap/send/open app”类简单任务：
- trace 中主 Agent role 为 `standalone`
- 不出现 `delegate_task` 调用

2. Pro 模式下执行多步任务：
- 仍出现 planner -> executor delegation
- `SubAgentActivity` 事件正常

3. 重启 App 后模式持久化正确。

---

## 8. Rollout and Compatibility

- 默认仍为 `PRO`，旧用户行为不变。
- `agentMode` 新字段有默认值，不影响旧调用方。
- 若增加 intent extra `agent_mode`，未知值回退到 `PRO`。
- 不保留 deprecated API / compatibility shim（仅行为兼容，不做代码层双轨）。

---

## 9. Non-Goals (this iteration)

- 自动任务复杂度识别并自动切 mode
- Basic 卡住后自动升级到 Pro
- 引入 `CognitionProfile`/`PromptVariant` 框架
- 每条消息级别的 mode 临时切换 UI

---

## 10. File Change Checklist

### Must Change
- `app/src/main/kotlin/com/moonkey/androidagent/protocol/Op.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/session/SessionAgentRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/AgentDef.kt` (new)
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/PlannerAgentDef.kt` (new)
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt` (new)
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/ExecutorAgentDef.kt` (new)
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/AgentDefRegistry.kt` (new)
- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentExecutionConfig.kt` (rename target)
- `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/prompt/PromptUtils.kt` (remove role-based system prompt path)
- `app/src/main/kotlin/com/moonkey/androidagent/agent/subagent/SubAgentRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsState.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/SettingsSheet.kt`

### Should Change
- `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/SettingsDropdowns.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivityIntentPayload.kt`
- `scripts/dev.sh`
- `scripts/debug-run.sh`

### Tests
- `app/src/test/kotlin/com/moonkey/androidagent/agent/cognition/prompt/PromptUtilsTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/agent/definition/AgentDefRegistryTest.kt` (new)
- `app/src/test/kotlin/com/moonkey/androidagent/agent/definition/AgentDefTest.kt` (new)
- `app/src/test/kotlin/com/moonkey/androidagent/session/AgentSessionTest.kt`

---

## 11. Definition of Done

满足以下条件即视为可合并：

- `./gradlew test` 通过（包含新增测试）。
- Basic/Pro 在 Settings 可切换且可持久化。
- Basic 主 Agent 不调用 `delegate_task`。
- Pro 路径行为与当前基线一致。
- `AgentConfig.kt` 不再存在，统一为 `AgentExecutionConfig.kt`，且无 `@Deprecated`/`typealias` 兼容层。
- 三个 `AgentDef` 在 `agent/definition/` 并列存在，且 prompt/tools/role 差异仅在 `definition` 内定义。
- `SessionAgentRunner` 与 `SubAgentRunner` 不再内嵌 agent 差异 if-else（只保留 registry 选型）。
