# Review: Phase 3 Hardening

**Scope:** `git diff 8410ca7..HEAD -- app/src/`
**Local verification:** `./gradlew testDebugUnitTest --tests 'com.moonkey.androidagent.llm.OpenAIErrorClassifierTest' --tests 'com.moonkey.androidagent.llm.CloudStreamRetryRunnerTest' --tests 'com.moonkey.androidagent.llm.CodexSseParserTest'` passed

## CRITICAL

None.

## HIGH

### HIGH-1: Typed OpenAI SDK rate-limit exceptions now lose `Retry-After`, weakening retry correctness

- **Files:** `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifier.kt:11-18`, `app/src/test/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifierTest.kt:111-118`
- The new fast-path for `com.openai.errors.RateLimitException` returns `RateLimitException(e.message ?: "Rate limited")` and drops all structured metadata from the SDK exception.
- The OpenAI SDK exception exposes `headers()`, so this path can preserve `Retry-After` directly. Before this change, the fallback classifier at least attempted to recover wait time from the message/cause text; now the typed branch bypasses that extraction entirely.
- Operationally, this means true SDK 429s will fall back to generic exponential backoff instead of honoring the server-provided wait window. That is a correctness regression in the retry policy, not just a nicer-error-message issue.
- The new test only asserts the mapped type, so it does not catch this loss of retry timing.
- **Fix:** extract `Retry-After` from `e.headers()` in the typed SDK branch and add a test that builds an SDK `RateLimitException` with that header and asserts `retryAfterMs`.

### HIGH-2: `activeCall.cancel()` is registered too late to stop the blocking stream it is meant to cancel

- **Files:** `app/src/main/kotlin/com/moonkey/androidagent/llm/CodexResponseClient.kt:143-155`, `app/src/main/kotlin/com/moonkey/androidagent/llm/CodexResponseClient.kt:208-211`
- `activeCall` is only canceled from `awaitClose`, but this `awaitClose` is reached after `streamWithRetry(...)` finishes. During the actual blocking work (`call.execute()` plus SSE reading), no close handler has been installed yet.
- That means the hardening does not cover the problematic case from the prior review: if the collector cancels while the coroutine is blocked in the HTTP call or a blocking stream read, the callback-flow cleanup has not been registered, so `activeCall.cancel()` never fires in time to unblock it.
- In other words, the code now stores the right object, but it still wires cancellation at the wrong lifecycle point.
- **Fix:** register job/channel cancellation against the in-flight `Call` before entering the blocking section, or restructure so the cleanup hook is active for the whole stream lifetime instead of only after it ends.

## LOW

### LOW-1: The new hardening paths still lack direct regression coverage

- **Files:** `app/src/test/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifierTest.kt:111-127`
- There are no tests for `Retry-After` preservation from SDK headers, no tests for the `INSECURE_SSL_FOR_EVAL` gate, and no tests that cancel a `CodexResponseClient` stream and verify the underlying `Call` is canceled.
- This is not the main problem here, but it is why both high-severity issues above still pass the current suite.

## Verdict

Changes requested.
