# Review: LLM Integration P1 Tests

**Scope:** `git diff e5904b5..HEAD -- app/src/test/kotlin/com/moonkey/androidagent/llm/`
**Local verification:** `./gradlew testDebugUnitTest --tests 'com.moonkey.androidagent.llm.CloudStreamRetryPolicyTest' --tests 'com.moonkey.androidagent.llm.CloudStreamRetryRunnerTest' --tests 'com.moonkey.androidagent.llm.CodexSseParserTest' --tests 'com.moonkey.androidagent.llm.OpenAIErrorClassifierTest'` passed
**Note:** this diff contains 9 tests labeled `KNOWN BUG`, not 6.

## CRITICAL

None.

## HIGH

### HIGH-1: RESOLVED (11d76d0f)

`retryAfterMs` test now asserts `testScheduler.currentTime == 10L` — proves the 5000ms retryAfterMs was lost to backoff fallback. Will flip to 5000ms when preservation fix lands.

### HIGH-2: RESOLVED (11d76d0f)

Runner tests now assert virtual elapsed time:
- `exhausting all retries`: asserts cumulative 70ms (10+20+40)
- `backoff increases between retries`: asserts 70ms, ruling out fixed 30ms

## LOW

### LOW-1: The Codex parser tests miss the two most stateful accumulator paths

- **Files:** `app/src/test/kotlin/com/moonkey/androidagent/llm/CodexSseParserTest.kt:176-258`, `app/src/main/kotlin/com/moonkey/androidagent/llm/CodexSseParser.kt:118-147`
- The production code explicitly claims to support parallel tool calls keyed by `output_index`, but the tests only cover a single happy-path function call.
- There is also no test for the fallback path where `response.output_item.done` supplies `item.arguments` without any prior accumulated deltas.
- This is not merge-blocking by itself, but it leaves the most stateful part of the SSE parser undercovered.
- **Fix:** add one interleaved two-tool-call case and one `output_item.done`-only case.

## Verdict

Approved. HIGH issues resolved. LOW-1 deferred.
