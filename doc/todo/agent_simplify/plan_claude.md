# Agent 模块简化方案

## 原则

1. **删除比重构优先** — 能删的代码不重写
2. **内联比抽象优先** — 只有一种实现的 interface 直接变成 class
3. **每一步可编译** — 每个 Phase 结束后项目能 build 通过
4. **不改外部 API** — Agent 对 session 层的接口保持不变
5. **保留测试覆盖** — 有测试的代码做等价变换

---

## Phase 1：低风险清理（死代码 + 过早抽象）

**风险**：Low  
**预计减少**：~80 行代码，~5 个文件简化

### 1.1 删除 TurnResult.parseErrors

`parseErrors` 字段永远为 `null`，是 Responses API 迁移后的遗留物。

```
变更文件: Turn.kt
操作: 从 TurnResult data class 中移除 parseErrors 字段
     移除所有 parseErrors = null 赋值
```

### 1.2 删除 ContextPolicy 单值 enum

```
变更文件: cognition/context/ContextPolicy.kt — 删除
变更文件: cognition/profile/CognitionProfile.kt — 移除 contextPolicy 字段
```

### 1.3 内联 RetryPolicy

```
变更文件: cognition/policy/RetryPolicy.kt — 删除
变更文件: cognition/profile/CognitionProfile.kt — retryPolicy: RetryPolicy → retryOnTransientNetworkError: Boolean
变更文件: 所有引用处做等价替换
```

### 1.4 删除 Turn.run()（非流式方法）

确认 `run()` 没有被调用（只有 `runStreaming()` 在使用），删除。

```
变更文件: Turn.kt
操作: 删除 suspend fun run() 方法（~30 行）
```

### 1.5 简化 modelNameToChatModel

用 `ChatModel.of(modelName)` 替代硬编码 when 表达式，加 fallback。

```
变更文件: Turn.kt
操作: 替换 modelNameToChatModel 实现为动态查找
     ~25 行 → ~5 行
```

### 1.6 合并双重角色枚举

保留 `AgentExecutionRole`（有 STANDALONE），删除 `cognition/AgentRole`。`PromptAssembler` 中的角色判断改为基于 `AgentExecutionRole`。

```
变更文件: cognition/AgentRole.kt — 删除
变更文件: cognition/prompt/PromptAssembler.kt — 改用 AgentExecutionRole 或 allowedToolNames 判断
变更文件: AgentConfig.kt — 保持不变
```

---

## Phase 2：消除不必要的间接层

**风险**：Low-Medium  
**预计减少**：~4 个 interface，~100 行间接代码

### 2.1 合并 Agent.kt 到 AgentRuntime.kt

`Agent` 是纯转发层，不提供任何附加价值。将 `AgentRuntime` 改名为 `Agent`（或保持 `AgentRuntime` 名称但删除 facade）。

```
方案 A（推荐）:
  删除 Agent.kt
  AgentRuntime 改为 public class Agent
  调用方直接用 Agent

方案 B:
  将 AgentRuntime 逻辑全部搬入 Agent.kt
  删除 AgentRuntime.kt

推荐方案 A，更简洁。
```

### 2.2 接口内联（4 个）

以下 interface 各只有一种实现，直接把 Default 实现变成普通 class：

| interface | Default 实现 | 操作 |
|-----------|-------------|------|
| `PromptAssembler` | `DefaultPromptAssembler` | 删除 interface，重命名 Default 为 `PromptAssembler`（普通 class） |
| `ContextPackager` | `DefaultContextPackager` | 删除 interface，重命名 Default 为 `ContextPackager`（普通 class） |
| `TurnToolPolicy` | `DefaultTurnToolPolicy` | 删除 interface，重命名 Default 为 `TurnToolPolicy`（普通 class） |
| `CognitionProfileRegistry` | `DefaultCognitionProfileRegistry` | 删除 interface，改为 companion `fun resolve()` 或 top-level 函数 |

### 2.3 消除 Observation 重复

直接在 `AgentTurnRunner` 中使用 `ToolObservation`，删除 `AgentObservation.kt`。

```
变更文件: AgentObservation.kt — 删除
变更文件: AgentTurnRunner.kt — 用 ToolObservation 替换 Observation
变更文件: AgentTrace.kt — toolResult() 参数类型改为 ToolObservation
```

---

## Phase 3：分解超大文件

**风险**：Medium  
**预计效果**：两个超限文件降到 400 行以下

### 3.1 AgentTurnRunner 分解

当前 `executeTurn()` 是 360 行的单一方法。按 ReAct 三阶段拆分：

```
新结构:
  AgentTurnRunner.kt     (~200 行) — 主流程协调
  TurnPerception.kt      (~80 行)  — screen capture + navigation state
  TurnToolExecutor.kt    (~180 行) — tool execution loop + observation capture

AgentTurnRunner.executeTurn() 变为:
  1. val perception = TurnPerception(services, eventDispatcher, trace).capture(turnId, turnNumber, state)
  2. val llmResult = callLlm(perception, turnId, turnNumber)
  3. val execution = TurnToolExecutor(services, eventDispatcher, trace, eventEmitter).execute(llmResult, turnId, turnNumber, perception.snapshot)
  4. return buildOutcome(execution)
```

### 3.2 AgentTrace 压缩

当前 475 行中约 300 行是 `buildJsonObject { ... }` 样板。两种方案：

**方案 A（推荐）：Data class + kotlinx.serialization**

