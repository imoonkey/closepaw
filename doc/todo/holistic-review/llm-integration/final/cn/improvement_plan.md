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

## Phase 3: 修复 P1 Classification、安全性、Cancellation — 已完成

**前置条件:** Phase 2
**状态:** 已完成 (855d9fc4, 2026-04-10)

### 3.1 加固 error classification — 已完成
**文件:** `OpenAIErrorClassifier.kt`

重构为 `when(e)` 快速路径：域异常保留 → 类型化 SDK 异常（`com.openai.errors.RateLimitException` 提取 Retry-After header，`InternalServerException`）→ `classifyByMessage()` 字符串匹配 fallback。

### 3.2 收窄 InsecureSslConfig — 已完成
**文件:** `InsecureSslConfig.kt`, `build.gradle.kts`

限制在 `BuildConfig.INSECURE_SSL_FOR_EVAL`（默认 `false`）之后。构建时传 `-PinsecureSslForEval=true` 启用。Eval runner (`runner_preflight.py`) 已更新。

### 3.3 在 flow 取消时取消底层 stream — 已完成
**文件:** `CodexResponseClient.kt`

`streamWithRetry` 在 `callbackFlow` 内部 `launch{}` 中运行。`awaitClose { activeCall?.cancel(); job.cancel() }` 在任何阻塞 I/O 之前注册，flow 取消立即终止 HTTP 连接。

---

## Phase 4: 提取共享 Responses Helpers — 已完成

**前置条件:** Phase 3
**状态:** 已完成 (73916643, 2026-04-10)

### 4.1 提取 `StreamRetryRunResult.closeFlow()` — 已完成

将相同的 post-retry epilogue 块（检查 completed、需要时发出 Failed、关闭 flow）提取为 `StreamRetryRunResult.closeFlow()`。三个 streaming client 共用：`OpenAIResponseClient`、`CodexResponseClient`、`ChatCompletionClient`。

### 4.2 保持 Chat 和 Leap 独立 — 已确认
- `ChatCompletionClient` -- 确实不同的线路协议（但也受益于 `closeFlow()`）
- `LFMLLMClient` -- 不同的后端族和生命周期

### 未提取（有意为之）
- Streaming 循环内部根本不同（SDK 事件 vs 解析的 SSE vs Chat Completions）
- 累积变量是简单初始化——不值得共享抽象
- Result logging 是 4 行相同模式但不值得独立函数

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
| 3 | 加固 classification + SSL + cancellation | 小（约 40 行） | 中——消除脆弱启发式和挂起 | **已完成** |
| 4 | 提取共享 Responses helpers | 中等 | 中——减少重复但不过度工程化 | **已完成** |
| 5 | 声明 local capability 差距 | 小（约 20 行） | 低——使隐式有损变为显式 | |
| 6 | 去重清理 | 小（净减约 30 行） | 低——减少代码但不改变行为 | |

**已移除:** JsonValueConverter 提取（误报——仅部分重叠），内部规范 request 模型（无当前缺陷证明需要）。

**预期最终结果:** 正确的 streaming 语义、诚实的 local capability、更少的重复——且不引入尚未证明其价值的新抽象。
