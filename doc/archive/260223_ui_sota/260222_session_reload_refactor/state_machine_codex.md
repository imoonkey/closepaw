status: draft

# Session-Level State Machine (Codex)

## 1. 第一性原理：拆成三条独立状态轴

当前系统把三件事混在一个枚举里，导致转移混乱。应拆成：

1. `ThreadBinding`
- `None`
- `Bound(sessionRef, mode=Reloadable|ViewOnly)`

2. `RuntimeLease`
- `Released`
- `Acquired(agentSessionId)`

3. `TaskState`
- `Idle`
- `Running(taskId)`
- `Paused(taskId)`

`SessionLevelState` 是这三条轴的组合，而不是单一大枚举。

## 2. 最小可用组合（可落地）

为了实现简单，先只暴露 5 个组合态：

1. `EMPTY`  
`None + Released + Idle`

2. `READY_COLD`  
`Bound + Released + Idle`

3. `READY_HOT`  
`Bound + Acquired + Idle`

4. `RUNNING`  
`Bound + Acquired + Running`

5. `PAUSED`  
`Bound + Acquired + Paused`

说明：  
- `Completed` 不再是 session-level 状态；它是 task 事件。  
- `Idle` 不再单独和 `Completed` 并列，统一进入 `READY_*`。

## 3. 事件与转移

1. `NewSession`
- 任意态 -> `EMPTY` -> `READY_COLD`（绑定新 thread）

2. `SelectHistory(sessionRef)`
- 任意态 -> `READY_COLD(Bound=sessionRef)`
- 若 snapshot 不可恢复，则 `mode=ViewOnly`

3. `UserInput` in `READY_COLD`
- `Reloadable`：reload runtime -> `READY_HOT` -> `RUNNING`
- `ViewOnly`：拒绝执行，提示“仅可浏览”

4. `UserInput` in `READY_HOT`
- 直接 -> `RUNNING`

5. `TaskCompleted`
- `RUNNING` -> checkpoint flush -> release runtime -> `READY_COLD`

6. `Takeover`
- `RUNNING` -> `PAUSED`

7. `Resume`
- `PAUSED` -> `RUNNING`

8. `StopTask`
- `RUNNING|PAUSED` -> cancel task -> `READY_HOT` or `READY_COLD`（按资源策略）

9. `CloseThread`（真正会话关闭）
- 任意态 -> `EMPTY`

## 4. 对现有枚举的重构建议

当前 `SessionState`:
- `Created`
- `Running`
- `Idle`
- `Paused`
- `Completed`
- `Shutdown`

建议：
1. 删除 `Completed`（改为事件，不再阻止后续输入）
2. `Created + Idle` 合并为 `Ready`
3. `Shutdown` 只用于 `CloseThread`，不要复用为 `StopTask`

目标枚举：
- `Ready`
- `Running`
- `Paused`
- `Closed`（可选，若保留 session object 生命周期）

## 5. 不变量

1. `ThreadBinding` 是 follow-up 唯一判定来源，不能由 `currentSession == null` 推断
2. `TaskCompleted` 后必须保持 `ThreadBinding=Bound`
3. reload 失败时禁止隐式 fresh fallback
4. `StopTask` 不得清除 binding
