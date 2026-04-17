# State & Concurrency 改进计划 — 最终版

由双重设计（Claude + Codex）对齐产生的计划。按风险排序。KISS 原则：减少同步方案的数量，而非增加。

---

## 指导原则

1. 为 session 状态设立一个序列化的生命周期拥有者
2. 为持久化的 session/checkpoint 状态设立一个序列化的写入者
3. 为进行中的 tool 实现真正的取消语义
4. 在交接非瞬时完成时使用显式的瞬态状态

---

## Phase 0：简单加固（随 Phase 1 顺带落地）

这些是不需要结构性变更的低成本修复。在自然契合时随其他改动一起落地，不作为独立的前置阶段。

### 0a. ToolRegistry：使用 ConcurrentHashMap

**文件**：`tool/ToolRegistry.kt`
**变更**：`mutableMapOf()` → `ConcurrentHashMap()`（1 行）

### 0b. TodoState：为 onMutation 添加 @Volatile

**文件**：`session/TodoState.kt`
**变更**：添加 `@Volatile`（1 行）

### 0c. HistoryManager：为 onMutation 添加 @Volatile

**文件**：`history/HistoryManager.kt`
**变更**：添加 `@Volatile`（1 行）

### 0d. SessionAgentRunner：在启动 coroutine 之前赋值状态

**文件**：`session/SessionAgentRunner.kt`
**变更**：在 `scope.launch` 之前用 null job 预注册 `RunnerState`，然后用捕获的 job 引用更新（约 10 行）

---

## Phase 1：使持久化成为单写入者

**优先级**：最高——防止数据丢失。

### 变更

- 将 `SessionRecordingService` 中所有磁盘写入通过一个由 `Mutex` 保护的写入者序列化，附带单调递增的 revision 号
- 写入在提交前检查其 revision 是否与当前一致——过时的写入将被丢弃
- 使 `SessionStorage.writeSession()` 原子化：临时文件 + 重命名（与现有 `writeSnapshot()` 模式一致）
- `forceCheckpoint()` 抢占 pending 的 debounced 任务，而非排队等待

### 验收标准

- 旧的 save 不能覆盖新的 session 记录
- 旧的 checkpoint 不能覆盖新的 checkpoint
- Force checkpoint 立即写入最新快照
- Session JSON 文件在文件层面是崩溃安全的

### 测试

- 重叠 save：无论完成顺序如何，逻辑上更新的 revision 获胜
- Checkpoint 同理
- 在 debounced checkpoint pending 期间执行 `forceCheckpoint()` → 持久化文件包含 forced 状态
- Session 写入使用临时文件替换

---

## Phase 2：序列化 AgentSession 生命周期

**优先级**：高——防止交错导致无效状态。

### 变更

- 将所有生命周期输入通过一个序列化路径：
  - UserInput、Takeover、Resume、Interrupt、Shutdown
  - Runner completion 回调
  - Idle-timeout 过期
- 实现：一个序列化的生命周期路径——如果 `Mutex` 足够则优先使用；如果多源事件模式需要更清晰的方式则使用最小化的命令序列化器。在实现时决定。
- 不允许 `SessionAgentRunner.onComplete` 直接修改 session 状态；通过相同路径路由
- 将 `idleTimeoutJob` 的创建/取消纳入序列化拥有者管理

### 验收标准

- Session 状态只能通过一个序列化的转换路径向前推进
- Completion 和 shutdown 不能交错进入无效状态
- Idle-timeout 的创建/取消不能与用户输入或 shutdown 竞态

### 测试

- Completion + shutdown 紧接着触发 → 最终状态是 `Shutdown`，不是 `Idle`
- 两个 `UserInput` 操作围绕 Idle→Running → 只启动一个任务
- 重复 shutdown → 恰好发出一个终结事件

---

## Phase 3：将 Takeover 建模为真正的多步骤状态

**优先级**：高——修复约定违反。

### 变更

- 添加内部瞬态状态（如 `PauseRequested` / `TakeoverPending`）
- 在 agent 确认到达暂停点之前不发布 `Paused`
- 在 takeover 仍然 pending 时拒绝 `Resume`
- 保持协议表面最小化——如果 UI 不需要，瞬态状态可以是内部的

### 验收标准

- 实现与 `Op.Takeover` 约定一致
- `SessionTakeover` 不能在 `SessionResumed` 之后发出
- 暂停点之前的快速 resume 被排队或干净地拒绝

### 测试

- Takeover 后立即 resume：顺序正确
- 在 pause 确认之前 `Paused` 不可被观察到
- Takeover/resume 事件仅以合法顺序发出

---

## Phase 4：使 Tool 取消成为真实的

**优先级**：中高——防止清理期间的虚假"全部就绪"。

### 变更

- 在 `activeToolCalls` 中存储每次调用的 `ToolExecutionContext`（或 cancellation token）
- `cancel(callId)` 对 approval 等待者和正在执行的调用都发出 token 信号
- `cancelAll()` 将调用推向取消状态，仅在终结时移除追踪
- 如果一个 tool 无法被协作取消，状态机明确说明

### 验收标准

- Router 驱动的 cancel 传播到正在执行的 tool
- `activeToolCalls` 反映真实的执行状态，而非乐观的清理
- Session 清理在 action 仍在运行时不报告"全部已取消"

### 测试

- 轮询 `context.isCancelled()` 的 tool stub：`router.cancel(callId)` 翻转标志
- `cancelAll()` 在一个 tool 等待 approval 而另一个正在执行时触发
- 清理在移除追踪之前验证 tool 的终结状态

---

## Phase 5：修正 Shutdown 原因语义

**优先级**：中——正确性和数据分析。

### 变更

- 将显式的 shutdown 原因传入生命周期转换，而非从前一个状态推断
- 从显式原因映射 `SessionCompleted.reason`

### 验收标准

- 从 `Idle` 手动 shutdown → `USER_STOPPED`，而非 `IDLE_TIMEOUT`
- Timeout shutdown → 仅当 timeout 实际触发时才为 `IDLE_TIMEOUT`

### 测试

- 从 Idle 手动 shutdown：原因为 USER_STOPPED
- 实际 timeout：原因为 IDLE_TIMEOUT

---

## Phase 6：Bootstrap 加固（证据驱动，最低优先级）

**优先级**：低——在核心不变量修复之后再处理。

### 变更（仅在有证据支撑时）

- 在层内部强制 off-main bootstrap，而非在调用方
- 将 `requireOffMainThread()` 从警告改为不变量或内部 dispatcher 跳转
- 考虑高频 MessageDelta 是否应该是尽力而为，而生命周期事件是无损的

### 验收标准

- Session 创建不能在主线程上执行 asset I/O
- 慢速 UI collector 不会阻塞 shutdown 或任务完成

---

## 清理（在方便时进行）

### SessionHistoryManager：移除冗余的 ConcurrentHashMap + Mutex

**文件**：`history/SessionHistoryManager.kt`
**变更**：将 `ConcurrentHashMap` 替换为普通 `HashMap`——所有访问已由 `cacheMutex` 保护

---

## 最低测试矩阵

在声明完成之前，以下所有测试必须通过：

- [ ] SessionRecordingService 重叠 save 排序
- [ ] SessionRecordingService force-checkpoint 抢占
- [ ] AgentSession completion 与 shutdown 交错
- [ ] AgentSession takeover/resume 竞态
- [ ] ToolRouter router 驱动的执行期间取消
- [ ] AgentSession 显式 idle shutdown 原因
