# LLM Integration 模块 -- 最终评审

**来源:** 双重设计评审 (Claude + Codex)，交叉审查、对齐并验证。
**日期:** 2026-04-08（2026-04-10 验证）
**状态:** 已验证
**基础设计:** CODEX（包含 CLAUDE 加固补充）
**验证结果:** 13 项确认为真实问题，2 项作为误报移除，采纳了更 KISS 的替代方案

---

## 总结

`llm/` 模块可以正常工作，但在最高风险区域存在正确性缺陷：streaming 完成语义和 retry 语义。整体架构接近成熟——model catalog 简洁、factory 模式合理、thread safety 正确——但 client 分类层级过于扁平（四个平级 client 而非三个 transport 族），且失败路径较为脆弱。

当前首要任务是修复 streaming 正确性。之后，应通过提取共享 Responses helpers 来简化模块，并使 capability 差异显式化。

---

## 架构评估

**做得好的方面：**
- `ModelCatalog` 简洁、不可变、可扩展（A 级）
- `LLMClientFactory` 使 provider/base-url/api 选择逻辑远离调用方
- `LLMClient` 为调用方提供统一的 streaming 和非 streaming 调用表面
- 全局 thread safety 正确（ConcurrentHashMap、Mutex、volatile）
- `LlmLogger` 正确地由 `BuildConfig.DEBUG` 控制

**需要修复的方面：**
- Streaming 完成语义分散在四个 client、一个 retry runner、一个 retry policy、一个 SSE parser 和一个 classifier 中——"什么算成功/失败/retry"的判定散落在太多地方
- Request 标准化在三处重复（`ChatCompletionInterop`、`CodexRequestBuilder`、`LFMLLMClient`/`LeapFunctionInterop`）
- Error classification 依赖于脆弱的字符串匹配，未保留已有类型化异常

---

## 按优先级排列的发现

### P0: Streaming/Retry 正确性

**1. streamWithRetry 中域异常的保留** (HIGH)
- **文件:** `CloudStreamRetryRunner.kt:50-61`, `OpenAIErrorClassifier.kt:11-52`
- `streamWithRetry()` 将所有捕获的异常都通过 `OpenAIErrorClassifier` 重新分类，即使异常已经是域级别的 `RateLimitException` 或 `TransientException`。classifier 不保留这些类型，因此 Codex 的 `handleErrorResponse()` 产生的 `RateLimitException` 可能被降级为普通 `RuntimeException`，从而静默禁用 retry。
- 这是所有其他 retry 修复的**前置条件**。

**2. `Created` 事件过早阻止 retry** (HIGH)
- **文件:** `CloudStreamRetryRunner.kt:33-41`, `CloudStreamRetryPolicy.kt:22-29`
- `streamWithRetry()` 对所有事件（包括 `Created`）都将 `emittedEvent` 翻转为 true。retry policy 在任何事件发出后都拒绝 retry。一个连接成功、发出 `Created` 后在任何文本/tool-call 内容之前失败的 stream 将不会 retry，即使 retry 是安全的（不会产生用户可见的重复）。
- 修复：区分元数据事件（`Created`）和不可逆输出事件（`TextDelta`、`ToolCallDone`）。

**3. `response.incomplete` 被当作成功处理** (HIGH)
- **文件:** `CodexResponseClient.kt:96-98`, `CodexSseParser.kt:95-97`
- Codex 的 streaming 和非 streaming 路径都将 `response.incomplete` 映射为 `Completed`。一个明确不完整的响应不应该以成功状态呈现。
- 修复：直接在 `response.incomplete` 分支处报错返回 `Failed`，无需额外的 partial-success 状态机。

**4. ChatCompletionClient 缺少终止完成检查** (HIGH)
- **文件:** `ChatCompletionClient.kt:209-218`
- 当 SDK stream 循环正常结束时发出 `Completed`，但未跟踪是否出现过终止 `finishReason`。过早关闭的 stream 会产生一个看起来完整但实际不完整的响应。
- 修复：跟踪 `sawFinishReason` 标志。如果 stream 结束时未出现该标志，抛出 `TransientException`。

**5. Stream 无完成事件结束应为 TransientException** (HIGH)
- **文件:** `OpenAIResponseClient.kt:155`, `CodexResponseClient.kt:178`
- 抛出 `RuntimeException("Stream ended without completion event")` 而非 `TransientException`。这阻止了对最常见 transient 故障的 retry：连接在任何事件之前断开。

### P1: Error Classification、安全性、Cancellation

**6. Error classification 脆弱且跨 transport 泄漏** (MEDIUM)
- **文件:** `OpenAIErrorClassifier.kt`, `CodexResponseClient.kt:242-260`
- Codex 的 `handleErrorResponse()` 已经产生类型化异常——应当保留它们（由修复 #1 完成）。对于 OpenAI SDK 错误，在字符串 fallback 之前添加类型化异常分支。
- 不要构建独立的 transport-classifier 抽象。保留一个 `OpenAIErrorClassifier`，添加域异常和类型化 SDK 异常的 fast-path 即可。这就足够 KISS。

