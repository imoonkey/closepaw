# Browser CDP Runtime — Holistic Review

## Summary

The change set mostly preserves the intended shape: one thin `browser_script` tool, a minimal WebView prelude exposing only `cdp()`, session-scoped ownership, and bundled `browser-use` snippets. The runtime ships with 5 deferred-to-followup High items; none are merge-blocking by virtue of being tracked as follow-up tasks.

## KISS

- The main product surface is still KISS: one agent-facing tool, no `browser_click`/`browser_type` family, and snippets stay outside the native runtime.
- The production seams mostly earn their keep: `BrowserScriptCapabilityGate` keeps the tool Android-free, `CdpConnectionFactory` makes the CDP client testable, and `BrowserSessionManager` owns browser resources instead of `SessionToolingBootstrapper`.
- The error model is starting to split across `DevtoolsSetupError`, `CdpException`, `ScriptResult`, and `BrowserScriptOutcome`. That can be acceptable if each layer has a crisp boundary, but current timeout and screenshot paths show the mapping is incomplete. Avoid adding another taxonomy; fix the cross-layer mappings first.
- The fakes are useful, but they also hid the biggest transport issue: `FakeCdpConnection` can connect to any `ws://...` URL, while the real Android path needs WebSocket frames over `chrome_devtools_remote`, not a normal network WebSocket.

## Security/policy

- `browser_script` is explicitly screen-changing, and `PolicyEngine` handles it before the user allow-list. In SMART mode it asks even for `com.android.chrome` when Chrome is `NORMAL`, and tests cover that rule.
- Runtime gates are execution-time gates: experimental setting, Shizuku availability, Shizuku permission, and Chrome DevTools preflight run on every tool call.
- High: screenshots can still enter prompt text as base64. `BrowserScriptTool` returns successful `ScriptResult.Ok` payloads verbatim, and `browser-use/scripts/page.js` returns `Page.captureScreenshot`'s `data` field directly. The design requires screenshots to be stored as trace artifacts and represented by compact markers, never base64 in tool output.
- The hidden WebView bridge exposes only `send` and `done` as `@JavascriptInterface` methods, and bundled snippets do not reference `AndroidBrowserScript`. Direct calls to `send` are equivalent to raw `cdp()` access, so the important boundary is still the tool/policy gate plus future URL/method policy.
- v1 intentionally lacks URL/method-level policy. That is acceptable for the spike only because the experimental toggle, Shizuku permission, and SMART approval are in place; it should stay called out before any production-default rollout.

## Session lifecycle

- `SessionServices.create()` installs skills, registers `BrowserScriptTool`, and creates a session-scoped `BrowserSessionManager` without allocating browser resources immediately. `SessionServices.cleanup()` closes the manager before platform, LLM, and trace teardown.
- `BrowserSessionManager.run()` enforces one script per session with a mutex and keeps healthy CDP resources warm between calls.
- High: cancellation/timeout can still inject user JavaScript after the call is no longer live. `ScriptHostWebViewClient.onPageFinished()` injects the prelude and user script without checking the runner cancellation flag in the page-finished callback or the prelude-evaluation callback.
- High: command response timeouts do not become useful JS rejections or a broken runtime. `ChromeCdpClient.sendRaw()` uses `withTimeout`, but `BrowserScriptBridge.dispatch()` treats all `CancellationException`s as coroutine cancellation and rethrows, leaving the JS promise unresolved until the whole tool times out.
- Medium: `BrowserSessionManager.close()` cancels the runtime job and closes CDP/bridge resources, but it does not await an active runner's WebView teardown. The runner likely unwinds through structured concurrency, but shutdown is not deterministic from the caller's perspective.

## Shizuku/CDP transport

- High: the core WebSocket transport does not follow the design. `ShizukuChromeDevtoolsBridge` uses the app process/UserService transports only for `/json/version` and `/json/list`; `BrowserSessionManager` then passes the returned `webSocketDebuggerUrl` to `ChromeCdpClient`, whose production factory opens a normal OkHttp WebSocket. On Android Chrome, the CDP WebSocket must be framed over the same `chrome_devtools_remote` localabstract socket or a deliberate proxy/forwarder.
- Setup failures are well separated at bootstrap time (`shizuku_unavailable`, permission missing, Chrome not running, socket missing/inaccessible, malformed response). Runtime CDP failures are less consistent: protocol errors become `CdpException`, transport failures mark broken, but command timeouts are treated like cancellation and do not mark or reconnect the runtime.
- Stale active-session recovery is implemented in the intended exactly-once shape: detect "Session with given id not found", run `Target.getTargets`, attach the first real page, and retry once.
- Dialog tracking is not implemented beyond generic event buffering. The design calls out `Page.javascriptDialogOpening` / `Closed` state because alerts can freeze page JS.

