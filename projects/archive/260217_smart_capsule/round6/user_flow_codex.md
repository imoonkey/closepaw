# Smart Capsule Round6 User Flow Spec (Codex)

Date: 2026-02-14  
Linked state machine: `doc/todo/0.02_smart_capsule/round6/state_machine_codex.md`

## 1. 目标

本文件给出“用户看到什么、点了会怎样”的完整陈列，用于后续直接转测试。

每条 flow 都包含：
- 前置状态（platform/surface/task/panel）
- 组件显示状态（island/capsule/rows/buttons）
- 用户动作
- 预期结果（状态迁移 + UI变化 + side effects）

## 2. 组件词典

- `Compose Capsule`: 主 app 底部胶囊
- `Overlay Capsule`: 系统悬浮胶囊
- `Status Island`: 顶部小岛
- `Row1`: thought
- `Row2`: primary/stop/nav
- `Row3`: 输入框 + action

Row2 nav:
- `⊖` minimize
- `📱` open main app
- `👁` open viewer

## 3. 全局不变量（所有 flow 都必须满足）

1. `Overlay Capsule` 和 `Status Island` 不可同时可见。
2. `surface = MAIN_APP` 时，不可出现任何系统 overlay。
3. A11y 模式下不可出现 `📱`。
4. `WaitingForInput/Action` 提交后必须离开 waiting 态，不允许卡住。
5. `Takeover + Add note` 不应导致 capsule 消失。

## 4. Accessibility Mode Flows

## 4.1 Main App 场景

### A1 Idle -> Start Task
- Pre: `platform=A11y, surface=MAIN_APP, task=Hidden`
- UI:
  - Compose Capsule: only Row3 (`Send`)
  - Overlay Capsule: hidden
  - Status Island: hidden
- Action: 输入任务并 `Send`
- Expect:
  - `task -> Running`
  - Compose Row1/Row2 展开
  - 无 island

### A2 Running -> Takeover
- Pre: `A11y, MAIN_APP, Running`
- UI:
  - Row2 primary=`Takeover`, stop=`Stop`, no nav
- Action: 点击 `Takeover`
- Expect:
  - 立即 `TakeoverPending`（有 pending 反馈）
  - session 确认后 `Takeover`
  - Row2 primary=`Resume`

### A3 Takeover -> Add note
- Pre: `A11y, MAIN_APP, Takeover`
- Action: Row3 输入并 `Add note`
- Expect:
  - `task` 保持 `Takeover`
  - 不自动 resume
  - chat history 新增 user supplement（side effect）

### A4 Running -> Stop
- Pre: `A11y, MAIN_APP, Running`
- Action: `Stop`
- Expect:
  - 最终到 `Hidden`（或 Done 后 auto-hide 到 Hidden，取决于 session 事件）
  - Compose 回到 only Row3

### A5 Ask User (Input)
- Pre: `A11y, MAIN_APP, Running`
- Event: `ask_user(question)`
- Expect:
  - `task -> WaitingForInput`
  - Row3 变为 response input + `Send`
- Action: 提交 response
- Expect:
  - `task -> Running("Processing response...")`
  - 后续 thought 正常流动

## 4.2 Overlay 场景（用户离开主 app）

### A6 App Background While Active
- Pre: `A11y, MAIN_APP, Running/Takeover/...`
- Action: 切到其他 app
- Expect:
  - `surface -> OTHER_APP`
  - Overlay Capsule shown
  - EdgeGlow shown
  - StatusIsland hidden

### A7 Overlay Running Input Lock
- Pre: `A11y, OTHER_APP, Running`
- UI:
  - Row3 显示但不可输入（或只读提示）
- Action: 点击 Row3
- Expect:
  - 不抢焦点，不弹键盘
  - 不影响 agent 执行

### A8 Overlay Takeover Input Allowed
- Pre: `A11y, OTHER_APP, Takeover`
- Action: Row3 输入 + Add note
- Expect:
  - 输入可用
  - task 仍为 Takeover
  - capsule 保持可见

### A9 No Return-to-App Controls
- Pre: `A11y, OTHER_APP, any active`
- UI:
  - Row2 nav: 无 `📱`、无 `👁`、无 `⊖`
- Action: 用户尝试回 app 控件
- Expect:
  - 不存在该入口

## 5. Virtual Display Mode Flows

## 5.1 Main App 场景

### V1 Idle/MainApp
- Pre: `VD, MAIN_APP, Hidden`
- UI:
  - Compose Capsule only
  - Overlay Capsule hidden
  - Status Island hidden

### V2 Running/MainApp (关键)
- Pre: `VD, MAIN_APP, Running`
- Expect:
  - 仍然只显示 Compose Capsule
  - 不显示 Status Island
  - 不显示 Overlay Capsule

### V3 MainApp Nav Constraints
- Pre: `VD, MAIN_APP, any task`
- UI:
  - Row2 nav 不显示 `⊖` / `📱` / `👁`

## 5.2 OTHER_APP + ISLAND 场景

