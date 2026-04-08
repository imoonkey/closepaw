# LLM Integration -- 最终改进计划

**来源:** 双重设计评审 (Claude + Codex)，交叉审查并对齐。
**日期:** 2026-04-08
**状态:** 已批准

---

## 原则

1. 先修正确性，再重构结构
2. 一套 stream 完成和 retry 的语义契约
3. Transport 差异应位于共享接口之下
4. 不支持的 capability 必须显式声明，不可静默降级
5. 保持 model catalog 简洁且数据驱动

---

## Phase 1: 添加 Streaming/Retry 测试

**前置条件:** 无

在修复 bug 之前先添加针对性测试。这些测试锁定当前行为并验证修复效果。

| 测试 | 目标 |
|------|------|
| `CloudStreamRetryRunnerTest` | Retry 语义、域异常保留、emittedEvent 跟踪 |
| `CloudStreamRetryPolicyTest` | 元数据事件 vs 不可逆事件的 policy 决策 |
| `CodexSseParserTest` | 事件映射、incomplete 处理、畸形输入韧性 |
| `OpenAIErrorClassifierTest` | Classification 准确性、误报场景 |
| `ChatCompletionClientStreamingTest` | 终止完成检测 |

测试风格：小型状态机测试，验证何时发生 retry、何时必须停止、何时 stream 完成、何时响应不完整、tool call 如何重建。

---

## Phase 2: 修复 P0 Streaming 正确性

**前置条件:** Phase 1

### 2.1 在 streamWithRetry 中保留域异常
**文件:** `CloudStreamRetryRunner.kt`

```kotlin
val classified = when (e) {
    is RateLimitException, is TransientException -> e
    else -> OpenAIErrorClassifier.classify(e)
}
```

### 2.2 区分元数据事件与不可逆输出以决定 retry
**文件:** `CloudStreamRetryRunner.kt`

跟踪"语义输出已发出"而非"任何事件已发出"：
- `Created` -> 不阻止 retry
- `TextDelta`, `ToolCallDone` -> 阻止 retry

### 2.3 对 `response.incomplete` 报错
**文件:** `CodexResponseClient.kt`, `CodexSseParser.kt`

移除 `response.incomplete -> Completed` 映射。以 `Failed` 状态呈现并附带后端原因。

### 2.4 在 ChatCompletionClient 中要求终止完成
**文件:** `ChatCompletionClient.kt`

```kotlin
var sawFinishReason = false
// 在 stream 循环中: 当 finishReason != null 时设置 sawFinishReason = true
// 循环结束后:
if (!sawFinishReason) {
    throw TransientException("Stream ended without finish_reason")
}
emitter.emit(LLMStreamEvent.Completed)
```

### 2.5 使 stream 无完成事件结束可 retry
**文件:** `OpenAIResponseClient.kt`, `CodexResponseClient.kt`

将 `RuntimeException("Stream ended without completion event")` 改为 `TransientException("Stream ended without completion event")`。

**验收标准：**
- 在 `Created` 之后、文本/tool 输出之前失败的 stream 会 retry
- 在文本/tool 输出之后失败的 stream 不会 retry
- Codex `response.incomplete` 永远不会以成功状态呈现
- Chat streaming 在没有终止完成的干净 EOF 上不发出 `Completed`

---

## Phase 3: 修复 P1 Classification、安全性、Cancellation

**前置条件:** Phase 2

### 3.1 Transport 自有的 error classification
**文件:** `OpenAIErrorClassifier.kt`, `CloudStreamRetryRunner.kt`

- 在字符串 fallback 之前先检查类型化 SDK 异常类：
  ```kotlin
  fun classify(e: Exception): Exception = when (e) {
      is com.openai.errors.RateLimitException -> RateLimitException(e.message ?: "Rate limited")
      is com.openai.errors.InternalServerException -> TransientException("Server error", e)
      else -> classifyByMessage(e)
  }
  ```
- 停止将 Codex 异常路由到 OpenAI classifier（Codex 的 `handleErrorResponse()` 已经产生类型化异常）

### 3.2 收窄 InsecureSslConfig
**文件:** `InsecureSslConfig.kt`

限制在比 `BuildConfig.DEBUG` 更窄的专用 eval-only 标志之后。最快速安全的做法是使用类似 `INSECURE_SSL_FOR_EVAL` 的配置标志。如有需要，后续可再追求仅日期信任放宽。

### 3.3 Cancellation 感知的 streaming
**文件:** 所有 streaming client，`CodexSseParser.kt`

- 在 stream 迭代循环中添加 `coroutineContext.ensureActive()`（每个 client 约 5 行）
- 对于 Codex：存储 OkHttp `Call` 引用，从 `awaitClose` 回调中取消（约 15 行）
- 对于 OpenAI/Chat：SDK stream 的 `use {}` 块已处理清理，但 `ensureActive()` 可防止空转等待

---

## Phase 4: 合并 Cloud Client 分类

**前置条件:** Phase 3

### 4.1 将 Codex 合并到 Responses transport 族

