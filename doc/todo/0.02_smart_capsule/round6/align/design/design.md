# Round6 Align Design

Date: 2026-02-14 (restarted)
Purpose: 作为 codex/claude 的共同基线。锁定状态机、可见性规则、user flow、side effects 与 bug prevention. 配套文档: `user_flow.md`（完整状态+流程枚举）、`bug_prevention.md`（bug-规则映射）。

## 1. Ground Truth（必须一致）

状态向量采用 4 维，任何渲染都只能从这里派生：

1. `PlatformMode`: `ACCESSIBILITY | VIRTUAL_DISPLAY`
2. `UserLocation`: `MAIN_APP | VD_VIEWER | OTHER_APP`
3. `CapsuleMode`:
   `Hidden | Running | TakeoverPending | Takeover | WaitingForInput | WaitingForAction | Done | Error`
4. `ShowPreference`（仅 VD + active + non-main 有效）: `ISLAND | CAPSULE`

约束：
- `OverlayCapsule` 与 `StatusIsland` 永远互斥。
- `MAIN_APP` 永远不显示系统 overlay。
- A11y 永远不显示 island。

## 2. 事件与迁移（必须一致）

## 2.1 CapsuleMode 迁移

### Universal events（from any mode, no guard）

- `TaskStarted(input) -> Running(input)`
- `Error(message) -> Error(message)`
- `AskUser(QUESTION, callId, q) -> WaitingForInput(callId, q)`
- `AskUser(ACTION, callId, instr) -> WaitingForAction(callId, instr)`

### Guarded events（specific source modes only, wrong mode → silently ignore with debug log）

- `ThoughtUpdate(t)` (`Running` only) `-> Running(t)`
- `TakeoverRequested` (`Running` only) `-> TakeoverPending`
- `TakeoverConfirmed` (`Running|TakeoverPending`) `-> Takeover`
- `Resumed` (`Takeover|TakeoverPending`) `-> Running("Thinking...")`
- `UserResponseSent(callId)`:
  - 仅在 `WaitingForInput|WaitingForAction` 生效
  - 必须 `callId` 匹配当前等待态，否则忽略
  - 匹配时 `-> Running("Processing response...")`
- `TaskCompleted`:
  - `GOAL_ACHIEVED|MAX_TURNS|TASK_IMPOSSIBLE|USER_STOPPED|INTERRUPTED -> Done`
  - `ERROR -> Error`
- `Done` 自动 3s `-> Hidden`
- `DismissError` (`Error` only) `-> Hidden`

Rationale: `TaskStarted`/`Error`/`AskUser` are server-driven events that can arrive in race conditions. Accepting them from any mode prevents lost events. `TakeoverRequested`/`Resumed`/`UserResponseSent` are user-driven and should be guarded.

## 2.2 VD 的 ShowPreference 迁移

- init: `ISLAND`
- `onViewerOpened`: `CAPSULE`
- `onViewerClosed`: `ISLAND`
- `onMinimize(⊖)`: `ISLAND`
- `onIslandTapped`:
  - `location=OTHER_APP`: open viewer，然后 `CAPSULE`
  - `location=VD_VIEWER`: 直接 `CAPSULE`（不能再次依赖 reopen viewer）
- `onAskUser`(VD): 强制 `CAPSULE`
- `onError`(VD): 强制 `CAPSULE`（保证有 close/dismiss 入口）

### 2.3 State-invariant force-CAPSULE（必须一致）

上述 2.2 中 `onAskUser`/`onError` 的 `强制 CAPSULE` 是**事件驱动**的初始触发。但如果后续事件（`onMinimize(⊖)` 或 `onViewerClosed`）将 `showPref` 改回 `ISLAND`，事件驱动的 force 不会重新触发。

为保证 `user_flow.md` B2i/B3i 表中 "Force to CAPSULE" 的状态不变量:

**`applyVisibility()` 必须包含以下 guard：**

```
if platformMode == VD
   && mode in {WaitingForInput, WaitingForAction, Error}
   && showPreference == ISLAND:
     showPreference = CAPSULE   // state-invariant force
```

理由：这三个 mode 需要用户交互（输入框/ [Done] / [Close]），而 Island 没有这些控件。若允许 ISLAND，用户将无法操作。

**影响：**
- `onMinimize(⊖)` 在 WI/WA/Error 期间实际为 no-op（设 ISLAND 后立即被 applyVisibility 覆盖回 CAPSULE）。
- `onViewerClosed` 在 WI/WA/Error 期间不会导致丢失交互 UI。
- 最终拍板：WI/WA/Error 期间**隐藏 `⊖`**，避免展示 no-op 控件导致用户困惑。
- 防御性实现：即便旧 UI/竞态仍触发 `onMinimize`，也必须被 state-invariant guard 覆盖回 CAPSULE。

