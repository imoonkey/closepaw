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
`AccessibilityScreenshotCapturer` 在等待 `takeScreenshot` / `takeScreenshotOfWindow` 时没有超时。`VirtualDisplayCaptureCoordinator` 在 `PixelCopy.request` 上同样如此。

影响：

- 丢失的框架回调会导致 `captureScreen()` 永久阻塞
- service 死亡或 surface 销毁可能导致一次坏的 turn 卡住整个 session
- 取消安全性不一致，尤其在 PixelCopy 路径上

修复要求：

- 一个共享的有界回调辅助工具
- 每个 callback-to-suspend 桥接都必须有超时
- 取消安全的 resume 检查
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
`VirtualDisplayPlatform` 将活跃状态分散在 `displayId`、`imageReader`、surface mode、callback token 和缓存的 binder proxy 中，但 `start`、`stop`、`captureScreen`、`performAction`、`switchToLivePreview`、`switchToImageReader` 和 binder death 处理之间没有串行化的所有者。

影响：

- `stop()` 可能与截图或操作并发
- surface 切换可能与截图截取并发
- IME 抑制在操作重叠时存在隐式竞态
- 可能出现半启动和半停止状态

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
accessibility 路径按层级升序排序窗口，然后使用第一个 root 来调用 `takeScreenshotOfWindow`，这偏向了最低层而非最顶层窗口。VD 路径选择 application window 时没有显式的层级排序。

影响：

- 截图、tree 和操作目标可能不一致
- dialog、popup 和分屏布局可能导致操作被发送到背景窗口

修复要求：

- 显式的最顶层窗口选择规则
- 一致的截图/root 定位策略
- 当单窗口假设不成立时，回退到全屏截图

### 4. Virtual display 的几何信息在旋转或 display 尺寸变化后变得陈旧
`VirtualDisplayConfig` 在启动时快照一次 app metrics，VD 栈之后一直使用它们。

影响：

- 旋转后坐标映射会漂移
- 截图裁剪和 viewer 触摸缩放变得不正确
- VD 上的应用布局可能与当前设备几何不一致

修复要求：

- 使用真实的 display metrics，而非 app content metrics
- 检测宽度、高度或密度变化
- 在几何变化时重建 VD 和 `ImageReader`

### 5. Accessibility 截图不支持软失败
accessibility 路径不会将 tree dump、trace 或 `Perceptor.snapshot()` 的失败降级为安全的平台级结果。

影响：

- 一个陈旧的 root 或 trace 失败就可能导致 `captureScreen()` 抛出异常
- accessibility 和 VD 路径之间的错误处理不对称

修复要求：

- 限制 debug 工作的开销
- 在平台边界捕获 tree/snapshot 失败
- 返回尽力的空结果或部分 snapshot，而非崩溃当前 turn

### 6. 资源所有权不一致
存在重复的热路径所有权问题：

- 临时 root 和 window 没有被一致地回收
- accessibility debug 截图没有保留上限
- 陈旧的 binder proxy 在 session 生命周期之后仍然存活

影响：

- 重复使用时 binder 压力上升
- debug 模式下磁盘占用无限增长
- 陈旧的 proxy 状态残留到后续 session

修复要求：

- 为临时 node 和 window 审计所有权并添加辅助工具
- 在 shutdown 和 broken 状态处理期间显式清理 proxy
- 对两种截图路径的 debug 产物实施有界保留

### 7. 部分平台调用在可能失败时仍上报成功
`VirtualDisplayAppController` 在 `launchOnDisplay(...)` 之后上报成功，但启动器吞掉了异常并仅做日志记录。

影响：

- 调用方无法区分应用启动失败和后续 UI 失败
- 重试和恢复逻辑得到了错误的输入

修复要求：

- 使启动返回真实的成功/失败结果
- 尽早拒绝无效的 display 状态
- 通过 `ActionResult` 传播失败

## 中优先级发现

### 1. Live preview surface 替换被忽略
一旦 surface controller 已经处于 `LIVE_PREVIEW` 模式，新的 `SurfaceView` 实例就会被忽略。

影响：

- viewer 重建可能使 VD 停留在已失效的 surface 上

修复要求：

- 比较 surface 标识，而不仅仅是 mode

### 2. Viewer shell fallback 可能阻塞调用线程
在没有 hidden display-id 注入功能的设备上，fallback 路径同步执行 shell input 并等待命令完成。

影响：

- 在较旧或退化的设备上，触摸转发可能严重阻塞

修复要求：

- 将 shell fallback 移出调用线程，或重新设计 fallback 路径

### 3. 无效的滚动方向被静默规范化
未知的方向字符串目前降级为向前滚动。

影响：

- 平台边界不应在未至少暴露该情况的前提下，将无效输入重新解释为另一个操作

修复要求：

- 显式验证输入
- 要么快速失败，要么有意识地 log-and-fallback

### 4. Append 模式的光标定位需要先验证再修改
存在一个合理的担忧：`NodeActionPerformer.setTextOnNode()` 可能在 `ACTION_SET_TEXT` 之后基于陈旧的 node 状态计算光标位置。这值得测试，但尚未被充分证实到可以作为已确认 bug 对待的程度。

修复要求：

- 为 append 模式的光标定位添加一个针对性的回归测试
- 仅在测试证明当前行为有误时才修改光标逻辑

## 低优先级发现

### 1. 应删除死代码的私有辅助方法
低风险清理：

- `AccessibilityGestureInjector.gestureDisplayId()`
- `NodeActionPerformer.performNodeActionAt()`

### 2. `DISPLAY_FLAGS` 需要文档
`VirtualDisplayPlatform` 中的原始 bitmask 不透明，应在行内添加文档说明。

## 非发现项与刻意不变更的项目
以下不是当前的健壮性问题，不应驱动重构：

- 保留 `ShizukuClient` 作为 facade 边界
- 保留 VD 栈中用于避免循环依赖的 lambda provider
- 暂不提取共享的 accessibility 截图抽象
- 没有失败测试之前不修改文本光标语义

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
以 Codex 设计为基础。将 Claude 的有效补充作为后续项目推进：

- 死代码移除
- `DISPLAY_FLAGS` 文档
- 基于测试验证的 append 模式光标定位审查

建议：CHANGES_REQUESTED。
