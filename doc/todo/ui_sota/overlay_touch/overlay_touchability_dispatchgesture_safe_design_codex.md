# Overlay Touchability 设计（不回退 `dispatchGesture` 成功率）

更新时间: 2026-02-21  
范围: 对齐 `doc/todo/ui_sota/align/design/ui_suggestions.md` 的 **1.1 Overlay touchability**，替换当前 hardcode hot fix（永久 `FLAG_NOT_TOUCHABLE`）。

## 1. 背景与问题

当前实现为了修复 `dispatchGesture` 被 overlay 吃掉的问题，给 `CapsuleOverlayHost` 永久加了 `FLAG_NOT_TOUCHABLE`（见 `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/CapsuleOverlayHost.kt`）。

这个 hot fix 的效果是：
1. 优点: `dispatchGesture` 在 eval 中恢复成功（`SystemBrightnessMax/Min` 2/2）。
2. 代价: overlay capsule 全部按钮和输入不可点，违背 interactive capsule 设计（`Takeover/Resume/Stop/Done/Close/输入` 在 overlay 场景不可达）。

问题本质是三个概念被耦合了：
1. overlay window 是否可触摸（`FLAG_NOT_TOUCHABLE`）。
2. 用户触摸是否应被锁定（running 时防干扰）。
3. agent 手势是否应穿透 overlay（保证 `dispatchGesture` 命中目标 app）。

## 2. 设计目标

P0 目标：
1. 恢复 capsule 在 overlay 场景下的可交互行为（至少覆盖 `Takeover/Resume/Stop/Done/Close/输入`）。
2. 保持 `dispatchGesture` 成功率不低于 hot fix 基线（不回退 debug5 中已修复的问题）。
3. 保留 running 阶段的用户防干扰能力（lock interaction 语义不丢）。

非目标：
1. 本设计不处理 DocumentsUI 特有点击失败（debug6 的另一类问题）。
2. 不改变现有状态机语义（`CapsuleMode` 及 `ServiceOverlayController` 事件流保持不变）。

## 3. 方案总览

采用“**模式驱动触摸策略 + 手势注入临时放行**”两层机制：

1. 模式驱动触摸策略（静态层）  
根据 `platformMode + userLocation + capsuleMode + hasActiveTask` 计算 overlay 目标触摸策略，而不是 hardcode 一个全局 `FLAG_NOT_TOUCHABLE`。

2. 手势注入临时放行（动态层）  
在每次 `dispatchGesture` 前后，临时强制 overlay pass-through，确保 agent 手势不会被 overlay 拦截；结束后恢复原策略。

核心原则：  
`dispatchGesture` 的通道优先级高于“用户锁触摸”策略，持续时间仅覆盖单次手势注入窗口（通常几十到几百毫秒）。

## 4. 目标触摸策略矩阵

以下是 overlay capsule 可见时的目标策略（对齐 1.1 建议并补上不回退约束）：

| CapsuleMode | 默认 window touchability | 说明 |
|---|---|---|
| `Hidden` | `NOT_TOUCHABLE` | 无可交互 UI，保持穿透 |
| `TakeoverPending` | `NOT_TOUCHABLE` | 过渡态，避免误触 |
| `Running` | `TOUCHABLE` | 允许 lock shield 生效；Row3 输入仍按现有逻辑禁用 |
| `Takeover` | `TOUCHABLE` | 用户接管态，需可操作 |
| `WaitingForInput` | `TOUCHABLE` | 必须可输入 |
| `WaitingForAction` | `TOUCHABLE` | 必须可点 Done/Stop |
| `Done` | `TOUCHABLE` | 可见期可交互（如关闭/确认） |
| `Error` | `TOUCHABLE` | 必须可点 Close |

动态覆盖规则（最高优先级）：
1. 当 `gestureDispatchInFlight == true` 时，强制 `NOT_TOUCHABLE`（pass-through）。
2. 手势回调 `onCompleted/onCancelled/timeout` 后恢复到模式策略。

## 5. 组件设计

### 5.1 新增 `OverlayTouchPolicy`（纯函数）

位置建议：`app/src/main/kotlin/com/moonkey/androidagent/app/OverlayTouchPolicy.kt`  
职责：从当前状态导出触摸策略，不直接操作 window。

建议数据结构：
```kotlin
internal enum class OverlayTouchability { TOUCHABLE, NOT_TOUCHABLE }

internal data class OverlayTouchPolicy(
    val capsuleTouchability: OverlayTouchability,
    val shouldLockUserInteraction: Boolean,
)
```

`shouldLockUserInteraction` 继续复用当前 `OverlayLocationPolicy.kt` 语义；  
`capsuleTouchability` 按上表映射。

### 5.2 新增 `GesturePassThroughGate`（引用计数）

目标：给 `AccessibilityGestureInjector` 一个“进入/退出手势注入窗口”的统一入口，避免遗漏恢复。

建议接口：
```kotlin
interface GesturePassThroughGate {
    fun beginGestureDispatch(): AutoCloseable
    val inFlightCount: StateFlow<Int>
}
```

