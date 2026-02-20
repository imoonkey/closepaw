# Fix Plan: click `--use-node false` (dispatchGesture path)

## Problem Statement

- 当前 `click --use-node false` 在多场景下表现为：
- `action_accepted=success`（`Gesture completed`）
- 但 `ui_changed=unchanged`
- 对照下 `click --use-node true` 可工作，`adb input tap` 也可工作。

结论：问题集中在 Accessibility `dispatchGesture` 点击链路，不是 setup 本身。

## Scope

- 本轮只聚焦 `click --use-node false`（gesture tap path）。
- 暂不处理 `long_press --use-node false`（后续沿用同一修复策略验证）。
- 验证页面固定为 Android Settings 首页，目标项：`Network & internet`。

## Success Criteria

1. 在 Settings 首页，对 `Network & internet` 进行 `--use-node false` 点击，结果应为：
- `action_accepted=success`
- `ui_changed=changed`
- 且页面实际进入 `Network & internet` 子页面。
2. 点击成功后执行 Back，能稳定回到 Settings 首页（可重复）。

## Execution Plan

1. 建立稳定复测脚本
- 每次 case 前通过 `uiautomator dump` 确认在 Settings 首页，动态取 `Network & internet` bounds center。
- case 后执行 Back 复位。
- 固定输出目录，保存 before/after 与 result.json。

2. 增加最小必要日志（不污染业务）
- 在 `AccessibilityGestureInjector.dispatchGesture` 增加 debug 日志：
- stroke 数、duration、path 起终点、dispatch 返回值、callback 类型（completed/cancelled）
- 当前 `rootInActiveWindow.packageName`、`serviceInfo.capabilities/flags`

3. 查官方资料 + 对齐实现
- 对照 Android 官方 `dispatchGesture` 文档与样例，核对 tap 手势构造与线程/回调要求。
- 若有 API 版本分支需求，按 SDK 做最小分支修复。

4. 迭代修复 tap 手势构造
- 候选方向（按成本低到高）：
- 调整 tap gesture duration（短按更短）
- 调整 path 形式（point / epsilon / micro-swipe）
- 调整回调 handler（显式主线程 handler）
- 必要时增加 gesture 注入前后短暂同步（如主线程 idle 或极短 delay）

5. 回归验证
- 在 Settings 页至少跑 3 轮 `click --use-node false`，确保稳定进入目标页。
- 保留 `--use-node true` 作为对照，确认未回归。

## Notes

- 本轮优先“先让它 work”，再考虑抽象和清理。
- 若修复依赖环境条件（特定 emulator / API level），文档中明确记录限制与后续计划。

## API Notes (Official)

- `AccessibilityService.dispatchGesture(...)` 官方示例 tap 使用 `ViewConfiguration.getTapTimeout()` 作为 tap duration。
- `GestureDescription.Builder.setDisplayId(int)` 在 API 30+ 可用，用于指定手势派发 display。
- `dispatchGesture` 的 `handler` 允许显式指定回调线程；`null` 时回调在 service 主线程。

参考：
- https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- https://developer.android.com/reference/android/accessibilityservice/GestureDescription.Builder

## Implemented Changes

文件：`app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityGestureInjector.kt`

1. tap duration 对齐官方示例
- 从固定常量改为 `ViewConfiguration.getTapTimeout().toLong()`。

2. gesture display 路由增强（API 30+）
- 构建 gesture 时根据 `rootInActiveWindow.window.displayId` 调用 `setDisplayId(...)`。
- 无可用 displayId 时回退 `Display.DEFAULT_DISPLAY`。

3. dispatch 回调线程显式化
- `dispatchGesture(..., callback, Handler(Looper.getMainLooper()))`。

4. 增强诊断日志
- 输出 displayId、strokeCount、每个 stroke 的 start/duration/path length、root package、service flags/capabilities、dispatch 布尔值、completed/cancelled 回调结果。

## Validation (Settings / Network & internet)

场景：Settings 首页点击 `Network & internet`，动作为 `click --use-node false`，成功后 Back 复位。

结果（3轮）：
- `status=success`
- `ui_changed=changed`
- post tree 均包含 `Navigate up` 与 `Internet | Networks available`（确认进入目标页）
- Back 后可回到 Settings 首页继续下一轮

结论：
- 本轮目标页面上，`dispatchGesture` click path 已稳定可用（3/3）。
