# Review: LLM Integration Milestone

**Scope:** `git diff e5904b5..HEAD -- app/src/`
**Local verification:** `./gradlew testDebugUnitTest --tests 'com.moonkey.androidagent.llm.CloudStreamRetryPolicyTest' --tests 'com.moonkey.androidagent.llm.CloudStreamRetryRunnerTest' --tests 'com.moonkey.androidagent.llm.CodexSseParserTest' --tests 'com.moonkey.androidagent.llm.OpenAIErrorClassifierTest'` passed

## Summary

The milestone fixes several real issues: `response.incomplete` is no longer treated as success, domain retry exceptions are preserved, and the Codex callback-flow now installs its close hook early enough to cancel an in-flight stream. The remaining problems are in the edges of those fixes: terminal failure semantics are still inconsistent, one of the new cancellation paths is racy, Chat Completions still accepts truncated finishes as success, and the error-classifier tests now codify known misclassifications.

## CRITICAL

None.

## HIGH

### HIGH-1: `streamWithRetry()` no longer treats a terminal `Failed` event as emitted output, so a failed attempt can still be retried

- **Files:** `app/src/main/kotlin/com/moonkey/androidagent/llm/CloudStreamRetryRunner.kt:47-53`, `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIResponseClient.kt:142-145`, `app/src/test/kotlin/com/moonkey/androidagent/llm/CloudStreamRetryRunnerTest.kt:147-159`
- The new runner only flips `emittedEvent` for `TextDelta` and `ToolCallDone`. `LLMStreamEvent.Failed` now sets `failureEmitted`, but it no longer counts as terminal output.
- That creates a bad edge case the new tests do not cover: if an attempt emits `Failed(...)` and then throws a retryable exception, `CloudStreamRetryPolicy` still sees `emittedEvent == false` and is allowed to retry. `OpenAIResponseClient` already has exactly that shape: it emits `Failed` on `event.isFailed()` and then throws.
- The result is inconsistent stream semantics: one attempt can surface a terminal failure to the caller and still be retried underneath. Best case, that wastes an extra request after the caller already saw an error. Worst case, a consumer that does not abort on `Failed` can observe `Failed` followed by a later retry/success path from the same logical stream.
- The new runner test only covers `emit(Failed)` followed by normal return, which is not the risky path used by the OpenAI client.
- **Fix:** make `Failed` count as emitted output in `streamWithRetry()`, or make `Failed` a terminal return from the runner, and add a regression test for `emit(Failed)` followed by a retryable throw.

### HIGH-2: `CodexResponseClient` fixed the lifecycle timing of cancellation, but the new `activeCall` handoff is still a cross-thread data race

- **Files:** `app/src/main/kotlin/com/moonkey/androidagent/llm/CodexResponseClient.kt:144-156`, `app/src/main/kotlin/com/moonkey/androidagent/llm/CodexResponseClient.kt:205-207`
- `activeCall` is written inside `withContext(Dispatchers.IO)` and read/canceled from `awaitClose` on the callback-flow producer thread. The reference is a plain mutable var with no `@Volatile`, `AtomicReference`, or other synchronization.
- The `Call` object itself is thread-safe, but the publication of the reference is not. A stale `null` read in `awaitClose` means the live socket never gets canceled when the collector disappears, which recreates the same stuck-stream/resource-leak class of bug this rewrite is trying to remove.
- This is exactly the kind of bug that will be intermittent in production and invisible to the current unit tests.
- **Fix:** store the in-flight call in an `AtomicReference<Call?>` (or at least a `@Volatile` field), clear it in `finally`, and add a cancellation-focused test around the new launch/awaitClose path.

### HIGH-3: `ChatCompletionClient` now requires a `finish_reason`, but it still treats every finish reason as success

- **Files:** `app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionClient.kt:128`, `app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionClient.kt:192-224`
- The new guard only checks whether `finishReason()` is present. It does not inspect the value before setting `sawFinishReason = true` and emitting `Completed`.
- That means truncation/policy-stop cases such as `length` or `content_filter` are still accepted as successful terminal output. The missing-finish retry is fixed, but incomplete-finish handling is still wrong.
- For tool calls this is especially risky: the stream can surface partial text or partially assembled tool arguments, then mark the response complete because some non-success finish reason existed.
- There is no client-level test for this branch, so the suite does not protect the contract the change is trying to harden.
- **Fix:** branch on the actual finish reason and only emit `Completed` for successful terminal reasons; map incomplete reasons to `Failed`/`TransientException` with regression tests for `length` and `content_filter`.

### HIGH-4: `OpenAIErrorClassifier` still has substring-based false positives, and the new tests now lock that broken behavior in

- **Files:** `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifier.kt:72-83`, `app/src/test/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifierTest.kt:60-85`
- The classifier still uses raw `contains("429")` / `contains("500")` matching. That misclassifies unrelated messages like `14291`, `5002`, or `5003` as rate-limit/server errors.
- In this milestone that matters more, not less, because the same classifier now sits on the streaming retry path. A false positive here can turn an arbitrary bug into a retry/backoff cycle or the wrong user-facing error.
- The new tests explicitly assert those false positives as passing behavior under `KNOWN BUG`, which means the suite now protects the bug instead of exposing it.
- **Fix:** replace substring matching with token/word-boundary matching or structured status parsing, then flip the `KNOWN BUG` tests to assert the correct non-rate-limit/non-transient classifications.

## LOW

### LOW-1: The new runner tests normalize the `maxRetries`/global retry-budget mismatch instead of catching it

- **Files:** `app/src/test/kotlin/com/moonkey/androidagent/llm/CloudStreamRetryRunnerTest.kt:214-233`, `app/src/main/kotlin/com/moonkey/androidagent/llm/CloudStreamRetryPolicy.kt:31-45`
- The test passes `maxRetries = 3` but then explicitly asserts a third backoff sleep because the policy uses `LLMClient.MAX_RETRIES = 5` instead of the method parameter.
- That is a useful clue during review, but as a committed assertion it blesses the mismatch instead of surfacing it. If the intent is for `streamWithRetry(maxRetries = N)` to mean “at most N attempts, with no extra delay after the last one,” this test now works against that cleanup.
- **Fix:** either thread `maxRetries` into `CloudStreamRetryPolicy.decide()` or stop asserting behavior that depends on the global constant instead of the supplied retry budget.

## Verdict

Changes requested.
