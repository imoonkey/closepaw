# 性能改进计划 — 最终版

基于对齐后的双重设计审查。按影响/工作量比排序。
作者: Claude + Codex

---

## 第一梯队: 快速胜利 (每项 < 1 小时)

### 1. 启用 R8 minification 和资源压缩
- **引用:** RES-4
- **文件:** `app/build.gradle.kts`
- **影响:** HIGH | **工作量:** LOW
- **变更:** 为 release 启用 `isMinifyEnabled = true`、`isShrinkResources = true`。为 kotlinx.serialization、Shizuku AIDL、OpenAI SDK reflection、Leap SDK JNI 添加 keep 规则。
- **预期:** APK 体积减少 20-40%，冷启动更快，内存占用更低。
- **验证:** Release APK 冒烟测试（启动、session、tool 调用、tracing）。对比 APK 大小和方法数。

### 2. 修复历史压缩 O(n^2) token 重算
- **引用:** CPU-4
- **文件:** `history/HistoryManager.kt`
- **影响:** HIGH | **工作量:** LOW
- **变更:** 在第二阶段淘汰过程中维护一个运行中的 token 总数。减去被淘汰项的开销，而非重新扫描完整历史。
- **验证:** 跨 add/remove/compress 循环的 token 估算单元测试。在大型合成历史上 benchmark。

### 3. 修复截断中的 O(n^2) indexOf
- **引用:** CPU-3
- **文件:** `perception/PerceptorInternals.kt`
- **影响:** MEDIUM | **工作量:** LOW
- **变更:** 用基于 HashSet 的去重或预计算 identity map 替换 `candidates.indexOf(c)`。消除选择循环中的所有线性扫描。
- **验证:** 保持当前截断顺序和去重行为的单元测试。在 500-1000 个合成候选上 benchmark。

### 4. 修复 FileTraceRecorder flush() bug 并添加批处理
- **引用:** IO-1, RES-2
- **文件:** `trace/FileTraceRecorder.kt`
- **影响:** MEDIUM | **工作量:** LOW
- **变更:** (a) 使 `WriteOp.Flush` 处理器调用 `writer.flush()`。(b) 停止在每个 `AppendLine` 上 flush。在显式 `Flush` 操作、关闭时以及可选的批量阈值（例如每 10 行或 channel 为空时）执行 flush。
- **验证:** 大量事件压力测试 trace 记录。确认 session 结束时最新行已写入磁盘。

### 5. 用 verbose 标志保护 streaming 累积
- **引用:** MEM-2
- **文件:** `llm/CodexResponseClient.kt`, `OpenAIResponseClient.kt`, `ChatCompletionClient.kt`
- **影响:** LOW | **工作量:** LOW
- **变更:** 暴露 `LlmLogger.isVerboseEnabled`。仅在 verbose logging 启用时分配 `StringBuilder` 和 tool-call buffer。delta 发射保持不变。
- **验证:** streaming 输出不变的单元测试。确认非 verbose 模式下不构建 `ResponsesResult` 对象。

### 6. 为 JPEG 压缩预分配 ByteArrayOutputStream
- **引用:** (仅 Claude 发现)
- **文件:** `platform/BitmapUtils.kt`
- **影响:** LOW | **工作量:** LOW
- **变更:** `ByteArrayOutputStream(bitmap.width * bitmap.height * 4 / 10)`（限制在 1KB-512KB）。消除每次压缩的 10-12 次 buffer 翻倍。
- **验证:** 验证 JPEG 输出完全一致。

---

## 第二梯队: 中等工作量 (每项 1-4 小时)

### 7. 单遍 Perceptor 遍历
- **引用:** CPU-1
- **文件:** `perception/Perceptor.kt`
- **影响:** HIGH | **工作量:** MEDIUM
- **变更:** 将 `INTERACTIVE_ONLY` 和 `ALL` 遍历合并为单遍。在遍历时将每个收集的元素标记为交互或非交互。在 `applyTruncation` 中保留优先级（该函数已处理交互/非交互分离）。
- **验证:** 在代表性 tree 上的 snapshot 一致性测试。对比候选数、交互覆盖率和捕获延迟。

### 8. 增量 screen downgrade 跟踪
- **引用:** CPU-5
- **文件:** `history/HistoryManager.kt`
- **影响:** MEDIUM | **工作量:** MEDIUM
- **变更:** 增量维护 screen-observation 索引，而非每次添加新 screen 时从头重建。避免每次 screen 添加的 O(n) 全历史扫描。
- **验证:** 长 session 中 screen downgrade 行为的单元测试。

### 9. 文本富化优化
- **引用:** CPU-2
- **文件:** `perception/PerceptorInternals.kt`
- **影响:** MEDIUM | **工作量:** MEDIUM
- **变更:** 用遍历期间的父/子传播或一次性构建的低开销空间索引替代完整文本源扫描。为每个源缓存 `mergedText()`。
- **验证:** 嵌套 label/button 富化行为的单元测试。在大型合成 snapshot 上 benchmark。

---

## 第三梯队: 需谨慎处理的变更

### 10. Bitmap 异常安全性
- **引用:** RES-1
- **文件:** `platform/AccessibilityScreenshotCapturer.kt`
- **影响:** LOW | **工作量:** LOW
- **变更:** 用嵌套的 `try/finally` 块包裹 `hardwareBitmap`、`softwareBitmap` 和 `scaledBitmap`，确保任何失败路径上都执行 recycle。
- **验证:** 失败路径测试或围绕 bitmap copy/compress 的故障注入。

### 11. 自适应操作后 retry
- **引用:** IO-2
- **文件:** `tool/action/PostActionAnalysis.kt`
- **影响:** MEDIUM | **工作量:** MEDIUM
- **变更:** 仅对常见延迟过渡的 action 类型进行 retry。在进行完整深度比较前使用低开销的变化信号（window/package/root 数量）。考虑缩短第二次尝试的间隔。
- **验证:** 对已知慢流程进行回归测试。测量优化前后的平均操作后延迟。

### 12. Streaming 取消 hook
- **引用:** RES-3
- **文件:** `llm/CodexResponseClient.kt`, `OpenAIResponseClient.kt`, `ChatCompletionClient.kt`
- **影响:** LOW | **工作量:** MEDIUM
- **变更:** 保存活跃 call/stream 的 handle。在 `awaitClose` 中取消。确保 retry 在取消时停止。
- **验证:** 启动长 stream，取消 collector，确认 socket/线程及时停止。

---

## 实施顺序

1. **R8 minification** — 最高 ROI，影响每个用户
2. **历史压缩 O(n^2)** — 简单修复，对长 session 可度量
3. **截断 indexOf O(n^2)** — 简单修复，帮助密集屏幕
4. **FileTraceRecorder flush bug + 批处理** — 正确性修复 + 性能
5. **Streaming 累积保护** — 微小变更
6. **ByteArrayOutputStream 预分配** — 微小变更
7. **单遍 Perceptor 遍历** — 最大的 CPU 收益，需仔细验证
8. **Screen downgrade 跟踪** — 有助于长 session
9. **文本富化优化** — 产品敏感，需仔细设计
10. **Bitmap 异常安全性** — 正确性加固
11. **自适应操作后 retry** — 需回归测试
12. **Streaming 取消 hook** — 依赖客户端库
