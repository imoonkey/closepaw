# Review: test-architecture (682286b0..HEAD) — final third pass

## Summary
Reviewed `682286b0..HEAD`, re-checked fix commit `6c3ee6fb`, and spot-checked the production edits in `OpenAIErrorClassifier`, `PermissionStateMonitor`, `ShellTool`, `HttpLlmCredentialValidator`, `SessionCoordinator`, plus the JWT base64url decode change in `OpenAIOAuth`.

Ran a focused verification slice:

```bash
./gradlew testDebugUnitTest \
  --tests 'com.moonkey.androidagent.llm.OpenAIErrorClassifierTest' \
  --tests 'com.moonkey.androidagent.onboarding.PermissionStateMonitorTest' \
  --tests 'com.moonkey.androidagent.tool.impl.ShellToolExecutionTest' \
  --tests 'com.moonkey.androidagent.onboarding.HttpLlmCredentialValidatorTest' \
  --tests 'com.moonkey.androidagent.session.SessionCoordinatorTest' \
  --tests 'com.moonkey.androidagent.llm.ChatCompletionClientTest' \
  --tests 'com.moonkey.androidagent.llm.CodexResponseClientTest' \
  --tests 'com.moonkey.androidagent.auth.OpenAIOAuthTest'
```

That slice passed. Current runtimes for the previously slow tests are `ChatCompletionClientTest` `2.254s`, `HttpLlmCredentialValidatorTest` `0.591s`, and `ShellToolExecutionTest` `1.040s`.

## Prior Findings Status
| Finding | Status | Notes |
| --- | --- | --- |
| v1 Medium #1 — OpenAIErrorClassifier false-positive boundary matching | FIXED | `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifier.kt:72-83` now uses non-alphanumeric boundaries for `429` and `500/502/503/504`; `app/src/test/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifierTest.kt:62-91` and `:165-186` cover both negative and positive cases. |
| v1 Medium #2 — ChatCompletionClientTest pinned the raw timeout leak | FIXED | `app/src/test/kotlin/com/moonkey/androidagent/llm/ChatCompletionClientTest.kt:125-155` now asserts that retry happens instead of pinning the terminal exception type. |
| v1 Medium #3 — PermissionStateMonitor test spied and stubbed the object under test | FIXED | `app/src/main/kotlin/com/moonkey/androidagent/onboarding/PermissionStateMonitor.kt:50-72` extracts pure derivation logic; `app/src/test/kotlin/com/moonkey/androidagent/onboarding/PermissionStateMonitorTest.kt:8-55` tests that pure helper directly. |
| v2 Medium — slow real-time tests | FIXED | `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/ShellTool.kt:16-18`, `:61-67`, `:116-122` and `app/src/main/kotlin/com/moonkey/androidagent/onboarding/HttpLlmCredentialValidator.kt:20-30`, `:46-50` make timeouts injectable; `app/src/test/kotlin/com/moonkey/androidagent/llm/ChatCompletionClientTest.kt:128-154` now exits after the second retry observation. Verified current runtimes in `app/build/test-results/testDebugUnitTest/TEST-com.moonkey.androidagent.llm.ChatCompletionClientTest.xml`, `TEST-com.moonkey.androidagent.onboarding.HttpLlmCredentialValidatorTest.xml`, and `TEST-com.moonkey.androidagent.tool.impl.ShellToolExecutionTest.xml`. |
| v2 Low — reflection-heavy white-box tests | NOT-FIXED | Reflection remains in `app/src/test/kotlin/com/moonkey/androidagent/llm/ChatCompletionClientTest.kt:159-191`, `app/src/test/kotlin/com/moonkey/androidagent/llm/CodexResponseClientTest.kt:201-216`, and `app/src/test/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModelTest.kt:113-124`. Non-blocking, but still brittle under harmless refactors. |

## Production Change Audit
- `OpenAIErrorClassifier` regex change is correct for the previously reported false-positive class. Positive `429` and `5xx` cases still classify as intended, and the added negative tests close the letter-adjacent gap.
- `PermissionStateMonitor.deriveRepairModel` companion extraction preserves production behavior: the instance method still probes live Android state and then delegates to the pure helper.
- `ShellTool(timeoutSeconds)` preserves the `10s` production default. `SessionToolingBootstrapper` still constructs `ShellTool()` with the default, while tests can safely override it.
- `HttpLlmCredentialValidator` timeout injection preserves the `5s` connect and `20s` read defaults used by `OnboardingViewModel`; only tests opt into shorter bounds.
- `SessionCoordinator.removeAt(0)` is the correct FIFO equivalent for the existing `mutableListOf<String>` queue. `SessionCoordinatorTest` still covers immediate send, queued send, and drain order.
- No regression was observed from the `OpenAIOAuth` base64url decode change or the `MockWebServer` test dependency addition.

## New Critical/High/Medium Findings
None.

## Verdict
**APPROVE**
