# 平台健壮性审查

## 范围
审查了整个 `platform/` 模块：

- `app/src/main/kotlin/com/moonkey/androidagent/platform/`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/`

关注点：

- accessibility service 死亡与陈旧 tree 处理
- virtual display 生命周期、Shizuku binder 断连，以及 surface/display 抖动
- 在 dialog、popup 和旋转条件下的 node/window 正确性
- 资源所有权与清理
- 竞态条件、有界故障行为，以及结果上报的真实性

## 总结
该模块的分解是合理的。`AndroidPlatform` 是一个清晰的边界，共享逻辑在该提取的地方做了提取，Shizuku facade 加 lambda provider 的设计选择也可以接受。它们不是主要问题。

真正的问题在于平台边界处的健壮性。accessibility 和 virtual display 的实现都假设框架会持续回调、对象会保持足够长的有效期、生命周期边界会被调用方串行化。这过于乐观了。在 service 死亡、binder 死亡、旋转、live preview 抖动或取消等情况下，平台可能会挂起、状态漂移、泄漏资源，或者在底层操作已经失败的情况下仍上报成功。

## 严重发现

### 1. 基于回调的截图路径没有超时限制
`AccessibilityScreenshotCapturer` 在等待 `takeScreenshot` / `takeScreenshotOfWindow` 时没有超时。`VirtualDisplayCaptureCoordinator` 在 `PixelCopy.request` 上同样如此。PixelCopy 路径也没有为预分配的 `Bitmap` 注册 `invokeOnCancellation` 清理，因此取消会泄漏 bitmap，尽管不会崩溃（coroutines 1.7.3 对已取消 continuation 上的延迟 resume 会静默丢弃）。

影响：

- 丢失的框架回调会导致 `captureScreen()` 永久阻塞
- 没有调用方（`AgentTurnRunner`、`TurnExecutionPhaseRunner`、`ToolRouter`）将平台截图包装在超时中，因此一个卡住的回调会阻塞整个 agent turn
- 即使不崩溃，取消仍会泄漏资源

修复要求：

- 一个共享的有界回调辅助工具，带有 `invokeOnCancellation` 清理
- 每个 callback-to-suspend 桥接都必须有超时
- 当回调始终不到达时，提供确定性的 fallback 或失败结果

### 2. Virtual display 手势不支持取消安全
`VirtualDisplayInputInjector` 注入 DOWN，挂起，之后注入 UP 来执行长按和滑动。如果协程在手势中途被取消，没有尽力清理事件。

影响：

- 目标应用可能处于卡在按下或拖动的状态
- 部分 MOVE 失败被忽略，因此手势在代码上报成功之前可能已经损坏

修复要求：

- 追踪 DOWN 是否已发送
- 在任何关键的中途注入失败时标记手势失败
- 在 `finally` 中发送尽力的 `ACTION_CANCEL` 或 `ACTION_UP`

## 高优先级发现

### 1. Virtual display 栈缺少统一的生命周期所有者
`VirtualDisplayPlatform` 将活跃状态分散在 `displayId`、`imageReader`、surface mode、callback token 和缓存的 binder proxy 中，但 `start`、`stop`、`captureScreen`、`performAction`、`switchToLivePreview`、`switchToImageReader` 和 binder death 处理之间没有串行化的所有者。`stop()` 的代码注释已经记录了 "Not safe to call concurrently with captureScreen/performAction" -- 这是一个已知的调用方约定，但没有任何机制来强制执行。

影响：

- `stop()` 可能与截图或操作并发，且没有守卫防止
- surface 切换可能与截图截取并发
- IME 抑制在操作重叠时存在隐式竞态
- 可能出现半启动和半停止状态
- `start()` 没有回滚：VD 创建之后如果发生取消/异常，`displayId` 和 `imageReader` 已被赋值，而 session 认为启动失败；下一次 `start()` 因 `displayId` 已经设置而 no-op

修复要求：

- 一个串行化的生命周期所有者
- 用显式状态替代松散关联的 volatile 变量
- 不允许任何公开操作观察到半启动状态

### 2. Shizuku binder 死亡没有作为生命周期转换处理
binder death listener 只做了日志记录。缓存的 Shizuku proxy 在 stop 或 binder death 时从未被清除，尽管 `ShizukuClient.clearCachedProxies()` 正是为此而存在的。

影响：

- 死掉的 binder wrapper 在 Shizuku 重启后仍可能存活
- 后续失败会发生在反射和传输层深处，而不是在平台边界
- session 无法区分可恢复的平台丢失和随机操作失败

修复要求：

- 在 binder death 时转换到 broken 状态
- 清除缓存的 proxy
- 释放本地 VD 资源
- 使后续调用以明确的原因 fail closed

### 3. 多窗口条件下的窗口选择是错误的
accessibility 路径按层级升序排序窗口（`.sortedBy { it.layer }`），然后使用 `roots.firstOrNull()?.windowId` 来调用 `takeScreenshotOfWindow`，这选择了最低层（底部）窗口而非最顶层。同时，`captureAccessibilityTree()` 从所有非 overlay/非 IME 窗口收集全部 root，但操作使用 `service.rootInActiveWindow`（单个 root），`getCurrentPackageName()` 也使用 `rootInActiveWindow`。这意味着截图、操作和隐私门控各自使用了不同的窗口/root 策略。在 VD 侧，`VirtualDisplayWindowAccessor.getRootOnDisplay()` 选择第一个 `TYPE_APPLICATION` 窗口但没有层级排序 -- 这个单 root 访问器被 `NodeActionPerformer` 用于操作定位，也被 `getCurrentPackageName()` 用于前台包名检测和隐私门控。

影响：

- 当存在 dialog 或 popup 时，accessibility 截图针对的是背景窗口（仅 Android U+ 受影响）
- accessibility tree 包含所有窗口的 root，但操作和隐私门控仅使用 `rootInActiveWindow` -- 一个被阻止的应用在被允许的 dialog 后面时可能通过包名检查，同时其背景 node 会泄漏到 tree 中
- VD node 操作在 dialog/popup 下可能针对错误的窗口
- `getCurrentPackageName()` 可能返回错误的包名，影响 `captureScreen()` 中的隐私门控

修复要求：

- 显式的最顶层窗口选择规则
- 一致的截图/root 定位策略
- 当单窗口假设不成立时，回退到全屏截图

### 4. Accessibility 截图不支持软失败
accessibility 路径不会将 tree dump、trace 或 `Perceptor.snapshot()` 的失败降级为安全的平台级结果。VD 路径将 `Perceptor.snapshot()` 包装在 try/catch 中，失败时返回空结果；accessibility 路径没有这样做。

影响：

- 一个陈旧的 root 或 trace 失败就可能导致 `captureScreen()` 抛出异常
- accessibility 和 VD 路径之间的错误处理不对称

修复要求：

- 限制 debug 工作的开销
- 在平台边界捕获 tree/snapshot 失败
- 返回尽力的空结果或部分 snapshot，而非崩溃当前 turn

### 5. 部分平台调用在可能失败时仍上报成功
`VirtualDisplayAppController` 在 `launchOnDisplay(...)` 之后上报成功，但 `ShizukuActivityLauncher.launchOnDisplay()` 捕获所有异常并仅做日志记录。`ShizukuClient.launchOnDisplay()` 返回 `Unit`，因此 controller 无条件返回 `ActionResult.Success`。

影响：

- 调用方无法区分应用启动失败和后续 UI 失败
- 重试和恢复逻辑得到了错误的输入

修复要求：

- 让启动异常传播以便外层 catch 处理
- 尽早拒绝无效的 display 状态
- 通过 `ActionResult` 传播失败

## 中优先级发现

### 1. Virtual display 的几何信息在旋转或 display 尺寸变化后变得陈旧
`VirtualDisplayConfig` 在启动时通过 `context.resources.displayMetrics` 快照一次 app 内容区域 metrics，VD 栈之后一直使用它们。注意：VD 是一个自包含的坐标空间，因此物理屏幕旋转时 VD 内的 agent 操作不会漂移。实际影响仅限于 viewer UX 退化和初始尺寸使用内容区域 metrics 而非真实 display metrics（`WindowManager.maximumWindowMetrics.bounds`）。

影响：

- 旋转后 viewer 触摸缩放变得不正确
- VD 从一开始就可能略小于物理 display
- VD 上的应用布局可能与 viewer 中的当前设备几何不一致

修复要求：

- 通过 `WindowManager` 获取真实的 display metrics，而非 app content metrics
- 检测宽度、高度或密度变化
- 在几何变化时重建 VD 和 `ImageReader`

### 2. 资源所有权存在特定缺口
代码库中的资源回收总体上是一致的，但在特定热路径上存在缺口：

- `AccessibilityPlatform.getCurrentPackageName()` 获取 `rootInActiveWindow` 但从未回收 -- 每次 `captureScreen()` turn 都会调用
- `VirtualDisplayPlatform.isKeyboardVisibleOnMainDisplay()` 获取 `AccessibilityWindowInfo` 对象但从未回收 -- 在 `performAction()` 中许多 IME 敏感操作之前都会调用
- accessibility debug 截图没有保留上限（VD 路径上限为 20）
- `ShizukuClient.clearCachedProxies()` 存在但从未被调用 -- 它是死代码

影响：

- 两处热路径泄漏在每个 turn 上都增加 binder 压力
- debug 模式下 accessibility 路径的磁盘占用无限增长
- 陈旧的 proxy 状态可能残留到 session 生命周期之后

修复要求：

- 在 `getCurrentPackageName()` 中回收 root node
- 在 `isKeyboardVisibleOnMainDisplay()` 中回收 window 对象
- 为 accessibility 路径添加有界的 debug 截图保留
- 在 shutdown 和 broken 状态处理期间调用 `clearCachedProxies()`

### 3. VD accessibility 截图吞掉了协程取消
`VirtualDisplayCaptureCoordinator.captureA11yTreeWithArtifacts()` 捕获 `Exception` 并将其转换为空结果。在 Kotlin 中，`CancellationException` 是 `Exception` 的子类，因此取消也会被吞掉。这不会导致平台挂起或破坏生命周期状态，但会将取消变成一个误导性的空截图结果，并可能延迟干净 shutdown。

修复要求：

- 重新抛出 `CancellationException`，而非吞掉它

### 4. VD accessibility 截图在 Dispatchers.Main 上运行重量级工作
`VirtualDisplayCaptureCoordinator.captureA11yTreeWithArtifacts()` 将整个流程包装在 `withContext(Dispatchers.Main)` 中，包括 `Perceptor.snapshot()` 和 `Perceptor.toPromptJson(snapshot)`。accessibility 路径将 Main 线程上的工作限制在 display/window 收集，并在 Main 块外执行感知工作。这违反了项目的 main-safe 规则。

影响：

- 大型 tree 的感知和 sanitized tree 序列化可能阻塞 service/viewer 主线程
- 增加了 VD 模式下出现卡顿或延迟框架回调的概率

修复要求：

- 将感知和序列化工作移出 `Dispatchers.Main`

### 5. Viewer shell fallback 可能阻塞调用线程
在没有 hidden display-id 注入功能的设备上，fallback 路径通过 `ShizukuShellExecutor.waitForProcess()` 同步执行 shell input，使用 `Thread.sleep` 轮询循环阻塞最长 30 秒。

影响：

- 在较旧或退化的设备上，触摸转发可能冻结 UI 线程

修复要求：

- 将 shell fallback 移出调用线程，或重新设计 fallback 路径

## 低优先级发现

### 1. Live preview surface 替换被忽略
一旦 surface controller 已经处于 `LIVE_PREVIEW` 模式，新的 `SurfaceView` 实例就会被忽略。正常的 viewer 生命周期会在 `surfaceDestroyed` / `onStop` 时调用 `notifyViewerHidden()`，将模式切回 `IMAGE_READER`，因此触发此问题需要异常的回调排序或此前切回失败。

### 2. 应删除死代码的私有辅助方法
低风险清理：

- `AccessibilityGestureInjector.gestureDisplayId()`
- `NodeActionPerformer.performNodeActionAt()`

### 3. `DISPLAY_FLAGS` 需要文档
`VirtualDisplayPlatform` 中的原始 bitmask（6 个十六进制 flag，含 hidden API）不透明，应在行内添加文档说明。不是健壮性 bug，仅影响可读性。

### 4. 无效的滚动方向被静默规范化
未知的方向字符串降级为向前滚动。生产环境的 tool 输入已通过 `MobileActionTool` schema enum 验证方向，因此仅影响直接/debug 构造路径。实际风险较低。

## 已验证的非问题

以下问题经调查确认不是 bug：

- **`setTextOnNode()` 中 append 模式的光标定位**：代码有意从 `ACTION_SET_TEXT` 之前的 snapshot 状态计算光标位置。`combined` 字符串和 selection offset 都来自相同的操作前值，这是一致的。独立审计验证了这是正确行为，而非陈旧状态。
- **`AccessibilityNodeFinder` 中的 node 回收**：DFS 模式在整个文件中都是正确的。
- **`collectRootsOnActiveDisplay()` 中的 root/window 生命周期**：回收 window 不会使其 root node 失效。
- **`OverlayTouchGate` 超时**：调用方（`AccessibilityGestureInjector`）在 try/finally 中正确处理了 close。

## 非发现项与刻意不变更的项目
以下不是当前的健壮性问题，不应驱动重构：

- 保留 `ShizukuClient` 作为 facade 边界
- 保留 VD 栈中用于避免循环依赖的 lambda provider
- 暂不提取共享的 accessibility 截图抽象

## 目标健壮性设计

### 平台边界规则
- 每个基于回调的框架 API 都必须有时间限制。
- 每个临时 Android 对象都必须有明确的所有权。
- 每个公开的 VD 操作都必须通过一个生命周期仲裁者：生命周期转换是排他的，操作调用在协调的 `Running` lease 下运行。
- binder death、service death 和几何变化必须是一等公民的状态转换，而非附带的日志行。

### Virtual display 生命周期状态机

#### 状态
- `Stopped`
- `Starting`
- `Running(image_reader)`
- `Running(live_preview)`
- `Broken`
- `Stopping`

#### 转换
- `Stopped -> Starting`
  副作用：创建 `ImageReader`，创建 virtual display，注册 binder death listener，初始化 surface mode。
- `Starting -> Running(image_reader)`
  守卫条件：所有启动步骤成功。
- `Starting -> Stopped`
  守卫条件：启动失败。
  副作用：关闭 reader，清除 callback，按需清除缓存的 proxy。
- `Running(*) -> Running(live_preview)`
  守卫条件：存在新的有效 preview surface。
- `Running(*) -> Running(image_reader)`
  守卫条件：preview 隐藏或 pixel copy fallback 需要。
- `Running(*) -> Broken`
  守卫条件：binder death 或不可恢复的平台丢失。
  副作用：使公开状态失效，清除 proxy，释放本地资源，使后续调用 fail closed。
- `Running(*) -> Stopping -> Stopped`
  副作用：对 listener、surface、display、reader、IME 状态和 proxy 进行幂等清理。

### 设计目标
将边缘情况转化为标准情况：

- 用一个 broken 状态替代多个部分失败状态
- 用一个有界回调适配器替代特定的 suspension
- 每类受管资源使用一条清理路径
- 用一个生命周期仲裁者替代分散的状态检查

## 建议

建议：CHANGES_REQUESTED。
