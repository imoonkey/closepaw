# 错误韧性改进计划 — 最终版

按影响优先排序 (P0 = 立即修复, P1 = 尽快修复, P2 = 后续改进)。
**基础**: Codex 改进计划 + Claude 补充项。通过双重设计对齐达成共识。

---

## P0: 立即修复

### 1. 使任务完成依赖于实际执行的 tool 结果

**文件**: `agent/AgentTurnRunner.kt`, `agent/TurnExecutionPhaseRunner.kt`, `agent/cognition/policy/TurnToolPolicy.kt`, `agent/AgentRuntimeTypes.kt`
**测试**: `TurnToolPolicyTest.kt`, `AgentErrorRecoveryTest.kt`

**问题**: `decideCompletion()` 使用计划的 tool 列表，而非实际执行结果。如果某个认知 tool 在 `complete_task` 之前失败，turn 仍可能被报告为完成。

**修复**: 从 `TurnExecutionPhaseRunner` 返回结构化的执行数据:

```kotlin
internal data class ExecutionPhaseResult(
    val actionSignature: String?,
    val executedToolIds: Set<String>,
    val terminalResult: ToolCallResult? = null
)
```

`AgentTurnRunner` 仅在以下条件满足时发射 `TurnOutcome.Complete`:
- `complete_task` 在计划中被选中
- `complete_task` 存在于 `executedToolIds` 中
- 之前没有 tool 返回 `Error` 或 `Cancelled`

否则根据实际执行结果返回 `TurnOutcome.Error` / `TurnOutcome.Cancelled`。

**工作量**: Medium。

---

### 2. 审批 UI 分发失败时快速失败

**文件**: `tool/ToolRouter.kt`, `agent/TurnExecutionPhaseRunner.kt`
**测试**: `ToolRouterTest.kt`

**问题**: `emitApprovalRequired()` 捕获并抑制发射器失败。这将损坏的审批通知路径变成了虚假的 60 秒用户超时。

**修复**: 让审批通知失败传播回 `ToolRouter.execute()`:

```kotlin
private suspend fun emitApprovalRequired(details: ApprovalDetails) {
    eventEmitter(
        ApprovalRequired(
            sessionId = config.sessionId,
            timestamp = System.currentTimeMillis(),
            actionId = details.callId,
            description = details.description,
            details = details
        )
    )
}
```

如果 `eventEmitter` throw，`ToolRouter` 返回 `ToolCallResult.Error("Approval request failed: ...")`，而非 `ToolCallResult.Cancelled("Approval timed out")`。

**工作量**: Small。

---

### 3. 将 `ask_user` 分类为 non-screen-changing

**文件**: `tool/ToolName.kt`, `tool/PolicyEngine.kt`, `tool/impl/AskUserTool.kt`
**测试**: `PolicyEngineTest.kt`

**问题**: `ask_user` 落入 `ToolName.Unknown`，被视为 screen-changing。在请求帮助前可能需要审批，在被屏蔽的应用中完全被拒绝。

**修复**: 在 canonical 列表中添加 `ToolName.AskUser`，标记 `isScreenChanging = false`。同时添加任何其他使用 `Unknown` fallback 的已发布非 UI tool（例如 `shell`）。

**工作量**: Small。

---

### 4. 从 `AgentService.onDestroy()` 中移除 `runBlocking`

**文件**: `app/AgentService.kt:213-224`

**问题**: 主线程上的 `runBlocking` 可能阻塞最多 5 秒，在 service 重启期间有 ANR 风险。

**修复**: 使用 fire-and-forget 方式关闭。Session 自行处理 checkpoint 持久化，`scope.cancel()` 会取消进行中的工作。`deliverCompletion` 使用 `NonCancellable` 来持久化完成状态。

```kotlin
override fun onDestroy() {
    isServiceActive = false
    instance = null
    eventCollectorJob?.cancel()
    eventCollectorJob = null

    val currentSession = session
    session = null
    currentSession?.let { scope.launch { it.submit(Op.Shutdown) } }

    overlayController?.dispose()
    // ... 其余清理 ...
    scope.cancel()
}
```

**工作量**: Small。

---

## P1: 尽快修复

### 5. 端到端保留 action outcome 语义

**文件**: `protocol/ActionEvents.kt`, `agent/TurnExecutionPhaseRunner.kt`, `app/AgentServiceEventHandler.kt`, `ui/chat/ChatEventReducer.kt`, `history/model/MessageConverter.kt`
**测试**: `ChatActionExecutionMappingTest.kt`, `SessionRecordingServiceTest.kt`

**问题**: `ActionExecuted(success: Boolean)` 将取消、拒绝、超时和失败合并到同一个桶中。失败时仍显示 `"✓ executed"` 状态。

**修复**: 用显式状态枚举替换布尔值:

```kotlin
enum class ActionExecutionStatus {
    SUCCESS,
    FAILED,
    CANCELLED,
    SKIPPED
}
```