## WebView host hardening

- The WebView lockdown is broadly aligned: no file/content access, no universal file URL access, blocked network loads/images, no DOM storage/database, no mixed content, no multi-window, and only the local blank host document is loaded.
- The JS prelude is minimal and does not add `sleep`, `log`, `pageJs`, tab, or input helpers.
- High: the cancellation guard is incomplete around `onPageFinished()` and the prelude callback, so a timed-out/cancelled script can still start running before teardown drains the main-thread queue.
- Navigation interception blocks loads after the initial page. That is sufficient for the blank host, but the cancellation race above is the higher-priority hardening gap because it can execute the exact script the user/tool runtime already abandoned.

## browser-use skill packaging

- The install model is correct: APK assets seed `context.filesDir/skills/browser-use`, `{{SKILL_DIR}}` is rewritten to the installed absolute path, `.install-complete` is written only after copy succeeds, and refresh failures keep a prior sentinel-marked install.
- The bundled `SKILL.md` points to installed `page.js`, `tabs.js`, and `input.js` paths and tells the agent to `cat` only the needed bundle before inlining helpers.
- Snippets are self-contained around `cdp()` and do not depend on the hidden bridge, CommonJS, or filesystem APIs.
- High from another dimension applies here too: `page.js`'s `screenshot()` returns base64 `data`. Once native screenshot artifact formatting exists, the snippet should return the artifact marker/metadata instead.

## Tests

- JVM coverage is broad for policy, tool validation/output/cancellation metadata, setup-error parsing, routing, stale-session recovery, bridge promise resolution/rejection, bundled skill installation, and session registration/cleanup.
- There is one instrumented WebView round-trip test, but it uses a fake CDP connection. It validates the hidden WebView prelude, not real Shizuku/Chrome transport.
- Important gaps remain:
  - no test proves CDP WebSocket traffic is carried over the Shizuku/localabstract transport;
  - no test prevents screenshot base64 from reaching tool output;
  - no test covers CDP command response timeout mapping/recovery;
  - no test covers cancellation before `onPageFinished()` / prelude callback;
  - no test covers stale `ServiceConnection` callback identity or unbind-on-failure for Shizuku UserService;
  - snippet checks are mostly static; there is no syntax/top-level-return harness for `page.js`.

## Docs

- `doc/main/infra/browser.md`, `doc/main/infra/tools.md`, and `doc/main/agent/agent_skills.md` cover the new architecture and point to `projects/active/browser/cn/design_codex.md` as the source of truth.
- The source-of-truth design was not edited during this review.
- There is a confusing duplicate `projects/active/browser/design_codex.md` in the change set, while tasks and stable docs point at `projects/active/browser/cn/design_codex.md`. `projects/active/browser/eng_plan_review_claude.md` still references the non-`cn` path. This should be cleaned up or explicitly marked historical to avoid future agents reading the wrong design.
- The docs currently describe the intended Shizuku-backed browser CDP runtime, but the implementation still opens a normal OkHttp WebSocket. Update docs after the transport fix so they describe the actual production path.

## High findings (converted to follow-up tasks)

No open critical/high findings — all High findings have been converted to follow-up tasks.

1. High — CDP WebSocket bypasses the Shizuku/localabstract transport after bootstrap.
   Task: `browser-cdp-websocket-shizuku-tunnel` (NEW).
   Description: `ShizukuChromeDevtoolsBridge` only performs HTTP bootstrap over the app/UserService socket transports, then `BrowserSessionManager` passes `webSocketDebuggerUrl` into `ChromeCdpClient`, and the production connection factory opens it with OkHttp as a normal network WebSocket. This violates the design's "LocalSocket(localabstract) -> HTTP bootstrap + WebSocket framing" transport and is likely non-functional on real Android Chrome unless an external TCP forwarder happens to exist.
   Remediation: implement a `CdpConnectionFactory` backed by the selected `DevtoolsSocketTransport`/UserService path, including WebSocket upgrade/framing over `chrome_devtools_remote`, or explicitly create and own a local proxy/forwarder. Validate that `webSocketDebuggerUrl` is only used for the DevTools path, not as an arbitrary network destination.

