# Smart Capsule Round6 State Machine (Codex)

Date: 2026-02-14  
Scope: Smart Capsule / Status Island / Main App Compose capsule 的统一状态定义（用于后续测试与实现）

## 1. Convergence 目标

本文件的目标是把以下三件事完全解耦并可判定：

1. Agent 任务状态（task truth）
2. 用户当前所处屏幕/场景（surface truth）
3. UI 面板显示偏好（panel truth）

并给出唯一的派生规则：
- 哪些组件可见
- 哪些按钮可点
- 点击后状态如何变化

## 2. 决策（本轮拍板）

1. A11y 模式下，不提供“回主 app”能力：
- 不显示 `📱`
- Row1 不可作为回 app 入口

2. VD 模式下，`Smart Capsule` 与 `Status Island` 永远互斥。

3. VD 模式下，点 island：
- 如果在主屏其他 app（`OTHER_APP`） -> 打开 VD Viewer
- 如果已在 Viewer -> 直接展开 capsule

4. `Takeover + Add note` 不应改变可见性，只是追加补充消息并保持 paused。

5. `WaitingForInput/WaitingForAction` 必须有确定退出路径（响应提交后转回 Running/Processing）。

## 3. 状态向量（Ground Truth）

## 3.1 主状态

```kotlin
enum class PlatformMode { ACCESSIBILITY, VIRTUAL_DISPLAY }

enum class UserSurface {
  MAIN_APP,      // AndroidAgent 主 app 前台
  VIEWER,        // VD Viewer 前台
  OTHER_APP      // 其他 app/桌面前台
}

enum class PanelMode {
  CAPSULE,
  ISLAND
}

sealed interface TaskState {
  data object Hidden : TaskState              // 无活跃任务
  data class Running(val thought: String) : TaskState
  data class TakeoverPending(val thought: String) : TaskState
  data class Takeover(val thought: String) : TaskState
  data class WaitingForInput(val callId: String, val question: String) : TaskState
  data class WaitingForAction(val callId: String, val instruction: String) : TaskState
  data class Done(val message: String) : TaskState
  data class Error(val message: String) : TaskState
}

data class UiState(
  val platform: PlatformMode,
  val surface: UserSurface,
  val task: TaskState,
  val panel: PanelMode,              // 仅在 VD + active + non-main-app 生效
)
```

## 3.2 派生量

```kotlin
val isActiveTask = task is Running
  || task is TakeoverPending
  || task is Takeover
  || task is WaitingForInput
  || task is WaitingForAction

val isTerminal = task is Done || task is Error
```

## 4. 可见性派生函数（唯一来源）

`deriveVisibility(uiState)` 产出：
- `showComposeCapsule`
- `showOverlayCapsule`
- `showStatusIsland`
- `showEdgeGlow`

## 4.1 规则

1. `surface == MAIN_APP`:
- `showComposeCapsule = true`
- `showOverlayCapsule = false`
- `showStatusIsland = false`
- `showEdgeGlow = false`

2. `platform == ACCESSIBILITY && surface == OTHER_APP`:
- active/terminal 时：`showOverlayCapsule = true`, `showEdgeGlow = true`
- Hidden 时：全 false
- island 永远 false

3. `platform == VIRTUAL_DISPLAY && surface != MAIN_APP`:
- Hidden: overlay/island/glow 全 false
- active/terminal:
  - `panel == CAPSULE` -> capsule true, island false
  - `panel == ISLAND` -> capsule false, island true
- glow 在 VD 下固定 false（不在真实屏幕画边缘光）

## 4.2 不允许状态组合（Invalid Combos）

1. `platform == ACCESSIBILITY && showStatusIsland == true`
2. `showOverlayCapsule == true && showStatusIsland == true`
3. `surface == MAIN_APP && (showOverlayCapsule || showStatusIsland || showEdgeGlow)`

出现时必须视为实现 bug。

## 5. 任务状态迁移（TaskState Machine）

## 5.1 事件定义

- `EV_TASK_STARTED(input)`
- `EV_THOUGHT_UPDATE(text)`
- `EV_TAKEOVER_CLICKED`
- `EV_SESSION_TAKEOVER_CONFIRMED`
- `EV_RESUME_CLICKED`
- `EV_SESSION_RESUMED`
- `EV_ASK_USER_INPUT(callId, question)`
- `EV_ASK_USER_ACTION(callId, instruction)`
- `EV_USER_RESPONSE_SUBMITTED(callId)`
- `EV_TASK_COMPLETED(reason, message)`
- `EV_ERROR(message)`
- `EV_DISMISS_ERROR`
- `EV_DONE_AUTO_HIDE_TIMEOUT`

## 5.2 转移表

