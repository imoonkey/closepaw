# 平台健壮性改进计划

## 目标
- 平台边界不存在无限等待
- 不存在半启动或半停止的 VD 状态
- stop、binder death 或重启后不存在陈旧的 binder proxy
- 在 dialog、popup、分屏窗口或旋转条件下，截图/tree/操作不存在不匹配
- 底层平台操作失败时不静默上报成功
- 对上述失败路径有回归测试覆盖

## 非目标
- 不将 `ShizukuClient` 从其 facade 角色中重构掉
- 不移除 VD 栈中的 lambda provider，除非有具体 bug 要求这样做
- 不仅仅为了代码风格而提取共享的 capture 抽象
- 没有失败的回归测试之前不修改文本光标语义

## 需要实现的状态机

### 状态
- `Stopped`
- `Starting`
- `Running(image_reader)`
- `Running(live_preview)`
- `Broken`
- `Stopping`

### 必须满足的不变量
- 同一时间只能有一个生命周期转换在运行
- 操作调用只能从 `Running(*)` 状态执行
- 操作调用在接触 VD 资源之前必须获取 `Running` lease 或等价保护
- binder death 使平台转入 `Broken` 状态
- `stop()` 在任何状态下都是幂等的
- 启动部分失败时总是清理资源并返回 `Stopped`

## 第一阶段：串行化 VD 生命周期

### 变更
- 为所有公开的 VD 操作添加一个生命周期仲裁者。
- 生命周期转换 `start`、`stop`、`switchToLivePreview`、`switchToImageReader` 和 binder death 处理获取排他访问权。
- 操作调用 `captureScreen` 和 `performAction` 在共享的 `Running` lease 或等价守卫下运行。它们默认不需要彼此之间的全局互斥锁，但也不能在 teardown 仍可能在其下进行时依赖一次性的状态检查。
- 用显式状态替代松散的 `displayId` / `imageReader` 所有权。
- 在正常 shutdown 和 broken 状态 teardown 期间调用 `clearCachedProxies()`。

### 文件
- `VirtualDisplayPlatform.kt`
- `VirtualDisplaySurfaceController.kt`
- `VirtualDisplayCaptureCoordinator.kt`
- `VirtualDisplayInputInjector.kt`
- `ShizukuClient.kt`

### 验收标准
- 任何公开的 VD 方法都不能观察到半停止状态。
- agent 发起的生命周期转换不能使正在进行的操作调用下的资源失效。
- binder death 使平台转入 `Broken` 状态。
- stop 或 Shizuku 重连后的重启不会复用陈旧的 proxy。
- 如果平台不在 Running 状态，操作调用以明确的消息快速失败。

## 第二阶段：为每个回调等待添加超时限制

### 变更
- 添加一个用于 callback-to-suspend 桥接的辅助工具，支持超时和取消安全的 resume 检查。
- 应用于：
  - `takeScreenshot`
  - `takeScreenshotOfWindow`
  - `PixelCopy.request`
- 使超时行为显式化：fail closed 或 fallback，但绝不无限等待。

### 文件
- `AccessibilityScreenshotCapturer.kt`
- `VirtualDisplayCaptureCoordinator.kt`

### 验收标准
- `captureScreen()` 始终在有界的截止时间内完成。
- 取消操作不会产生延迟 resume 崩溃。
- PixelCopy 失败干净地 fallback，不会卡住截图流程。

## 第三阶段：使输入注入支持自清理

### 变更
- 使 VD 长按和滑动支持取消安全。
- 一旦 DOWN 事件发送，追踪手势所有权直到完成。
- 将 MOVE 失败视为手势失败，而非进行中的成功。
- 在 `finally` 中发送尽力的 `ACTION_CANCEL` 或 `ACTION_UP`。
- 将 IME 抑制保持在串行化的生命周期内，使 suppress/restore 不会错误地交错。

### 文件
- `VirtualDisplayInputInjector.kt`
- `VirtualDisplayPlatform.kt`

### 验收标准
- 取消长按或滑动不会使目标 UI 处于卡住的触摸状态。
- IME 抑制和恢复不会在重叠调用之间竞态。

## 第四阶段：修复窗口选择和截图/root 的一致性

### 变更
- 为两个平台定义显式的可选窗口规则。
- 对于单窗口操作，使用最顶层的相关窗口。
- 当存在多个相关窗口时，选择确定性的 fallback，而非混合 tree 和截图来源。
- 在选择 root 之前按层级排序 VD 窗口。

### 文件
- `AccessibilityPlatform.kt`
- `VirtualDisplayWindowAccessor.kt`

### 验收标准
- dialog 和 popup 不会导致截图/tree 不匹配。
- 当更高层级的窗口活跃时，node 操作不会指向背景窗口。