## 3. 可见性规则（必须一致）

`isActive = hasActiveTask || mode in {Done, Error}`

### A11y
- `MAIN_APP`: Compose only（hidden/full 由 mode 决定）
- `OTHER_APP && isActive`: OverlayCapsule + Glow
- `OTHER_APP && !isActive`: all hidden

### VD
- `MAIN_APP`: Compose only（Overlay/Island hidden）
- `VD_VIEWER | OTHER_APP`:
  - `!isActive`: Overlay/Island hidden
  - **State-invariant force（Section 2.3）：** `mode in {WaitingForInput, WaitingForAction, Error} && ShowPreference==ISLAND` → 先 force `ShowPreference=CAPSULE`
  - `isActive && ShowPreference=CAPSULE`: show OverlayCapsule
  - `isActive && ShowPreference=ISLAND`: show Island

## 4. NavSpec（本轮统一方案）

| Platform | Context | ⊖ | 📱 | 👁 |
|---|---|---|---|---|
| A11y | MAIN_APP | no | no | no |
| A11y | OTHER_APP | no | no | no |
| VD | MAIN_APP | no | no | yes |
| VD | VD_VIEWER | yes | yes | no |
| VD | OTHER_APP + capsule | yes | yes | yes |
| VD | OTHER_APP + island | island only | no | no |

说明：
- 采用 `VD + MAIN_APP 显示 👁`（跟当前实现一致，且避免把“看虚拟屏”入口藏得太深）。
- A11y 全禁用导航按钮。
- `NavSpec` 是“上下文允许性”规则，不等于“该 mode 必定显示按钮”。
- 最终渲染还受 row 可见性约束：
  - 若当前 mode 隐藏 Row2（如 `Done`），则不渲染 Row2-R 按钮。
  - `VD + MAIN_APP + Hidden` 是特例：`👁` 必须可达，可放在 collapsed capsule 或同一组件的固定入口区。
- mode 覆盖规则（最终拍板）：
  - 在 VD overlay 场景，`mode in {WaitingForInput, WaitingForAction, Error}` 时，`⊖` 必须隐藏。
  - 其他可见性按表执行（`📱/👁` 仍按 context 决定）。

## 5. Side Effects 契约（必须一致）

1. `TaskCompleted` 必须让 chat history 出现 completion 文本；`result` 为空时也要写默认文案（如 `"Task completed"`）。
2. `SupplementReceived(text)` 必须写入一条 user message。
3. `SupplementReceived` 的 capsule 闪烁确认在 A11y/VD 都应可见（如果 capsule 可见）。

## 6. 实现锚点（供双方文档引用）

- `UserLocation` 不能靠 `isAppInForeground` 二元判断，必须区分 `MainActivity` 与 `VirtualDisplayViewerActivity`。
- `onIslandTapped` 在 `VD_VIEWER` 不应再次 `onOpenViewer()`，应直接切 `ShowPreference=CAPSULE` 并 `applyVisibility()`。
- `ChatViewModel.handleTaskCompleted` 不能在 `result == null/blank` 时跳过文本追加。

## 7. Resolved Questions

1. **VD + MAIN_APP 下 👁 = yes**: CONFIRMED. 跟当前实现一致，用户需要入口来查看 VD。
2. **UserResponseSent callId 严格 guard**: CONFIRMED. Mismatch 静默忽略（debug log），不可提前离开 waiting。
3. **onError(VD) → force CAPSULE**: CONFIRMED. 必选策略，保证 Error 态有 dismiss 入口。

## 8. Input Focus Policy（必须一致）

Overlay capsule 的 Row3 输入框：不同 mode 下需要不同的 focus 策略，避免输入冲突。

| Mode | A11y Overlay | VD Overlay | Compose (Main App) |
|------|-------------|-----------|-------------------|
| Hidden | N/A | N/A | Row3 enabled |
| Running | **Disabled** (hint: "Take over to type note") | Enabled | Enabled |
| TakeoverPending | **Disabled** | Enabled | Enabled |
| Takeover | **Enabled** (agent paused) | Enabled | Enabled |
| WaitingForInput | **Enabled** + auto-focus + keyboard | Enabled + auto-focus + keyboard | Enabled + auto-focus |
| WaitingForAction | N/A (no Row3) | N/A (no Row3) | N/A (no Row3) |
| Done | N/A (no Row3) | N/A (no Row3) | N/A (no Row3) |
| Error | N/A (no Row3) | N/A (no Row3) | N/A (no Row3) |

