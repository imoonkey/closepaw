# 对齐设计：设备能力宣告

## 目标

只暴露当前 session 在运行时**真的能够执行**的命令，而不是先暴露一张几乎静态的工具列表，等模型调用之后才发现失败。

这必须保留当前架构：

- 一份共享的 session tool catalog
- per-agent allowlists
- sub-agent 的 filtered tool views
- 当 capability 在 planning 后、execution 前发生变化时仍然安全

同时，这也应为未来类似 `node.describe` 的 capability advertising 打下干净基础。

## 当前约束

目前：

- `SessionToolingBootstrapper` 会 eager 注册内建工具。
- `SessionAgentRunner` 会 lazy 注册 `ask_user` 和 `delegate_task`。
- `Turn` 每个 turn 都会从 `ToolRegistry` 重新生成 tool schemas。
- `ToolRouter` 只检查工具是否存在，不检查它是否当前可用。
- sub-agents 通过父 registry 构建 filtered child registries。

这意味着系统已经具备一个有利条件：工具 schema 是按 turn 重建的。我们应该利用这一点，而不是引入一个重事件、重变更通知的系统。

## 核心设计

### 1. Session 级 capability snapshot

新增一个 session-scoped capability source，负责产出某个时刻的原始运行时事实快照。

```kotlin
data class CapabilitySnapshot(
    val platformMode: PlatformMode,
    val activeCaps: Set<CapabilityId>,
    val inactiveReasons: Map<CapabilityId, String>,
    val generatedAtEpochMs: Long
)

enum class CapabilityId {
    UI_ACTION,
    APP_LAUNCH,
    LOCAL_SHELL,
    USER_RESPONSE,
    DELEGATION
}
```

作用：

- 作为汇总运行时真实状态的唯一位置
- 为未来 `node.describe` 提供稳定载荷
- 为所有工具可用性检查提供共享输入

这个结构刻意保持小。它不是第二套 policy layer。

### 2. 每个工具一个 provider

每个工具自己拥有自己的 availability decision。

```kotlin
interface ToolProvider {
    val name: String
    fun createSpec(): ToolSpec
    fun availability(snapshot: CapabilitySnapshot): ToolAvailability
}

sealed interface ToolAvailability {
    data object Available : ToolAvailability
    data class Unavailable(val reason: String) : ToolAvailability
}
```

规则：

- 一个 provider 对应一个 tool name
- 共享 predicate 可以抽出来，但最终 availability decision 仍应留在工具本地
- 这样系统才能在未来扩展到 plugin 或 skill-owned tools

例子：

- `wait`、`complete_task`、`write_todos`、`scratchpad`：始终可用
- `mobile_action`、`system_button`：要求 `UI_ACTION`
- `open_app`：要求 `APP_LAUNCH`
- `shell`：Phase 1 可以先视作始终可用，因为命令级失败本来就有显式错误
- `ask_user`：要求 `USER_RESPONSE`
- `delegate_task`：要求 `DELEGATION`

从初稿修正出来的一点：

- `wait` 必须始终可用
- `open_app` 不能因为常与 accessibility 一起出现，就和 `mobile_action` 绑定成同一个 capability

### 3. Agent allowlists 与 capability filtering 保持分离

Capability filtering 与 agent policy filtering 是两件不同的事情。

真正的有效工具集合应是：

`provider available at runtime` ∩ `agent allowedTools` ∩ `excludedTools`

这必须同时适用于：

- standalone agent
- planner
- executor
- 从父视图过滤出来的 sub-agents

动态 capability 层不能把一个统一的 `allowedToolNames` 集合烘焙进 session-wide catalog。

### 4. 把 `ToolRegistry` 演化成 provider-backed catalog + filtered views

名字仍然叫 `ToolRegistry`，但内部存的东西改成 providers，而不是静态的 live tools。

需要的操作：

```kotlin
class ToolRegistry(
    private val capabilitySource: CapabilitySource,
    private val providers: Map<String, ToolProvider>,
    private val allowedNames: Set<String>? = null,
    private val excludedNames: Set<String> = emptySet()
) {
    fun register(provider: ToolProvider)
    fun getAvailable(name: String): AvailableTool?
    fun generateResponsesApiTools(filter: ((ToolSpec) -> Boolean)? = null): List<FunctionTool>
    fun getNames(): Set<String>
    fun describe(filter: ((String) -> Boolean)? = null): DeviceDescription
    fun createFilteredCopy(
        allowedNames: Set<String>,
        excludedNames: Set<String> = emptySet()
    ): ToolRegistry
}
```

行为：

