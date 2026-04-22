# Round 4 Code Review (Codex #2)

## Scope
- Diff scope: `339448dd127c6de7a6612f918be8a7d9351ff7b1..HEAD`
- Only reviewed: `app/` changes
- Focus: state machine, transition edges, multi-component rendering consistency, KISS simplification

## Summary
当前实现已经接近可用，但核心问题不是“某个按钮坏了”，而是 **状态语义和窗口可见性混在一起**，再叠加 **乐观状态跳转**（先改 UI 状态，再等 session 反馈），导致你提到的现象会系统性反复出现。

## High
1. `VD + 主 App` 下 `👁` 按钮是 no-op，链路断开  
   - `ChatScreen` 里 `SmartCapsuleCompose.onNavigate` 被写成空实现，`NavSpec` 又允许 VD+MAIN_APP 显示 watch 图标，结果“能点但不做事”。  
   - 证据: `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatScreen.kt:120`, `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatScreen.kt:134`, `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/model/CapsuleRenderSpec.kt:162`

2. Status Island 可进入“点一下全没了”的空白态  
   - `onIslandTapped()` 先 hide island，再 show capsule；但当前 mode 若是 `Hidden`，`SmartCapsuleManager` 收到后会立即 `hide()`，最终岛和胶囊都消失。  
   - 证据: `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:115`, `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:121`, `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/SmartCapsuleManager.kt:213`  
   - 这个路径在 `showCapsule()`/`onViewerClosed()` 无 active task 保护时很容易触发。  
   - 证据: `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:152`, `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:135`

3. 状态机和 session ACK 解耦，出现乐观迁移导致“按钮看起来点了但没生效”  
   - takeover: 点击后先本地 `onTakeoverRequested()`，再提交 `Op.Takeover`；如果 session 拒绝/时序错位，UI 会卡在 `TakeoverPending`。  
   - user response: 点击发送时先 `onUserResponseSent()` 切到 Running，再 deliver；但 deliver 可能失败（callId mismatch / 已超时），UI 已经离开等待态。  
   - 证据: `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:57`, `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:63`, `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt:146`, `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:336`, `app/src/main/kotlin/com/moonkey/androidagent/session/UserResponseChannel.kt:53`

4. `complete_task` 的完成信息在状态链路里被丢失，导致“完成后只有 Completed”  
   - `TurnOutcome.Complete(message)` 有 summary，但 `Agent` 转成 `AgentStopReason.GoalAchieved` 时丢掉 message。  
   - `AgentSession.TaskCompleted.result` 只有 Error 分支才填，成功完成是 null。  
   - 结果是主界面/胶囊只能显示泛化 “Completed/Task complete”。  
   - 证据: `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt:629`, `app/src/main/kotlin/com/moonkey/androidagent/agent/Agent.kt:101`, `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentRuntimeTypes.kt:7`, `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:257`

5. `TaskCompleted` 立刻触发 `Op.Shutdown`，把“任务完成”和“会话结束”硬绑定  
   - `MainActivity` 在 task 完成即 shutdown，会额外产生 `SessionCompleted(USER_STOPPED/INTERRUPTED)`，和真实完成语义交叉，影响 overlay/island 收尾逻辑。  
   - 这会放大“完成后 UI 不一致”问题。  
   - 证据: `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:117`, `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:123`, `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:273`

## Medium
1. `Hidden` 语义在 Overlay 与 Main App 中冲突，导致死代码和行为分裂  
   - `CapsuleRenderSpec.Hidden` 定义了 Row3 输入。  
   - 但 Overlay manager 遇到 Hidden 会直接 `hide()`，因此 Overlay 的 Hidden-Row3 永远不可达；`onSend` 分支基本死路径。  
   - 证据: `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/model/CapsuleRenderSpec.kt:131`, `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/SmartCapsuleManager.kt:213`, `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/SmartCapsuleManager.kt:275`

2. `CapsuleContext` 被当作“当前在哪个屏幕”的真相源，但切换点不一致  
   - `onIslandTapped()` 直接设置 `SCREEN_VIEWING`，即使 viewer 还没打开；`NavSpec` 因此可能提前隐藏 watch。  
   - 证据: `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:116`, `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/model/CapsuleRenderSpec.kt:162`

3. `CapsuleStateHolder` 缺少 taskId/callId 级别的强一致约束  
   - `onTaskStarted(taskId, ...)` 不存 taskId；`onUserResponseSent(callId)` 不校验 callId。  
   - 这会让旧事件/错事件污染当前 UI 模式。  
   - 证据: `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt:84`, `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt:146`

4. 测试主要覆盖单类状态流，缺少跨组件时序测试  
   - 目前测试集中在 `CapsuleStateHolder` 本身，没覆盖 `ServiceOverlayController + SmartCapsuleManager + Session` 的真实异步链路。  
   - 证据: `app/src/test/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolderTest.kt:17`

## Root Cause Pattern
- 你现在的系统有两个“半状态机”并行：
  - `CapsuleMode`（视觉 + 部分业务语义）
  - Window 可见性策略（capsule/island/glow）
- 再加上若干“先改 UI 再等 session”的乐观事件，形成了跨组件竞态。

这就是为什么会出现你说的“一堆 case 各种怪异”，而不是单点 bug。

## KISS Refactor Direction
1. 拆成两个状态层（最关键）
   - `TaskUiState`: Running / Paused / WaitingInput(callId) / WaitingAction(callId) / Completed(summary) / Error
   - `OverlayVisibilityState`: None / Island / Capsule
   - 不要再让 `Hidden` 同时表示“无任务”和“窗口不可见”。

2. 所有 UI 状态迁移改为“基于 session 事件确认”
   - 点击只发 Intent (`Op.Takeover`, `Op.UserResponse`)，不直接改 `CapsuleStateHolder`。
   - 仅在 `SessionTakeover`, `AskUser`, `TaskCompleted` 等事件到达时迁移模式。

3. 引入最小身份约束
   - `TaskUiState` 持有 `taskId`。
   - Waiting 状态持有 `callId`，发送响应必须匹配 callId 后才迁移。

4. 修正 completion 数据通路
   - `AgentStopReason.GoalAchieved` 携带 summary，直通 `TaskCompleted.result`。
   - UI 和 history 都用同一份 completion payload，避免 “Completed” 泛化文案。

5. 解耦 task complete 与 session shutdown
   - 默认 `TaskCompleted -> Session Idle`，由用户或策略决定是否 shutdown。
   - shutdown 只做资源回收，不重新定义 task 语义。

## Minimum Change Set (优先顺序)
1. 先修断路: `ChatScreen.onNavigate` 接到 `OPEN_VIEWER` 时打开 viewer（主症状立即下降）。
2. 去掉乐观迁移: 删除 `onTakeoverRequested()`/`onUserResponseSent()` 的点击即迁移，只保留 ACK 驱动。
3. island tap 加 guard: 无 active task 时不 hide island；或退回 MAIN_APP 引导。
4. completion payload 打通: `TurnOutcome.Complete.message -> TaskCompleted.result`。
5. 把 `MainActivity` 的 task-complete auto-shutdown 改成可配置/延后策略。

## Recommendation
`CHANGES_REQUESTED`  
先做上面 1-4 的收敛，再继续加 case；否则每新增交互都会继续放大状态竞态。

