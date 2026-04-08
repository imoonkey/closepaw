# 错误处理与韧性审查 — 最终版

**基础**: Codex 设计（跨层语义分析）+ Claude 降级分析
**共识方**: Claude + Codex，通过双重设计对齐达成一致

---

## 概述

代码库具备良好的**局部故障隔离**: 使用 result 对象而非未检查的 throw，在源头附近进行 retry，采用保守的 fallback，以及面向安全的降级策略（失败时关闭）。

主要薄弱点在于**跨层错误语义**。系统在 `protocol/AgentError.kt` 中定义了丰富的类型化错误模型，但实际执行路径在 session 和 UI 层看到之前，就将错误退化为字符串、布尔值和通用的完成原因。这导致:

1. 部分故障被错误报告（虚假完成、审批吞没）
2. 部分故障在本地可恢复，但因后处理过于脆弱而变成终端错误
3. 面向用户的状态混为一体: denied、cancelled、timed out、impossible 和 internal error
4. 历史/session 持久性弱于 checkpoint 设计所暗示的程度

---

## 表现良好的部分

1. **Tool 执行隔离**: `ToolRouter` 将所有 tool 执行包裹在 try/catch 中 — 单个 tool 崩溃不会导致 agent 循环崩溃
2. **Cloud LLM retry**: 三层 retry 系统（CloudLlmRetry 用于非 streaming，CloudStreamRetryRunner 用于 streaming，Agent 级可恢复 retry）提供纵深防御。Rate limit 处理及 `retryAfterMs` 提取设计合理
3. **平台 result 对象**: `AccessibilityPlatform` 和 `VirtualDisplayPlatform` 倾向返回 `ActionResult` 而非 throw
4. **Checkpoint 系统**: `SessionCheckpointCoordinator` + `SessionRecordingService` 构建了真正的恢复方案
5. **安全降级**: 被屏蔽应用的遮蔽和审批 TOCTOU 复检采用关闭失败策略
6. **取消传播**: `CompletableDeferred<AgentStopReason>` + `AtomicBoolean` 模式确保取消能到达所有层级
7. **Recording service 作为副作用观察者**: 所有 recording 故障都是非致命的 — agent 不会因 recording 失败而停止
8. **Checkpoint 失败非致命**: `flushIdleReady()` 失败时 session 在内存中保持存活，并显示用户可见的状态消息
9. **空屏捕获优雅降级**: 返回零元素 snapshot；agent 仍可导航（按 home、等待、重试）
10. **死 session 处理**: `SessionCoordinator` 返回 `SESSION_DEAD` 并支持自动重新加载

---

## 发现

### Critical (P0)

#### C1. 即使 `complete_task` 从未执行，仍声明任务完成

**文件**: `agent/AgentTurnRunner.kt:103-122`, `agent/TurnExecutionPhaseRunner.kt:51-63`

`AgentTurnRunner.decideCompletion()` 使用计划的 tool 列表，而非实际执行结果。如果某个认知 tool 在 `complete_task` 之前失败，执行中断但 turn 仍报告完成。

**具体错误路径**: 模型返回 `remember_experience(...)` 然后 `complete_task(status=success)`。`remember_experience` 失败。执行循环中断。`complete_task` 从未运行。Turn 仍然完成，因为 `decideCompletion()` 仅检查计划的 tool call。

这是一个正确性 bug — agent 在从未实际调用 `complete_task` 的情况下报告成功。

#### C2. 审批通知失败被吞没，重新标记为用户超时

**文件**: `tool/ToolRouter.kt:129-153`, `agent/TurnExecutionPhaseRunner.kt:165-178`

`TurnExecutionPhaseRunner.emitApprovalRequired()` 捕获并抑制来自事件发射器的异常。`ToolRouter` 期望回调在失败时 throw。当它没有 throw 时，tool 等待 60 秒，然后返回 `"Approval timed out"` — 将责任归咎于用户没有响应他们从未看到的提示。

根因丢失，用户被错误归咎。

### High

#### H1. 类型化 protocol 错误模型是死代码

**文件**: `protocol/AgentError.kt:11-170`, `protocol/SessionLifecycleEvents.kt:18-23`

`AgentError` 定义了 11 种错误变体，带有可恢复性和特定类别的 payload。`SessionError` 被定义为一等 protocol 事件。两者都未被运行时产生。所有错误路径都经过 `TurnErrorClassifier` → `TurnOutcome.Error(message)` → `AgentStopReason.Error(message)` → `CompletionReason.ERROR`。

实际结果: LLM 错误、策略拒绝、用户拒绝、平台故障和格式错误的 tool call 在 session/UI 边界上结构上看起来都一样。

#### H2. `TASK_IMPOSSIBLE` 存在于 protocol 中但运行时从未产生

**文件**: `protocol/CompletionReason.kt:14-18`, `agent/Agent.kt:117-123`, `session/AgentSession.kt:391-397`

"系统故障" 和 "agent 判定任务不可能" 之间的区别被丢弃了。两者都映射到 `CompletionReason.ERROR`。UI 和 recording 层为 `TASK_IMPOSSIBLE` 做了接线但从未看到它。

#### H3. Action 状态被扁平化 — denied/cancelled/timed-out/failed 全部 → "failed"

