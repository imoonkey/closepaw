# LLM Integration Review (Codex)

Date: 2026-04-08

## Scope

Reviewed only the `llm/` module and `app/src/main/assets/llm_models.json`.

Files reviewed:

- `app/src/main/kotlin/com/moonkey/androidagent/llm/LLMClient.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/LLMClientFactory.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIResponseClient.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifier.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionClient.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionInterop.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/MessageContentExtractor.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/CodexResponseClient.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/CodexRequestBuilder.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/CodexSseParser.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/LFMLLMClient.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/LeapFunctionInterop.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/CloudLlmRetry.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/CloudStreamRetryRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/CloudStreamRetryPolicy.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/ModelCatalog.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/LocalLLMConfig.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/LlmLogger.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/InsecureSslConfig.kt`
- `app/src/main/assets/llm_models.json`

Verification performed:

- Ran `./gradlew :app:testDebugUnitTest --tests 'com.moonkey.androidagent.llm.*'` and it passed.
- Existing `llm` tests cover `ModelCatalog`, `LLMClientFactory`, and local schema/JSON conversion only.

## Executive Summary

The module works, but the current design is more complex than the problem requires and that complexity is concentrated in the riskiest place: stream handling. The biggest issues are not model selection or JSON catalog loading; they are incorrect completion semantics, retry behavior that is weaker than it looks, and duplicated protocol adaptation across four concrete clients.

The code currently has four concrete clients:

1. `OpenAIResponseClient`
2. `ChatCompletionClient`
3. `CodexResponseClient`
4. `LFMLLMClient`

From a design perspective, that is one client too many. The system does need three transport families if local inference remains a product requirement:

1. Responses API
2. Chat Completions API
3. Local Leap SDK

`CodexResponseClient` should not remain a peer “client type.” It is a transport/auth variant of the Responses family, and modeling it as a full sibling has already produced duplicated request conversion, duplicated stream accumulation, and inconsistent retry behavior.

Recommendation: `CHANGES_REQUESTED`.

## Current Architecture

The good parts:

- `ModelCatalog` is clean, flat, and easy to extend.
- `LLMClientFactory` keeps provider/base-url/api selection out of call sites.
- `LLMClient` gives the rest of the app one surface for streaming and non-streaming calls.

The weak part is the semantic center of gravity:

- Caller inputs are normalized around OpenAI `ResponseInputItem` and `FunctionTool` types.
- Each client then reinterprets those types independently.
- Streaming correctness is split across each client, `CloudStreamRetryRunner`, `CloudStreamRetryPolicy`, `OpenAIErrorClassifier`, and `CodexSseParser`.

That is too many places for “what counts as success/failure/retry” to live.

## Findings

### High

1. Streaming retry loses retryable exceptions and can silently disable retries.

`streamWithRetry()` reclassifies every caught exception through `OpenAIErrorClassifier`, even when the exception is already a domain-level `RateLimitException` or `TransientException` (`CloudStreamRetryRunner.kt:50-61`). `OpenAIErrorClassifier` does not preserve either type (`OpenAIErrorClassifier.kt:11-52`), so a Codex `RateLimitException` from `handleErrorResponse()` (`CodexResponseClient.kt:242-260`) can be downgraded into a generic `RuntimeException` and stop retrying. The same problem applies to explicit transient failures such as “stream ended without completion event.”

This is a correctness bug, not just a cleanup issue. The retry layer is advertised as shared, but it does not preserve the semantics emitted by the clients it wraps.

2. The streaming retry policy treats `Created` as partial output and blocks safe retries too early.

`streamWithRetry()` marks `emittedEvent = true` for every event, including `LLMStreamEvent.Created` (`CloudStreamRetryRunner.kt:33-41`). `CloudStreamRetryPolicy` then refuses retry after any emitted event (`CloudStreamRetryPolicy.kt:22-29`). That means a stream that creates successfully and then fails before any text or tool-call payload will not retry, even though retry would be safe and user-visible duplication would not occur.

The policy should distinguish between metadata and irreversible output. Right now it does not.

3. Codex treats `response.incomplete` as success.

Both the non-streaming and streaming Codex paths treat `response.incomplete` the same as `response.done` / `response.completed` (`CodexResponseClient.kt:96-98`, `CodexSseParser.kt:95-97`). That converts an explicitly incomplete response into `LLMStreamEvent.Completed` and can surface truncated text or partial tool-call arguments as if the turn succeeded.

This is the clearest streaming robustness bug in the module.

4. Chat Completions streaming reports success without validating that the stream reached a terminal model finish.

`ChatCompletionClient` emits `Completed` whenever the SDK stream loop ends normally (`ChatCompletionClient.kt:209-218`). It does not track whether a terminal `finishReason` was actually seen, and it emits completed tool calls whenever any finish reason appears (`ChatCompletionClient.kt:189-203`), not when the stream is proven complete.

Compared with `OpenAIResponseClient`, which explicitly requires a completion event (`OpenAIResponseClient.kt:132-156`), the chat path is materially weaker and can accept clean EOF as success.

