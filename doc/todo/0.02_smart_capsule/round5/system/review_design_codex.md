# Smart Capsule Round5 — Review + Ground Truth Design (Codex)

Date: 2026-02-14  
Scope: `339448dd127c6de7a6612f918be8a7d9351ff7b1..HEAD` (`app/` only) + `round5/qi_bug_note.md`

## 1. Review Findings (Severity Ordered)

## Critical

1. `WaitingForInput/WaitingForAction` 缺少可达的“响应已接收”转移，状态机会卡住或靠偶然事件跳转。
- Evidence:
  - `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt:157` 定义了 `onUserResponseSent()`。
  - 但调用链中没有触发它：`app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:61` 只提交 `onUserResponse`，不改状态；`app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:341` 仅 deliver，不发 UI ack event。
- Impact:
  - 对应你的等待态异常、状态与实际执行不同步（`qi_bug_note` 2.x / 5.4 风险）。
- Root cause:
  - UI 状态机依赖的 ack event 缺失。

2. Status Island 的显示策略错误：它直接从 `mode != Hidden` 推导“必须显示”，忽略 context 和用户切换意图，导致 island/capsule 同时出现或被下一 turn 复活。
- Evidence:
  - `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/StatusIslandManager.kt:86-99`：collect `mode` 后在非 Hidden 时总是 `show()`。
  - `ServiceOverlayController.hideIsland()` 只是临时隐藏，下一次 mode 变化会被观察器再次拉起。
- Impact:
  - 对应 `qi_bug_note` 2.4/2.5/5.1/5.4（“只能一个可见”被破坏）。
- Root cause:
  - “窗口可见性”与“任务状态”耦合错误。

3. VD 下 context 模型不完整：无法表示 `MAIN_APP`，导致在主 App 前台仍按 BACKGROUND 策略处理。
- Evidence:
  - `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:170-178`：VD 分支忽略 window state。
  - `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:415-416`：VD context 仅 `SCREEN_VIEWING/BACKGROUND`。
  - `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:187-189` + `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt:423`：VD 任务启动直接 show island。
- Impact:
  - 对应 `qi_bug_note` 1.4/3.2（主 App 里不该有 island）。
- Root cause:
  - context 维度不完整（少了真实前台 surface）。

4. VD 完成后执行 handoff relaunch（把虚拟屏幕 app 拉到主屏）是强副作用，破坏你当前产品预期。
- Evidence:
  - `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt:316-321` 触发 `performHandoff()`。
  - `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt:525-542` 真实启动目标 app。
- Impact:
  - 对应 `qi_bug_note` 5.3（播放被打断、双开异常）。
- Root cause:
  - completion policy 与 display strategy 混杂。

## High

1. NavSpec 与行为链路不一致：在 MAIN_APP 暴露了不该出现/未完整接线的按钮。
- Evidence:
  - `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/model/CapsuleRenderSpec.kt:159`：`showMinimize` 与 context 无关。
  - `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatScreen.kt:133-137`：Compose 只处理 `OPEN_VIEWER`，`MINIMIZE/OPEN_APP` 在主 App 为 no-op。
- Impact:
  - 对应 `qi_bug_note` 3.2.1 / 5.2（按钮可见但无效）。

2. 运行态 overlay 允许输入并抢焦点，容易和 agent 操作冲突。
- Evidence:
  - `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/model/CapsuleRenderSpec.kt:49-58`：Running 显示 Row3 可输入。
  - `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/SmartCapsuleManager.kt:288-292`：任何 focus 都将 overlay 设为 focusable 并拉起键盘。
- Impact:
  - 对应 `qi_bug_note` 2.6/2.7（焦点冲突、键盘覆盖）。

3. 用户动作 pending 缺失（Stop/Takeover 点击后没有过渡反馈），体验上“点了像没点”。
- Evidence:
  - UI 只在 session event 到达后更新（例如 `SessionTakeover`），但 takeover ack 发出较晚：`app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:296-301`。
- Impact:
  - 对应 `qi_bug_note` 1.3/2.1/3.1。

4. chat/history 与 overlay state 同步链不完整（supplement/complete summary）。
- Evidence:
  - `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:321-337`：supplement 写入 `HistoryManager`，但未写 `SessionRecordingService`。
  - `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModel.kt:103-115`：忽略 `SupplementReceived`。
  - `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModel.kt:267-276`：TaskCompleted 仅标记完成，不把 `event.result` 合并进 agent message。
- Impact:
  - 对应 `qi_bug_note` 3.4/3.5。

## Medium

1. `CapsuleMode.Hidden` 在 Compose 与 overlay 语义混用，导致分支复杂和死路径。
- Evidence:
  - `CapsuleRenderSpec.Hidden` 有 Row3，但 overlay manager 遇 Hidden 直接 hide：`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/SmartCapsuleManager.kt:212`。
