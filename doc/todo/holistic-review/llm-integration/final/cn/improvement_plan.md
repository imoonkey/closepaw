# LLM Integration -- 最终改进计划

**来源:** 双重设计评审 (Claude + Codex)，交叉审查、对齐并验证。
**日期:** 2026-04-08（2026-04-10 验证）
**状态:** 已验证
**验证变更:** 2 项误报移除，采纳更 KISS 的替代方案，#14 提升为 P0 bug

---

## 原则

1. 先修正确性，再重构结构
2. 一套 stream 完成和 retry 的语义契约
3. Transport 差异应位于共享接口之下
4. 不支持的 capability 必须显式声明，不可静默降级
5. 保持 model catalog 简洁且数据驱动

---

## Phase 1: 添加 Streaming/Retry 测试 — 已完成

**前置条件:** 无
**状态:** 已完成 (11d76d0f, 2026-04-10)

添加 4 个测试类（62 个测试），锁定当前行为，包含 6 个已知 bug 测试（修复后会翻转）：

| 测试 | 目标 | 状态 |
|------|------|------|
| `CloudStreamRetryRunnerTest` | Retry 语义、域异常保留、emittedEvent 跟踪、虚拟时间 backoff 断言 | 已完成 |
| `CloudStreamRetryPolicyTest` | 元数据事件 vs 不可逆事件的 policy 决策 | 已完成 |
| `CodexSseParserTest` | 事件映射、incomplete 处理、畸形输入韧性、tool call 累积 | 已完成 |
| `OpenAIErrorClassifierTest` | Classification 准确性、误报场景 | 已完成 |
| `ChatCompletionClientStreamingTest` | 终止完成检测 | 跳过 — 需 SDK mock；已被 runner 测试间接覆盖 |

测试风格：小型状态机测试，验证何时发生 retry、何时必须停止、何时 stream 完成、何时响应不完整、tool call 如何重建。

---

## Phase 2: 修复 P0 Streaming 正确性 — 已完成

**前置条件:** Phase 1
**状态:** 已完成 (6c821852, 2026-04-10)

全部 6 项修复已实现，加上审查轮次修复 Failed-terminal 处理：

### 2.1 在 streamWithRetry 中保留域异常 — 已完成
**文件:** `CloudStreamRetryRunner.kt`
短路处理：`RateLimitException`/`TransientException` 跳过 `OpenAIErrorClassifier.classify()`。

### 2.2 区分元数据事件与不可逆输出以决定 retry — 已完成
**文件:** `CloudStreamRetryRunner.kt`
仅 `TextDelta`/`ToolCallDone` 设置 `emittedEvent`。`Created` 不再阻止 retry。

### 2.3 对 `response.incomplete` 报错 — 已完成
**文件:** `CodexResponseClient.kt`, `CodexSseParser.kt`
`response.incomplete` 映射为 `Failed` 附带 `incomplete_reason`。Streaming 循环在 Failed 事件时立即 break。

### 2.4 在 ChatCompletionClient 中要求终止完成 — 已完成
**文件:** `ChatCompletionClient.kt`
跟踪 `sawFinishReason`；缺失时抛出 `TransientException("Stream ended without finish_reason")`。

### 2.5 使 stream 无完成事件结束可 retry — 已完成
**文件:** `OpenAIResponseClient.kt`, `CodexResponseClient.kt`
改为 `TransientException("Stream ended without completion event")`。

### 2.6 MessageContentExtractor 已删除 — 已完成
**文件:** `ChatCompletionInterop.kt`, `LFMLLMClient.kt`, `LlmLogger.kt`, `LlmInputItemsTraceSerializer.kt`
将 `ChatCompletionInterop.extractStringContent()` 提升为 `internal`；替换所有调用点；删除 `MessageContentExtractor.kt`。

**验收标准 — 全部满足：**
- 在 `Created` 之后、文本/tool 输出之前失败的 stream 会 retry ✓
- 在文本/tool 输出之后失败的 stream 不会 retry ✓
- Codex `response.incomplete` 永远不会以成功状态呈现 ✓
- Chat streaming 在没有终止完成的干净 EOF 上不发出 `Completed` ✓
- `EasyInputMessage.Content` 通过类型化 API 提取，而非 `toString()` ✓

---

## Phase 3: 修复 P1 Classification、安全性、Cancellation

**前置条件:** Phase 2

### 3.1 加固 error classification（保持简单）
**文件:** `OpenAIErrorClassifier.kt`

保留一个 classifier。在字符串 fallback 之前添加域异常和类型化 SDK 异常的 fast-path：

```kotlin
fun classify(e: Exception): Exception = when (e) {
    is RateLimitException, is TransientException -> e  // 保留已有域异常
    is com.openai.errors.RateLimitException -> RateLimitException(e.message ?: "Rate limited")
    is com.openai.errors.InternalServerException -> TransientException("Server error", e)
    else -> classifyByMessage(e)  // 已有的字符串匹配 fallback
}
```

不要构建独立的 transport-classifier 抽象。这就够了。

### 3.2 收窄 InsecureSslConfig
**文件:** `InsecureSslConfig.kt`