| Current | Event | Guard | Next |
|---|---|---|---|
| Hidden | TASK_STARTED | - | Running |
| Running | THOUGHT_UPDATE | - | Running |
| Running | TAKEOVER_CLICKED | - | TakeoverPending |
| TakeoverPending | SESSION_TAKEOVER_CONFIRMED | - | Takeover |
| Takeover | RESUME_CLICKED or SESSION_RESUMED | - | Running("Thinking...") |
| Running/Takeover/TakeoverPending | ASK_USER_INPUT | - | WaitingForInput |
| Running/Takeover/TakeoverPending | ASK_USER_ACTION | - | WaitingForAction |
| WaitingForInput | USER_RESPONSE_SUBMITTED(callId) | callId match | Running("Processing response...") |
| WaitingForAction | USER_RESPONSE_SUBMITTED(callId) | callId match | Running("Processing response...") |
| Any active/waiting/takeover | TASK_COMPLETED success | - | Done |
| Any active/waiting/takeover | TASK_COMPLETED error | - | Error |
| Any | ERROR | - | Error |
| Error | DISMISS_ERROR | - | Hidden |
| Done | DONE_AUTO_HIDE_TIMEOUT | - | Hidden |

## 5.3 Guard 要点

1. `USER_RESPONSE_SUBMITTED` 必须校验 `callId`，不匹配时状态不变。
2. `THOUGHT_UPDATE` 对非 Running 可忽略，但不能导致卡死等待态。
3. `TAKEOVER_CLICKED` 在非 Running 下忽略。

## 6. Surface/Panel 迁移

## 6.1 Surface 迁移

| Event | Next Surface |
|---|---|
| app foreground = AndroidAgent | MAIN_APP |
| open VD viewer | VIEWER |
| leave app/viewer to home/other app | OTHER_APP |

## 6.2 Panel 迁移（仅 VD + 非 MAIN_APP）

| Event | Guard | Next Panel |
|---|---|---|
| click minimize `⊖` | capsule visible | ISLAND |
| click island | surface == VIEWER | CAPSULE |
| click island | surface == OTHER_APP | CAPSULE + open viewer |
| viewer opened | - | CAPSULE |
| viewer closed | - | ISLAND |

## 7. 组件渲染规范

## 7.1 Smart Capsule 行为（Row 1/2/3）

| TaskState | Row1 thought | Row2 primary | Row2 stop | Row3 input |
|---|---|---|---|---|
| Running | thought | Takeover | Stop | Add note |
| TakeoverPending | Handing over... | disabled Handing over | Stop | Add note (A11y 禁输入) |
| Takeover | paused thought | Resume | Stop | Add note |
| WaitingForInput | Awaiting response + question | none | Stop | Send response |
| WaitingForAction | Action needed + instruction | Done | Stop | hidden |
| Done | success message | none | none | hidden |
| Error | error message | none | Close | hidden |
| Hidden | no row1/row2 in compose | n/a | n/a | main input |

## 7.2 导航按钮显示

| Platform | Surface | `⊖` | `📱` | `👁` |
|---|---|---|---|---|
| A11y | MAIN_APP | no | no | no |
| A11y | OTHER_APP | no | no | no |
| VD | MAIN_APP | no | no | no |
| VD | VIEWER | yes | yes | no |
| VD | OTHER_APP + capsule | yes | yes | yes |
| VD | OTHER_APP + island | island only | (in island no nav) | (in island no nav) |

说明：
- A11y 中明确禁止 `📱`
- Viewer 中 `📱` 必须可用（回主 app）

## 8. 历史消息契约（Chat/Recording）

这部分不是 UI 状态，但必须作为状态机 side-effect 契约：

1. 收到 `TaskCompleted(result != null)`：
- chat history 必须追加/展示 completion 文本
- session recording 必须把该 turn 的 agent message finalize

2. 收到 `SupplementReceived(text)`：
- chat history 必须出现一条 user message（补充内容）

否则视为 flow 失败。

## 9. 对你当前 4 个新问题的定位

1. `VD viewer: capsule -> island -> island tap 不恢复 capsule`  
- 属于 `PanelMode` 迁移或 `applyVisibility` 应用缺失（`ISLAND -> CAPSULE` 未生效）。

2. `complete_task message 不进 chat history`  
- 属于 side-effect 契约违背（第 8 节）。

3. `VD 点 📱 无效`  
- 属于 nav action 路由/surface 迁移违背（第 7.2 与 6.1）。

4. `VD takeover + add note 后 capsule 消失且卡 pause`  
- 属于 `Takeover` 状态下的 visibility 不应变化原则被破坏（第 4 节 + 5.2）。

## 10. 测试前置结论

本状态机定义可以覆盖你当前需求，且可直接转成测试：
- 状态断言测试（TaskState 转移）
- 可见性断言测试（deriveVisibility）
- 用户流测试（surface/panel/action）
- side-effect 测试（chat/recording）