- Impact:
  - 对应你观察到的 idle/结束阶段 UI 不稳定感（3.3/1.5 的根之一）。

---

## 2. Ground Truth State Machine (重新定义)

目标：把“任务状态”“用户所在 surface”“窗口可见性偏好”拆开，避免一个 enum 承担全部语义。

### 2.1 State Model (Single Source of Truth)

```kotlin
// 1) Agent/task truth: only changed by session events (authoritative)
sealed interface TaskUiState {
  data object Idle : TaskUiState
  data class Running(val thought: String, val phase: TurnPhase?) : TaskUiState
  data class TakeoverPending(val thought: String) : TaskUiState
  data class Takeover(val thought: String) : TaskUiState
  data class WaitingForInput(val callId: String, val question: String) : TaskUiState
  data class WaitingForAction(val callId: String, val instruction: String) : TaskUiState
  data class Completed(val message: String, val atMs: Long) : TaskUiState
  data class Failed(val message: String) : TaskUiState
}

// 2) Where user currently is on real display
enum class UserSurface {
  MAIN_APP,      // AndroidAgent app foreground
  VIEWER,        // VirtualDisplayViewerActivity foreground
  OTHER_APP      // Any other foreground app/home
}

// 3) User preference for compact/expanded control in VD background
enum class PanelMode {
  CAPSULE,
  ISLAND
}

// 4) Ephemeral pending feedback for button UX (does not mutate task truth)
sealed interface PendingCommand {
  data object Stop : PendingCommand
  data object Takeover : PendingCommand
  data object Resume : PendingCommand
  data class UserResponse(val callId: String) : PendingCommand
}

// 5) Unified UI state
// platform + surface + task + panel + pending 共同决定所有渲染与可见性
```

### 2.2 Event Principles

1. 任务状态只由 session 事件驱动（authoritative），不由点击直接驱动。
2. 点击只设置 `pendingCommand` + 提交 `Op`。
3. ack 事件清除 pending 并推进状态（避免 optimistic desync）。

建议补充 event：
- `AgentEvent.SessionTakeoverPending`（Op.Takeover accepted, waiting current turn finish）
- `AgentEvent.UserResponseAccepted(callId)` / `AgentEvent.UserResponseRejected(callId, reason)`

### 2.3 Transition Rules (Ground Truth)

| Current TaskUiState | Event | Guard | Next TaskUiState |
|---|---|---|---|
| Idle | TaskStarted(input) | - | Running(input, null) |
| Running | ThoughtUpdate(t) | - | Running(t, phase) |
| Running | SessionTakeoverPending | - | TakeoverPending(lastThought) |
| TakeoverPending | SessionTakeover | - | Takeover(lastThought) |
| Running/TakeoverPending/Takeover | AskUser(QUESTION, callId) | - | WaitingForInput(callId, question) |
| Running/TakeoverPending/Takeover | AskUser(ACTION, callId) | - | WaitingForAction(callId, instruction) |
| WaitingForInput | UserResponseAccepted(callId) | callId match | Running("Processing response...", null) |
| WaitingForAction | UserResponseAccepted(callId) | callId match | Running("Processing response...", null) |
| Takeover | SessionResumed | - | Running("Thinking...", null) |
| Any active | TaskCompleted(success, msg) | - | Completed(msg) |
| Any active | TaskCompleted(error, msg) | - | Failed(msg) |
| Completed | AutoHideTimeout | in MAIN_APP: immediate; overlay: 1.5~3s | Idle |
| Failed | DismissError | - | Idle |
| Any | SessionError(msg) | - | Failed(msg) |

Invalid transitions: no-op + log，不抛异常。

### 2.4 Rendering + Visibility Invariants

Invariant A: 同一时刻 `StatusIsland` 与 `OverlayCapsule` 不能同时可见。  
Invariant B: 在 `MAIN_APP`，系统 overlay（capsule/island）都不可见，只用 Compose capsule。  
Invariant C: 在 Accessibility 平台，永不显示 island。  
Invariant D: 在 VD + `OTHER_APP` 且 task active，显示 `ISLAND xor CAPSULE`，由 `PanelMode` 决定。  
Invariant E: `WaitingForInput/WaitingForAction` 必须通过 callId ack 离开，不允许“靠其他随机事件跳转”。

### 2.5 Input/Focus Policy (解决 2.6/2.7)

Accessibility overlay：
- `Running/TakeoverPending`：Row3 只读或禁用（提示 “Take over to type note”）。
- `Takeover/WaitingForInput`：允许输入并可拉起键盘。
- `WaitingForAction`：无输入框。

这样可避免与 agent 同时争焦点；也符合你希望“先 takeover 再输入”。

### 2.6 Nav Policy (ground truth)

