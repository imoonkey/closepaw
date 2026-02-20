# UI 不合理点与修改建议 (Codex)

更新时间: 2026-02-20
依据: 当前代码 + round6/round7 历史 design + debug5 对 `FLAG_NOT_TOUCHABLE` 的临时说明。

## P0

### 1) Overlay capsule 永久 `FLAG_NOT_TOUCHABLE` 导致交互路径被切断

现状证据:
1. `CapsuleOverlayHost` 创建 window 时固定加 `FLAG_NOT_TOUCHABLE`（`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/CapsuleOverlayHost.kt:272`-`273`）。
2. 同文件仍实现了大量交互逻辑（按钮、输入 focus、keyboard、interaction lock），但在该 flag 下无法直接触发。

风险:
1. `WaitingForInput/WaitingForAction/Error` 在 overlay 场景缺少可执行入口。
2. A11y/VD 外部场景无法通过 overlay 完成 takeover/resume/stop。

建议:
1. 引入运行时开关（例如 `OverlayInteractionMode = PASS_THROUGH | INTERACTIVE`）。
2. `PASS_THROUGH` 保留 `FLAG_NOT_TOUCHABLE`（用于当前 eval 临时策略）。
3. `INTERACTIVE` 去掉 `FLAG_NOT_TOUCHABLE`，并保留现有 focus/keyboard/lock 逻辑。
4. 增加 instrumentation case 覆盖 `WaitingForInput` overlay 可输入。

## P1

### 2) A11y 策略与 round6 规范漂移（Island / Nav）

现状证据:
1. 代码允许 A11y + OTHER_APP 显示 island（`deriveOverlayVisibility`，`app/src/main/kotlin/com/moonkey/androidagent/app/OverlayLocationPolicy.kt:98`-`103`）。
2. `NavSpecTest` 明确允许 A11y overlay 显示 minimize（`app/src/test/kotlin/com/moonkey/androidagent/ui/overlay/model/NavSpecTest.kt:85`-`94`）。
3. round6 文档要求 A11y 不显示 island、A11y 不显示导航按钮（`doc/archive/260217_smart_capsule/round6/align/design/design.md:19`, `91`）。

风险:
1. 设计认知与代码行为分叉，后续 debug 容易误判。

建议:
1. 二选一并固化:
   - 路线 A: 回到 round6（A11y 无 island/无 nav）。
   - 路线 B: 承认新策略并更新 SOTA 设计文档 + 测试命名（明确“为什么 A11y 允许 island/minimize”）。
2. 不建议继续“文档一套、代码一套”。

### 3) UserResponse 的“乐观状态切换”只在 overlay 路径发生

现状证据:
1. Overlay 路径在提交前调用 `stateHolder.onUserResponseSent(callId)`，立即切 `Processing response...`（`app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:79`-`83`）。
2. Main app `ChatScreen -> viewModel.sendUserResponse` 不走该状态切换（`app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatScreen.kt:142`, `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModel.kt:223`）。

风险:
1. 同一动作在 Main app/overlay 的即时反馈不一致。

建议:
1. 在 Main app 路径也复用 `onUserResponseSent` guard（可通过 `AgentService.instance?.capsuleStateHolder`）。
2. 保证两条入口一致地进入 `Running("Processing response...")`。

### 4) Interaction lock 设计与当前 flag 组合失效

现状证据:
1. `shouldLockUserInteraction` + `setInteractionLocked` 试图通过全屏拦截阻断底层交互。
2. 但 window 本身是 `FLAG_NOT_TOUCHABLE`，拦截层接不到事件。

建议:
1. 若保留 pass-through 模式，直接声明 lock 逻辑在该模式下禁用，避免误导。
2. 若需要真实 lock，必须在 interactive 模式下启用触摸并验证。

## P2

### 5) `resolveUserLocation` 对 className 形态较敏感

现状证据:
1. 仅当 `className` 命中 `Activity/Launcher/.app./Home` 才更新位置（`app/src/main/kotlin/com/moonkey/androidagent/app/OverlayLocationPolicy.kt:26`-`32`）。

风险:
1. 某些 ROM/窗口类型可能导致位置更新延迟或漏判。

建议:
1. 增加降级策略（例如允许 package 级别 fallback + debounce）。
2. 增加 trace log 中“location ignored reason”字段，方便线上定位。

## 建议执行顺序

1. 先做 P0（交互开关 + 测试）。
2. 再做 P1（A11y 策略定版 + 文档/测试统一）。
3. 最后做 P2（稳健性增强）。
