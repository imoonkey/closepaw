# State & Concurrency 审查 — 最终版

由 Claude 和 Codex 双重设计对齐产生的审查文档。基础：Codex 设计，辅以 Claude 的本地加固发现。

---

## 范围

- `session/` (13 个文件)
- `protocol/` (27 个文件)
- `history/` (15 个文件)
- `agent/Agent.kt`
- `tool/ToolRouter.kt`, `ToolRegistry.kt`
- `tool/PolicyEngine.kt`
- `ui/chat/ChatViewModel.kt`

## 总结

代码库在局部构建块方面做得不错：`HistoryManager` 使用 `@Synchronized` 配合防御性拷贝，`PolicyEngine` 使用 `AtomicReference` 加 concurrent sets，`SessionHistoryManager` 在缓存失效方面表现保守。当前代码中不存在死锁可能，也未发现资源泄漏。

主要问题出现在更上一层——多个局部线程安全的组件在组合时缺少统一的序列化边界。最大的风险是：

1. 持久化写入可能被乱序执行，导致数据丢失
2. Takeover/pause 状态机与其约定不一致
3. `AgentSession` 的生命周期操作在 suspend 点之间未被序列化
4. Tool 取消仅停留在记录层面——正在执行的 tool 并没有被真正停止

**建议**：`CHANGES_REQUESTED`——在将 session/持久化路径视为可靠（应对快速用户交互、stop/shutdown 竞态或进程死亡）之前，需要先解决上述问题。

---

## 发现

### Critical

#### 1. SessionRecordingService：旧的写入可能覆盖更新的 session/checkpoint 状态

**存在数据丢失风险——最高优先级发现。**

`scheduleSave()` 会取消上一个 job 并启动一个新的 debounce job，但不会等待已经在运行的写入完成。`save()` 在锁内快照状态，在锁外写入。两个并发的 save 可能以乱序完成，旧的快照覆盖新的。

此外，`SessionStorage.writeSession()` 直接写入目标文件（不是临时文件 + 重命名），与 snapshot 写入不同。进程在写入中途死亡可能损坏 session JSON。

`forceCheckpoint()` 通过 `join()` 等待 pending job，但没有先取消它，所以"强制"并非抢占式的。

**证据**：`SessionRecordingService.kt:295-395`，`SessionStorage.kt:77-87` vs `SessionStorage.kt:181-196`。

#### 2. Takeover/pause 状态机违反了其声明的约定

协议规定 takeover 先完成当前 action，然后进入 `Paused`。但实现中 `handleTakeover()` 在 agent 到达暂停点之前就立即设置了 `Paused`，并在该窗口期内接受 `Resume`。

这意味着 `SessionResumed` 可能在 `SessionTakeover` 之前被发出，或者 agent 完全跳过暂停状态，而 UI 却认为自己拥有控制权。

**证据**：`protocol/Op.kt:19-35`，`session/AgentSession.kt:399-424`，`agent/Agent.kt:183-190`。

### High

#### 3. AgentSession 生命周期操作在 suspend 点之间未序列化

`AgentSession` 是顶层生命周期拥有者，但没有 actor、mutex 或命令队列。Handler 在状态转换期间 suspend（emit、forceCheckpoint、platform.start 等），允许交错执行。多个入口点以独立 coroutine 的形式启动操作。

典型的失败场景：completion 启动，在 emit/checkpoint 处 suspend，shutdown 运行并拆除资源，completion 恢复并在已经 shutdown 的 session 上设置 `Idle`。

目前依赖主线程限制来获得部分安全性，但这是一个 coroutine 交错问题（不仅仅是多线程问题）。

**证据**：`session/AgentSession.kt:227-509`，`session/SessionAgentRunner.kt:111-114`。

#### 4. ToolRouter 的 cancel/cancelAll 没有真正取消正在执行的 tool

`cancel()` 解决 approval 等待者并移除追踪条目。`cancelAll()` 完成 approval deferred 并清除 map。两者都没有向正在执行的 tool 发出信号。`SimpleToolRouterContext.cancel()` 存在但 router 从未调用它。Session 清理在错误假设 tool 执行已停止的前提下继续进行。

**证据**：`tool/ToolRouter.kt:239-397`，`tool/ToolSpec.kt:108-123`。

### Medium

#### 5. 从 Idle 状态显式 shutdown 被报告为 IDLE_TIMEOUT

`handleShutdown()` 根据前一个状态推导 completion 原因，将 `Idle` 映射为 `IDLE_TIMEOUT`。用户显式停止一个空闲 session 会被记录为超时。

**证据**：`session/AgentSession.kt:495-499`，`session/SessionCoordinator.kt:164-175`。

#### 6. 非主线程 bootstrap 仅是约定，不是不变量

`SessionLlmBootstrapper.requireOffMainThread()` 只是记录一个警告但仍然继续执行。阻塞式 asset I/O 在调用线程上运行。安全性依赖于每个调用方都用 `withContext(Dispatchers.Default)` 包装。

**证据**：`session/SessionLlmBootstrapper.kt:31-109`。

### Low（快速修复）

#### 7. ToolRegistry：未同步的 HashMap

普通 `mutableMapOf()` 被多个 coroutine 访问。修复方案：`ConcurrentHashMap`。

#### 8. TodoState：onMutation 未标记 @Volatile

与正确使用 `@Volatile` 的 `ScratchpadState` 不一致。

#### 9. HistoryManager：onMutation 未标记 @Volatile

回调在 `@Synchronized` 块之外被调用。Shutdown 时的 null 赋值可能与并发 mutation 竞态。

#### 10. SessionAgentRunner：状态在 coroutine launch 之后才发布

状态赋值发生在 `scope.launch` 之后，因此在该窗口期内调用 `pause()`/`stop()` 会看到过时的状态（agent=null）。

#### 11. SessionHistoryManager：冗余的 ConcurrentHashMap + Mutex

所有访问都由 `cacheMutex` 保护，使得 concurrent map 变得冗余。

---

## 状态所有权表

| 组件 | 可变状态 | 当前保护方式 | 状态 |
| --- | --- | --- | --- |
| AgentSession | _state, currentTaskId, idleTimeoutJob | 无显式保护；依赖调用方约定 | **需要序列化** |
| SessionAgentRunner | active Agent, Job, completion signal | `synchronized(stateLock)` | 局部 OK，跨组件不行 |
| Agent | turnCount, pauseState, pauseConfirmed, stopRequested | 混合（StateFlow, AtomicBoolean, Mutex） | Pause 协议不一致 |
| HistoryManager | items, token estimate | `@Synchronized` | 局部可靠 |
| SessionRecordingService | currentSession, fileName, save/checkpoint jobs, buffer | `synchronized(stateLock)` | 内存层已保护；**磁盘写入未序列化** |
| ToolRouter | activeToolCalls, pendingApprovals | `ConcurrentHashMap` | 追踪并发；**取消不完整** |
| PolicyEngine | approval mode, allow-lists | AtomicReference, concurrent sets | OK |
| ChatViewModel | _uiState, _messages, streamingBuffer | 主线程约定 + chatStateLock | 基于约定 |

---

## 测试空白

以下场景未发现测试覆盖：
- 重叠的 save/checkpoint job，旧写入在新写入之后完成
- 在 debounced checkpoint pending 期间执行 force checkpoint
- Takeover 后立即 resume，在 pause 确认之前
- Session completion 与显式 shutdown 交错
- Router 驱动的 cancel/cancelAll 在 tool 执行期间
- 显式的空闲 session shutdown 与实际的 idle timeout 原因对比
