status: draft

# Session Reload Refactor Design (Codex)

## 1. 目标

1. 修复 follow-up 继续失败（包含“reload 失败 toast”和“静默 fresh 导致忘记历史”）  
2. 从 session-level 视角重构状态管理，去掉 spaghetti 变量耦合  
3. 保持 task 完成后释放 runtime 资源的设计目标  

## 2. 核心判断（first principles）

session-level 的本质不是 “AgentSession 对象是否还活着”，而是：
- 当前 UI 绑定的是哪条会话线程（thread binding）

只要 binding 在，runtime 可以被释放并重建；  
如果 binding 丢了，就算 UI 还显示旧消息，也只是“假继续”。

## 3. 当前架构问题（按严重性）

1. **缺少单一状态 owner**：`currentSession`、`selectedSessionForReload`、recordingService、checkpoint state 并行决定行为。  
2. **`Completed` 语义错误**：把“任务完成”做成“会话终止”，导致 follow-up 必须走旁路。  
3. **fallback 策略错误**：reload 不满足条件时，有时 toast 硬失败，有时静默 fresh 软失败，行为不一致。  
4. **Stop/New Session 语义混用**：任务停止与会话关闭没有严格分层。  

## 4. 目标架构

新增单一协调器：`SessionThreadCoordinator`（命名可调整）

职责：
1. 持有唯一 `SessionLevelState`（见 `state_machine_codex.md`）
2. 决策所有入口事件（send/select/new/stop）
3. 统一调用 `create/reload/release`
4. 维护 thread binding 与 runtime lease 的一致性

禁止：
- 在 `MainActivity` 里用多个字段分散编码状态机
- 任何“无 binding 时自动猜最近 session”的隐式行为

## 5. 数据模型建议

```kotlin
data class SessionRef(
    val sessionId: String,
    val sessionFileName: String,
    val contextFileName: String
)

data class SessionLevelState(
    val binding: ThreadBinding,
    val runtime: RuntimeLease,
    val task: TaskState
)
```

其中：
- `ThreadBinding` 决定 follow-up 语义
- `RuntimeLease` 只决定是否需要 reload/create
- `TaskState` 只决定能否接收并发输入

## 6. 关键行为改造

1. `TaskCompleted` 后：
- 必做：checkpoint flush + release runtime
- 但 state 进入 `READY_COLD`（保留 binding），不是 terminal

2. 用户发送 follow-up 且 runtime 已释放：
- 必须先按 binding reload
- reload 失败进入 `ViewOnly`，不允许静默 fresh

3. `NewSession`：
- 显式清 binding
- 创建新 binding

4. `SelectHistory`：
- 只改变 binding，不直接隐式执行任务
- 首条输入时才执行 reload + run

## 7. 对现有代码的最小侵入改造顺序

### Stage 1: 引入单一状态容器（不改业务语义）
1. 新建 `SessionThreadCoordinator`，把 `currentSession/selectedSessionForReload` 收敛进去  
2. `MainActivity` 只发事件，不直接分支拼接状态逻辑  

### Stage 2: 重定义 session-level 状态语义
1. `Completed` 从 session-level 移除（改为 task 事件）  
2. `Created+Idle` 合并为 `Ready`  
3. `TaskCompleted -> READY_COLD`（保 binding）  

### Stage 3: 统一失败策略与 UX
1. reload 失败统一进入 `ViewOnly`  
2. UI 明确区分“可继续 / 仅浏览”  
3. 删除静默 fresh fallback  

## 8. 验收标准

1. 在 `run_20260222_181746` 这种任务完成后，直接 follow-up 不点历史也能继续同一上下文。  
2. 同一聊天窗口内，不出现“UI 还在旧对话但 LLM 实际 fresh”的情况。  
3. reload 不可用时，用户得到一致且明确的“仅浏览 + 新会话”路径。  
4. 状态转移可以由单一状态图解释，不依赖隐式变量组合。  

## 9. 非目标

1. 不做旧 schema 的复杂兼容迁移  
2. 不在本轮引入多会话并发执行  
3. 不调整 smart capsule task-level 状态机，只做 session-level 对齐
