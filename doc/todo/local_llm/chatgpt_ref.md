下面给你一个**“只改 `llm_client.kt`，上层 agent / tool-call 逻辑完全不动”**的最简单优雅方案：把 `llm_client.kt` 变成一个**兼容 OpenAI Responses API 的“本地/远程双后端适配器”**。

> 关键点：上层仍然以为自己在用 *Responses API*（messages + tools + tool calls），只是 `llm_client.kt` 里面把“远程 HTTP 调用”替换成“本地推理 + 伪造 Responses API 输出事件”。

---

## 0) 为什么选 GGUF + llama.cpp（而不是 ONNX）

LiquidAI 的 LFM2.5-1.2B-Thinking 官方同时提供 **GGUF / ONNX** 两种落地格式：GGUF 适配 llama.cpp，ONNX 给 ORT 加速用 ([Hugging Face][1])。
**最省心的 Android 端侧方案通常是 GGUF + llama.cpp**：

* GGUF：CPU 推理部署成熟、JNI/封装很多、KV cache/采样成熟
* LFM2.5-1.2B-Thinking 主打手机端内存 < 1GB 可跑 ([Liquid AI][2])（这对 agent app 很关键）
* ONNX 虽然更“工程正规”，但你还得处理 tokenize / generation loop / kv cache（除非用 ORT generate API，但接入复杂度更高）

✅ 所以推荐：**用 LFM2.5-1.2B-Thinking-GGUF + llama.cpp Kotlin binding**
（ONNX 作为备选路径放在最后）

---

## 1) 总体设计：`llm_client.kt` 里做一个“双后端 + 兼容层”

把你的 `llm_client.kt` 拆成三块，但都放在同一个文件里（你只改这一处）：

### A. 维持原来的对外接口不变

比如你现在上层是这样用的（示意）：

```kotlin
interface LlmClient {
  suspend fun responses(request: ResponsesRequest): ResponsesStream
}
```

你保持这个签名、保持返回结构、保持你上层的解析逻辑都不动。

---

### B. `llm_client.kt` 内部改成：RemoteBackend / LocalBackend

* `RemoteBackend`：你现在的 OpenAI Responses API HTTP 调用逻辑原封不动放进去
* `LocalBackend`：新写一个本地推理版本
* 一个 `selectBackend()`：决定走本地还是远程（按开关/网络/模型是否已下载/内存是否够）

```kotlin
class LlmClientImpl(
  private val config: LlmConfig,
  private val remote: RemoteBackend = OpenAiResponsesBackend(config),
  private val local: LocalBackend = LfmLocalBackend(config)
): LlmClient {

  override suspend fun responses(request: ResponsesRequest): ResponsesStream {
    return if (local.isReady() && config.preferOnDevice) {
      local.responses(request)
    } else {
      remote.responses(request)
    }
  }
}
```

> 你上层完全不需要知道“现在是本地还是远程”。

---

## 2) 本地推理怎么做：最小改动的“工具调用兼容”

你现在 tool call 是走 OpenAI 的 tool calls API。
本地模型不会天然给你 Responses API 格式，所以我们要**让模型按你上层期待的格式输出**，并在 `llm_client.kt` 做一次 parse + 事件映射。

### 核心策略：用“单通道 JSON 协议”逼模型输出

在本地推理时，把 request 里的 tools（schema）塞进 system prompt，要求模型只输出两种结果之一：

#### 1) 普通回答：

```json
{"type":"message","content":"..."}
```

#### 2) 工具调用：

```json
{"type":"tool_call","name":"search","arguments":{"q":"..."}} 
```

然后 `llm_client.kt` 解析这个 JSON，转换成你原先上层正在消费的 tool-call 事件结构。

> 这一步是整个“只改底层 client”方案的关键：**让本地输出长得像远程输出**。

---

## 3) `llm_client.kt` 里的 LocalBackend：推荐直接用现成 Kotlin llama.cpp binding

你可以直接用现成的 Kotlin binding（不用自己手撸 JNI）。
比如 `kotlinllamacpp` 这类项目就是 Android Kotlin 的 llama.cpp binding ([GitHub][3])，并且 Maven Central 也有 AAR 包 ([Maven Central][4])。

> 这不算“改上层”，只是加一个依赖 + 在 `llm_client.kt` 调用它。

### LocalBackend 的职责

1. **加载 GGUF 模型**
2. **拼 prompt**（messages + tools）
3. **采样生成（支持流式 token）**
4. **解析 JSON 输出 → 伪造 Responses API stream**

---

## 4) prompt 拼接方式（既优雅又稳）

把用户的 messages 原样放进去，然后额外加一个系统约束段（重点是 tool-call JSON 协议）：

**System prompt（建议模板）**

* 说明你是 agent
* 给出 tools 列表（名字 + JSON schema）
* 规定只能输出 JSON（防止模型夹杂解释文本）
* 明确 tool_call / message 两种类型
* 要求 arguments 必须是合法 JSON 对象
* 如果不需要工具就直接 message

示意（你在 `llm_client.kt` 里生成）：

```text
You are an Android on-device agent.
When you need to use a tool, respond ONLY with a single line JSON:
{"type":"tool_call","name":"TOOL_NAME","arguments":{...}}

If you don't need a tool, respond ONLY with:
{"type":"message","content":"...final answer..."}

Available tools:
1) get_location: { ...json schema... }
2) http_request: { ... }
...
```