- `generateResponsesApiTools(...)` 先用当前 snapshot 评估 provider availability，再应用现有 filter callback
- `getAvailable(name)` 会在当前 snapshot 下解析单个工具，返回可执行 spec 或 unavailable reason
- `createFilteredCopy(...)` 会在同一 provider catalog 和 capability source 上创建一个 filtered view，这样 sub-agents 也保持动态

这样可以避免 clear-and-rebuild 的 churn，同时概念上保留现有调用点。

### 5. 每个 session 只 bootstrap 一次

每个 session 只注册一次 providers，但分两个构造阶段，因为不是所有依赖一开始都准备好了。

Stage 1 providers，在 session / bootstrap wiring 时注册，此时 platform predicates 与 session state 已经存在：

- `complete_task`
- `wait`
- `write_todos`
- `scratchpad`
- `mobile_action`
- `system_button`
- `open_app`
- `shell`

Stage 2 providers，在 session event wiring 完成后统一注册：

- `ask_user`
- `delegate_task`：总是注册，但由 `DELEGATION` capability gate 控制。这样避免有条件的 bootstrap 分支，也让系统模型保持一致：每个工具都有 provider，可用性总是在运行时计算。

这里的关键变化是：`SessionAgentRunner.start()` 不应继续在运行中途往 registry 里补注册工具。

## 刷新模型

Phase 1 采用 pull-based 模式。

在这些规范边界上刷新 capability snapshot：

- session 创建时
- 第一次 `platform.start()` 时
- hot-idle re-entry 时
- 每次 planning phase 开始时
- execution 之前立刻刷新一次
- approval wait 之后、真正调用工具之前再刷新一次
- platform-start 或 execution-time capability failure 发生后

为什么这样就够：

- tool schemas 本来就是每个 turn 重建
- 大多数 capability 变化都发生在 turn 边界
- execution-time refresh 能补上 approval wait 或瞬时断连带来的 race

Phase 1 不需要 event bus。

## 运行时流程

### Planning

1. `TurnPlanningPhaseRunner` 刷新 capability snapshot。
2. `Turn` 调用 `ToolRegistry.generateResponsesApiTools(...)`。
3. Tool registry 只包含当前可用、并且通过 agent allowlist 过滤的工具。
4. 模型根本看不到当前不可用的工具。

### Execution

1. `ToolRouter.execute(...)` 通过 `ToolRegistry.getAvailable(name)` 解析工具。
2. 如果不可用，返回带 provider reason 的确定性 tool error。
3. 校验参数并构造 invocation。
4. 如需审批，则按当前逻辑等待。
5. 审批通过后，再刷新 capability 并重新解析工具。
6. 如果工具在等待期间变得不可用，应当干净失败，而不是执行过期意图。

这个 execution-time recheck 是必须的，不是可选优化。

### Sub-agents

1. `SubAgentRunner` 从父 registry 创建 filtered registry view。
2. 子视图共享同一个 provider catalog 和 capability source。
3. 子视图仍应用自己的 allowlist，但 availability 仍是实时的。

这保留了现有多 agent 设计，而不是推倒重来。

## Device Description

现在就暴露一个稳定的内部描述对象：

```kotlin
data class DeviceDescription(
    val commands: List<String>,
    val caps: List<String>,
    val platform: String,
    val version: String
)
```

含义：

- `commands`：经过 runtime capability filtering 后当前可用的 tool names
- `caps`：当前激活的 capability ids
- `platform`：当前 platform mode
- `version`：app version

Phase 1 用它来做：

- debug summaries
- traces 与 inspection artifacts
- 为未来外部控制面做语义对齐

## 错误处理

Capability refresh 必须足够稳健：

- 如果刷新发生瞬时失败，保留上一份完整 snapshot，并记录日志
- 不能发布半构建的 snapshot
- 如果此时还没有任何 snapshot，回退到保守快照，隐藏所有 capability-gated 工具

这样在重连等场景下行为仍然确定。

## Phase 1 暂不处理

- push-based permission / service listeners
- networked gateway integration
- 面向用户的 live capability inspection UI
- 在 prompt 里专门枚举不可用工具

Phase 1 里，仅仅把不可用工具从 schema 里移除就已经足够。

## 为什么这是正确的设计

这是解决真实问题的最小设计：

- 它尊重当前 agent / sub-agent wiring
- 它让 availability 归属于各个工具
- 它能防止 capability 丢失后的过期执行
- 它直接为 `node.describe` 铺好路

主要反目标是：

- 不把单一 session-wide allowlist 烘焙进 registry
- 不做会掩盖 tool-specific capability rules 的 grouped provider
- 不做 register / unregister churn loop
- 在真正需要前，不做 event-driven machinery
