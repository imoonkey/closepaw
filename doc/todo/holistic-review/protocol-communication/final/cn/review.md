# Protocol & Communication -- 最终审查

日期: 2026-04-08
审查人: Claude, Codex (double-design, 交叉审查, 已对齐)

---

## 总体评估

核心命令/状态层 (`Op`, `SessionState`) 是扎实的 -- 小型、封闭, 且映射到真实的运行时状态转换。而 event 层和 config 层承载的接口面远超运行时实际需要: 更多的 domain、更多的 completion reason、更多的 error 类别、更多的 event 类型, 但系统或 UI 实际上并未使用它们。

下一轮迭代应优先进行删除和语义修正, 而非增加抽象。

---

## 运作良好的部分

- **`Op`** -- 8 个操作映射到真实的用户意图。`AgentSession.submit()` 中使用穷举式 `when`。无遗漏或冗余的 op。
- **`SessionState`** -- 5 个状态 (`Created`, `Running`, `Idle`, `Paused`, `Shutdown`) 匹配 hot-idle 生命周期。转换由 guard check 强制执行。边界正确: approval/user-wait 状态由 capsule/UI 层处理, 而非 session state。
- **小型 enum** -- `TurnPhase`, `AskUserType`, `AppTier`, `ApprovalDecision`, `ApprovalScope`, `ScreenStatePhase` 范围恰当。
- **sealed 的使用** -- 对于真正封闭的集合是恰当的。问题不在于 "sealed 太多" -- 而是对某些类别使用 sealed 毫无收益。

---

## High-Priority 发现

### H1. Event Domain 层级结构缺乏消费端支撑

`AgentEventDomains.kt` 定义了 12 个 marker interface。所有消费者都是对具体 event 类型做 switch, 而非对 marker 做分发。全仓库搜索未发现任何非 protocol 的消费者导入了任何 marker interface。命名不一致 (`...LifecycleEvent` vs `...DomainEvent`)。净效果: 更高的分类成本, 没有更简单的分发。

### H2. Event 接口面超出了实际运行时契约

- **`AgentError.kt`** -- 11 个 error variant, companion factory, abstract property。从未被实例化, 从未被分发, `isRecoverable` 从未被读取。约 170 行死代码。
- **`SessionError`** -- 已声明, 消费者中有处理分支, 但从未有生产者发出。
- **`TodosUpdated` / `ScratchpadUpdated`** -- 由 `AgentEventDispatcher` 发出, 在所有消费者中都落入 `else -> Unit`。
- **`ApprovalResolved`** -- 已发出但没有任何 event 消费者。Approval UI 状态在 op 提交之前已在本地解决。
- **`TurnStarted.phase`** -- 始终为 `PERCEPTION`, 紧接着就是 `TurnPhaseChanged(PERCEPTION)`。二者之一是冗余的。
- **`ApprovalRequired.actionId`** -- 与 `ApprovalDetails.callId` 重复。消费者使用 `details.callId`。
- **`StatusUpdate.emoji`** -- `String? = null`, 从未被赋予非 null 值。状态字符串直接内嵌 emoji。

### H3. CompletionReason 混淆了任务结果和 Session 关闭原因

`CompletionReason` 同时服务于 `TaskCompleted` (结果: `GOAL_ACHIEVED`, `MAX_TURNS`, `TASK_IMPOSSIBLE`, `ERROR`) 和 `SessionCompleted` (原因: `USER_STOPPED`, `IDLE_TIMEOUT`, `INTERRUPTED`)。消费者不得不对不可能的状态做分支 -- 例如 `SessionCompleted` 配合 `GOAL_ACHIEVED`。`TASK_IMPOSSIBLE` 没有生产者。这是契约设计缺陷, 不仅仅是死代码。

### H4. SessionConfig 职责混合; 持久化有丢失

`SessionConfig` 将执行控制、模型路由、平台/感知、可观测性和 eval 开关压缩到一个扁平对象中。`SessionCheckpointCoordinator` 仅持久化其中一部分 -- `actionDelayMs`、`approvalMode`、`debugMode`、`traceEnabled`、`traceRunId` 和 `excludedTools` 在重新加载时被静默丢弃。`SessionLlmConfig` 允许矛盾状态 (`backendType = OPENAI` 同时 `localConfig` 非 null)。

---

## Medium-Priority 发现

### M1. Approval 标识符命名不一致

`Op.Approve` 使用 `actionId`。`Op.UserResponse`、`ToolRouter.resolveApproval()` 和 `ApprovalDetails` 使用 `callId`。同一个交互流程中, 相同的底层概念使用了不同的名称。

### M2. Protocol 包混合了领域契约和 UI 展示

`StatusUpdate` 自带可选 emoji, 已是展示就绪状态。`ThoughtUpdate` 携带已截断的展示文本。`TextUtils.kt` 中的 `sanitizeThought()` 是纯字符串工具函数, 导致 UI 代码对 protocol 包产生依赖。包名暗示的内容比实际包含的更稳定、更语义化。

---

## Low-Priority 发现

### L1. ApprovalDetails.args: JSONObject

将 `org.json` 依赖泄漏到 protocol 层。在进程内运行没有问题, 但如果 event 跨序列化边界传输则会产生影响。

### L2. 文件粒度

五个文件各包含一个 data class。复杂度很低, 在当前规模下可以接受。

### L3. 序列化不一致

只有 `ScreenStatePhase` 有 `@Serializable`。对于进程内 event 是可以接受的。
