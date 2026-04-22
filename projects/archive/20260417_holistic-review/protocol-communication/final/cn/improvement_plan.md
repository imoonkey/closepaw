# Protocol & Communication -- 最终改进计划

日期: 2026-04-08
基础: Codex 设计 (结构主体) + Claude 战术条目 (具体删除项)
状态: 已对齐 -- 双方审查者均 APPROVE

---

## 目标

- 使 protocol 只表达运行时实际保证的内容。
- 保留已经运作良好的封闭命令/状态集合。
- 在引入新抽象之前, 先删除投机性接口面。
- 将持久的领域事实与 UI 展示逻辑分离。

## 指导立场

朝更小、更精确的契约演进。不引入更丰富的继承体系, 不使用通用消息封装, 不做向后兼容的补丁。

---

## Phase 1: 裁剪 Event 接口面 (零行为变更)

### 1A. 删除 AgentError.kt

约 170 行, 11 个 variant + companion factory。从未在任何地方被实例化。

**步骤:**
1. 删除 `protocol/AgentError.kt`
2. 从 `SessionError` 中移除 `val error: AgentError` (或完全删除 `SessionError` -- 见 1B)
3. `./gradlew assembleDebug`

### 1B. 删除 SessionError

在 `SessionLifecycleEvents.kt` 中声明, 消费者中有处理分支, 但从未被发出。

**步骤:**
1. 从 `SessionLifecycleEvents.kt` 中移除 `SessionError` data class
2. 从 `AgentServiceEventHandler` 和 `ChatEventReducer` 中移除 `is SessionError ->` 分支
3. 清理孤立的 import
4. 构建验证

### 1C. 折叠 Marker-Interface 分类体系

删除 `AgentEventDomains.kt`。将所有 25 个 event data class 更新为直接继承 `AgentEvent`。让文件分组来承载领域组织。

### 1D. 移除死代码/冗余 Event 元素

- 移除 `StatusUpdate.emoji` 字段以及 `AgentServiceEventHandler` 中的消费分支
- 停止发出 `TodosUpdated` 和 `ScratchpadUpdated` (或完全删除这些 event class)
- 停止发出 `ApprovalResolved` (UI 解决是本地的)
- 移除 `ApprovalRequired.actionId` (与 `ApprovalDetails.callId` 重复)
- 移除 `TurnStarted.phase` 字段, 或停止发出初始的 `TurnPhaseChanged(PERCEPTION)` -- 二选一

### 1E. 移动 sanitizeThought()

将 `protocol/TextUtils.kt` 移至 `util/TextUtils.kt`。更新 `CapsuleStateHolder.kt`、`TurnPlanningPhaseRunner.kt`、`CapsuleModeTest.kt` 中的 import。

**目标: 约移除 257 行, 约消除 27 个类型。单次 commit。**

---

## Phase 2: 修复生命周期语义

### 2A. 拆分 CompletionReason

用两个 enum 替代 `CompletionReason`:

```kotlin
enum class TaskOutcome {
    GOAL_ACHIEVED, MAX_TURNS, TASK_IMPOSSIBLE, ERROR, USER_STOPPED
}

enum class SessionEndReason {
    USER_STOPPED, IDLE_TIMEOUT, INTERRUPTED
}
```

更新:
- `TaskCompleted(..., outcome: TaskOutcome)`
- `SessionCompleted(..., reason: SessionEndReason)`

这将消除消费者中的不可能状态。

### 2B. 决定 Hot-Idle 转换 Event

如果 UI/录制需要知道 session 在任务完成后何时变为可复用状态, 则添加一个专用的 idle-transition event。否则保持 hot idle 为内部状态, 停止重载 `SessionCompleted`。

---

## Phase 3: 统一交互与 Approval 命名

### 3A. 规范化 ID 名词

