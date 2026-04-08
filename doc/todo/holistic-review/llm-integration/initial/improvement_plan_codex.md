# LLM Integration Improvement Plan (Codex)

Date: 2026-04-08

## Goal

Make the `llm/` module simpler and safer by fixing stream correctness first, then collapsing the client taxonomy to the minimum set of transport families:

1. Responses
2. Chat-compatible
3. Local Leap

This plan assumes no work outside the LLM integration surface except where test harnesses need it.

## Principles

1. Fix correctness before refactoring shape.
2. One semantic contract for stream completion and retry.
3. Transport differences belong below the shared interface.
4. Unsupported capabilities must be explicit, not silently degraded.
5. Keep the model catalog simple and data-driven.

## Phase 1: Fix Streaming Correctness

### Changes

1. Preserve domain exceptions in streaming retry.

- Update `CloudStreamRetryRunner.kt` so it does not blindly pass `RateLimitException` and `TransientException` through `OpenAIErrorClassifier`.
- Introduce a small helper like `classifyRetryableException(e: Exception): Exception` that first preserves existing domain exceptions, then delegates to a transport-specific classifier only for raw transport errors.

2. Retry only before user-visible output.

- Change the retry runner to track “semantic output emitted” instead of “any event emitted.”
- `Created` must not block retry.
- `TextDelta` and `ToolCallDone` should block retry.

3. Treat incomplete responses as incomplete failures.

- Remove `response.incomplete -> Completed` from `CodexSseParser.kt`.
- Update `CodexResponseClient.kt` to surface incomplete status as `Failed` with the backend-provided reason.
- If partial text exists, include it in logs but do not mark the turn successful.

4. Require terminal completion in chat streaming.

- Update `ChatCompletionClient.kt` to track a real terminal condition before emitting `Completed`.
- If the stream ends without a final finish signal, raise a transient failure instead of silently succeeding.

### Acceptance Criteria

- A stream that fails after `Created` but before text/tool output retries.
- A stream that fails after text/tool output does not retry and surfaces a single failure.
- Codex `response.incomplete` is never surfaced as success.
- Chat streaming does not emit `Completed` on clean EOF without terminal completion.

### Tests to Add

- `CloudStreamRetryRunnerTest`
- `CloudStreamRetryPolicyTest`
- `CodexSseParserTest`
- `ChatCompletionClientStreamingTest` or an extracted stream-state-machine test

## Phase 2: Make Error Classification Transport-Owned

### Changes

1. Split classification by transport.

- Keep `OpenAIErrorClassifier.kt` for SDK/OpenAI-compatible transports only.
- Add a Codex-specific classifier or inline classification path that returns domain exceptions directly.
- Stop routing Codex exceptions through the OpenAI string-matching classifier.

2. Tighten classification inputs.

- Prefer typed HTTP status / transport exceptions over message substring matching where possible.
- Preserve auth failures, quota failures, rate limits, and server errors as distinct categories.

3. Normalize retry semantics.

- Both non-streaming and streaming paths should use the same retryability categories.
- `TransientException` and `RateLimitException` should mean the same thing across all cloud transports.

### Acceptance Criteria

- Existing typed domain exceptions survive classification.
- Codex rate limits trigger retry in the same way as OpenAI/OpenRouter rate limits.
- Non-retryable auth and validation failures surface immediately with stable messages.

### Tests to Add

- `OpenAIErrorClassifierTest`
- `CodexErrorClassificationTest`
- `CloudLlmRetryTest`

## Phase 3: Collapse the Cloud Client Taxonomy

### Changes

1. Merge the two Responses-family clients.

- Replace `OpenAIResponseClient` and `CodexResponseClient` with one Responses adapter plus strategy objects for:
  - auth/header building
  - request encoding
  - stream decoding

2. Keep `ChatCompletionClient` separate.

- Chat has a genuinely different wire protocol and should remain its own adapter.
- It should reuse shared retry, logging, and completion semantics rather than owning them ad hoc.

3. Keep `LFMLLMClient` separate.

- Local Leap is a distinct backend family and deserves its own adapter.
- Its capability differences must be explicit.

### Suggested Shape

```kotlin
interface LlmTransport {
    suspend fun call(request: LlmRequest): ResponsesResult
    fun stream(request: LlmRequest): Flow<LLMStreamEvent>
    val capabilities: LlmCapabilities
}
```

