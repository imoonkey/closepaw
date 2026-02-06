# Agent 模块复杂度分析

## 概述

`agent/` 目录当前共 **36 个文件，约 3400 行代码**。核心问题不是代码量本身，而是**不必要的间接层、过早抽象、职责边界模糊**。很多类存在的理由是"将来可能用到"，但实际上只有一种实现或一个值。

## 文件清单与现状

### 根级别（14 文件，~2200 行）

| 文件 | 行数 | 问题 |
|------|------|------|
| Agent.kt | 27 | **空壳 facade**，仅转发 AgentRuntime |
| AgentConfig.kt | 76 | 含 `AgentExecutionRole` enum（与 cognition/AgentRole 重复） |
| AgentEventDispatcher.kt | 121 | 正常，方法多但每个都简单 |
| AgentObservation.kt | 25 | **重复抽象**，与 ToolObservation 近乎相同 |
| AgentPromptBuilder.kt | 92 | 与 ContextPackager 职责重叠 |
| AgentRuntime.kt | 176 | 主循环，合理但依赖链长 |
| AgentStopReason.kt | 13 | 干净 |
| **AgentTrace.kt** | **475** | **超限（>400 行）**，大量 JSON 样板代码 |
| **AgentTurnRunner.kt** | **554** | **严重超限（>400 行）**，一个方法做了所有事 |
| Turn.kt | 307 | `modelNameToChatModel` 硬编码映射；`run()` 非流式方法可能已不使用 |
| TurnInputBuilder.kt | 108 | 合理 |
| TurnOutcome.kt | 13 | 干净 |
| TurnRunnerState.kt | 14 | 干净 |
| ActionDescriptionFormatter.kt | 151 | 极其冗长的格式化逻辑 |

### cognition/（18 文件，~800 行）

| 文件 | 行数 | 问题 |
|------|------|------|
| AgentRole.kt | 19 | 与 AgentConfig 中的 `AgentExecutionRole` 功能重复 |
| context/ContextPolicy.kt | **5** | **只有一个值的 enum**（STANDARD） |
| context/ContextPackager.kt | 105 | 与 AgentPromptBuilder 职责重叠 |
| context/NavigationState.kt | 111 | 合理 |
| metrics/RunMetrics.kt | 13 | data class 使用 var，不符合不可变原则 |
| policy/RetryPolicy.kt | **5** | **只有一个 Boolean 字段的 data class** |
| policy/ExecutorStepPolicy.kt | 77 | 合理 |
| policy/LoopDetectionPolicy.kt | 52 | 合理 |
| policy/TurnToolPolicy.kt | 80 | interface + default impl，但只有一个实现 |
| profile/CognitionProfile.kt | 25 | 合理 |
| profile/CognitionProfileRegistry.kt | 18 | interface + default impl，但只有一个实现且逻辑极简 |
| profile/BuiltinCognitionProfiles.kt | 19 | 合理 |
| prompt/PromptAssembler.kt | 33 | interface + default impl，但只有一个实现 |
| prompt/ExecutorPromptTemplate.kt | 88 | 合理 |
| prompt/PlannerPromptTemplate.kt | 61 | 合理 |
| trace/ArbitrationTrace.kt | 25 | 干净 |
| trace/CognitionTraceRedactor.kt | 80 | 合理 |
| trace/LlmInputItemsTraceSerializer.kt | 72 | 合理 |

### subagent/（4 文件，~283 行）

| 文件 | 行数 | 问题 |
|------|------|------|
| AgentDefinition.kt | 36 | 合理 |
| AgentRegistry.kt | 26 | 合理 |
| ExecutorAgent.kt | 19 | 合理 |
| SubAgentRunner.kt | 202 | `run()` 方法偏长（~90 行） |

---

## 核心问题详析

### 问题 1：空壳 Facade（Agent.kt）

`Agent` 是 `AgentRuntime` 的完全透传，没有任何附加逻辑：

```kotlin
class Agent(...) {
    private val runtime = AgentRuntime(...)
    suspend fun run() = runtime.run()
    suspend fun pause() = runtime.pause()
    suspend fun resume() = runtime.resume()
    fun stop() = runtime.stop()
}
```

**影响**：多一层间接，增加认知负担，没有实际价值。

### 问题 2：AgentTurnRunner 是上帝方法（554 行）

