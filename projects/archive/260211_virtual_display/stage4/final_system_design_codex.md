# Virtual Display Stage 4 - Final System Design (Codex)

Date: 2026-02-11

## 0. Final Call

结论先说：**采用 Hybrid Mode**。

- Background（用户不看）: `ImageReader`（低开销，给 Agent 看）
- Watching（用户在看）: `setVirtualDisplaySurface(viewerSurface)` + `PixelCopy`（高流畅 + Agent 仍可截图）

这不是“可以试试”的方案，这是当前代码和 Android API 下最合理的方案。

同时坚持两条硬约束：
- `ACCESSIBILITY` 模式行为不变（no regression）
- `VIRTUAL_DISPLAY` 模式主屏只保留 Island，`glow + capsule` 只在 Viewer Activity 内

## 1. Why Hybrid Is Feasible (API Check)

我核了官方 API 和 AOSP 接口，结论是可行：

1. `VirtualDisplay#setSurface(Surface)` 官方公开 API 存在。  
2. `PixelCopy.request(Surface, Bitmap, ...)` 官方公开 API 存在。  
3. AOSP `IDisplayManager` 存在 `setVirtualDisplaySurface(...)`（系统侧能力明确）。

落地含义：
- 你现在通过 Shizuku 走 `IDisplayManager.createVirtualDisplay(...)`，只要保留创建时 token/callback，就可以后续切换 surface。
- 所以 Hybrid 不是“未来幻想”，是现在可实现的工程路径。

## 2. Current Code Reality (Aligned to Codebase)

当前代码里最关键的事实：

1. 串扰根因成立
- `ServiceOverlayController` 当前不区分平台模式，VD 时也会继续走主屏 overlay。  
- `AgentService.runAgent()` 启动时直接 `showCapsule()`，VD 下天然会漏到主屏。

2. 键盘串扰根因成立
- `TypeExecutor` 固定两段式：`SetTextOnNodeAt` 失败后 `TapAt -> SetTextOnFocused`。  
- 在 VD 下这条 fallback 会触发真实屏 IME。

3. 你现在的 VD 平台是好底座
- `VirtualDisplayPlatform` 已经稳定：Shizuku 创建 display + ImageReader 截图 + displayId 定向输入注入。  
- 不需要推倒重写，只要加“surface 切换”和“截图分流”。

## 3. Architecture (KISS, Single Responsibility)

### 3.1 Keep / Add / Deprecate

Keep:
- `VirtualDisplayPlatform`（作为唯一 VD 平台实现）
- `ServiceOverlayController`（仅用于 ACCESSIBILITY 模式）

Add:
- `VirtualDisplayUiController`（VD 模式 UI 单一控制器）
- `VirtualDisplayUiStore`（Service <-> Viewer 的共享状态/事件总线）
- `StatusIslandManager`（主屏唯一 overlay）
- `VirtualDisplayViewerActivity` + `VirtualDisplayViewerScreen`
- `CompletionHandoffManager`
- `TextInputPolicy`（平台能力）

Deprecate:
- VD 模式下 `ServiceOverlayController` 的 `EdgeGlowManager/SmartCapsuleManager` 路径
- 所有“VD 也在主屏挂 capsule/glow”的代码

### 3.2 Runtime Ownership

- `AgentService`: 会话生命周期 + 事件分发
- `VirtualDisplayUiController`: 仅负责 VD 模式 UI 状态流转（Island / Viewer chrome / completion）
- `VirtualDisplayPlatform`: 仅负责 VD 平台能力（capture/action/surface routing）
- `VirtualDisplayViewerActivity`: 只负责渲染和手势，不碰 Agent 业务

## 4. Core Flows

### 4.1 Session Start (VD)

1. Session 启动，平台模式 = `VIRTUAL_DISPLAY`
2. `AgentService` 选择 `VirtualDisplayUiController`（而不是 `ServiceOverlayController`）
3. `VirtualDisplayUiController` 显示 Island
4. `VirtualDisplayPlatform` 默认绑定 `imageReader.surface`（headless）

### 4.2 User Taps Island (Watch)

1. 打开 `VirtualDisplayViewerActivity`
2. Activity `SurfaceView` ready -> 上报 `viewerSurface`
3. `VirtualDisplayPlatform` 切换到 live surface（Hybrid 切换）
4. Viewer 内渲染 capsule + glow（Compose/View，非系统 overlay）

### 4.3 User Swipes Up (Exit Watch)

1. Activity `finish()`
2. 清空 `viewerSurface`
3. `VirtualDisplayPlatform` 切回 `imageReader.surface`
4. Agent 继续跑，Island 保持

### 4.4 Task Complete + Handoff

1. `TaskCompleted(reason=GOAL_ACHIEVED)`
2. 若 viewer 正在显示：先展示 success 状态短暂反馈，再关闭 viewer
3. `CompletionHandoffManager` 尝试把 `lastActivePackage` 启到 display 0
4. Island 显示完成态后自动消失

失败/停止路径：不做 handoff，只给明确状态提示。

## 5. Hybrid Mode Implementation Detail

### 5.1 `ShizukuClient`

新增能力：
- 保存 `createVirtualDisplay` 的 callback/token（按 displayId 映射）
- `setVirtualDisplaySurface(displayId, surface)`

