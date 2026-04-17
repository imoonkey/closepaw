# Review: test-architecture (682286b0..HEAD)

## Summary
Reviewed `682286b0..HEAD` across `app/src/main`, `app/src/test`, and `app/build.gradle.kts`. I also ran a targeted unit slice covering `OpenAIErrorClassifierTest`, `SessionCoordinatorTest`, `PermissionStateMonitorTest`, `ChatViewModelTest`, `ChatCompletionClientTest`, `CodexResponseClientTest`, and `AgentServiceEventHandlerTest`; that slice passed.

The good news: most of the new phase-runner, validator, and trace tests are behavior-oriented; `SessionCoordinator`'s `removeAt(0)` change preserves the intended FIFO behavior; and the OpenAI classifier still catches the intended `429`/`500` positive cases. The main issues are one correctness gap in the new classifier boundary logic and several new tests that are either white-box or cement behavior that should remain fixable.

## Critical
None.

## High
None.

## Medium
1. `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifier.kt:72-83`, `app/src/test/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifierTest.kt:60-79`
   The "word-boundary" fix is only digit-bounded, not token-bounded. `(?<!\d)429(?!\d)` and `(?<!\d)5(?:00|02|03|04)(?!\d)` still match alphanumeric tokens such as `req_429abc`, `error500beta`, or `x503y`, so the classifier can still misclassify arbitrary IDs/messages as retryable rate-limit/server errors. Remediation: tighten matching to non-alphanumeric boundaries, or parse explicit status fields when available, and add negative tests for letter-adjacent cases while preserving positive cases like `HTTP 429` and `status:500`.

2. `app/src/test/kotlin/com/moonkey/androidagent/llm/ChatCompletionClientTest.kt:125-148`, underlying behavior in `app/src/main/kotlin/com/moonkey/androidagent/llm/CloudLlmRetry.kt:34-38`
   This new test is a KNOWN-BUG-style assertion. The test name says timeout classification becomes `TransientException`, but the assertion pins the current raw `SocketTimeoutException` leak after retries. That makes a future fix to preserve the domain exception look like a regression. Remediation: assert retry behavior/backoff and final transient classification, or rename and document the raw-cause contract explicitly if that leak is actually intentional.

3. `app/src/test/kotlin/com/moonkey/androidagent/onboarding/PermissionStateMonitorTest.kt:15-25`
   The test uses a spy and stubs `isAccessibilityEnabled()`, `isOverlayEnabled()`, and `isBatteryOptimized()` on the object under test, so it never exercises the actual Android-state probes. This is exactly the case where pure logic should be extracted instead of mocking the runtime. Remediation: extract a pure `deriveRepairModel(...)` helper that takes booleans and unit-test that directly; keep only a thin Android-facing smoke test for the three probe methods.

## Low
1. `app/src/test/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModelTest.kt:101-125`, `app/src/test/kotlin/com/moonkey/androidagent/llm/ChatCompletionClientTest.kt:153-185`, `app/src/test/kotlin/com/moonkey/androidagent/llm/CodexResponseClientTest.kt:201-216`
   Several new tests reach into private fields and methods via reflection. They pass today, but they mostly protect private structure rather than user-visible behavior and will fail on harmless refactors. Remediation: move request-building and error-mapping into explicit collaborators or exercise public seams with injected clients or `MockWebServer`, and make the ViewModel teardown assertion behavior-based instead of inspecting `eventCollectionJob`.

2. `app/src/test/kotlin/com/moonkey/androidagent/tool/impl/ShellToolExecutionTest.kt:20-31`
   The timeout test is a real 10-second wall-clock unit test (`sleep 15` against a 10-second production timeout) and is host-shell dependent. That increases suite time and flake surface for routine unit runs. Remediation: inject the process runner or timeout so timeout behavior can be simulated instantly, and keep real shell execution as a small opt-in integration test if needed.

## Recommendation
REQUEST CHANGES