```kotlin
// 把每种 trace 事件定义为 @Serializable data class
@Serializable
data class SessionStartedData(
    val goal: String,
    val taskId: String,
    val agentId: String,
    ...
)

// trace 方法变为
fun sessionStarted(config: AgentConfig) {
    trace.emit(
        sessionId = sessionId.value,
        type = "session_started",
        data = TraceJson.instance.encodeToJsonElement(
            SessionStartedData(
                goal = config.goal,
                taskId = config.taskId,
                ...
            )
        )
    )
}
```

预期 475 行 → ~250 行。

**方案 B：提取 TraceDataBuilder helper**

```kotlin
object TraceDataBuilder {
    fun sessionStarted(config: AgentConfig): JsonObject = buildJsonObject { ... }
    fun sessionStopped(reason: AgentStopReason, turns: Int): JsonObject = buildJsonObject { ... }
    ...
}
```

把 JSON 构建逻辑搬到 helper，AgentTrace 专注于 emit 调用。

### 3.3 ActionDescriptionFormatter 简化

当前 click/long_press/type 的 target resolution 代码高度重复（每个 action 都重复 resource_id/text/bounds/point 判断）。提取公共方法：

```kotlin
private fun resolveTarget(args: JSONObject): String {
    val resourceId = args.optString("resource_id", "").trim()
    val text = args.optString("text", args.optString("target_text", "")).trim()
    return when {
        resourceId.isNotEmpty() -> "resource_id '$resourceId' (index ${args.optInt("resource_id_index", 0)})"
        text.isNotEmpty() -> "text \"$text\" (index ${args.optInt("text_index", 0)})"
        args.has("x1") && args.has("y1") -> "bounds (${args.optInt("x1")},...)"
        args.has("x") && args.has("y") -> "(${args.optInt("x")},${args.optInt("y")})"
        else -> "element ${args.optInt("element_index", -1)}"
    }
}
```

预期 151 行 → ~80 行。

---

## Phase 4：合并 Context 构建管线

**风险**：Medium  
**预计效果**：消除 ContextPackager/AgentPromptBuilder 的职责重叠

### 4.1 统一上下文构建

当前流程：
```
AgentPromptBuilder.buildSystemPrompt()    → system prompt
AgentPromptBuilder.buildUserContext()      → UserContext(text, image)
ContextPackager.buildTurnInput()           → 调用 buildUserContext + 追加 reminders
```

问题：`ContextPackager` 和 `AgentPromptBuilder` 两者都在做 "context building"，边界不清。

**简化方案**：将 `ContextPackager` 的 reminder 逻辑合入 `AgentPromptBuilder`。

```
合并后:
  AgentPromptBuilder.buildSystemPrompt(profile, reminders)  → system prompt（含 state context + reminders）
  AgentPromptBuilder.buildUserContext(snapshot)              → UserContext(text, image)

删除: cognition/context/ContextPackager.kt
```

`AgentTurnRunner` 中原先两步调用变为一步：
```kotlin
// Before:
val packagedInput = contextPackager.buildTurnInput(profile, rawData)
val userContext = packagedInput.userContext

// After:
val userContext = promptBuilder.buildUserContext(snapshot, loopWarning, systemReminders)
```

---

## 执行顺序与依赖

```
Phase 1 (独立清理，无依赖)
  ├── 1.1 删除 parseErrors
  ├── 1.2 删除 ContextPolicy
  ├── 1.3 内联 RetryPolicy
  ├── 1.4 删除 Turn.run()
  ├── 1.5 简化 modelNameToChatModel
  └── 1.6 合并角色枚举

Phase 2 (需 Phase 1 完成)
  ├── 2.1 合并 Agent/AgentRuntime
  ├── 2.2 接口内联 (4个)
  └── 2.3 消除 Observation 重复

Phase 3 (需 Phase 2 完成)
  ├── 3.1 AgentTurnRunner 分解
  ├── 3.2 AgentTrace 压缩
  └── 3.3 ActionDescriptionFormatter 简化

Phase 4 (需 Phase 3 完成)
  └── 4.1 统一 Context 构建管线
```

---

## 预期收益

| 指标 | 当前 | Phase 1 后 | Phase 2 后 | Phase 3 后 | Phase 4 后 |
|------|------|-----------|-----------|-----------|-----------|
| 文件数 | 36 | 33 | 29 | 30* | 29 |
| 总行数 | ~3400 | ~3250 | ~3050 | ~2700 | ~2600 |
| 超限文件 | 2 | 2 | 2 | 0 | 0 |
| 仅一种实现的 interface | 4 | 4 | 0 | 0 | 0 |
| 重复概念 | 2 | 1 | 0 | 0 | 0 |

*Phase 3 拆分了 AgentTurnRunner 所以文件数回升 1

---

## 风险缓解

| 风险 | 缓解 |
|------|------|
| Phase 2.1 合并 Agent/AgentRuntime 影响上层调用 | 保持 class 名为 `Agent`，签名不变 |
| Phase 3.1 分解 AgentTurnRunner 可能引入 bug | 先写测试覆盖 executeTurn 的关键路径 |
| Phase 3.2 序列化方案可能改变 trace JSON 格式 | 运行现有 trace diff 确认输出一致 |
| Phase 4 合并 ContextPackager 影响 cognition profile 机制 | CognitionProfile 的字段继续由 AgentPromptBuilder 消费 |

---

## 不做的事

以下虽然可以进一步简化但风险收益比不好，暂不做：

1. **不删除 subagent/ 子系统** — 虽然增加了复杂度，但它是可工作的功能，不是死代码
2. **不合并 cognition/ 子目录** — 子目录结构本身是好的组织方式
3. **不改 trace 的 emit API** — trace 基础设施在多处使用，改动传播太大
4. **不改 CognitionProfile 系统** — profile 机制本身合理，只是实现有过度抽象
