# LLM Integration 模块 -- 最终评审

**来源:** 双重设计评审 (Claude + Codex)，交叉审查并对齐。
**日期:** 2026-04-08
**状态:** 已批准
**基础设计:** CODEX（包含 CLAUDE 加固补充）

---

## 总结

`llm/` 模块可以正常工作，但在最高风险区域存在正确性缺陷：streaming 完成语义和 retry 语义。整体架构接近成熟——model catalog 简洁、factory 模式合理、thread safety 正确——但 client 分类层级过于扁平（四个平级 client 而非三个 transport 族），且失败路径较为脆弱。

当前首要任务是修复 streaming 正确性。之后，应通过将 Codex client 合并到 Responses transport 族中来简化模块，并使 capability 差异显式化。

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
- 修复：映射为 `Failed` 并附带后端提供的原因。记录部分文本但不标记该轮为成功。

**4. ChatCompletionClient 缺少终止完成检查** (HIGH)
- **文件:** `ChatCompletionClient.kt:209-218`
- 当 SDK stream 循环正常结束时发出 `Completed`，但未跟踪是否出现过终止 `finishReason`。过早关闭的 stream 会产生一个看起来完整但实际不完整的响应。
- 修复：跟踪 `sawFinishReason` 标志。如果 stream 结束时未出现该标志，抛出 `TransientException`。

**5. Stream 无完成事件结束应为 TransientException** (HIGH)
- **文件:** `OpenAIResponseClient.kt:155`, `CodexResponseClient.kt:178`
- 抛出 `RuntimeException("Stream ended without completion event")` 而非 `TransientException`。这阻止了对最常见 transient 故障的 retry：连接在任何事件之前断开。

### P1: Error Classification、安全性、Cancellation

**6. Transport 自有的 error classification** (MEDIUM)
- **文件:** `OpenAIErrorClassifier.kt`, `CodexResponseClient.kt:242-260`
- 停止将 Codex 错误路由到 `OpenAIErrorClassifier` 的字符串匹配。Codex 的 `handleErrorResponse()` 已经产生类型化异常——应当保留它们。对于 OpenAI SDK 错误，先检查类型化异常类（`RateLimitException`、`InternalServerException`），再 fallback 到字符串匹配。
- 当前字符串匹配存在误报风险："14291" 匹配 "429"，"5002" 匹配 "500"。

**7. InsecureSslConfig 接受所有证书** (MEDIUM)
- **文件:** `InsecureSslConfig.kt:36-40`
- 注释写的是"跳过证书日期验证"，但实现信任所有服务器证书。Debug 构建携带真实的 API key 和 OAuth token。
- 修复：限制在比 `BuildConfig.DEBUG` 更窄的专用 eval-only 标志之后。后续可选择仅放宽日期验证。

**8. Cancellation 感知的 streaming** (MEDIUM)
- **文件:** 所有 streaming client，`CodexSseParser.kt`
- `CodexSseParser.parse()` 中的阻塞读取不感知 cancellation。已取消的 flow 可能挂起直到 HTTP 读取超时（120 秒）。
- 修复：在 stream 迭代循环中添加 `coroutineContext.ensureActive()`。对于 Codex，存储 OkHttp `Call` 引用并从 `awaitClose` 中取消。

### P2: 架构

**9. 三个 transport 族，而非四个平级 client** (STRUCTURAL)
- `CodexResponseClient` 是 Responses 族的一个 transport/auth 变体，而非真正不同的协议。差异（auth、request encoding、stream decoding）是线路层面的关注点，不是语义 transport 关注点。将 Codex 视为平级 client 使得 request 构建、stream 累积和 retry 处理的碎片化得以延续。
- 修复：合并为 Responses 族 transport，使用可插拔的 strategy 对象（request encoder、stream decoder、auth/header provider、error classifier）。使用组合而非继承。

**10. 显式 capability 声明** (STRUCTURAL)
- `LFMLLMClient` 相对于 cloud transport 存在语义损失：丢弃非 user/non-assistant 角色、通过无类型 `Any` helper 平坦化内容、生成随机 tool call ID、在没有 call-ID 关联的情况下回放 tool 输出。这种 capability 损失是隐式的。
- 修复：定义 `LlmCapabilities` data class（vision、developer messages、stable tool call ID、parallel tool calls、streaming）。使 transport 声明 capability。显式地执行或降级。

### P3: 去重

**11. 共享 JsonValueConverter** -- 从 `CodexRequestBuilder` 和 `LeapFunctionInterop` 中提取（约 40 行重复）。

**12. 共享 ToolParameterExtractor** -- 从 `CodexRequestBuilder.convertToolParameters()` 和 `LeapToolSchemaAdapter.parseToolParameters()` 中提取（约 30 行重复）。

**13. 共享 post-retry flow handler** -- 将三个 streaming client 中重复的 10 行 post-retry 清理代码块提取到 `CloudStreamRetryRunner.handleRetryResult()` 中。

**14. 移除 MessageContentExtractor** -- 将 `ChatCompletionInterop.extractStringContent` 改为 internal 并从 `LFMLLMClient` 和 `LlmLogger` 直接使用。

### P3: 有条件的

**15. 内部规范 request 模型** -- 仅在 Responses 族合并后重复仍然较高时才推进。这是一个好工具，但在更简单的清理完成之前不应成为强制架构。

---

## 评分卡

| 领域 | 评级 | 备注 |
|------|------|------|
| 架构 | B+ | 抽象清晰，层级偏平一层 |
| Streaming 正确性 | C | 多个完成/retry 缺陷 |
| Error Handling | C+ | 正常路径可用，classification 脆弱 |
| Retry 逻辑 | B- | Policy 设计正确，实现有缺口 |
| Thread Safety | A | 全局正确 |
| 代码重复 | C+ | 三方 request 标准化，streaming 样板代码 |
| Cancellation | C | Stream 循环中无显式支持 |
| 测试覆盖 | C | 仅 catalog/factory/local 有测试；streaming/retry 未测试 |
| Config/Catalog | A | 简洁、不可变、可扩展 |
| 安全性 | B- | Debug SSL 范围过宽，其他方面适当 |