**7. InsecureSslConfig 接受所有证书** (MEDIUM)
- **文件:** `InsecureSslConfig.kt:36-40`
- 注释写的是"跳过证书日期验证"，但实现信任所有服务器证书。Debug 构建携带真实的 API key 和 OAuth token。
- 修复：限制在比 `BuildConfig.DEBUG` 更窄的专用 eval-only 标志之后。后续可选择仅放宽日期验证。

**8. Cancellation 感知的 streaming** (MEDIUM)
- **文件:** `CodexResponseClient.kt`, `CodexSseParser.kt`
- `CodexSseParser.parse()` 中的阻塞读取不感知 cancellation。已取消的 flow 可能挂起直到 HTTP 读取超时（120 秒）。
- 修复：在 `CodexResponseClient` 中存储 OkHttp `Call` 引用，从 `awaitClose` 中取消。这是主要修复——`ensureActive()` 仅在阻塞读取返回后循环重新获得控制时才起作用。

### P2: 架构

**9. Codex 和 OpenAI Responses client 共享重复逻辑** (STRUCTURAL)
- `CodexResponseClient` 是 Responses 族的一个 transport/auth 变体，而非真正不同的协议。Stream 累积、完成检查和 retry epilogue 存在重复。
- 修复：先提取共享 Responses helpers（request/result 累积、完成检查、retry epilogue 处理）。仅在提取 helpers 后仍有明显重复时才合并为单一 transport 类。不要预先过度工程化 strategy 模式。

**10. Local capability 损失是隐式的** (STRUCTURAL)
- `LFMLLMClient` 丢弃非 user/non-assistant 角色、通过无类型 `Any` helper 平坦化内容、生成随机 tool call ID、在没有 call-ID 关联的情况下回放 tool 输出。
- 修复：添加一个窄的 `LocalLlmSemantics` 对象，声明应用其他部分需要推理的特定局限性。在有第二个消费者之前不要构建宽泛的通用 `LlmCapabilities` 框架。

### P3: 去重

**11. 共享 ToolParameterExtractor** -- 从 `CodexRequestBuilder.convertToolParameters()` 和 `LeapToolSchemaAdapter.parseToolParameters()` 中提取（约 30 行重复）。一个返回 `JSONObject?` 的 helper，调用方决定 fallback。

**12. 共享 post-retry flow handler** -- 将三个 streaming client 中重复的 10 行 post-retry 清理代码块提取到 `CloudStreamRetryRunner.handleRetryResult()` 中。可能被 Responses helper 提取（#9）所涵盖。

### P0（Bug）: 内容提取

**13. MessageContentExtractor 向 Leap 输入垃圾数据** (HIGH)
- **文件:** `MessageContentExtractor.kt`, `LFMLLMClient.kt:299`, `LlmLogger.kt:33`, `LlmInputItemsTraceSerializer.kt:25`
- `MessageContentExtractor.extractMessageContent(Any)` 接收 `EasyInputMessage.Content` 但 fallthrough 到 `toString()`，将 `Content{textInput=...}` 这样的包装字符串而非实际文本输入到 Leap。这是一个**功能性 bug**，不仅仅是去重问题。
- 修复：创建类型化的 `extractStringContent(content: EasyInputMessage.Content): String` 工具函数。更新全部四个调用点。删除 `MessageContentExtractor.kt`。

### 已移除（验证前评审中的误报）

**~~JsonValueConverter 提取~~** -- Codex 和 Leap 实现仅有部分重叠（Codex 保留 `JSONObject.NULL`，Leap 返回 Kotlin `null` 并重新解析原始 JSON 字符串）。不是干净的共享工具。

**~~内部规范 request 模型~~** -- 没有当前缺陷证明其必要性。完全推迟；仅在所有具体修复落地后再评估。

---

## 评分卡

| 领域 | 评级 | 备注 |
|------|------|------|
| 架构 | B+ | 抽象清晰，层级偏平一层 |
| Streaming 正确性 | B+ | P0 bug 已修复：域异常保留、retry 策略、incomplete 处理、finishReason 检查 |
| Error Handling | B+ | 类型化 SDK 异常优先，域异常保留，字符串匹配 fallback 最后 |
| Retry 逻辑 | B+ | 域异常保留，元数据与输出区分 |
| Thread Safety | A | 全局正确 |
| 代码重复 | B | Retry epilogue 通过 closeFlow() 共享；streaming 循环有意保持独立 |
| Cancellation | B+ | Codex stream 通过 OkHttp Call 在 flow 关闭时取消；SDK client 尚未覆盖 |
| 测试覆盖 | B | 63 测试；3 个已知 bug 剩余（classifier 误报，Phase 3） |
| Config/Catalog | A | 简洁、不可变、可扩展 |
| 安全性 | B+ | Debug SSL 限制在 INSECURE_SSL_FOR_EVAL 构建标志之后 |