Possible implementations:

- `ResponsesTransport`
- `ChatCompletionsTransport`
- `LeapLocalTransport`

Possible strategies under `ResponsesTransport`:

- `OpenAiResponsesWire`
- `CodexResponsesWire`

### Acceptance Criteria

- The factory chooses among three transport families, not four peer clients.
- Codex-specific code lives under the Responses family rather than as a separate top-level client.
- Shared stream/retry behavior is implemented once.

## Phase 4: Introduce One Canonical Internal Request Model

### Changes

1. Stop making every adapter decode OpenAI union types directly.

- Add internal data classes such as:
  - `LlmRequest`
  - `LlmMessage`
  - `LlmContentPart`
  - `LlmToolDefinition`
  - `LlmToolCallRecord`

2. Add one boundary converter from OpenAI SDK request types.

- Convert `ResponseInputItem` and `FunctionTool` once at the module edge.
- Adapters then consume the internal model, not SDK-specific unions.

3. Reuse normalization logic everywhere.

- Roles
- multimodal content
- tool schemas
- function-call history
- tool-result correlation

### Why This Is Worth Doing

Right now the module pays the complexity cost of an internal canonical model without actually having one. The same semantic conversion is repeated in `ChatCompletionInterop.kt`, `CodexRequestBuilder.kt`, and `LFMLLMClient.kt`. A small internal model would reduce duplication and make transport differences obvious.

### Acceptance Criteria

- Each adapter has one input model.
- Role/content/tool normalization lives in one place.
- Adding a new transport does not require re-learning OpenAI SDK unions everywhere.

## Phase 5: Make Capability Gaps Explicit

### Changes

1. Define transport capabilities.

Example fields:

- `supportsVision`
- `supportsDeveloperMessages`
- `supportsParallelToolCalls`
- `supportsStableToolCallIds`
- `supportsStreaming`

2. Enforce or degrade explicitly.

- If a transport cannot preserve a feature, either reject it early or transform it in one documented place.
- Do not silently drop semantic information during replay.

3. Make local semantics honest.

- If Leap cannot preserve call IDs, encode that as a declared limitation.
- If local does not support developer-role history or multimodal input, declare and gate it.

### Acceptance Criteria

- Local-specific lossiness is documented in code and enforced by capability checks.
- The rest of the app can reason about backend differences without special-casing concrete client classes.

## Phase 6: Tighten Security and Logging

### Changes

1. Replace “trust everything” debug SSL with the narrowest possible workaround.

- If the only problem is frozen emulator time, the trust policy should only relax date validity, not all certificate validation.
- If that is not practical, gate the insecure path behind an explicit eval/debug flag narrower than `BuildConfig.DEBUG`.

2. Revisit verbose logging.

- Continue avoiding logs in release.
- Consider redacting especially sensitive tool outputs and long prompts even in debug/eval builds.

### Acceptance Criteria

- Debug cloud traffic no longer disables all certificate validation by default.
- Logging remains useful for evaluation without dumping more sensitive content than necessary.

## Test Strategy

Add targeted tests before or during each phase. Priority order:

1. `CloudStreamRetryRunnerTest`
2. `CodexSseParserTest`
3. `OpenAIErrorClassifierTest`
4. `CodexRequestBuilderTest`
5. `ChatCompletionInteropTest`
6. `LocalInteropParityTest`

The most important test style is not end-to-end HTTP mocking. It is small state-machine tests that prove:

- when retry happens
- when retry must stop
- when a stream is complete
- when a response is incomplete
- how tool calls are reconstructed

## Suggested Order of Execution

1. Add retry/completion tests.
2. Fix streaming correctness bugs.
3. Split transport-specific classification.
4. Merge Codex into the Responses family.
5. Introduce the internal request model.
6. Add capability declarations.
7. Narrow insecure SSL behavior.

## Non-Goals

- Do not redesign `ModelCatalog`.
- Do not remove local inference support.
- Do not expand provider count during this cleanup.
- Do not mix prompt-builder changes into this work unless a capability declaration requires it.

## Expected Outcome

After this plan, the module should be materially simpler:

- three transport families instead of four peer clients
- one shared definition of completion and retry
- one place where request semantics are normalized
- explicit capability gaps instead of silent drift

That is the minimum shape that satisfies KISS while still supporting OpenAI Responses, OpenAI-compatible Chat APIs, and local Leap inference.