Rationale: A11y Running/TakeoverPending 禁用输入是因为 agent 正在控制真实屏幕，overlay 获焦会导致 focus 冲突。VD 模式 agent 在虚拟屏幕操作，不存在冲突。

## 9. Row1 Tap Behavior（必须一致）

| PlatformMode | Row1 Tap Action |
|-------------|-----------------|
| A11y | **null** (disabled). Tap 会改变前台 app，打断 agent 的屏幕控制。 |
| VD | Opens Main App (`onOpenApp()`). Safe：agent 在虚拟屏幕，不受影响。 |

## 10. Naming Convention（映射到现有代码）

Design doc 和代码的命名对照，避免混淆：

| Design doc | Existing code | Codex doc |
|-----------|--------------|-----------|
| `CapsuleMode` | `CapsuleMode` (sealed interface) | `TaskState` |
| `UserLocation` | `isAppInForeground` + `isViewerVisible` (to be refactored) | `UserSurface` |
| `ShowPreference` | `ShowPreference` (enum in ServiceOverlayController) | `PanelMode` |
| `CapsuleContext` | `CapsuleContext` (enum: MAIN_APP, SCREEN_VIEWING, BACKGROUND) | — |

Note: `CapsuleContext` is the NavSpec rendering context, derived from `UserLocation` + `PlatformMode`. Mapping:
- `MAIN_APP → CapsuleContext.MAIN_APP`
- `VD_VIEWER → CapsuleContext.SCREEN_VIEWING`
- `OTHER_APP → CapsuleContext.SCREEN_VIEWING (A11y) / BACKGROUND (VD)`

## 11. VD Task Completion Contract （必须一致）

VD 模式下任务结束后，**不得**把虚拟屏幕上的 app launch 到真实屏幕。

具体规则：
1. `TaskCompleted` handler 唯一 side effects: mode transition (→Done) + chat history 追加 completion text。
2. **NO `startActivity` / `launchApp` intent** from VD to real screen.
3. 虚拟屏幕上的 app 留在虚拟屏幕。用户如需查看，通过 👁 打开 VD Viewer。
4. 真实屏幕上的前台 app 不变（无 navigation side effect）。

Rationale: round5 #5.3 中，task 结束后 VD 上的 YouTube 被 launch 到真实屏幕，打断了用户正在播放的视频。

## 12. Compose Capsule 单组件约束（必须一致）

MAIN_APP 下的 Compose Capsule 在所有 CapsuleMode 下是同一个 widget：

- `Hidden`: 只显示 Row3（input + Send）。这就是 capsule 的 Hidden 态，不是单独的 "input dock"。
- `Running/TakeoverPending/Takeover`: 展开 Row1 + Row2 + Row3（Row3 变为 Add note）。
- `WaitingForInput`: 展开 Row1(question) + Row2(Stop) + Row3(Send response)。
- `WaitingForAction`: Row1(instruction) + Row2(Done + Stop)。无 Row3。
- `Done`: Row1(teal + msg)。Row2/Row3 hidden。3s 后 → Hidden（回到 Row3 only）。
- `Error`: Row1(red + msg) + Row2([Close])。Row3 hidden。

注：
- 上述 Row2 描述的是 Row2-L（主操作区）。
- Row2-R（`⊖/📱/👁`）是否显示由 Section 4 的 NavSpec 决定，并且受“该 mode 是否渲染 Row2”约束。

`VD + MAIN_APP + Hidden` 特例说明：
- 为满足 Section 4 的 `👁` 入口要求，Hidden 态允许在**同一个 Compose Capsule 组件内部**额外暴露 `👁`。
- 这不构成“第二个组件”，也不违反单组件约束；禁止实现成独立 input dock + 独立 capsule 两套实现。

**禁止**实现为两个独立 component（一个 capsule、一个 input dock）。必须是一个 composable，内部根据 mode 控制 Row1/Row2 的展开/折叠。

Transition 动画：Row1+Row2 expand/collapse，无闪烁，无中间空白态。

## 13. Supplement 行为约束（必须一致）

用户通过 Row3 发送 supplement（Add note）时：

1. **CapsuleMode 不变。** 不自动 resume，不离开 Takeover/Running 等。
2. **ShowPreference 不变。**
3. **UserLocation 不变。**
4. **不调用 applyVisibility。**（因为没有任何状态维度变化。）
5. Row3 text 清空，keyboard hidden（A11y overlay）或 stays（VD/Compose）。
6. Overlay capsule flash "Received" confirmation on thought line（A11y 和 VD 都要做）。
7. ChatViewModel: 插入 user message 到 chat history。
8. Session: supplement 排队等待 agent 下一个 turn 消费。

