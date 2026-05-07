# 最终设计：把 Session 作为规范的产品对象

## 目标

让 `Session` 成为一个持久化的产品对象，负责在任务完成、空闲退出、进程死亡以及未来多入口场景下维持对话连续性。

当前代码把这个概念拆在三个模型中：

- `session/AgentSession.kt`：热运行时
- `history/model/SessionRecord.kt`：可浏览 transcript
- `history/model/SessionRuntimeSnapshot.kt`：reload 状态

这种拆分导致了当前的胶水逻辑：

- `SessionCoordinator.selectedSessionForReload`
- `SessionCoordinator.lastDeadSessionFileName`
- `SessionHistoryManager.externalActiveSessionId`
- `session-*.json` 与 `context-*.json` 的文件名配对

这些其实都是同一个问题的症状：产品层没有一个规范的 session identity。

## 核心决策

### 1. 一个持久化的 session identity

引入一个以不可变 `SessionId` 为 key 的规范持久 session aggregate。

`SessionId` 是持久身份。

`routeKey` 是一个稳定字符串，用来为某个入口解析或创建正确的 open session。

例子：

- `main`
- `direct:telegram:12345`
- `group:telegram:67890`

内部 history 选择应该直接使用 `sessionId`。`routeKey` 只用于入口路由，不用于重新发现一个已知 session。

### 2. 把产品生命周期与运行时状态拆开

Session 状态必须拆成三条独立轴：

- lifecycle：`OPEN | ARCHIVED`
- residency：`HOT | COLD`
- execution：`IDLE | QUEUED | RUNNING | PAUSED | RECOVERING`

这是关键修复。

当运行时关闭时，session 变成 `COLD`，而不是死亡。后续消息仍然命中同一个持久 session，并按需从冷态 hydrate 运行时。

### 3. 保持并发简单

当前设备一次只能执行一个自动化任务。

V1 采用：

- 一个全局执行通道
- 每个 session 一个逻辑 inbox
- 对同一 running 或 queued session 的重复输入使用 collect 语义

暂时不需要真正的多 lane 执行。

### 4. 使用 append-only 的持久 history

为每个 session 持久化一个语义事件流：

- append-only 的 `events.jsonl`
- 只写 finalized events
- 不存 token-by-token delta

这样写入更耐崩溃，也能保留持久时间线，而不会让存储和复杂度爆炸。

## 规范模型

```kotlin
data class SessionManifest(
    val id: SessionId,
    val routeKey: String?,
    val title: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lifecycle: SessionLifecycle,
    val lastKnownExecution: SessionExecutionState,
    val preview: SessionPreview,
    val metadata: SessionMetadata,
)

enum class SessionLifecycle {
    OPEN,
    ARCHIVED,
}

enum class SessionResidency {
    HOT,
    COLD,
}

enum class SessionExecutionState {
    IDLE,
    QUEUED,
    RUNNING,
    PAUSED,
    RECOVERING,
}

data class SessionPreview(
    val lastUserText: String?,
    val lastAgentText: String?,
    val lastCompletionReason: String?,
)
```

关键边界：

- `manifest.json` 是快速列举 / 路由所用的持久摘要 / 索引状态
- `events.jsonl` 才是规范的持久时间线
- `checkpoint.json` 才是规范的运行时 hydration blob

`manifest.json` 不能演化成第二份独立真相。任何在语义上重要的东西，都必须能从 session 目录中恢复出来。

## 存储布局

每个 session 一个目录：

```text
files/sessions/<sessionId>/
  manifest.json
  events.jsonl
  checkpoint.json
  artifacts/
```

### `manifest.json`

用途：

- 快速渲染 session 列表
- route lookup
- preview metadata
- lifecycle state

它取代了 `SessionInfo` 的角色，但它是派生摘要，不是规范对话日志。

### `events.jsonl`

规范的持久时间线。示例事件类型：

- `SessionCreated`
- `UserInputAccepted`
- `TaskQueued`
- `TaskStarted`
- `MessageFinalized`
- `ActionFinalized`
- `ArtifactRecorded`
- `TaskCompleted`
- `SessionArchived`

规则：

- 只能追加
- 只持久化语义事件
- agent message 只写 finalized 结果，不写 streaming deltas
- artifact 在事件里记录路径，不写原始 blob

### `checkpoint.json`

用于热运行时恢复的快速 reload 状态：

- prompt history
- todos
- scratchpad
- config snapshot
- last safe checkpoint metadata

这就是现有 `SessionRuntimeSnapshot` 概念，只是挪进了规范的 session 目录，并移除了 identity ownership。

### `artifacts/`

session 范围内的持久文件，例如：

- screen captures
- 与 trace 关联的 screen state exports
- 未来的 action evidence files

Artifacts 属于 session，不应该散落在一个无关的平行存储命名空间里。

## 运行时架构

### `SessionRepository`

持久 session 数据的单一真相来源。

职责：