5. Debug SSL is broader than the comment claims.

`InsecureSslConfig` says it “skips certificate date validation” for frozen emulator clocks (`InsecureSslConfig.kt:11-19`), but the trust manager implementation accepts every server certificate without validation (`InsecureSslConfig.kt:36-40`). This config is wired into all cloud clients in debug builds (`OpenAIResponseClient.kt:44-49`, `ChatCompletionClient.kt:40-45`, `CodexResponseClient.kt:227-230`).

Because debug builds can still carry real API keys or OAuth tokens, this is a real LLM integration risk, not merely a test-only concern.

### Medium

1. There are too many top-level client classes for the actual transport surface.

The cloud path is split across `OpenAIResponseClient`, `ChatCompletionClient`, and `CodexResponseClient`, but the last of those is mostly a Responses-API variant with different auth, wire formatting, and SSE parsing. Treating Codex as a full sibling creates duplicated accumulation and lifecycle code (`OpenAIResponseClient.kt`, `CodexResponseClient.kt`) and duplicated request adaptation (`ChatCompletionInterop.kt`, `CodexRequestBuilder.kt`, `LFMLLMClient.kt`).

KISS suggests one shared semantic core with three thin adapters, not four peers.

2. The shared client contract overstates feature parity.

`LLMClient` presents a uniform abstraction, but `LFMLLMClient` is semantically lossy relative to the cloud adapters:

- It drops non-user/non-assistant roles when converting history (`LFMLLMClient.kt:291-299`).
- It flattens message content through a generic `extractMessageContent(Any)` helper rather than explicit union handling (`LFMLLMClient.kt:299`, `MessageContentExtractor.kt:4-16`, compare with `ChatCompletionInterop.kt:176-275`).
- It generates random tool call IDs instead of preserving stable call IDs (`LFMLLMClient.kt:338-345`).
- It replays tool outputs without call-ID correlation (`LFMLLMClient.kt:319-324`).

This may be acceptable as a product tradeoff, but the capability loss is implicit rather than modeled.

3. Error classification is heuristic and provider-specific instead of transport-specific.

`OpenAIErrorClassifier` relies on message text matching (`OpenAIErrorClassifier.kt:15-98`) and is used as the universal classifier, including inside the shared streaming retry runner. That is fragile for OpenAI-compatible providers and especially fragile for Codex, which already has a typed HTTP error path in `handleErrorResponse()`.

The code needs a transport-level retry/error contract, not one global string-matching classifier.

4. The module duplicates request normalization in three places.

The same conceptual conversion work is repeated in:

- `ChatCompletionInterop.kt`
- `CodexRequestBuilder.kt`
- `LFMLLMClient.kt` / `LeapFunctionInterop.kt`

Each path re-explains how to convert roles, content, tool definitions, and tool-call history. That duplication is where semantic drift has already appeared.

5. Test coverage misses the failure-prone surfaces.

The existing `llm` tests exercise `ModelCatalog`, `LLMClientFactory`, and Leap schema/JSON conversion. There are no direct tests for:

- `OpenAIResponseClient`
- `ChatCompletionClient` streaming
- `CodexResponseClient`
- `CodexSseParser`
- `CodexRequestBuilder`
- `CloudLlmRetry`
- `CloudStreamRetryRunner`
- `CloudStreamRetryPolicy`
- `OpenAIErrorClassifier`

The code that is most likely to regress is the least covered.

## Do We Need Three Client Types?

Yes for transport families, no for the current class hierarchy.

Keep:

1. Responses transport
2. Chat-compatible transport
3. Local Leap transport

Collapse:

- `CodexResponseClient` should become a Responses transport mode, not a separate top-level client.

The right abstraction boundary is “how requests are sent and streamed,” not “which brand name is on the backend.”

## Design Direction

The module should move toward:

1. One canonical internal request/response model inside `llm/`
2. One shared streaming state machine and retry contract
3. Three transport adapters: Responses, Chat, Leap
4. Explicit capability declarations for transport-specific gaps

That would simplify the code and make failure semantics obvious.

## Suggested End State

- `LLMClient` becomes a thin orchestrator around a transport adapter, or the transport itself becomes the primary interface.
- `OpenAIResponseClient` and `CodexResponseClient` are merged into a Responses adapter with pluggable auth/request/stream parsing strategy.
- `ChatCompletionClient` keeps only Chat-specific request/stream translation.
- `LFMLLMClient` keeps Leap-specific conversation/model lifecycle, but unsupported semantics are declared explicitly instead of being silently dropped.
- Retry classification is transport-owned and preserves domain exceptions.
- Stream completion is represented explicitly as `completed`, `failed`, or `incomplete`; “incomplete == completed” is removed everywhere.

## Bottom Line

The module is structurally close to something solid, but its current simplification story is upside down: catalog loading and factory selection are clean, while the hard semantic parts are fragmented.

The immediate priority is correctness in streaming and retry. After that, the code should be simplified by reducing the number of peer client types and centralizing request normalization and stream semantics.
