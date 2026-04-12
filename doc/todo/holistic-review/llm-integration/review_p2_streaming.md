# Review: Phase 2 Streaming Correctness

**Scope:** `git diff eec4595..HEAD -- app/src/main/kotlin/com/moonkey/androidagent/llm/ app/src/test/kotlin/com/moonkey/androidagent/llm/`
**Local verification:** `./gradlew testDebugUnitTest --tests 'com.moonkey.androidagent.llm.*' --tests 'com.moonkey.androidagent.trace.*'` passed

## CRITICAL

None.

## HIGH

### HIGH-1: `response.incomplete` is still not terminal in the Codex streaming client

- **Files:** `app/src/main/kotlin/com/moonkey/androidagent/llm/CodexSseParser.kt:98-103`, `app/src/main/kotlin/com/moonkey/androidagent/llm/CodexResponseClient.kt:161-182`, `app/src/main/kotlin/com/moonkey/androidagent/llm/CloudStreamRetryRunner.kt:32-75`
- The parser fix is correct in isolation: `response.incomplete` now maps to `LLMStreamEvent.Failed("Response incomplete: ...")`.
- The streaming client does not treat that `Failed` event as terminal. It emits the failure event, keeps reading, then throws `TransientException("Stream ended without completion event")` because `sawCompletion` never becomes true.
- That creates incorrect behavior in both edge cases:
  - If no `TextDelta` or `ToolCallDone` was emitted before `response.incomplete`, `streamWithRetry()` sees `emittedEvent == false` and retries a non-retryable incomplete response.
  - If semantic output was already emitted, `streamWithRetry()` returns `FailAndStop` and emits a second generic failure event on top of the explicit incomplete failure.
- The updated test only checks parser mapping in `CodexSseParserTest.kt:106-117`; it does not cover actual `CodexResponseClient` streaming behavior, so this regression slips through green tests.
- **Fix:** make `CodexResponseClient.chatWithToolsStreaming()` stop immediately on `LLMStreamEvent.Failed` from the parser and surface that backend failure as terminal, with a dedicated client-level test for `response.incomplete`.

## LOW

### LOW-1: Fixes 4 and 6 still have no direct regression tests

- **Files:** `app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionClient.kt:191-224`, `app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionInterop.kt:264-274`, `app/src/main/kotlin/com/moonkey/androidagent/llm/LFMLLMClient.kt:292-302`, `app/src/main/kotlin/com/moonkey/androidagent/llm/LlmLogger.kt:30-34`, `app/src/main/kotlin/com/moonkey/androidagent/trace/LlmInputItemsTraceSerializer.kt:23-30`
- The diff updates the finish-reason guard and removes the generic `MessageContentExtractor`, but the only test changes are in `CloudStreamRetryRunnerTest` and `CodexSseParserTest`.
- There is still no direct test that `ChatCompletionClient` retries when a stream ends without `finish_reason`, and no focused test that `EasyInputMessage.Content` now produces actual text instead of wrapper strings like `Content{...}` in the LFM conversion/logging/trace paths.
- This is not blocking by itself, but these fixes are now enforced only by code inspection and package-level compilation.

## Verdict

Changes requested.
