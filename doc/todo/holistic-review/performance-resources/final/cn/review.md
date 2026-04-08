# 性能与资源效率审查 — 最终版

范围: `app/src/main/kotlin/com/moonkey/androidagent/`
日期: 2026-04-08
作者: Claude + Codex (双重设计对齐)

---

## CPU

### CPU-1: Perceptor 每次捕获对 accessibility tree 遍历两次
- **文件:** `perception/Perceptor.kt:69-94`
- **影响:** HIGH
- **描述:** `snapshot()` 对每个 root 先以 `INTERACTIVE_ONLY` 模式遍历一次，再以 `ALL` 模式遍历一次，导致 tree walking、node 属性读取、bounds 提取、dedup-key 构造和子节点检索全部翻倍，发生在最热的捕获路径上。
- **依据:** 两个完整的 `for (root in roots)` 循环，各自调用 `traverse(...)`。`traverse()` 每个 node 读取 text/description/hint/resource ID、action 支持、bounds、可见比率和 class name。

### CPU-2: 文本富化在候选数上呈二次复杂度
- **文件:** `perception/PerceptorInternals.kt:23-46`
- **影响:** MEDIUM
- **描述:** `enrichEmptyTextElements()` 为每个缺失文本的交互元素扫描所有文本源。在 `maxElements * 2` 上限下，可能产生数十万次包含性检查以及反复的 `mergedText()` 调用。
- **依据:** `candidates.map { ... textSources.asSequence().filter { contains(...) } ... }`。受候选上限约束，但在该上限内为 O(n^2)。

### CPU-3: 截断过程中进行重复线性搜索
- **文件:** `perception/PerceptorInternals.kt:58-77`
- **影响:** MEDIUM
- **描述:** `applyTruncation()` 在交互循环、非交互循环和兜底循环中调用 `candidates.indexOf(c)`。每次 `indexOf` 都是 O(n) 线性扫描，且使用了开销较大的 data-class `equals()`。
- **依据:** 三个选择分支中均调用了 `indexOf()`。

### CPU-4: 历史压缩反复重算 token 预算
- **文件:** `history/HistoryManager.kt:131-136, 188-240`
- **影响:** HIGH
- **描述:** 淘汰循环在每次迭代中检查 `estimateTokenCount()`，但每次 `removeAt()` 都会设置 `lastTokenEstimate = null`，迫使执行一次完整的 `items.sumOf { it.estimateTokens() }` 重新扫描。压缩开销随历史大小呈二次增长。
- **依据:** `estimateTokenCount()` 仅在未发生变更时缓存。在 `compress()` 中，每条移除路径都会使缓存失效。

### CPU-5: screen downgrade 在每个新 screen 上重新扫描完整历史
- **文件:** `history/HistoryManager.kt:45-59, 67-84, 326-343`
- **影响:** MEDIUM
- **描述:** 添加新的 screen observation 会触发 `downgradeOldScreens()`，该函数重建完整的 screen 索引列表，然后重新访问所有旧 screen。工作量随会话长度增长。
- **依据:** `downgradeOldScreens()` 对整个历史执行 `items.withIndex().filter(...).map(...)`，且每次记录 `SCREEN_OBSERVATION` 时都会调用。

---

## 内存

### MEM-1: 截图捕获峰值占用多个全帧分配
- **文件:** `platform/AccessibilityScreenshotCapturer.kt:127-180`
- **影响:** MEDIUM
- **描述:** 截图路径创建一个 hardware bitmap，复制为完整 ARGB_8888，可能再分配一个缩放后的 bitmap，然后生成 JPEG 字节。在 1080x2340 显示设备上，峰值约为每次捕获 12-15MB。
- **依据:** `wrapHardwareBuffer` → `.copy(ARGB_8888)` → `scaleBitmapIfNeeded` → `compressJpeg`。仅在正常路径上执行 recycle。

### MEM-2: streaming 客户端为未使用的 debug 日志累积响应
- **文件:** `llm/CodexResponseClient.kt:151-169`, `OpenAIResponseClient.kt:95-142`, `ChatCompletionClient.kt:122-218`
- **影响:** LOW
- **描述:** 所有 streaming 客户端构建 `StringBuilder` 累加器和 tool-call 列表，仅用于 `LlmLogger.logOutput()`，而该方法在 release 构建中立即返回 (`VERBOSE_LOGGING = BuildConfig.DEBUG`)。
- **依据:** 累加器无条件构建；`LlmLogger.logOutput()` 在禁用时立即退出。

---

## 电池 / IO

