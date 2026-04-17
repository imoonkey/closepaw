# Review: test-architecture (682286b0..HEAD) — second pass

## Summary
Reviewed `682286b0..HEAD` across `app/src/main`, `app/src/test`, and `app/build.gradle.kts`, with a targeted re-check of fix commit `301df1d5`. I also ran a focused unit slice covering `OpenAIErrorClassifierTest`, `PermissionStateMonitorTest`, `ChatCompletionClientTest`, `OpenAIOAuthTest`, `HttpLlmCredentialValidatorTest`, `OnboardingViewModelTest`, `SessionCoordinatorTest`, `ShellToolExecutionTest`, `ChatViewModelTest`, and `CodexResponseClientTest`; that slice passed.

The three previously reported Medium findings are correctly addressed. I did not find new correctness regressions in the production changes, but I did find one new Medium issue in the test suite and one remaining Low issue.

## Previous Medium Findings Status
1. OpenAIErrorClassifier false-positive boundary matching — **FIXED**
   `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifier.kt:72-83` now uses non-alphanumeric boundaries for `429` and `5xx` matching instead of plain substring checks. `app/src/test/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifierTest.kt:62-107` adds the missing negative cases, including the previously problematic letter-adjacent tokens. The focused test slice passed.

2. PermissionStateMonitor spy-based test of stubbed object-under-test methods — **FIXED**
   `app/src/main/kotlin/com/moonkey/androidagent/onboarding/PermissionStateMonitor.kt:50-72` now exposes a pure companion `deriveRepairModel(...)` helper, and `app/src/test/kotlin/com/moonkey/androidagent/onboarding/PermissionStateMonitorTest.kt:8-55` exercises that pure logic directly instead of spying and stubbing the runtime probes. The focused test slice passed.

3. ChatCompletionClientTest pinning the timeout-leak terminal exception — **FIXED**
   `app/src/test/kotlin/com/moonkey/androidagent/llm/ChatCompletionClientTest.kt:125-148` no longer asserts that the final exception is the raw `SocketTimeoutException`; it now checks retry behavior instead. That resolves the original review concern. Separate note: this test is still part of the new suite-performance issue below, but that is a different problem from the original correctness concern.

## Critical
None.

## High
None.

## Medium
1. Several new unit tests pay real wall-clock timeout/backoff costs, making the new test suite materially slow.
   `app/src/test/kotlin/com/moonkey/androidagent/llm/ChatCompletionClientTest.kt:125-148` drives the full `CloudLlmRetry` backoff loop under `runBlocking`; the recorded runtime is `16.391s` in `app/build/test-results/testDebugUnitTest/TEST-com.moonkey.androidagent.llm.ChatCompletionClientTest.xml:2-4`. `app/src/test/kotlin/com/moonkey/androidagent/onboarding/HttpLlmCredentialValidatorTest.kt:74-79` uses `SocketPolicy.DISCONNECT_AT_START` against production `HttpURLConnection` timeouts and took `20.018s` in `app/build/test-results/testDebugUnitTest/TEST-com.moonkey.androidagent.onboarding.HttpLlmCredentialValidatorTest.xml:2-10`. `app/src/test/kotlin/com/moonkey/androidagent/tool/impl/ShellToolExecutionTest.kt:20-32` still shells out to `sleep 15` and took `10.019s` in `app/build/test-results/testDebugUnitTest/TEST-com.moonkey.androidagent.tool.impl.ShellToolExecutionTest.xml:2-8`. In aggregate, the focused 10-class slice took `48s`, and these three tests account for almost all of that runtime. Remediation: inject retry/backoff clocks and process/HTTP failure seams so timeout classification can be asserted instantly in unit tests, and leave real-time timeout coverage to a small opt-in integration layer.

## Low
1. Reflection-heavy white-box tests still pin private implementation details.
   `app/src/test/kotlin/com/moonkey/androidagent/llm/ChatCompletionClientTest.kt:153-185`, `app/src/test/kotlin/com/moonkey/androidagent/llm/CodexResponseClientTest.kt:201-216`, and `app/src/test/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModelTest.kt:113-120` reach into private methods and fields instead of testing public seams. They pass today, but they will fail on harmless refactors to request-building or teardown internals. Remediation: extract request/error mapping into explicit collaborators or test through injected clients, and verify `ChatViewModel` teardown through observable behavior rather than `eventCollectionJob`.

## Verdict
**REQUEST CHANGES**