- 按 `sessionId` 获取
- 按 `routeKey` resolve 或 create
- 列出 sessions
- archive / delete sessions
- 读写 `manifest.json`
- 追加 `events.jsonl`
- 读写 `checkpoint.json`

它吸收当前分散在 `SessionHistoryManager` 和 `SessionStorage` 里的持久化职责。

### `SessionRuntimePool`

负责管理按 `SessionId` 索引的 live `AgentSession` 实例。

职责：

- 为 open session 创建 hot runtime
- 从 `checkpoint.json` hydrate cold runtime
- 暴露 hot / cold residency
- 将 idle runtime 释放回 cold state

`AgentSession` 保留为执行引擎，但它不再等于产品层的 session 自身。

### `SessionScheduler`

替代 `SessionCoordinator`。

职责：

- 管理单一全局执行通道
- 为每个 session 维护 pending inbox
- 实现 collect 语义
- 维护 session execution state 的迁移

Collect 语义：

- 如果某个 session 已处于 `RUNNING` 或 `QUEUED`，新增输入就追加到同一个 session inbox
- 当该 session 可运行时，scheduler 会按到达顺序把待处理输入合并成一个 follow-up turn

这样“三条快速连续用户消息”会变成一次规范 continuation，而不是三次 agent launch。

## 状态机

```text
Lifecycle:  OPEN ---------------------------------------> ARCHIVED

Residency:  HOT <--------------hydrate/release---------> COLD

Execution:  IDLE -> QUEUED -> RUNNING -> IDLE
                       ^          |
                       |          v
                    collect     PAUSED
```

规则：

- 只有 `OPEN` session 可以接收新输入
- `ARCHIVED` session 依旧可见，但不可运行
- `OPEN + COLD` 是正常稳态
- `RUNNING + new input` 仍然留在同一个 session 内
- 运行时死亡永远不会改变 session identity

## 入口流

所有入口都使用同一条解析路径：

1. 推导 `routeKey`
2. `SessionRepository.resolveOrCreate(routeKey)`
3. 追加 `UserInputAccepted`
4. 如果 runtime 是 cold，就从 `checkpoint.json` hydrate
5. 把 session 放入 `SessionScheduler`
6. 在单一全局 lane 中执行

例子：

- app 默认输入框 -> `main`
- 未来 direct message source -> `direct:<source>:<id>`
- 未来 group source -> `group:<source>:<id>`

History 选择则不同：

- UI 已经持有 `sessionId`
- UI 直接按 `sessionId` 加载
- 内部不再需要 `session:<id>` 这种间接寻址

## UI 模型

UI 会变成对持久 session 的一个视图：

- 侧边栏列出 `manifest.json` 摘要
- 当前选中项是持久的 `sessionId`
- chat screen 即使在 runtime cold 时，也能渲染持久时间线
- active / running badge 来自规范 session state，而不是 bridge fields

这样 activity 层就不再需要 reload intent。

## 与当前代码的映射

保留：

- `AgentSession` 作为热执行 runtime
- `SessionRuntimeSnapshot` 的内容模型，只是换到 `checkpoint.json`
- `AgentMessageBuffer` 对 finalized message / action 的分组

替换：

- `SessionHistoryManager` -> 并入 `SessionRepository`
- `SessionStorage` 的 filename-pair 模型 -> 改成 per-session directory store
- `SessionCoordinator` -> `SessionScheduler`
- `SessionInfo` -> 变成派生 manifest summary
- `selectedSessionForReload` -> 删除
- `lastDeadSessionFileName` -> 删除
- `externalActiveSessionId` -> 删除

## 迁移

不做长期 backward-compat 层。

迁移路径：

1. 引入新的 per-session directory 布局。
2. 添加一个一次性 importer，把旧文件导入新布局：
   - `SessionRecord.messages` -> `MessageFinalized` 与 `ActionFinalized` events
   - `SessionRecord.screenStates` -> `artifacts/` 下的文件 + `ArtifactRecorded` events
   - `SessionRuntimeSnapshot` -> `checkpoint.json`
3. 所有新写入都切到新布局。
4. 把 session 解析和 active-session ownership 从 `MainActivity` 中移出。
5. 用 `SessionScheduler` 替换 `SessionCoordinator`。
6. 删除旧的 filename-pair 逻辑和 reload-specific bridge。

完成导入后，只写新模型。

## V1 非目标

- 真正的多 lane 执行
- 任意 session compaction 策略
- 超出稳定 `routeKey` 字符串之外的 channel-specific routing abstraction

如果未来超长 session 确实需要 compaction，应把它作为语义事件之上的存储操作来加，而不是提前把 V1 复杂化。

## 为什么这个方案更优

- runtime、storage 和 UI 共享同一个规范 session identity
- idle shutdown 只是转成 cold state，而不是对话死亡
- 跨入口连续性变成路由问题，而不是 reload hack
- queueing 成为 session 的属性，而不是 UI 胶水
- 存储布局天然拥有 artifacts 与未来 evidence files

这是在真正解决需求前提下，最简单的设计。