2. High — Screenshot CDP results can leak base64 into prompt/tool output.
   Task: `browser-screenshot-artifact-formatting` (NEW).
   Description: `BrowserScriptTool` returns successful runner JSON directly, and `browser-use/scripts/page.js` returns `Page.captureScreenshot`'s `data` field. This violates the design requirement that screenshots never appear as base64 in tool output and should be stored through `TraceRecorder.storeBytes`.
   Remediation: add native screenshot result formatting for `Page.captureScreenshot`: decode/store bytes as a trace artifact, replace `data` with compact metadata/artifact id, and update `page.js` plus tests to assert no base64 reaches user-facing output.

3. High — WebView cancellation race can execute user script after timeout/cancel.
   Task: `browser-script-host-cancel-guard` (EXISTING).
   Description: `ScriptHostWebViewClient.onPageFinished()` and its prelude `evaluateJavascript` callback inject the prelude/user script without checking whether the call has already been cancelled. A queued page-finished callback can therefore run before teardown and start arbitrary agent JS after the runtime has abandoned the tool call.
   Remediation: pass a cancellation predicate into `ScriptHostWebViewClient`; check it before prelude injection and again inside the prelude-evaluation callback. Add a regression test for cancellation before `onPageFinished()`.

4. High — Shizuku UserService binding can be corrupted by stale callbacks and failure callbacks do not unbind.
   Task: `browser-cdp-bridge-spike-hardening` (EXISTING).
   Description: `ShizukuUserServiceProvider` stores one mutable `connection`/`pendingCont`, but callbacks do not verify they belong to the current bind generation. A stale `onServiceConnected` can install a transport for a newer/closed obtain, and `onServiceDisconnected` / `onBindingDied` / `onNullBinding` complete with error without unbinding/clearing the failed connection.
   Remediation: add a per-bind generation or connection identity token, ignore stale callbacks, clear connection state on terminal failures, call `unbindUserService` for each failed bind, and extend tests. This overlaps the existing `browser-cdp-bridge-spike-hardening` follow-up task.

5. High — CDP command timeouts leave JS promises unresolved and do not mark the runtime broken.
   Task: `browser-cdp-command-timeout-recovery` (NEW).
   Description: `ChromeCdpClient.sendRaw()` times out with `TimeoutCancellationException`; `BrowserScriptBridge.dispatch()` rethrows all `CancellationException`s instead of rejecting the specific CDP promise. The script then waits for the global tool timeout, and `BrowserSessionManager` does not mark the CDP connection broken for that path.
   Remediation: map command response timeouts to a typed CDP error that rejects the JS promise and decide whether that timeout marks the client broken/reconnectable. Add tests for promise rejection, trace outcome, and next-call recovery.

## Follow-up tasks recommended

- NEW `browser-cdp-websocket-shizuku-tunnel`: Implement the real Shizuku/localabstract-backed CDP WebSocket connection and remove reliance on a normal OkHttp network WebSocket URL.
- NEW `browser-screenshot-artifact-formatting`: Store `Page.captureScreenshot` payloads as trace artifacts and return compact screenshot metadata instead of base64.
- EXISTING `browser-script-host-cancel-guard`: Finish the existing cancellation-guard follow-up for `onPageFinished()` and prelude callback races.
- EXISTING `browser-cdp-bridge-spike-hardening`: Finish the existing UserService bind-generation and failure-unbind hardening task.
- NEW `browser-cdp-command-timeout-recovery`: Convert command response timeouts into JS rejections and deterministic broken/reconnect behavior.
- NEW `browser-dialog-state-tracking`: Track `Page.javascriptDialogOpening` and `Page.javascriptDialogClosed` so helpers can report dialog state before page JS freezes.
- NEW `browser-use-snippet-harness`: Add syntax/static execution tests for snippet bundles, especially `pageJs` top-level return detection and screenshot helper output shape.
- NEW `browser-doc-source-cleanup`: Remove or clearly mark the duplicate non-`cn` browser design document and update stale references.
