# Phase 4 Review — TermuxShellTool + Registration

## Verdict
REQUEST_CHANGES

## Findings

1. severity: high, file: app/src/main/kotlin/ai/closepaw/tool/impl/TermuxShellTool.kt:323, description: `Call.await()` has a cancellation/resource race after OkHttp returns a `Response`. The callback checks `cont.isActive` and then calls `cont.resume(response)`, but coroutine cancellation can happen after that check, or after `resume(response)` before the resumed coroutine reaches the surrounding `.use { ... }`. In that prompt-cancellation path the `Response` is not closed, so the socket/connection can leak exactly in the cancellation path this phase is trying to make reliable. suggested fix: Resume the cancellable continuation with an on-cancellation resource cleanup handler that closes the `Response`, or use an equivalent `tryResume`/close-on-failure pattern. Avoid the `isActive` TOCTOU check for the success path; close the response whenever the continuation cannot take ownership of it.

2. severity: medium, file: app/src/test/kotlin/ai/closepaw/tool/impl/TermuxShellToolTest.kt:119, description: The cancellation test proves only that the coroutine completes with `CancellationException`; it does not prove `invokeOnCancellation { call.cancel() }` actually cancels the OkHttp call or closes the TCP connection. If the `call.cancel()` hook were removed, this test would still pass because `suspendCancellableCoroutine` itself resumes the cancelled coroutine promptly while the HTTP request keeps running in the background. The design relies on TCP disconnect as the bridge subprocess-kill signal, so this is the critical assertion. suggested fix: Add a socket-level or OkHttp `EventListener` test that fails without `Call.cancel()`, preferably by holding a request open, cancelling the coroutine, and asserting the server observes the client disconnect / cancelled call before the delayed response completes.

3. severity: medium, file: app/src/test/kotlin/ai/closepaw/tool/impl/TermuxShellToolTest.kt:21, description: The unit suite covers the headline ten cases, but misses several checklist-specific contracts: command/cwd/env request-body mapping, HTTP 400 `invalid_request`, tolerance of extra bridge response fields, and validation failures for unknown params / wrong `cwd` type / non-string `env` values. The implementation currently appears to handle these, but regressions would not be caught. suggested fix: Add focused MockWebServer assertions for the full request body and table-driven validation/result-mapping cases, including `400 {"error":"invalid_request"}` and a `200` response with harmless extra fields.

4. severity: medium, file: app/src/test/kotlin/ai/closepaw/agent/cognition/policy/TurnToolPolicyTest.kt:12, description: Phase 4 changes `TurnToolPolicy` so only cognitive tools are hoisted, but there is no regression test proving `termux_shell` preserves LLM ordering relative to `mobile_action`, `shell`, or `ask_user`. The existing tests still exercise cognitive hoisting and screen-tool retention, but not the new shell-like ordering contract. suggested fix: Add cases such as `[mobile_action, termux_shell]` and `[termux_shell, mobile_action]` and assert the order is unchanged, plus a mixed `[scratchpad, mobile_action, termux_shell]` case showing only the cognitive tool is hoisted.

## Notes

- Focused verification passed: `./gradlew :app:testDebugUnitTest --tests 'ai.closepaw.tool.impl.TermuxShellToolTest' --tests 'ai.closepaw.agent.cognition.policy.TurnToolPolicyTest'`.
- `TermuxShellTool` maps HTTP 200, non-zero `exit_code`, and `timed_out=true` to `Success`; maps 409 to `bridge_busy`; maps 400 `workspace_escape` / `invalid_request`; and maps IO failures to `bridge_unavailable`.
- The timeout ladder is mostly wired correctly: bridge `timeout_ms` is clamped to 120s and OkHttp `readTimeout` / `callTimeout` are set to `timeout_ms + 5000ms`.
- The per-invocation OkHttp client is built from the shared client with `newBuilder()`, so dispatcher and connection pool sharing are preserved.
- `ToolName.TermuxShell` is wired as `termux_shell`, `isScreenChanging=false`, and `ToolUi` keeps the sealed `when` exhaustive.
- File sizes are within the 400-line limit: `TermuxShellTool.kt` 335 lines, `TermuxShellToolTest.kt` 213 lines, `ToolName.kt` 226 lines, `TurnToolPolicy.kt` 119 lines, `ToolUi.kt` 64 lines.