### 5.2 `VirtualDisplayPlatform`

新增状态：
- `CaptureRoute.IMAGE_READER` / `CaptureRoute.LIVE_SURFACE`
- 当前 viewer surface 引用

截图策略：
- `IMAGE_READER`: 维持现状 `acquireLatestImage()`
- `LIVE_SURFACE`: `PixelCopy.request(viewerSurface, bitmap, ...)`

切换策略：
- viewer visible + surface valid -> 切到 `LIVE_SURFACE`
- viewer gone/surface destroyed -> 切回 `IMAGE_READER`

失败策略：
- `PixelCopy` 连续失败 N 次（建议 2）-> 强制回退 `IMAGE_READER`，并上报 viewer 状态（让 UI 提示）

## 6. Bug Fixes (Root Cause First)

### 6.1 Overlay Leak

- VD 模式主屏只允许 `StatusIslandManager`
- `EdgeGlowManager/SmartCapsuleManager` 只在 ACCESSIBILITY 模式可触发
- Viewer 内的 glow/capsule 是普通 Activity 内 UI，不是 `WindowManager` overlay

### 6.2 Ghost Keyboard

新增平台能力：

```kotlin
enum class TextInputPolicy {
    NODE_ONLY,
    TAP_TO_FOCUS_ALLOWED
}
```

- `VirtualDisplayPlatform` -> `NODE_ONLY`
- `AccessibilityPlatform` -> `TAP_TO_FOCUS_ALLOWED`

`TypeExecutor` 调整：
- 在 `NODE_ONLY` 下彻底禁用 `TapAt -> SetTextOnFocused` fallback
- 失败就返回明确原因给模型重选节点

这才是根因修复，不是靠 shell keyevent 擦屁股。

## 7. Event Contract Cleanup (Needed for Correct Handoff)

当前 `TaskCompleted` 没 completion reason，不足以判断是否该 handoff。必须改。

```kotlin
data class TaskCompleted(
    ...,
    val reason: CompletionReason,
    val result: String?
)
```

映射来源：`AgentStopReason -> CompletionReason`
- `GoalAchieved -> GOAL_ACHIEVED`
- `MaxTurnsReached -> MAX_TURNS`
- `UserRequested -> USER_STOPPED`
- `Error -> ERROR`

`VirtualDisplayUiController` 只在 `GOAL_ACHIEVED` 触发 handoff。

## 8. File-Level Change Plan

新增：
- `app/src/main/kotlin/com/moonkey/androidagent/app/virtualdisplay/VirtualDisplayUiController.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/virtualdisplay/VirtualDisplayUiStore.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/virtualdisplay/CompletionHandoffManager.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/StatusIslandManager.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/viewer/VirtualDisplayViewerActivity.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/viewer/VirtualDisplayViewerScreen.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/TextInputPolicy.kt`

修改：
- `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/AndroidPlatform.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityPlatform.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayPlatform.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/ShizukuClient.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/TypeExecutor.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/protocol/AgentEvent.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt`
- `app/src/main/AndroidManifest.xml`（注册 Viewer Activity）

## 9. Rollout Order

1. 先改 `TextInputPolicy + TypeExecutor`（先灭键盘串扰）
2. 再做 `AgentService` 平台分流（先灭 overlay 串扰）
3. 上 `StatusIslandManager + ViewerActivity`（完成可见交互闭环）
4. 上 Hybrid surface 切换（`ShizukuClient + VirtualDisplayPlatform`）
5. 最后做 handoff 和事件语义清理（`TaskCompleted.reason`）

每步都可单独验证，不需要大爆炸式改动。

## 10. Tests and DoD

单测：
- `TypeExecutor`: `NODE_ONLY` 下不允许 Tap fallback
- `VirtualDisplayUiController`: 事件 -> UI state 映射
- `AgentSession`: `TaskCompleted.reason` 映射正确

集成：
- VD 模式运行时主屏无 capsule/glow
- Island 点击可进 Viewer，Viewer 上滑退出不中断任务
- Viewer 进入/退出时 `setVirtualDisplaySurface` 切换成功
- VD 输入时主屏不弹 IME
- `GOAL_ACHIEVED` 触发 handoff，失败时有明确 fallback 提示

回归：
- ACCESSIBILITY 模式 overlay 行为与当前一致

DoD：
1. VD 主屏只剩 Island
2. Keyboard 串扰复现用例归零
3. Viewer 流程稳定（进入/退出/不中断）
4. Hybrid 模式可切换且截图链路不断
5. A11y 模式无回归

## 11. Non-goals (This Stage)

- 不做 user takeover（触摸接管）
- 不做 PiP
- 不做录屏回放
- 不做跨 display task “无损迁移”黑魔法

## 12. References (API Feasibility)

- VirtualDisplay `setSurface`  
  https://developer.android.com/reference/android/hardware/display/VirtualDisplay#setSurface(android.view.Surface)
- PixelCopy `request(Surface, ...)`  
  https://developer.android.com/reference/android/view/PixelCopy#request(android.view.Surface,android.graphics.Bitmap,android.view.PixelCopy.OnPixelCopyFinishedListener,android.os.Handler)
- AOSP `VirtualDisplay.java`  
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/display/VirtualDisplay.java
- AOSP `IDisplayManager.aidl`  
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/display/IDisplayManager.aidl