选择 `callId` (或 `toolCallId`)。将 `Op.Approve.actionId` 重命名为 `callId`。确保在 `Op.Approve`、`ApprovalRequired`、`ApprovalDetails` 和 `ToolRouter.resolveApproval()` 之间保持一致。

### 3B. 重命名 Op.Approve

```kotlin
data class ResolveApproval(
    val callId: String,
    val decision: ApprovalDecision,
    val scope: ApprovalScope = ApprovalScope.ONCE,
    val packageName: String? = null
) : Op
```

### 3C. 不可变 Approval 载荷

将 `ApprovalDetails.args: JSONObject` 替换为 `JsonObject` (kotlinx.serialization) 或 `Map<String, Any>` (如果 UI 需要读取)。如果 UI 从不使用 args, 则移除该字段。

---

## Phase 4: 重构 SessionConfig

### 4A. 按职责拆分

```kotlin
data class ExecutionConfig(
    val maxTurns: Int,
    val actionDelayMs: Long,
    val approvalMode: ApprovalMode,
    val agentMode: AgentMode,
    val platformMode: PlatformMode,
    val perceptionConfig: PerceptionConfig
)

data class ModelConfig(
    val mainModel: String,
    val executorModel: String?,
    // backend + local settings -- 使非法状态不可表示
)

data class ObservabilityConfig(
    val debugMode: Boolean,
    val traceEnabled: Boolean,
    val traceRunId: String?
)

// EvalConfig 仅在 excludedTools 属于生产启动路径时才需要
```

### 4B. 修复持久化

持久化并恢复每个对运行时行为有实质影响的字段。如果某些字段有意不持久化, 需要在文档中明确说明, 并在类型层面强制执行 (分离持久化配置与瞬态配置)。

### 4C. 使非法 LLM 状态不可表示

将 `SessionLlmConfig(backendType, localConfig?)` 替换为 sealed type 或 validated builder, 使其无法产生矛盾的组合。

---

## Phase 5: 明确 protocol/ 边界

### Option B (已采纳): protocol/ = 领域契约

移出 `protocol/`:
- `StatusUpdate` -> `ui/events/` 或 `session/events/`
- `ThoughtUpdate` -> 同上
- 任何 emoji/展示格式化辅助函数

保留在 `protocol/`:
- `Op`, `SessionState`, `AgentEvent`, lifecycle event, action event, approval event, perception event
- Config 类型, enum, value class

---

## 执行顺序

| 步骤 | Phase | 风险 | 依赖 |
|------|-------|------|------|
| 1 | Phase 1 (裁剪) | None | 无 -- 纯删除 |
| 2 | Phase 2 (生命周期) | Medium | 涉及消费者 |
| 3 | Phase 3 (命名) | Low | Phase 2 稳定后 |
| 4 | Phase 4 (配置) | Medium | Phase 2 稳定后 |
| 5 | Phase 5 (边界) | Low | 所有前置 phase 完成 |

Phase 1 可以立即作为单次 commit 落地。Phase 2-5 应各自作为独立 PR, 并附带构建验证。

---

## 非目标

- 不添加更丰富的 event 继承树
- 不添加通用消息封装
- 不保留向后兼容补丁
- 不合并单 event 文件 (在当前规模下可以接受)

---

## 总结

| Phase | 移除/变更行数 | 受影响类型数 | 风险 |
|-------|-------------|------------|------|
| 1. 裁剪 | 约移除 257 行 | 约消除 27 个 | None |
| 2. 生命周期 | 约变更 50 行 | 1 个拆分为 2 个 | Medium |
| 3. 命名 | 约变更 30 行 | 2-3 个重命名 | Low |
| 4. 配置 | 约重构 100 行 | 1 个拆分为 3-4 个 | Medium |
| 5. 边界 | 约移动 40 行 | 2-3 个迁移 | Low |

全部 phase 完成后: 一个更小、更精确的 protocol 模块, 每个类型都有存在的理由, 契约与运行时实际保证一致。