**文件**: `agent/TurnExecutionPhaseRunner.kt:148-162`, `app/AgentServiceEventHandler.kt:60-63`, `ui/chat/ChatEventReducer.kt:101-129`

`ActionExecuted(success: Boolean)` 将所有非成功结果合并。`TurnExecutionPhaseRunner` 即使对失败的 action 也发射 `"✓ <tool> executed"`。`ActionState.Skipped` 存在于 UI 模型中但从未使用。

#### H4. `ask_user` 被分类为 screen-changing tool

**文件**: `tool/ToolName.kt:11-17, 70-83`, `tool/PolicyEngine.kt:48-57`

`ask_user` 不在 `ToolName` enum 中，默认为 `isScreenChanging = true`。在请求帮助前需要审批；在被屏蔽的应用中完全被拒绝。这是倒置的 — `ask_user` 恰好是为 agent 被阻塞的场景而存在的。

#### H5. 关闭清理仅部分加固

**文件**: `session/SessionServices.kt:221-225`

仅 `platform.stop()` 被 try/catch 保护。`llmClient.cleanup()`、`llmClientFactory.cleanupAll()`、`traceRecorder.close()` 可能在清理序列中途中断。

#### H6. `delegate_task` 将子 agent 失败报告为 tool 成功

**文件**: `tool/impl/DelegateTaskTool.kt:161-186`

即使 `result.success` 为 false，也返回 `textToolSuccess(...)`。这影响 action card、历史状态和父 turn 控制流 — 不仅仅是 LLM 上下文。结构性语义 bug。

#### H7. Session 文件写入非原子

**文件**: `history/storage/SessionStorage.kt:77-87`

`writeSession()` 直接写入（没有 temp+rename）。`writeSnapshot()` 正确使用了原子模式。`writeSession()` 期间的进程崩溃会损坏 session 文件。损坏的文件从 `SessionHistoryManager` 中静默消失，用户无任何解释。

#### H8. `onDestroy()` 用 `runBlocking` 阻塞主线程

**文件**: `app/AgentService.kt:213-224`

主线程上 5 秒超时有 ANR 风险，特别是在 service 重启期间。

#### H9. Agent 可恢复 retry 预算过低

**文件**: `agent/Agent.kt:29`

`MAX_RECOVERABLE_RETRIES = 1` 意味着两次连续瞬态错误就会杀死 session。计数器在成功时重置，但在不稳定的移动网络上，一次 retry 太激进了。

### Medium

#### M1. 操作后 observation 捕获可能升级局部失败

**文件**: `agent/TurnExecutionPhaseRunner.kt:181-206, 214-239`

当 tool 失败且没有嵌入 observation 时，`captureObservationWithSnapshot()` 在没有防护的情况下被调用。如果 screen capture throw，局部 tool 失败就会变成 turn 级失败。错误处理本身不应是脆弱的。

#### M2. CancellationException 被当作通用失败捕获

**文件**: `agent/AgentTurnRunner.kt:82-126`, `agent/Turn.kt:78-140`, `session/SessionAgentRunner.kt:87-101`

宽泛的 `catch (Exception)` 块可能将协作式取消转换为通用错误完成。部分路径处理了它，但设计在 cancellation 安全性上不一致。

#### M3. Context-length 错误暴露原始 API 消息

**文件**: `agent/TurnErrorClassifier.kt:31-37`

`TurnErrorClassifier` 正确地将 context-length 错误识别为不可恢复的，但向用户展示原始提供商文本（例如 "This model's maximum context length is 128000 tokens"）。没有给用户可操作的指引。

#### M4. Session/bootstrap 失败展示不佳

**文件**: `session/SessionCoordinator.kt`, `app/AgentService.kt:342`, `ui/main/MainActivity.kt`

LLM bootstrap 失败仅通过 overlay 状态展示。Chat 路径丢失上下文，用户的原始输入文本被丢弃。

#### M5. `completeSession()` 在 null session 时静默跳过

**文件**: `history/SessionRecordingService.kt:207`

`currentSession ?: return` 且无日志消息。同一类中所有其他 guard 语句都记录了警告。

### Low

#### L1. 发射文本后的部分 stream 失败 — 行为正确但未记录

**文件**: `llm/CloudStreamRetryPolicy.kt:24-29`, `agent/Turn.kt:122-126`

发射文本和部分 tool call 后的 stream 失败会丢弃 tool call。当前行为（turn 失败，agent 级 retry 重新运行）是最安全的选项。应记录为有意设计。

#### L2. CloudLlmRetry throw cause 丢失了 TransientException 包装

**文件**: `llm/CloudLlmRetry.kt:38`

在非 streaming 路径中，解包 `TransientException` 导致同一异常被分类两次。功能上正确，但对维护者来说容易混淆。

---

## 综合分析

最高价值的修复不是大范围重构，而是:

1. **修复两个正确性 bug**，即运行时报告了错误的结果 (C1, C2)
2. **使一个类型化错误信封在 agent/session/UI 中具有权威性** — 将 `AgentError` 裁剪到活跃变体，然后贯穿接入 (H1)
3. **端到端保留 cancel/deny/skip 语义** (H3, H4)
4. **加固 shutdown 和持久化**，使故障处理本身不脆弱 (H5, H7, H8)
