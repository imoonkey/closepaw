# Smart Capsule State Machine SOTA (Codex)

更新时间: 2026-02-20
范围: 当前代码中的状态机与控制流（Capsule + Overlay 可见性 + Session 事件桥接）。

## 1. 主状态机: `CapsuleStateHolder`

来源: `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt`。

### 1.1 状态集合

`Hidden | Running | TakeoverPending | Takeover | WaitingForInput | WaitingForAction | Done | Error`

### 1.2 Universal events（无前置 guard）

1. `onTaskStarted(taskId, input)` -> `Running(sanitizeThought(input))`。
2. `onError(message)` -> `Error(sanitizeThought(message))`。
3. `onAskUser(QUESTION, msg, callId)` -> `WaitingForInput(msg, callId)`。
4. `onAskUser(ACTION, msg, callId)` -> `WaitingForAction(msg, callId)`。

### 1.3 Guarded events（不满足条件直接忽略）

1. `onThoughtUpdate` 仅 `Running` 生效。
2. `onTakeoverRequested` 仅 `Running` -> `TakeoverPending`。
3. `onTakeoverConfirmed` 仅 `Running|TakeoverPending` -> `Takeover`。
4. `onResumed` 仅 `Takeover|TakeoverPending` -> `Running("Thinking...")`。
5. `onUserResponseSent(callId)`:
   - 仅 `WaitingForInput|WaitingForAction` 生效。
   - `callId` 必须匹配当前等待态。
   - 成功后 -> `Running("Processing response...")`。

### 1.4 终态与自动迁移

1. `onTaskCompleted`:
   - `GOAL_ACHIEVED` -> `Done(message or "Task completed")`
   - `MAX_TURNS` -> `Done("Max steps reached")`
   - `TASK_IMPOSSIBLE` -> `Done("Task impossible")`
   - `USER_STOPPED` -> `Done("Stopped")`
   - `INTERRUPTED` -> `Done("Interrupted")`
   - `ERROR` -> `Error("Error occurred")`
2. `Done` 进入后 3 秒自动 -> `Hidden`。
3. `onDismissError` 仅 `Error` -> `Hidden`。
4. `onTaskCompleted` 在 `Hidden|Done|Error` 会被忽略。

## 2. 扩展状态变量（非 `CapsuleMode`）

1. `isStopPending`:
   - `onStopRequested` 在可 Stop 的 mode 内置 true。
   - 新任务、终态事件会清零。
2. `turnPhase` 与 `isAgentMidTurn`:
   - 由 `onTurnPhaseChanged`、`onResumed` 等路径维护。
3. `previousMode`:
   - 每次 `setMode` 更新，用于渲染过渡与输入清理策略。
4. `hasActiveTask`:
   - `Running/TakeoverPending/Takeover/WaitingForInput/WaitingForAction` 为 true。

## 3. 可见性状态机: `ServiceOverlayController`

来源: `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt` + `OverlayLocationPolicy.kt`。

### 3.1 控制变量

1. `platformMode`。
2. `userLocation`（`MAIN_APP | VD_VIEWER | OTHER_APP`）。
3. `showPreference`（初始 `ISLAND`）。

### 3.2 可见性决策入口

`applyVisibility()` 是唯一裁决点，输出:
1. `showCapsule`
2. `showIsland`
3. `showGlow`
4. `normalizedShowPreference`

### 3.3 用户位置迁移

1. `handleWindowStateChangedInternal` 通过 `resolveUserLocation` 更新 `userLocation`。
2. 非默认 display 的窗口状态变化会被忽略，防止 VD 子窗口扰动真实前台位置。
3. MainActivity 会主动调用 `onMainAppVisible` 做位置收敛兜底。

### 3.4 ShowPreference 迁移

1. `onTaskStarted` -> `CAPSULE`。
2. `onAskUser` -> `CAPSULE`。
3. `onSessionError` -> `CAPSULE`。
4. `onMinimize` -> `ISLAND`。
5. `onViewerOpened` -> `CAPSULE`。
6. `onViewerClosed` -> `ISLAND`。
7. `deriveOverlayVisibility` 在 `WaitingForInput/WaitingForAction/Error` 自动规范到 `CAPSULE`。

### 3.5 Island 触发状态迁移

1. 若 `!hasActiveTask && mode !is Done/Error` -> 打开主 app。
2. A11y: 直接 `showPreference=CAPSULE`。
3. VD + `VD_VIEWER`: 直接 `showPreference=CAPSULE`。
4. VD + 非 `VD_VIEWER`: 打开 viewer（viewer lifecycle 再驱动位置与可见性）。

## 4. 交互锁状态机（当前实现现状）

来源: `shouldLockUserInteraction` + `CapsuleOverlayHost.setInteractionLocked`。

1. 逻辑定义:
   - A11y: `OTHER_APP` 且 mode 非 `Takeover/Hidden/Done/Error` 时 lock=true。
   - VD: `VD_VIEWER` 且 mode 非 `Takeover/Hidden/Done/Error` 时 lock=true。
2. lock=true 时 overlay host 切全屏并挂拦截 View。
3. 但当前 capsule window 同时带 `FLAG_NOT_TOUCHABLE`，导致该锁交互策略处于弱化状态（window 本身不接收触摸）。

## 5. Session 事件到状态机的映射

来源: `AgentServiceEventHandler`。

1. `TaskStarted` -> `overlay.onTaskStarted`。
2. `ThoughtUpdate` -> `overlay.onThoughtUpdate`。
3. `TurnPhaseChanged` -> `overlay.onTurnPhaseChanged`。
4. `TaskCompleted` -> `overlay.onTaskCompleted`。
5. `SessionCompleted` -> `overlay.onSessionCompleted`。
6. `SessionTakeover/SessionResumed` -> `overlay.onSessionTakeover/onSessionResumed`。
7. `SupplementReceived` -> `overlay.onSupplementReceived`。
8. `AskUser` -> `overlay.onAskUser`。

## 6. Chat 侧完成/补充状态一致性

1. `TaskCompleted` 总会通过 `completionSummary` 产出 completion 文本（空值默认 `Task completed`）。
2. `SupplementReceived` 总会插入 user message。
3. 这两点已与 capsule 终态文案 fallback 保持一致性。
