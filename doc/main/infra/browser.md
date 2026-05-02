# Browser CDP Runtime

> Session-scoped `browser_script` runtime: `BrowserSessionManager`, Chrome CDP transport,
> Shizuku bridge ownership, settings gate, and policy.
> Last updated: 2026-05-02 (browser-session-integration)

Design source of truth: [projects/active/browser/cn/design_codex.md](../../../projects/active/browser/cn/design_codex.md).

## Overview

`browser_script` is the single agent-facing entry point for browser automation. The tool runs
JavaScript inside an app-owned hidden WebView, but the WebView is only the script host. The target
browser is the user's real Android Chrome profile reached through Chrome DevTools Protocol (CDP)
over the Shizuku bridge to `chrome_devtools_remote`.

The runtime exposes one primitive to scripts:

```javascript
await cdp(method, params = {}, options = {})
```

There are no native `browser_click`, `browser_type`, or helper APIs. Loops, retries, parsing, and
workflow-specific helpers live in the submitted JavaScript or installed agent skill snippets.

## Session Ownership

`SessionServices.create()` owns browser runtime wiring:

- Creates a session-scoped `BrowserSessionManager` with `context.applicationContext` and the session
  coroutine scope.
- Registers `BrowserScriptTool` in `ToolRegistry`.
- Wires `DefaultBrowserScriptCapabilityGate` to `AppSettingsStore`, `ShizukuStatusAdapter`,
  `BrowserSessionManager.preflight()`, and a `BrowserScriptInvoker`.
- Writes `browser_script` trace metadata through the session `TraceRecorder`.

`SessionToolingBootstrapper` stays Android-free. It creates policy, state, core built-in tools, and
the router, but it does not receive `Context`, construct WebViews, or own Shizuku/CDP resources.

## Lifecycle

`BrowserSessionManager` construction is cheap. It does not allocate a WebView, CDP socket, or
Shizuku UserService binding until capability preflight or the first script call needs them.

- Session created: manager exists, browser transport is idle.
- Capability gate: checks experimental setting, Shizuku availability, Shizuku permission, then
  bridge preflight.
- First `browser_script`: acquires the per-session script lease, fetches Chrome `/json/version` and
  page targets, opens the CDP WebSocket, attaches the first real page, enables core domains, and
  runs the script host.
- Running/Idle: healthy CDP resources stay warm for follow-up calls.
- Transport failure, synchronous send failure, or server WebSocket close: manager marks the runtime
  broken, closes both CDP and Shizuku bridge/UserService resources, and the next call rebuilds from
  scratch.
- Session cleanup: `SessionServices.cleanup()` closes `browserSessionManager` before platform, LLM,
  and trace teardown.

Only one `browser_script` can run per session at a time. A concurrent invocation returns a clear
busy failure instead of sharing mutable Chrome target/session state.

## Settings And Policy

`browserScriptEnabled` is persisted in `AppSettingsStore` as `browser_script_enabled`, defaults to
`false`, and is exposed in Settings under **Permissions & Advanced → Experimental → Browser
automation (experimental)**.

`ToolName.BrowserScript` is screen-changing because CDP can mutate Chrome tabs, profile state,
dialogs, navigation, and page content even when Chrome is not foreground. `PolicyEngine` applies a
browser-specific rule after the BLOCKED-app floor and before user allow-lists:

| Approval mode | `browser_script` decision |
|---------------|---------------------------|
| `ALWAYS_ASK` | Ask |
| `AUTO_APPROVE` | Allow at policy; the tool still applies runtime capability gates |
| `SMART` | Ask, including for `com.android.chrome` even though Chrome is `NORMAL` |

The session and persistent allow-lists do not bypass the SMART-mode browser prompt.

## Verification

Primary regression coverage:

- `PolicyEngineTest` covers the Chrome SMART-mode ask rule and allow-list bypass guard.
- `SessionBrowserIntegrationTest` covers `browser_script` registration.
- `SessionServicesCleanupTest` covers shutdown cleanup after browser use.
- `BrowserSessionManagerTest` covers transport failure, synchronous send failure, server close, full
  CDP/bridge teardown, and reconnect on the next run.
- `AppSettingsStoreTest` covers the experimental flag default and round-trip persistence.