### IO-1: Trace 记录在每个事件上都执行 flush
- **文件:** `trace/FileTraceRecorder.kt:128-132`
- **影响:** MEDIUM
- **描述:** `AppendLine` 写入一行后立即 flush `BufferedWriter`，破坏了批处理，增加了 trace 运行期间的写放大。
- **依据:** 每个 `WriteOp.AppendLine` 路径执行 `writer.append(...)`、`writer.newLine()`、`writer.flush()`。

### IO-2: 操作后验证每次 action 最多触发三次完整捕获
- **文件:** `tool/action/PostActionAnalysis.kt:22-40, 81-90`
- **影响:** MEDIUM
- **描述:** 当首次操作后捕获看起来未变化时，代码在 500ms 和 1000ms 后分别重试。每次重试调用 `platform.captureScreen()`（perception + 可选截图）。每次 action 的总预算为 1800ms。
- **依据:** 始终执行初始捕获，若结果为 `Unchanged` 则重试两次。这是为检测慢过渡而有意设计的。

---

## 资源管理

### RES-1: Bitmap 清理不具有异常安全性
- **文件:** `platform/AccessibilityScreenshotCapturer.kt:129-155, 184-186`
- **影响:** LOW
- **描述:** 仅 `HardwareBuffer` 在 `finally` 中关闭。hardware bitmap、software bitmap 和 scaled bitmap 仅在正常路径上执行 recycle。分配后发生的异常会将大块 native 内存留给 GC 处理。
- **依据:** `hardwareBuffer.close()` 是唯一的 `finally` 清理。所有 `recycle()` 调用都在成功路径中。

### RES-2: `flush()` 实际上并未 flush writer
- **文件:** `trace/FileTraceRecorder.kt:135, 175-179`
- **影响:** MEDIUM
- **描述:** 公开的 `flush()` API 通过 channel 发送 `WriteOp.Flush`，但处理器仅完成 deferred 而未调用 `writer.flush()`。期望持久化的调用者实际获得的只是一个队列屏障。
- **依据:** `WriteOp.Flush` 分支仅执行 `op.done.complete(Unit)`。没有 `writer.flush()`。

### RES-3: streaming flow 没有显式取消机制
- **文件:** `llm/CodexResponseClient.kt:135-196`, `OpenAIResponseClient.kt:86-175`, `ChatCompletionClient.kt:113-231`
- **影响:** LOW
- **描述:** `awaitClose` 处理器仅记录日志。没有存储活跃的 call/stream handle 用于取消。放弃 flow 无法主动停止进行中的网络读取或 retry 循环。
- **依据:** `awaitClose` 块中仅包含日志记录。

### RES-4: Release 构建禁用了代码和资源压缩
- **文件:** `app/build.gradle.kts:24-27`
- **影响:** HIGH
- **描述:** `isMinifyEnabled = false` 导致 OpenAI SDK、OkHttp、Compose、Leap SDK 等的死代码留在 APK 中。没有 R8 tree shaking、没有优化、没有资源压缩。预计 APK 膨胀 20-40%。
- **依据:** Release build type 仅包含 `isMinifyEnabled = false`。

---

## 附录: 已确认正确的模式

以下区域已审查，实现正确，无需更改。

- **Bitmap 生命周期:** 所有 bitmap 路径正确 recycle 中间产物。`if (scaledBitmap !== softwareBitmap)` 防止双重 recycle。`UiChangeDetector.decodeToGrayscale8x8` 使用 `try/finally`。
- **Session 清理:** `SessionServices.cleanup()` 正确排序资源回收。`AgentSession.handleShutdown()` 是幂等的。
- **Session 空闲超时:** 5 分钟自动关闭防止遗忘会话的资源泄漏。
- **SessionRecordingService:** 500ms 防抖保存防止过多的磁盘 I/O。
- **FileTraceRecorder channel:** `trySend` 满时丢弃对于诊断追踪是正确的。
- **LlmLogger 开关:** `VERBOSE_LOGGING = BuildConfig.DEBUG` 在 release 中屏蔽所有日志。
- **Token 估算:** `0.25f * content.length` 高效且保守。
- **Compose overlay:** `DisposeOnDetachedFromWindow` 确保正确的 composition 清理。

## 附录: 不建议的优化（过早优化）

- 在 Perceptor 中池化 Rect/Point 对象 — JVM 逃逸分析已处理这些。
- 用手动字符串拼接替换 JSONObject — 可读性优先于边际性能提升。
- 缓存 AccessibilityNodeInfo 字段 — 已有 Binder 缓存，增加了过期数据风险。