映射关系:
- `ToolCallResult.Success` → `SUCCESS`
- `ToolCallResult.Error` → `FAILED`
- `ToolCallResult.Cancelled("User denied"|"Approval timed out"|"Blocked app...")` → `SKIPPED`
- 用户主动中止 → `CANCELLED`

仅对 `SUCCESS` 发射成功勾选标记。

**工作量**: Medium。

---

### 6. 将 `TASK_IMPOSSIBLE` 从内部 `ERROR` 中分离

**文件**: `agent/Agent.kt`, `agent/AgentRuntimeTypes.kt`, `session/AgentSession.kt`
**测试**: `AgentSessionTest.kt`, `CompleteTaskToolTest.kt`

**问题**: `complete_task(status = "failure")` 变成 `AgentStopReason.Error`，因此 `CompletionReason.TASK_IMPOSSIBLE` 永远不会被产生。

**修复**: 添加专用的 stop reason:

```kotlin
sealed class AgentStopReason {
    data class GoalAchieved(val message: String = "Goal achieved") : AgentStopReason()
    data class TaskImpossible(val message: String) : AgentStopReason()
    data object UserRequested : AgentStopReason()
    data object MaxTurnsReached : AgentStopReason()
    data class Error(val message: String) : AgentStopReason()
}
```

`Agent.kt` 将不成功的 `TurnOutcome.Complete` 转换为 `TaskImpossible`。`AgentSession.toCompletionReason()` 映射到 `CompletionReason.TASK_IMPOSSIBLE`。

**工作量**: Medium。

---

### 7. 使类型化错误信封具有权威性

**文件**: `protocol/AgentError.kt`, `protocol/TaskLifecycleEvents.kt`, `protocol/SessionLifecycleEvents.kt`, `agent/TurnErrorClassifier.kt`, `agent/AgentRuntimeTypes.kt`, `session/AgentSession.kt`, `app/AgentServiceEventHandler.kt`, `ui/chat/ChatEventReducer.kt`

**问题**: `AgentError` 存在但实际执行剥离为字符串。逻辑重复且 protocol 表面存在死代码。

**修复**: 将 `AgentError` 裁剪为运行时实际产生的类别，然后贯穿 `TurnOutcome.Error`、`AgentStopReason.Error` 和终端事件 payload:

```kotlin
data class TaskCompleted(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val taskId: String,
    val result: String?,
    val reason: CompletionReason,
    val error: AgentError? = null
) : TaskLifecycleEvent
```

UI 渲染 `error?.message`；不通过字符串推断类别。如果此变更后 `SessionError` 冗余则移除。

**工作量**: Large。

---

### 8. 从 `delegate_task` 返回结构化失败

**文件**: `tool/impl/DelegateTaskTool.kt`
**测试**: `DelegateTaskToolTest.kt`

**问题**: 失败的子 agent 运行通过 `textToolSuccess(...)` 返回，因此父控制流和 action card 将其视为成功。

**修复**: 当 `result.success` 为 false 时，返回 `ToolExecutionResult.Failure("Sub-agent failed: ...")`。仅对成功的委托保留 success。

**工作量**: Small。

---

### 9. 加固清理和 observation fallback

**文件**: `session/SessionServices.kt`, `agent/TurnExecutionPhaseRunner.kt`
**测试**: `TurnExecutionPhaseRunnerActionSignatureTest.kt`

**问题**: 清理在第一个 throw 的步骤处停止。操作失败后的 observation 捕获可能将局部 tool 失败升级为 turn 失败。

**修复**:
- 独立包裹每个清理步骤 (`platform.stop()`, `llmClient.cleanup()`, `llmClientFactory.cleanupAll()`, `traceRecorder.close()`)
- 用 `runCatching` 包裹 `captureObservationWithSnapshot()`；失败时回退到 `ToolObservation.TextOutput(result.toContextString())`

**工作量**: Small-Medium。

---

### 10. 增加 agent 可恢复 retry 预算

**文件**: `agent/Agent.kt:29`

**问题**: `MAX_RECOVERABLE_RETRIES = 1` 意味着两次连续瞬态错误就会杀死 session。对移动网络来说太激进。

**修复**:

```kotlin
private const val MAX_RECOVERABLE_RETRIES = 3
```

LLM 级 retry（5 次尝试带 backoff）处理大多数瞬态问题。Agent 级 retry 是最后手段，但在不稳定的移动网络上，1 次不够。

**工作量**: Trivial。

---

### 11. 用户友好的 context-length 错误消息

**文件**: `agent/TurnErrorClassifier.kt`

**问题**: Context-length 错误暴露原始 API 消息，没有可操作的指引。

**修复**:

```kotlin
if (isContextLimit) {
    return TurnErrorClassification(
        message = "Conversation too long for model context window. " +
            "Try starting a new task or reducing the number of turns.",
        recoverable = false
    )
}
```

**工作量**: Small。

---

### 12. 在 `completeSession()` 中记录 null-session guard 日志