限制在比 `BuildConfig.DEBUG` 更窄的专用 eval-only 标志之后。最快速安全的做法是使用类似 `INSECURE_SSL_FOR_EVAL` 的配置标志。如有需要，后续可再追求仅日期信任放宽。

### 3.3 在 flow 取消时取消底层 stream
**文件:** `CodexResponseClient.kt`

存储 OkHttp `Call` 引用，从 `awaitClose` 中取消。这是主要修复——`ensureActive()` 仅在阻塞读取返回后才起作用。

```kotlin
// 在 streaming callbackFlow 中:
val call = httpClient.newCall(request)
awaitClose { call.cancel() }
```

---

## Phase 4: 提取共享 Responses Helpers

**前置条件:** Phase 3

### 4.1 从 Responses 族 client 中提取共享 helpers

从 `OpenAIResponseClient` 和 `CodexResponseClient` 中提取共同逻辑：
- Request/result 累积
- 完成检查
- Retry epilogue 处理

暂保留两个 client 类。仅在提取 helpers 后仍有明显重复时才合并为单一 transport 类。

### 4.2 保持 Chat 和 Leap 独立
- `ChatCompletionClient` -- 确实不同的线路协议
- `LFMLLMClient` -- 不同的后端族和生命周期

**验收标准：**
- 共享 Responses helpers 减少代码重复
- 两个 Responses 族 client 使用相同的 completion/retry 逻辑
- 除非重复确实需要，否则不构建过度工程化的 strategy 模式

---

## Phase 5: 声明 Local Capability 差距

**前置条件:** Phase 4

### 5.1 添加窄的 `LocalLlmSemantics` 对象
声明应用其他部分需要推理的特定局限性：

```kotlin
object LocalLlmSemantics {
    val dropsNonUserAssistantRoles = true
    val generatesRandomToolCallIds = true
    val noToolResultCorrelation = true
    val flattensContentToString = true
}
```

在有第二个消费者之前不要构建宽泛的通用 `LlmCapabilities` 框架。

### 5.2 显式限制或转换
如果 Leap 无法保留某功能，在一个文档化的位置拒绝或转换。不静默丢弃。

---

## Phase 6: 去重清理

**前置条件:** Phase 4

### 6.1 提取共享 ToolParameterExtractor
创建 `ToolParameterExtractor.kt`（约 25 行）。合并 `CodexRequestBuilder.convertToolParameters()` 和 `LeapToolSchemaAdapter.parseToolParameters()` 的逻辑。一个返回 `JSONObject?` 的 helper，调用方决定 fallback。节省约 30 行。

### 6.2 修复 MessageContentExtractor（P0 bug）
**这是一个功能性 bug，不仅仅是去重。** `MessageContentExtractor.extractMessageContent(Any)` 接收 `EasyInputMessage.Content` 但 fallthrough 到 `toString()`，将 `Content{textInput=...}` 这样的包装字符串输入到 Leap。

修复：创建类型化的 `extractStringContent(content: EasyInputMessage.Content): String`。更新所有调用点（`LFMLLMClient`、`LlmLogger`、`LlmInputItemsTraceSerializer`）。删除 `MessageContentExtractor.kt`。

> 注：如果容易插入，这应在 Phase 2 与其他 P0 修复一起完成。列在此处是因为与去重相关。

### 6.3 共享 post-retry flow handler（如仍需要）
如果三个 retry epilogue 在 Phase 2-4 后仍然存在，提取一个小 helper。否则重复会被 Responses helper 提取自然消除。

---

## ~~Phase 7~~ 已移除

内部规范 request 模型作为误报移除。没有当前缺陷证明其必要性。完全推迟；仅在所有具体修复落地后再评估。

---

## 非目标

- 不重新设计 `ModelCatalog`
- 不移除 local inference 支持
- 不在清理期间增加 provider 数量
- 不混入 prompt-builder 变更，除非 capability 声明有此需要

---

## 执行总结

| Phase | 内容 | 工作量 | 影响 | 状态 |
|-------|------|--------|------|------|
| 1 | 添加 streaming/retry 测试 | 中等 | 使安全修复成为可能 | **已完成** |
| 2 | 修复 P0 streaming 正确性（5 项）+ MessageContentExtractor bug | 小（约 35 行） | 高——消除静默截断、丢失的 retry、Leap 垃圾输入 | **已完成** |
| 3 | 加固 classification + SSL + cancellation | 小（约 40 行） | 中——消除脆弱启发式和挂起 | |
| 4 | 提取共享 Responses helpers | 中等 | 中——减少重复但不过度工程化 | |
| 5 | 声明 local capability 差距 | 小（约 20 行） | 低——使隐式有损变为显式 | |
| 6 | 去重清理 | 小（净减约 30 行） | 低——减少代码但不改变行为 | |

**已移除:** JsonValueConverter 提取（误报——仅部分重叠），内部规范 request 模型（无当前缺陷证明需要）。

**预期最终结果:** 正确的 streaming 语义、诚实的 local capability、更少的重复——且不引入尚未证明其价值的新抽象。