用一个采用 strategy/composition 的 Responses 族 transport 替代 `OpenAIResponseClient` 和 `CodexResponseClient`：

```kotlin
class ResponsesTransport(
    private val requestEncoder: ResponsesRequestEncoder,
    private val streamDecoder: ResponsesStreamDecoder,
    private val authProvider: ResponsesAuthProvider,
    private val errorClassifier: ResponsesErrorClassifier,
) : LlmTransport { ... }
```

Strategy：
- `OpenAiResponsesWire` -- SDK 原生 streaming，API key auth
- `CodexResponsesWire` -- OkHttp + 自定义 SSE，OAuth + 自定义 header

### 4.2 保持 Chat 和 Leap 独立
- `ChatCompletionsTransport` -- 确实不同的线路协议
- `LeapLocalTransport` -- 不同的后端族和生命周期

### 4.3 在一处共享 retry/completion
将所有 stream retry 和 completion 逻辑移入 transport 基类或共享 runner。消除各 client 的 post-retry 样板代码。

**验收标准：**
- Factory 在三个 transport 族之间选择，而非四个平级 client
- Codex 专有代码归属于 Responses 族之下
- 共享 stream/retry 行为仅实现一次

---

## Phase 5: 显式 Capability 声明

**前置条件:** Phase 4

### 5.1 定义 transport capability
```kotlin
data class LlmCapabilities(
    val supportsVision: Boolean,
    val supportsDeveloperMessages: Boolean,
    val supportsParallelToolCalls: Boolean,
    val supportsStableToolCallIds: Boolean,
    val supportsStreaming: Boolean,
)
```

### 5.2 使 local 语义真实
- 声明 Leap 的局限性：无 stable call ID、无 developer-role history、内容平坦化
- 在一个文档化的位置显式执行或降级
- 应用其他部分可以基于后端差异进行推理，无需对具体类做特殊处理

---

## Phase 6: 去重清理

**前置条件:** Phase 4

### 6.1 提取共享 JsonValueConverter
创建 `JsonValueConverter.kt`（约 20 行）。从 `CodexRequestBuilder` 和 `LeapFunctionInterop` 中移除重复代码。节省约 40 行。

### 6.2 提取共享 ToolParameterExtractor
创建 `ToolParameterExtractor.kt`（约 25 行）。合并 `CodexRequestBuilder.convertToolParameters()` 和 `LeapToolSchemaAdapter.parseToolParameters()` 的逻辑。使用 Leap 版本更好的日志功能。节省约 30 行。

### 6.3 共享 post-retry flow handler
在 `CloudStreamRetryRunner` 中添加 `ProducerScope<LLMStreamEvent>.handleRetryResult()`。替换三个 client 中的 10 行代码块。节省约 20 行。（可能被 Phase 4 的 transport 合并所涵盖。）

### 6.4 移除 MessageContentExtractor
删除 `MessageContentExtractor.kt`。将 `ChatCompletionInterop.extractStringContent` 改为 internal。更新 `LFMLLMClient` 和 `LlmLogger` 的调用点。

---

## Phase 7: 评估内部规范 Request 模型（有条件的）

**前置条件:** Phase 4 完成并评估

**门控条件:** 仅在 Responses 族合并后重复仍然较高时才推进。

如果有必要：
- 添加内部 data class：`LlmRequest`、`LlmMessage`、`LlmContentPart`、`LlmToolDefinition`、`LlmToolCallRecord`
- 在模块边界一次性转换 `ResponseInputItem` 和 `FunctionTool`
- Transport 消费内部模型，而非 SDK 特定的联合类型

这是一个好工具，但在更简单的清理完成之前不应成为强制架构。

---

## 非目标

- 不重新设计 `ModelCatalog`
- 不移除 local inference 支持
- 不在清理期间增加 provider 数量
- 不混入 prompt-builder 变更，除非 capability 声明有此需要

---

## 执行总结

| Phase | 内容 | 工作量 | 影响 |
|-------|------|--------|------|
| 1 | 添加 streaming/retry 测试 | 中等 | 使安全修复成为可能 |
| 2 | 修复 P0 streaming 正确性（5 项） | 小（约 30 行变更） | 高——消除静默截断和丢失的 retry |
| 3 | 修复 P1 classification + 安全性 + cancellation | 中等（约 60 行） | 中——消除脆弱启发式和挂起 |
| 4 | 合并为 3 个 transport 族 | 大（重构） | 高——消除结构性重复 |
| 5 | Capability 声明 | 小（约 30 行） | 中——使隐式有损变为显式 |
| 6 | 去重清理 | 小（净减约 70 行） | 低——减少代码但不改变行为 |
| 7 | 内部 request 模型（有条件的） | 大 | 取决于 Phase 4 结果 |

**预期最终结果:** 更少的代码行、更少的 client、正确的 streaming 语义、显式的 capability，以及模块简化方向的拨正：困难的语义部分是干净的，而不仅仅是简单的 catalog/factory 部分。