行为要求：
1. 支持嵌套/并发（计数器而非布尔）。
2. `close()` 幂等。
3. 出错路径也能恢复（`finally` 保证）。

### 5.3 `AccessibilityGestureInjector` 接入 Gate

位置：`app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityGestureInjector.kt`

在 `dispatchGesture()` 内：
1. `val token = gate.beginGestureDispatch()`
2. 执行 `service.dispatchGesture(...)`
3. 在 `finally` 里 `token.close()`

这样可以确保任何 tap/swipe/longPress 都自动触发临时 pass-through，不依赖调用方手动开关。

### 5.4 `CapsuleOverlayHost` 改为“可动态切 flag”

位置：`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/CapsuleOverlayHost.kt`

改动原则：
1. 去掉永久 hardcode `FLAG_NOT_TOUCHABLE`。
2. 根据“模式策略 + inFlight 覆盖”动态更新 `LayoutParams.flags`。
3. 保留现有 `setInteractionLocked()` 的全屏锁触摸层逻辑（不改 UI 结构）。

等效逻辑：
1. `effectiveNotTouchable = (policy == NOT_TOUCHABLE) || (gestureDispatchInFlight > 0)`
2. `flags` 始终基于 `NOT_FOCUSABLE` 与 `LAYOUT_IN_SCREEN`，按 `effectiveNotTouchable` 增删 `FLAG_NOT_TOUCHABLE`。

### 5.5 `ServiceOverlayController` 作为编排点

位置：`app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt`

职责：
1. 在 `applyVisibility()` 里同时计算：
   - `OverlayVisibilityDecision`（已有）
   - `OverlayTouchPolicy`（新增）
2. 把 touch policy 下发给 `capsuleManager`。
3. 提供 `GesturePassThroughGate` 给 platform 层（通过 service/platform factory 传递）。

## 6. 关键时序

### 6.1 Agent 手势执行时序（保证不回退）

1. `AccessibilityGestureInjector.dispatchGesture()` 开始。
2. `beginGestureDispatch()` -> `inFlightCount + 1`。
3. overlay 观察到 in-flight，立即进入 `NOT_TOUCHABLE`。
4. `service.dispatchGesture(...)` 注入手势，事件穿透到目标 app。
5. 回调完成/取消/超时。
6. `token.close()` -> `inFlightCount - 1`。
7. overlay 恢复到当前 mode 的触摸策略。

### 6.2 用户交互时序（恢复 capsule 可用）

1. mode 进入 `WaitingForInput/WaitingForAction/Error/Takeover/...`。
2. `OverlayTouchPolicy` 计算为 `TOUCHABLE`。
3. capsule overlay 去掉 `FLAG_NOT_TOUCHABLE`。
4. 用户可直接点击按钮/输入。

## 7. 验证与回归门禁

### 7.1 自动化测试

新增/修改测试：
1. `OverlayTouchPolicyTest`：覆盖 8 个 `CapsuleMode` 的 touchability 映射。
2. `GesturePassThroughGateTest`：覆盖嵌套 begin/close、异常恢复、幂等 close。
3. `CapsuleOverlayHost` 单元测试或 Robolectric 测试：验证 in-flight 时 `FLAG_NOT_TOUCHABLE` 被置位，结束后恢复。

### 7.2 回归验证（必须）

1. 复跑 debug5 对应用例：`SystemBrightnessMax/Min`，要求不低于 hot fix 基线（2/2）。
2. 手工验证 overlay 交互：
   - `WaitingForInput` 可输入并发送。
   - `WaitingForAction` 可点 Done/Stop。
   - `Error` 可点 Close。
   - `Takeover` 可点 Resume/Stop。
3. 验证 running 锁触摸：
   - 非 takeover 时用户触摸不应误操作底层 app。
   - takeover 后应恢复用户直控。

## 8. 风险与缓解

风险 1：手势窗口期间用户触摸“漏进”底层 app。  
缓解：  
1. 放行窗口只覆盖单次手势 dispatch 周期。  
2. 用计数器减少误恢复。  
3. 增加 telemetry：记录每次 in-flight 持续时长与超时次数。

风险 2：flag 频繁切换导致窗口抖动或焦点异常。  
缓解：  
1. 仅在状态变化时更新 params（去重）。  
2. 复用现有 `setOverlayFocusable()` 逻辑，不在 gate 中改 focus flag。

风险 3：并发手势时 gate 恢复顺序错误。  
缓解：  
1. 强制引用计数 + 幂等 token close。  
2. 测试覆盖并发 begin/close。

## 9. 迁移步骤（建议）

1. 第一步：落地 `OverlayTouchPolicy` + 单元测试（不改行为，先接入日志观察）。
2. 第二步：引入 `GesturePassThroughGate`，`AccessibilityGestureInjector` 接入。
3. 第三步：移除永久 `FLAG_NOT_TOUCHABLE` hardcode，启用动态策略。
4. 第四步：跑 eval + 手工交互回归，通过后删除临时兼容分支。

---

这份设计的核心交付标准是：  
**overlay 可交互能力恢复** 与 **`dispatchGesture` 成功率不回退** 同时成立，而不是二选一。