- `showMinimize`: 仅 `platform==VD && surface==OTHER_APP && panel==CAPSULE`。
- `showWatch`: 仅 `platform==VD && surface==OTHER_APP`。
- `showApp`: VD 可选；Accessibility overlay 建议隐藏（避免干扰 agent on-screen workflow）。
- island tap in VD + OTHER_APP：按你的偏好直接 `openViewer()`（不是展开 capsule）。

---

## 3. Refactor Proposal (KISS / Occam)

## Phase 1 (Must) — 先把系统变成“可判定”

1. 引入单一 reducer state（`TaskUiState + UserSurface + PanelMode + PendingCommand`）。
2. `ServiceOverlayController` 从“事件分支里到处 show/hide”改为：
   - 更新 state
   - 调用一个纯函数 `derivePresentation(state)`
   - 最后统一 `applyPresentation()`
3. StatusIslandManager 不再按 `mode != Hidden` 自作主张 show；它只接收 Presentation 指令。
4. VD 加入 `MAIN_APP` surface 跟踪（不要在 VD 分支忽略 `TYPE_WINDOW_STATE_CHANGED`）。

## Phase 2 (Must) — ack 链补全

1. 在 `AgentEvent` 增加 takeover/user_response ack 事件。
2. `AgentSession.handleTakeover()`：
   - pause accepted 后立即 emit `SessionTakeoverPending`（或在 `pause()` 成功发起后发）
   - pause confirmed 后 emit `SessionTakeover`
3. `AgentSession.handleUserResponse()`：
   - deliver=true emit `UserResponseAccepted(callId)`
   - deliver=false emit `UserResponseRejected(callId, reason)`
4. `CapsuleStateHolder` 仅响应这些 ack 事件推进等待态。

## Phase 3 (Must) — product semantics 对齐你的 bug note

1. 删除 VD completion handoff（`performHandoff()` no-op）。
2. `NavSpec` 改成 context-aware，移除 MAIN_APP 的 minimize。 
3. `Status Island <-> Capsule` 互斥由 `PanelMode` 管理，跨 turn 不回弹。
4. overlay 输入策略按 2.5 执行。

## Phase 4 (High) — history/chat consistency

1. supplement 同时写 `SessionRecordingService` 与 ChatViewModel（显示成 user message）。
2. `TaskCompleted.result` 进入最后 agent message block（不是只放 banner）。

---

## 4. Bug Note Coverage Map

| qi_bug_note | Root Cause | Proposal Fix |
|---|---|---|
| 1.3 / 2.1 / 3.1 | 无 pending command UX | `PendingCommand` + ack clear |
| 1.4 / 3.2 / 3.2.1 | VD 无 MAIN_APP surface + nav policy错误 | Phase1 surface tracking + Phase3 nav fix |
| 1.5 / 3.3 | Hidden 语义混用 + 完成态渲染策略不清 | Ground truth split + MAIN_APP completion直接回Idle |
| 2.3 | Accessibility overlay 暴露不该有的导航 | Nav policy: hide showApp on A11y |
| 2.4 / 2.5 / 5.1 | island observer“自动复活” | Presentation-driven visibility + `PanelMode` |
| 2.6 / 2.7 | Running 态可输入 + 焦点冲突 | Input policy gating + takeover-before-input |
| 3.4 / 3.5 | recording/chat event链路不完整 | Phase4 history/chat consistency |
| 4.2 | island tap行为不符预期 | VD island tap -> openViewer |
| 5.2 | nav action/visibility contract broken | Phase3 nav contract clean-up |
| 5.3 | completion handoff副作用 | remove `performHandoff()` |
| 5.4 | visibility+waiting态ack缺失叠加 | Phase1+2（state + ack） |

---

## 5. Verification Plan (for implementation stage)

1. Unit tests:
- `CapsuleStateReducerTest`: 全状态 × 全事件转移矩阵。
- `PresentationPolicyTest`: `(platform, surface, task, panel)` -> `(showCapsule, showIsland, showGlow)`。
- `AckEventFlowTest`: user_response/takeover ack 成功与失败路径。

2. Integration tests:
- `ServiceOverlayController` + fake managers：验证 island/capsule 永不同时可见。
- VD MainApp foreground 场景：任务运行时 island 不出现。

3. Manual regression:
- 直接复跑 `round5/user_flow_test_codex.md` 的 P0/P1。

---

## 6. Simplification Checklist (KISS)

- 删除 “每个事件里手动 show/hide overlay” 的分散逻辑，改一次性 `derive + apply`。
- 删除 `mode != Hidden => island.show()` 这类隐式策略。
- 把 `CapsuleContext` 触发器分散更新收拢到单一 reducer。
- 把“用户点击后的过渡反馈”从 `CapsuleMode` 主状态机剥离到 `PendingCommand`。