`executeTurn()` 方法独自完成：
1. 感知（screen capture）
2. 导航状态追踪
3. 循环检测
4. 步骤限制评估
5. LLM 调用（通过 Turn）
6. 工具调用仲裁
7. 工具执行
8. 观察捕获
9. 历史记录
10. 事件分发
11. Trace 记录

这些职责混在一个方法中，违反了 400 行限制，也不利于理解和测试。

### 问题 3：Observation 与 ToolObservation 重复

```kotlin
// agent/AgentObservation.kt
sealed class Observation {
    data class ScreenState(...) : Observation()
    data class TextOutput(...) : Observation()
}

// tool/ToolObservation.kt（几乎一样）
sealed class ToolObservation {
    data class ScreenState(...) : ToolObservation()
    data class TextOutput(...) : ToolObservation()
}
```

然后有个转换函数 `toObservation()` 在两者之间映射。这是不必要的抽象层。

### 问题 4：过早抽象（Premature Abstraction）

以下类/接口只有一种实现或一个值，增加了代码量却没有实际收益：

| 抽象 | 实现 | 建议 |
|------|------|------|
| `ContextPolicy` enum | 仅 `STANDARD` | 删除，用 Boolean 或直接内联 |
| `RetryPolicy` data class | 仅一个 `Boolean` 字段 | 删除，用 Boolean 参数 |
| `interface PromptAssembler` | 仅 `DefaultPromptAssembler` | 合并为普通类或函数 |
| `interface ContextPackager` | 仅 `DefaultContextPackager` | 合并为普通类或函数 |
| `interface TurnToolPolicy` | 仅 `DefaultTurnToolPolicy` | 合并为普通类或函数 |
| `interface CognitionProfileRegistry` | 仅 `DefaultCognitionProfileRegistry` | 合并为 companion/top-level 函数 |

### 问题 5：双重角色枚举

```kotlin
// AgentConfig.kt
enum class AgentExecutionRole { PLANNER, EXECUTOR, STANDALONE }

// cognition/AgentRole.kt
enum class AgentRole { PLANNER, EXECUTOR }
```

两个枚举表达几乎相同的概念，只是 `AgentExecutionRole` 多了 `STANDALONE`。

### 问题 6：AgentPromptBuilder 与 ContextPackager 职责重叠

- `AgentPromptBuilder.buildUserContext()` 负责把 ScreenSnapshot 转成 UserContext
- `ContextPackager.buildTurnInput()` 调用 `promptBuilder.buildUserContext()` 再包装额外 reminders

这两层嵌套关系不直观。Context 构建逻辑应该在一个地方。

### 问题 7：AgentTrace 的 JSON 样板代码

AgentTrace（475 行）的大部分内容是手动构建 `buildJsonObject { ... }` 调用。每个 trace 事件都有 10-30 行 JSON 构建代码。这是纯粹的样板，可以通过 data class + 序列化大幅压缩。

### 问题 8：Turn.kt 中的 modelNameToChatModel 硬编码

```kotlin
private fun modelNameToChatModel(modelName: String): ChatModel {
    return when (modelName.lowercase()) {
        "gpt-5.2" -> ChatModel.GPT_5_2
        "gpt-5.2-pro" -> ChatModel.GPT_5_2_PRO
        ...
        else -> ChatModel.GPT_5_2
    }
}
```

每加一个模型就要改代码。应该用 `ChatModel.of(modelName)` 或类似的动态查找。

### 问题 9：TurnResult.parseErrors 永远是 null

```kotlin
data class TurnResult(
    val content: String?,
    val toolCalls: List<ToolCallRequest>,
    val isComplete: Boolean,
    val parseErrors: List<String>? = null  // 永远是 null
)
```

死字段，移除。

### 问题 10：Turn.run() 可能已不使用

`AgentTurnRunner` 只调用 `turn.runStreaming()`，非流式 `turn.run()` 方法可能已是死代码。

---

## 复杂度量化

| 指标 | 当前值 | 目标 |
|------|--------|------|
| 总文件数 | 36 | ~28（-22%） |
| 总行数 | ~3400 | ~2600（-24%） |
| 超限文件（>400 行） | 2 | 0 |
| 单值 enum/单字段 data class | 2 | 0 |
| 仅一种实现的 interface | 4 | 0 |
| 重复概念（Observation, Role） | 2 | 0 |