**文件**: `history/SessionRecordingService.kt:207`

**问题**: `currentSession ?: return` 静默跳过 session 完成且无日志。同一类中所有其他 guard 都记录了警告。

**修复**:

```kotlin
val session = currentSession ?: run {
    Log.w(TAG, "completeSession called with no active session")
    return
}
```

**工作量**: Trivial。

---

## P2: 后续改进

### 13. 使 session 写入原子化并展示损坏的历史

**文件**: `history/storage/SessionStorage.kt`, `history/SessionHistoryManager.kt`, `ui/chat/ChatSessionHistoryController.kt`
**测试**: `SessionStorageTest.kt`, `SessionHistoryManagerTest.kt`

**问题**: `writeSession()` 直接写入而 `writeSnapshot()` 是原子的。损坏的文件从 session 列表中静默消失。

**修复**: 对 `writeSession()` 使用 temp+rename。当 `extractSessionInfo()` 无法解析时，展示可见的占位符或明确的消息，而非静默忽略。

**工作量**: Medium。

---

### 14. 使 cancellation 异常安全

**文件**: `agent/AgentTurnRunner.kt`, `agent/Turn.kt`, `session/SessionAgentRunner.kt`
**测试**: `AgentErrorRecoveryTest.kt`, `AgentSessionTest.kt`

**问题**: 宽泛的 `catch (Exception)` 块可能将协作式 cancellation 转换为通用错误完成。

**修复**: 在通用 catch 之前添加显式的 `catch (e: CancellationException) { throw e }`。将 `SessionAgentRunner` 的用户请求路径与意外 cancellation 分开。

**工作量**: Small。

---

### 15. 为 eval/debug-run 提供可配置的审批超时

**文件**: `tool/ToolRouter.kt:38`

**问题**: 60 秒审批超时在自动化 eval 运行中总是触发。

**修复**: 通过 `SessionConfig` 或 `AgentExecutionConfig` 使超时可配置:

```kotlin
private val approvalTimeoutMs: Long = config.approvalTimeoutMs ?: 60_000L
```

**工作量**: Small（需要将 config 传递到 `ToolRouter`）。

---

### 16. 记录 stream 部分失败设计

**文件**: `llm/CloudStreamRetryPolicy.kt`, `agent/Turn.kt`

**问题**: 部分输出后的 stream 失败会丢弃已收集的 tool call。当前行为是最安全的。

**修复**: 记录设计决策 — 不需要代码变更。Turn 失败后通过 agent 级 retry 重新运行是正确做法，因为重新发射部分 tool call 有基于不完整数据执行的风险。

**工作量**: None（仅文档）。

---

### 17. 改善 bootstrap/session 失败的用户体验

**文件**: `session/SessionCoordinator.kt`, `app/AgentService.kt`, `ui/main/MainActivity.kt`

**问题**: Session 创建失败仅显示 toast/overlay 状态。用户的初始输入文本丢失。Chat UI 不展示错误。

**修复**:
- 当 session 创建失败时保留用户的初始文本
- 通过 chat/session UX 展示启动失败，而不仅仅是 toast/状态
- 保持 retry/reload 行为明确

**工作量**: Medium。

---

## 总结

| # | 优先级 | 描述 | 工作量 | 风险 |
|---|--------|------|--------|------|
| 1 | P0 | 完成依赖于已执行的 `complete_task` | Medium | 虚假成功/失败 |
| 2 | P0 | 审批分发失败时快速失败 | Small | 虚假用户超时 |
| 3 | P0 | `ask_user` 设为 non-screen-changing | Small | 用户交接被阻塞 |
| 4 | P0 | 从 `onDestroy()` 移除 `runBlocking` | Small | ANR 风险 |
| 5 | P1 | 端到端 action outcome 语义 | Medium | UI/历史误导 |
| 6 | P1 | 将失败完成映射到 `TASK_IMPOSSIBLE` | Medium | 错误类型混淆 |
| 7 | P1 | 使类型化错误具有权威性 | Large | 死代码 + 不一致 |
| 8 | P1 | `delegate_task` 返回结构化失败 | Small | 失败 = 成功 |
| 9 | P1 | 加固清理和 observation fallback | Small-Med | 脆弱的故障处理 |
| 10 | P1 | 增加可恢复 retry 预算 | Trivial | 不稳定网络上 session 死亡 |
| 11 | P1 | 用户友好的 context-length 消息 | Small | 差的用户体验 |
| 12 | P1 | 在 `completeSession()` 中记录 null-session 日志 | Trivial | 静默跳过 |
| 13 | P2 | 原子 session 写入 + 损坏展示 | Medium | Session 丢失 |
| 14 | P2 | CancellationException 安全 | Small | 停止被误报为错误 |
| 15 | P2 | 可配置审批超时 | Small | 仅 eval |
| 16 | P2 | 记录 stream 部分失败设计 | None | N/A |
| 17 | P2 | Bootstrap/session 失败用户体验 | Medium | 用户输入丢失 |