**要点：因为不变任何状态维度，capsule 可见性不可能因 supplement 而变化。** 这是 round6 #4 的根本防护。

## 14. UserLocation 检测实现约束（必须一致）

当前 `isAppInForeground` 只区分 "我们的 package" vs "其他 package"。这把 VD Viewer 和 Main App 混为一谈。

### 检测逻辑

```
handleWindowStateChanged(packageName, className):
  if packageName == ourPackage:
    if className contains "VirtualDisplayViewer":
      location = VD_VIEWER
      isAppInForeground = false   // overlay MUST stay visible
      isViewerVisible = true
    else:
      location = MAIN_APP
      isAppInForeground = true    // overlay hides (compose handles it)
      isViewerVisible = false
  else:
    location = OTHER_APP
    isAppInForeground = false
    isViewerVisible = false
```

### 关键: isAppInForeground 在 VD_VIEWER 时必须为 false

否则 applyVisibility 会隐藏 overlay capsule，导致 VD Viewer 上没有任何 capsule UI。这是 round6 #1, #3, #4 的共同根因。

### onViewerOpened / onViewerClosed 与 handleWindowStateChanged 的关系

- `onViewerOpened()`: VD Viewer Activity 的 `onResume` 回调。设置 `showPref=CAPSULE` 并调用 `applyVisibility()`。
- `onViewerClosed()`: VD Viewer Activity 的 `onPause` 回调。设置 `showPref=ISLAND`。
- `handleWindowStateChanged()`: 系统级回调，可能比 Activity lifecycle 更早或更晚。两者应保持一致。
- 如果有 race condition，以最后到达的为准（最终一致即可，因为 applyVisibility 是幂等的）。

## 15. onIslandTapped 逻辑（必须一致）

```
onIslandTapped():
  // 无活跃任务且非 Done/Error → 打开主 App
  if !hasActiveTask && mode !is Done && mode !is Error:
    onOpenApp()
    return

  // VD 模式逻辑
  if platformMode == VD:
    if isViewerVisible:
      // 已在 VD Viewer → 直接切换偏好
      showPreference = CAPSULE
      applyVisibility()
    else:
      // 在其他 App → 打开 VD Viewer
      // Viewer lifecycle 会调用 onViewerOpened() → CAPSULE
      onOpenViewer() ?: onOpenApp()

  // A11y 模式（理论上不应有 island，但防御性处理）
  // no-op
```

## 16. Prohibited Behaviors（必须一致）

完整列表见 `user_flow.md` Part 4。以下为核心禁止项：

| # | 禁止行为 | 历史 Bug |
|---|---------|---------|
| P1 | Island + Capsule 同时可见 | round5 #2.4,#2.5,#5.1 |
| P2 | MAIN_APP 出现系统 overlay | round5 #1.4,#3.2 |
| P3 | A11y 出现 Island | round5 #2.4 |
| P4 | A11y overlay 出现导航按钮 | round5 #2.3 |
| P5 | A11y Running 时 overlay Row3 获焦 | round5 #2.7 |
| P6 | TaskCompleted 无 chat history msg | round6 #2, round5 #3.4 |
| P7 | Supplement 无 chat history user msg | round5 #3.5 |
| P8 | VD task 结束后 app launch 到真实屏幕 | round5 #5.3 |
| P9 | Takeover+AddNote 导致 capsule 消失 | round6 #4 |
| P10 | VD Viewer 上 island tap 重新启动 viewer | round6 #1 |
| P11 | VD Viewer 上 📱 无效 | round6 #3 |
| P12 | Island 卡在 "Working..." 不更新 | round5 #5.4 |
| P13 | idle input dock 与 capsule Row3 分离 | round5 #1.5,#3.3 |
| P14 | Takeover/Stop 点击后无即时反馈 | round5 #1.3 |
| P15 | VD 背景点 island 却在主屏显示 overlay capsule | round5 #4.2 |
| P16 | callId mismatch 仍退出 WaitingFor* | latent high-risk |
| P17 | Stop 点击后没有 Stopping transient 反馈 | round5 #1.3 |
| P18 | viewer lifecycle/window 乱序导致同位置不同可见性结果 | latent race-risk |

## 17. 待 Codex 确认的新增内容

