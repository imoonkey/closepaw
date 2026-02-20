# Smart Capsule User Flow SOTA (Codex)

更新时间: 2026-02-20
范围: 以当前代码为准，不以历史 design doc 为准。

## 1. 当前生效前提

1. Overlay capsule window 当前包含 `FLAG_NOT_TOUCHABLE`（`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/CapsuleOverlayHost.kt:272`-`273`）。
2. 这意味着 overlay capsule 目前是“可见但不可直接触摸交互”的临时形态；Island 仍可点击（`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/IslandOverlayHost.kt:104`）。
3. Main App 内的 Compose capsule 不受该 flag 影响，可正常交互。

## 2. User Flow 的状态维度（代码真实维度）

1. `PlatformMode`: `ACCESSIBILITY | VIRTUAL_DISPLAY`（`app/src/main/kotlin/com/moonkey/androidagent/protocol/SessionConfig.kt:62`）。
2. `UserLocation`: `MAIN_APP | VD_VIEWER | OTHER_APP`（`app/src/main/kotlin/com/moonkey/androidagent/app/OverlayLocationPolicy.kt:8`）。
3. `CapsuleMode`: `Hidden/Running/TakeoverPending/Takeover/WaitingForInput/WaitingForAction/Done/Error`（`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/model/CapsuleMode.kt:10`）。
4. `ShowPreference`: `CAPSULE | ISLAND`（`app/src/main/kotlin/com/moonkey/androidagent/app/OverlayLocationPolicy.kt:14`），Controller 初始值是 `ISLAND`（`app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:103`）。

## 3. 可见性流转（由 `deriveOverlayVisibility` 决定）

来源: `app/src/main/kotlin/com/moonkey/androidagent/app/OverlayLocationPolicy.kt:79`。

1. `isActive = hasActiveTask || mode is Done || mode is Error`。
2. `MAIN_APP` 一律隐藏系统 overlay（A11y/VD 都一样）。
3. 非 MAIN_APP 且 active 时:
   - `WaitingForInput/WaitingForAction/Error` 会强制 `normalizedShowPreference=CAPSULE`。
   - 否则按 `showPreference` 显示 capsule 或 island。
4. A11y:
   - `OTHER_APP + active` 可显示 capsule 或 island（二选一）。
   - glow 在 `OTHER_APP + active` 显示。
5. VD:
   - `VD_VIEWER/OTHER_APP + active` 显示 capsule 或 island（二选一）。
   - glow 仅在 `VD_VIEWER + hasActiveTask` 显示。

## 4. 各 Mode 的 UI 行为（渲染层）

来源: `CapsuleRenderSpec` + `SmartCapsuleSurface`。

1. `Hidden`: 只显示 Row3（输入 + send），VD Main App 下额外可显示 👁 入口（`showOpenViewer`，`app/src/main/kotlin/com/moonkey/androidagent/ui/capsule/surface/SmartCapsuleSurface.kt:140`）。
2. `Running`: Row1 thought + Row2 `[Takeover][Stop]` + Row3 `Add note`。
3. `TakeoverPending`: Row1 `Handing over...` + Row2 `[Handing over(disabled)][Stop]` + Row3 `Add note`。
4. `Takeover`: Row1 paused-thought + Row2 `[Resume][Stop]` + Row3 `Add note`。
5. `WaitingForInput`: Row1 `Awaiting response` + question body + Row2 `[Stop]` + Row3 `Send`（支持自动 focus）。
6. `WaitingForAction`: Row1 `Action needed` + instruction + Row2 `[Done][Stop]`，无 Row3。
7. `Done`: Row1 done message，Row2/Row3 隐藏；3 秒自动回 `Hidden`（`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt:39`, `269`-`274`）。
8. `Error`: Row1 error + Row2 `[Close]`，无 Row3。

输入可用性（overlay）:
1. A11y + `Running/TakeoverPending`: Row3 disabled，提示 `Take over to type note`（`app/src/main/kotlin/com/moonkey/androidagent/ui/capsule/surface/SmartCapsuleSurface.kt:80`-`84`, `129`）。
2. 其他情况 Row3 enabled（含 VD overlay 与 A11y Takeover/WaitingForInput）。

## 5. 导航按钮（Row2-R）当前行为

来源: `NavSpec.from`（`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/model/CapsuleRenderSpec.kt:167`-`183`）。

1. `Done` 隐藏所有导航按钮。
2. `showMinimize` 条件: 有 island + 非 MainApp + 非 `WaitingForInput/WaitingForAction/Error`。
3. `showApp` 条件: 非 MainApp 且平台不是 A11y。
4. `showWatch` 条件: 平台不是 A11y 且 context 不是 `SCREEN_VIEWING`。
5. 当前测试明确允许 A11y overlay 出现 minimize（`app/src/test/kotlin/com/moonkey/androidagent/ui/overlay/model/NavSpecTest.kt:85`-`94`）。

## 6. 关键场景流（按实际代码）

1. Task 开始:
   - `onTaskStarted` -> mode=Running，`showPreference=CAPSULE`，立即 `applyVisibility`（`app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:243`-`246`）。
2. Ask user:
   - `AskUserTool` 发 `AskUser` 事件（`app/src/main/kotlin/com/moonkey/androidagent/tool/impl/AskUserTool.kt:122`）。
   - overlay 收到后进入 `WaitingForInput/WaitingForAction` 并强制展示 capsule（`app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:302`-`305`）。
3. Viewer 打开/关闭:
   - 打开: `userLocation=VD_VIEWER` + `showPreference=CAPSULE`（`app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:195`-`199`）。
   - 关闭: location 回 `OTHER_APP` + `showPreference=ISLAND`（`app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:202`-`208`）。
4. Island 点击:
   - 无 active task 且非 Done/Error -> 打开主 app。
   - VD 下若已在 viewer，直接切 `CAPSULE`；否则打开 viewer。
   - A11y 下直接切 `CAPSULE`（`app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:172`-`191`）。
5. Supplement:
   - Session 发 `SupplementReceived`（`app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:270`）。
   - Chat 追加 user message（`app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatEventReducer.kt:163`）。
   - 如果 overlay capsule 正在显示，触发 flash confirmation（`app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:296`-`300`）。
6. Task 完成:
   - `TaskCompleted` -> `Done/Error`（`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt:205`-`224`）。
   - `Done` 3 秒后自动 `Hidden`。
   - Chat 总会追加 completion 文本（空结果时默认 `Task completed`，`app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModel.kt:25`）。
7. Main app 前台兜底:
   - MainActivity 在 `onCreate/onStart/onResume/onNewIntent` 通知 `onMainAppVisible`，强制 MAIN_APP 可见性收敛（`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:136`, `141`, `148`）。

## 7. `FLAG_NOT_TOUCHABLE` 对当前 User Flow 的实际影响

1. Overlay capsule 虽可显示，但按钮/输入无法直接点击（当前临时策略）。
2. 因此 A11y/VD 的 overlay 内 `Takeover/Resume/Stop/Done/Close/输入` 处于“UI 有定义、运行时不可触”的状态。
3. 可触发的主要入口变成:
   - Main app Compose capsule。
   - Island 点击（切换/唤起）。