### V4 Enter Background With Active Task
- Pre: `VD, MAIN_APP, active`
- Action: 用户离开主 app
- Expect:
  - `surface -> OTHER_APP`
  - 默认 `panel=ISLAND`
  - `Status Island shown`, `Overlay Capsule hidden`

### V5 Island Tap -> Open Viewer
- Pre: `VD, OTHER_APP, active, panel=ISLAND`
- Action: 点击 island
- Expect:
  - 打开 Viewer (`surface -> VIEWER`)
  - `panel -> CAPSULE`
  - Overlay Capsule shown
  - Status Island hidden

### V6 Island Tap With No Active Task
- Pre: `VD, OTHER_APP, Hidden, panel=ISLAND`
- Action: 点击 island
- Expect:
  - 打开主 app（或 no-op + toast；当前推荐打开主 app）
  - 不应出现“island 消失后什么都没有”

## 5.3 VIEWER + CAPSULE 场景

### V7 Viewer Running Controls
- Pre: `VD, VIEWER, Running, panel=CAPSULE`
- UI:
  - Overlay Capsule shown
  - Row2 nav 显示 `⊖` + `📱`
  - 不显示 `👁`（已在 viewer）

### V8 Minimize In Viewer
- Pre: `VD, VIEWER, active, panel=CAPSULE`
- Action: 点击 `⊖`
- Expect:
  - `panel -> ISLAND`
  - Overlay Capsule hidden
  - Status Island shown

### V9 Re-open Capsule In Viewer (你报的问题1)
- Pre: `VD, VIEWER, active, panel=ISLAND`
- Action: 点击 island
- Expect:
  - `panel -> CAPSULE`
  - Status Island hidden
  - Overlay Capsule shown
- Fail symptom (current bug): island 消失但 capsule 不出现。

### V10 Phone Icon Back To Main App (你报的问题3)
- Pre: `VD, VIEWER, active, panel=CAPSULE`
- Action: 点击 `📱`
- Expect:
  - `surface -> MAIN_APP`
  - Overlay Capsule hidden
  - Status Island hidden
  - 主 app Compose capsule 可见

### V11 Takeover + Add note Must Stay Visible (你报的问题4)
- Pre: `VD, VIEWER, Takeover, panel=CAPSULE`
- Action: 输入并 `Add note`
- Expect:
  - task 仍为 Takeover
  - panel 保持 CAPSULE
  - Overlay Capsule 继续可见
  - 不出现“全消失且卡 pause”

## 5.4 VIEWER + ISLAND 场景（可逆性验证）

### V12 Reversible Toggle
- Pre: `VD, VIEWER, active`
- Flow:
  1. capsule -> click `⊖` -> island
  2. island -> click island -> capsule
- Expect:
  - 可以无限可逆
  - 任意时刻 island/capsule 仅一个可见

## 6. AskUser 专项 Flows

### Q1 WaitingForInput Round Trip
- Pre: `any platform, any non-main overlay/mainapp active`
- Event: `ask_user(question, callId=abc)`
- Expect:
  - `task -> WaitingForInput(callId=abc)`
  - 输入并提交 callId=abc
  - `task -> Running("Processing response...")`

### Q2 WaitingForAction Round Trip
- Pre: `any platform active`
- Event: `ask_user(action, callId=xyz)`
- Expect:
  - `task -> WaitingForAction(callId=xyz)`
  - 点击 `Done`
  - `task -> Running("Processing response...")`

### Q3 CallId Mismatch Guard
- Pre: `WaitingForInput(callId=abc)`
- Action: 提交 `callId=wrong`
- Expect:
  - state 不变
  - UI 不应假装已恢复 Running

## 7. Completion / History Flows

### H1 Complete Task Message In Chat (你报的问题2)
- Pre: task in Running
- Event: `TaskCompleted(result="...")`
- Expect:
  - chat history 最后一条 agent message 包含 completion text
  - banner 显示 completed summary
  - session recording finalize 当前 agent message

### H2 Done Auto-hide
- Pre: `task=Done`
- Event: auto-hide timeout
- Expect:
  - `task -> Hidden`
  - 按 surface/platform 规则隐藏 overlay/island
  - MAIN_APP 只剩 Compose Row3

## 8. 不合理 / 禁止 Flow（明确排除）

1. A11y 下通过 `📱` 或 Row1 回主 app（已禁止）。
2. MAIN_APP 中出现 island。
3. `Status Island` 与 `Overlay Capsule` 同时可见。
4. `WaitingFor*` 提交后继续停在 waiting（除 callId mismatch 情况）。

## 9. Round6 收敛结论

`state_machine_codex.md` 与本 user flow 已对齐：
- 每个平台场景都有明确可见性规则
- 每个任务状态都有明确组件/按钮行为
- 你当前新增 4 个问题都被纳入必测 flow（V9/V10/V11/H1）

下一步（你说的步骤2）可以直接按这些 flow 写测试：
- reducer/state transition tests
- visibility policy tests
- integration flow tests