| Section | 内容概要 | 需确认 |
|---------|---------|--------|
| 11 | VD Task Completion: 禁止 launch app 到真实屏幕 | 是否同意 |
| 12 | Compose Capsule 单组件约束 | 是否同意 |
| 13 | Supplement 零状态变化约束 | 是否同意 |
| 14 | UserLocation 检测：VD_VIEWER 时 isAppInForeground=false | 是否同意 |
| 15 | onIslandTapped 完整逻辑 | 是否同意 |
| 16 | Prohibited Behaviors 列表 | 是否有遗漏 |
| user_flow.md | 完整状态枚举 + 16 个 critical flow scenarios | 是否有遗漏或不一致 |
| bug_prevention.md | 每个历史 bug → 防护规则映射 | 是否覆盖完整 |
| 18 | Control feedback（Takeover/Stop）瞬时反馈契约 | 是否同意 |
| 19 | 并发乱序下可见性幂等收敛 | 是否同意 |
| 20 | 测试门槛（flow + bug matrix） | 是否同意 |

## 18. Control Feedback Contract（必须一致）

为避免 round5 `1.3` 类问题（点击后“无响应感”），定义额外瞬时 UI 反馈契约。

说明：这不是新的主状态机 mode，不进入 `CapsuleMode`；它是渲染层短暂标记（transient UI flag）。

### 18.1 Takeover

- 点击 `[Takeover]` 后，必须**立即**进入 `TakeoverPending`（主状态已覆盖）。
- 用户在一个渲染帧内就能看到 `"Handing over..."` 和 disabled 主按钮。

### 18.2 Stop

- 点击 `[Stop]` 后，必须在当前可见 capsule 上立即出现 pending 反馈：
  - `[Stop]` 文案改为 `"Stopping..."`（或等价文案）；
  - `[Stop]` 置灰/disabled，防止重复点击；
  - 该反馈持续到 `TaskCompleted/SessionEnded/SessionError` 任一终止事件到达。
- Stop 点击本身不改变 `CapsuleMode`，只改变该短暂反馈标记。

### 18.3 Clear 条件

- 终止事件（Done/Error/Hidden）到达时清除 pending 标记。
- 新任务 `TaskStarted` 到达时清除 pending 标记。

## 19. 并发与幂等约束（必须一致）

为防止 viewer lifecycle 与 window 事件乱序导致“同态不同渲染”：

1. `applyVisibility()` 必须是纯派生、幂等函数（同一状态向量，重复调用结果一致）。
2. `onViewerOpened/onViewerClosed` 与 `handleWindowStateChanged` 可乱序到达，但最终 `UserLocation` 与可见性必须收敛一致。
3. 任意时刻只允许一个真实来源写入 `UserLocation` 最终值（推荐：统一经过 `updateContext` 派生）。
4. `showPreference` 只能由第 2.2 节列出的事件改写，禁止其他隐式改写路径。

## 20. Test Gate（必须一致）

在 round6 进入实现前，必须满足以下测试门槛：

1. `user_flow.md` F1-F16 全部有对应 automated test（unit/integration 可组合）。
2. `bug_prevention.md` 每条 bug 至少绑定一个防回归断言。
3. 对 `applyVisibility` 做笛卡尔组合测试：
   - `PlatformMode x UserLocation x CapsuleMode(active/terminal/hidden) x ShowPreference`
   - 断言 island/capsule 互斥、MAIN_APP 无 overlay、A11y 无 island。
   - **断言 force-CAPSULE 状态不变量**：VD + (WaitingForInput|WaitingForAction|Error) + ISLAND → 最终显示 Capsule（不是 Island）。
4. 对 `UserResponseSent(callId)` 增加 mismatch guard 测试，确保 waiting 态不会误退出。
5. 对 Stop feedback 增加“点击后 1 帧内可见反馈”测试。
6. 对 `VD + MAIN_APP + Hidden` 增加 `👁` 可达性测试（且仍为单组件实现）。
7. 对 `mode in {WI, WA, Error}` 增加 `⊖ hidden` 断言（B2c/B3c 均覆盖）。

## 21. State-Flow Consistency Check（必须一致）

为避免“flow 期待了状态机未定义行为”，要求每条 flow 都满足：

1. Flow 中每个事件必须能在 Section 2.1/2.2 找到唯一 transition 定义。
2. Flow 中每个可见性断言必须能在 Section 3 + Section 4 找到派生依据。
3. Flow 中每个 side-effect 断言必须能在 Section 5/11/13/18 找到契约来源。
4. 若 flow 使用了 transient UI（如 `Stopping...`），必须显式标注“非 CapsuleMode 主状态”。
5. 任何新增 flow 必须同步更新 `bug_prevention.md` 的验证条目与 `Section 20` 测试门槛。
6. **Merge gate**: `bug_prevention.md` 中任一 bug 缺少对应回归测试时，禁止合并到 main。CI 或 review 时必须逐条检查覆盖率。
