# Virtual Display Phase 4 - System Design

## 0. 设计立场

- KISS：少模块、浅层状态机、单向数据流。
- 可读性优先：状态和职责放在明面上，不靠隐式副作用。
- 不做向后兼容包袱：旧路径可以直接废弃/替换。
- 以代码为准，不以旧文档为准。

---

## 1. 目标与非目标

## 1.1 目标

1. 在 `PlatformMode.VIRTUAL_DISPLAY` 下建立完整 UI 闭环：
- Dynamic Island（入口）
- Viewer（观看）
- 手势退出（不中断执行）
- Completion Handoff（结果带回主屏）

2. 修复两个已知串扰 bug：
- overlay（glow + capsule）不再出现在真实主屏。
- virtual display 输入时，真实主屏 IME 不再被触发。

3. 保持现有 Agent / Session 主流程不重写，只补必要接口。

## 1.2 非目标

- 不追求“跨 display 任务对象 100% 无损迁移”。
- 不做复杂多进程 IPC 框架。
- 不做第二套 UI 状态源（禁止双写）。

---

## 2. 现状诊断（基于代码）

## 2.1 Overlay 串扰根因

当前 `AgentService` 始终持有 `ServiceOverlayController`，并在事件中持续驱动：
- `AgentService.kt` 中 `overlayController?.onTaskStarted/...`
- `ServiceOverlayController.kt` 使用 `EdgeGlowManager + SmartCapsuleManager`
- 两者都是 `TYPE_ACCESSIBILITY_OVERLAY`，目标是真实主屏

问题：`ServiceOverlayController` 没有 `platformMode` 分支，virtual display 时仍会把 overlay 加到主屏。

## 2.2 键盘串扰根因

`TypeExecutor` 当前策略：
- Attempt 1: `SetTextOnNodeAt`
- Attempt 2: `TapAt -> SetTextOnFocused`

在 virtual display 下，Attempt 2 的 `TapAt` 会触发输入焦点链路，IME 可能落到真实主屏显示（系统行为）。

结论：virtual display 的文本输入必须禁用 `tap-to-focus` fallback。

---

## 3. 新架构（最小改动版）

## 3.1 单一 UI 协调器

新增：`app/VirtualDisplayUiCoordinator.kt`

职责：
- 接收 `AgentEvent` + viewer 可见性 + platform 模式。
- 产出单一 `VirtualDisplayUiState`（StateFlow）。
- 驱动两个渲染终端：
  - `VirtualIslandManager`（真实主屏，仅入口）
  - `ViewerOverlay`（Viewer 内 glow + capsule）

禁止：
- 不直接操作 Agent 业务逻辑。
- 不做平台动作执行。

### 状态模型

```kotlin
data class VirtualDisplayUiState(
    val sessionId: String?,
    val running: Boolean,
    val paused: Boolean,
    val phase: TurnPhase?,
    val statusText: String,
    val appPackage: String?,
    val appLabel: String?,
    val viewerVisible: Boolean,
    val lastError: String?
)
```

状态源：
- `TaskStarted/TaskCompleted/SessionCompleted`
- `TurnPhaseChanged`
- `StatusUpdate`
- `ScreenCaptured.packageName`（用于当前 app）

---

## 3.2 渲染分流（关键）

### A. ACCESSIBILITY 模式（保持现有）

- 继续使用 `ServiceOverlayController`（edge glow + smart capsule）。

### B. VIRTUAL_DISPLAY 模式（新路径）

- 主屏：只显示 `VirtualIslandManager`。
- Viewer：显示 `edge glow + smart capsule`（在 Activity 内部 Composable 渲染，不走系统 overlay window）。

这条分流直接消灭 overlay 串扰。

---

## 3.3 Viewer 页面

新增：
- `app/VirtualDisplayViewerActivity.kt`
- `ui/virtualdisplay/VirtualDisplayViewerScreen.kt`

能力：
- 渲染 virtual display 帧流。
- 叠加 Viewer 内 chrome（glow + capsule）。
- 底部上滑退出（finish activity，不发 stop op）。

退出语义：
- 只影响观看态，不影响 session 执行态。

---

## 3.4 帧流共享（一个生产者）

新增：`platform/virtualdisplay/VirtualDisplayFrameHub.kt`

目的：
- 给 Agent 截图与 Viewer 预览提供同一帧源，避免 `ImageReader` 多方抢读。

原则：
- 单消费者读取 `ImageReader.acquireLatestImage()`。
- 发布 `StateFlow<FramePacket?>`。
- `captureScreenshot()` 从 hub 取最新帧编码结果，不再自己直接抢 Image。

`FramePacket`：

```kotlin
data class FramePacket(
    val timestamp: Long,
    val width: Int,
    val height: Int,
    val jpeg: ByteArray
)
```

---

## 3.5 文本输入策略分流（修复 IME 串扰）

新增平台能力字段：

```kotlin
enum class TextInputPolicy {
    NODE_ONLY,
    TAP_TO_FOCUS_ALLOWED
}
```

`AndroidPlatform` 新接口：

```kotlin
fun textInputPolicy(): TextInputPolicy
```

实现：
- `AccessibilityPlatform` -> `TAP_TO_FOCUS_ALLOWED`
- `VirtualDisplayPlatform` -> `NODE_ONLY`