## 第五阶段：处理旋转和 display 尺寸抖动

### 变更
- 通过 `WindowManager` 获取真实的 display metrics，而非 app content metrics。
- 检测宽度、高度或密度变化。
- 在几何变化时重建 VD、`ImageReader` 和坐标映射。
- 保持 viewer 触摸缩放和截图处理与重建后的几何一致。

### 文件
- `VirtualDisplayConfig.kt`
- `VirtualDisplayPlatform.kt`
- `VirtualDisplayViewerTouchHandler.kt`
- `VirtualDisplayCaptureCoordinator.kt`

### 验收标准
- 旋转设备不会破坏截图裁剪、触摸缩放或边界解释。
- 旋转后 VD 几何与当前真实 display 几何匹配。

## 第六阶段：强化平台边界正确性

### 变更
- 使 accessibility 截图路径在 tree、trace 和 snapshot 失败时软失败。
- 使应用启动返回真实的成功/失败结果。
- 在 VD 启动和操作路径中尽早拒绝无效的 display 状态。
- 修复 live preview surface 替换，使重建的 viewer surface 能够接管。
- 将 shell touch fallback 移出调用线程，或重新设计使调用方不必同步等待。
- 使滚动方向处理显式化：验证输入，要么快速失败，要么有意识地 log-and-fallback。

### 文件
- `AccessibilityPlatform.kt`
- `VirtualDisplayAppController.kt`
- `ShizukuActivityLauncher.kt`
- `VirtualDisplaySurfaceController.kt`
- `VirtualDisplayViewerTouchHandler.kt`
- `NodeActionPerformer.kt`

### 验收标准
- accessibility 截图错误返回尽力的平台结果，而非中止当前 turn。
- VD 应用启动失败对调用方可见。
- viewer 重建不会使 display 停留在已失效的 surface 上。

## 第七阶段：规范化清理和低风险维护

### 变更
- 审计临时 node 和 window 的所有权，在有用的地方集中回收辅助工具。
- 为 accessibility debug 截图添加保留上限，与 VD 路径保持一致。
- 删除死代码的私有辅助方法：
  - `AccessibilityGestureInjector.gestureDisplayId()`
  - `NodeActionPerformer.performNodeActionAt()`
- 在行内记录 `DISPLAY_FLAGS` bitmask。
- 如果所改动的代码使其成本较低，也可顺便加固小的清理边界，如异常截图复制路径上的 bitmap 清理。

### 文件
- `AccessibilityPlatform.kt`
- `AccessibilityScreenshotCapturer.kt`
- `VirtualDisplayScreenshotProcessor.kt`
- `AccessibilityGestureInjector.kt`
- `NodeActionPerformer.kt`
- `VirtualDisplayPlatform.kt`

### 验收标准
- 重复使用平台不会导致未回收对象压力增长或 debug 产物无限积累。
- 保留的私有辅助方法是有意为之的。
- 无需逆向工程 bitmask 即可读懂 `DISPLAY_FLAGS`。

## 第八阶段：通过测试锁定成果

### 变更
- 为以下场景添加针对性测试：
  - 有界截图超时行为
  - VD 手势期间的取消
  - binder death 和陈旧 proxy 失效
  - 多窗口条件下的窗口选择
  - 旋转驱动的 resize 和 remap
  - live preview surface 替换
  - 真实的应用启动失败传播
- 为 `setTextOnNode()` 中 append 模式的光标定位添加一个针对性的回归测试。
- 仅在该测试证明当前行为有误时才修改光标逻辑。

### 文件
- `app/src/test/kotlin/com/moonkey/androidagent/platform/` 下的测试
- 框架 fake 不够用时使用 instrumentation 测试

### 验收标准
- 最高风险的边缘情况有回归测试覆盖。
- 光标定位行为基于证据驱动，而非推测。

## 实施顺序
1. 第一阶段和第二阶段同时进行。
   消除最大的挂起和僵尸状态风险。
2. 第三阶段。
   修复中断安全性并清理 IME 时序。
3. 第四阶段和第五阶段。
   修复感知、截图和注入之间的正确性漂移。
4. 第六阶段。
   使平台输出真实化，viewer 行为确定性化。
5. 第七阶段和第八阶段。
   清理剩余债务并防止回归。

## 完成标准
- 没有平台调用可以在框架回调上无限等待。
- 没有 VD session 可以在 binder death 或 stop 之后保持半存活状态。
- 在常见多窗口场景下不存在截图/tree/操作不匹配。
- 旋转不会破坏 VD 几何。
- 启动和输入结果是真实的。
- Claude 保留的低优先级项目要么已实现，要么已被测试明确否定。