> tools 的 schema 直接来自你的 `ResponsesRequest.tools`，所以不用改上层定义。

---

## 5) 流式输出怎么兼容（上层完全不动）

你原来可能是消费 OpenAI 的 streaming events，例如：

* `response.output_text.delta`
* `response.tool_call.delta`
* `response.completed`

本地 llama.cpp 生成 token 时，你可以：

### 方案 A（最简单）：本地不真正流式，生成完一次性吐

* 最省事、最稳
* 缺点：体验没那么“打字机”

### 方案 B（推荐）：token 流式 → 你在 client 内部缓冲到“完整 JSON”

* 每来一个 token append 到 buffer
* 当 buffer 满足“能 parse 出完整 JSON（括号闭合）”时：

  * 如果是 message：按你原来上层的 text delta 事件逐段发（或者一次性发）
  * 如果是 tool_call：直接发 tool_call 事件，然后 `completed`

这样上层**仍然以为自己在消费 Responses stream**。

---

## 6) 模型文件怎么放手机上（不改上层的最佳实践）

GGUF 体积很大，不建议打包进 APK。

### 推荐策略：首次启动后台下载到 app 私有目录

* 例如：`context.filesDir/models/LFM2.5-1.2B-Thinking-Q4_K_M.gguf`
* `llm_client.kt` 里 `local.isReady()` 判断文件是否存在
* 不存在 → 自动走远程 backend（无感 fallback）
* 下载完成 → 自动切本地

LFM2.5-1.2B-Thinking 官方提供 GGUF 格式就是为了这种本地部署 ([Hugging Face][1])。

---

## 7) “最优雅”的容错：JSON 不合法就自动重试一次

本地模型最常见问题：输出不严格 JSON（多了前后文本、少引号、trailing comma）。

在 `llm_client.kt` 做一个**轻量修复 + 1 次重试**就很顶：

* 先尝试提取第一段 `{ ... }`（从第一个 `{` 到最后一个 `}`）
* parse 失败 → 追加一个 system 修正提示再生成一次，比如：

```text
Your last output was invalid JSON. Output ONLY a valid JSON object exactly matching the schema.
```

> 上层不会感知到你做了 retry。

---

## 8) 推荐默认参数（1.2B 端侧可用）

LFM2.5-1.2B-Thinking 是 reasoning 模型，建议：

* temperature：0.2 ~ 0.6（tool call 更稳）
* top_p：0.9
* max_tokens：按你任务（工具调用通常很短）
* context：尽量控制（别把全世界 history 都塞进去）

官方强调它能在手机上低内存运行 ([Liquid AI][2])，但你仍然要避免超长上下文把 KV cache 撑爆。

---

## 9) 你最终会得到的效果

✅ 你只改 `llm_client.kt`
✅ 你的 agent 编排层、tool dispatcher、action 执行、UI 展示、stream 处理全都不用动
✅ 离线可用、低延迟、本地隐私
✅ 模型未下载/内存不够/低端机 → 自动走 OpenAI 远程（体验不崩）

---

## 10) 备选：如果你强烈想要 ONNX（不推荐优先）

LFM2.5-1.2B-Thinking 也提供 ONNX 格式 ([Hugging Face][1])。
但你要处理：

* tokenizer（通常要 HF tokenizer 或自己实现 BPE）
* generation loop / kv cache（除非你接 ORT generate API）
* Android NNAPI / EP 的兼容坑

ONNX Runtime 的 Android 指南本身也更偏“通用模型部署”，LLM 生成链路没 GGUF/llama.cpp 这么一站式 ([onnxruntime.ai][5])。

---

# 你可以直接照抄的“最小实现清单”

只要你按下面做，就能跑起来：

1. 下载 `LiquidAI/LFM2.5-1.2B-Thinking-GGUF` 的某个量化（Q4）([Hugging Face][1])
2. 加一个 llama.cpp Kotlin binding 依赖（AAR）([Maven Central][4])
3. 只改 `llm_client.kt`：

   * 加 `LocalBackend`
   * prompt 注入 tools JSON 协议
   * 解析 JSON → 转成你现有的 tool-call event
   * fallback 到 remote backend

---

如果你愿意，我可以在你下一条消息里按你现有 `llm_client.kt` 的接口（比如你现在的 `ResponsesRequest/ResponsesStream` 数据结构）**把 LocalBackend 的 Kotlin 代码骨架直接写出来**，做到你复制粘贴基本能编译跑。

[1]: https://huggingface.co/LiquidAI/LFM2.5-1.2B-Thinking?utm_source=chatgpt.com "LiquidAI/LFM2.5-1.2B-Thinking"
[2]: https://www.liquid.ai/blog/lfm2-5-1-2b-thinking-on-device-reasoning-under-1gb?utm_source=chatgpt.com "LFM2.5-1.2B-Thinking: On-Device Reasoning Under 1GB"
[3]: https://github.com/ljcamargo/kotlinllamacpp?utm_source=chatgpt.com "ljcamargo/kotlinllamacpp: Run GGUF models on your ..."
[4]: https://central.sonatype.com/artifact/io.github.ljcamargo/llamacpp-kotlin?utm_source=chatgpt.com "llamacpp-kotlin - io.github.ljcamargo - Maven Central - Sonatype"
[5]: https://onnxruntime.ai/docs/tutorials/mobile/?utm_source=chatgpt.com "Deploy on mobile | onnxruntime"