`TypeExecutor` 修改：
- 当 `NODE_ONLY`：禁用 `TapAt -> SetTextOnFocused` fallback。
- 只允许 `SetTextOnNodeAt` / `SetTextOnFocused`。
- 失败时返回明确错误，引导模型重新选择可编辑节点。

这条改动是修 IME 串扰的主刀位。

---

## 4. Completion Handoff（专业定义 + 落地）

定义：
- **Task Continuity Handoff**：任务成功后，将目标 app 的用户上下文转移到主屏（display 0）前台，best-effort。

新增：`app/CompletionHandoffManager.kt`

输入：
- `lastActivePackage`（来自最近 `ScreenCaptured.packageName`）

执行：
1. 用 `PackageManager.getLaunchIntentForPackage(package)` 拿 launch component。
2. 优先 `am start -n <component> --display 0 -W`（Shizuku shell）。
3. 失败回退 `ShizukuClient.launchOnDisplay(intent, 0)`。
4. 再失败：保留灵动岛完成提示 + 可点击“打开目标 app”。

说明：
- 这是“连续体验保证”，不是“任务对象无损迁移”。
- 先做稳态可用，不做 fragile 的 task-reparent 黑魔法。

---

## 5. 代码改造清单（按文件）

1. 新增
- `app/VirtualDisplayUiCoordinator.kt`
- `app/VirtualDisplayViewerActivity.kt`
- `ui/virtualdisplay/VirtualDisplayViewerScreen.kt`
- `ui/virtualdisplay/VirtualIslandManager.kt`
- `app/CompletionHandoffManager.kt`
- `platform/virtualdisplay/VirtualDisplayFrameHub.kt`

2. 修改
- `app/AgentService.kt`
  - 根据 `SessionConfig.platformMode` 选择 UI 渲染路径
  - virtual mode 下停用 `ServiceOverlayController` 事件驱动
  - 接入 `VirtualDisplayUiCoordinator`
- `tool/action/TypeExecutor.kt`
  - 加入 `textInputPolicy` 分流
- `platform/AndroidPlatform.kt`
  - 增加 `textInputPolicy()`
- `platform/AccessibilityPlatform.kt`
  - 返回 `TAP_TO_FOCUS_ALLOWED`
- `platform/virtualdisplay/VirtualDisplayPlatform.kt`
  - 返回 `NODE_ONLY`
  - 接入 `VirtualDisplayFrameHub`

3. 废弃（可直接 deprecated）
- `ServiceOverlayController.handleWindowStateChanged()` 在 virtual mode 下的前后台切换逻辑
- virtual mode 对 `EdgeGlowManager/SmartCapsuleManager` 的 WindowManager 直接挂载路径

---

## 6. 执行时序（关键流程）

## 6.1 任务开始（virtual mode）

1. `AgentSession` 发 `TaskStarted`
2. `AgentService` -> `VirtualDisplayUiCoordinator.start(...)`
3. `VirtualIslandManager.show()`
4. 用户点击灵动岛 -> `VirtualDisplayViewerActivity` 打开
5. Viewer 收 `uiState + frameFlow` 渲染

## 6.2 用户上滑退出 Viewer

1. Viewer 识别 bottom-up swipe
2. `finish()`
3. `viewerVisible=false` 回写 coordinator
4. 任务继续，灵动岛继续显示

## 6.3 任务完成

1. `TaskCompleted` + `SessionCompleted(GOAL_ACHIEVED)`
2. coordinator 切到 success 状态
3. `CompletionHandoffManager.execute(lastActivePackage)`
4. 成功：打开主屏目标 app；失败：保留可点击提示
5. 清理 island/viewer 状态

---

## 7. 测试策略

## 7.1 单测

- `TypeExecutor`：
  - `NODE_ONLY` 下不走 tap fallback
  - `TAP_TO_FOCUS_ALLOWED` 行为不变
- `VirtualDisplayUiCoordinator`：
  - 事件到状态映射
  - viewerVisible 切换
  - completion 后状态收敛

## 7.2 集成测试

- Virtual mode 运行时主屏无 glow/capsule。
- 点击灵动岛进入 viewer，退出后任务不断。
- 输入动作期间主屏不弹 IME（回归重点）。
- goal achieved 后触发 handoff 到 display 0。

## 7.3 回归检查

- Accessibility 模式 overlay 行为不退化。
- pause/resume/stop 在 island + viewer 两端一致。

---

## 8. 风险与回退

风险 1：某些 ROM 对 display 0 启动策略不同。
- 处理：handoff 采用双路径（shell + launchOnDisplay）并保底提示。

风险 2：帧流开销过高。
- 处理：Viewer 不可见时降帧或仅保留最新帧；统一 JPEG 尺寸上限。

风险 3：viewer/activity 生命周期抖动。
- 处理：`StateFlow` 持有最后状态，activity 重建可恢复。

---

## 9. 完成定义（DoD）

1. virtual mode 下主屏不再出现 `EdgeGlowManager/SmartCapsuleManager` 窗口。
2. IME 串扰复现用例修复（type 不触发主屏键盘）。
3. 灵动岛 -> viewer -> 上滑退出 全链路稳定。
4. 任务成功后触发 Completion Handoff（成功或明确回退提示）。
5. 代码中 virtual UI 入口唯一：`VirtualDisplayUiCoordinator`。

