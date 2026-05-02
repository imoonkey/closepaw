# Engineering Plan Review: Browser CDP Script Runtime

Reviewer: Claude
Design: `projects/active/browser/design_codex.md` (draft v1)
Date: 2026-04-29

---

## Plan Summary

Add browser automation to ClosePaw by connecting to Android Chrome's CDP (Chrome DevTools Protocol) through its local abstract Unix socket. Shizuku provides the elevated privileges needed to access the socket. The agent gets one tool (`browser_script`) that runs a JavaScript automation script in a hidden WebView, where `cdp()` calls bridge through Kotlin to the real Chrome instance.

The architecture has four layers:

1. **ShizukuChromeDevtoolsBridge** — Shizuku-privileged access to `chrome_devtools_remote` socket, HTTP bootstrap (`/json/version`, `/json/list`), and WebSocket proxying.
2. **ChromeCdpClient** — WebSocket CDP session management: command/response matching, page session routing, stale-session recovery, event buffering, dialog tracking.
3. **BrowserScriptRunner** — Hidden WebView as a JS runtime. Injects a prelude that exposes `cdp()`, `sleep()`, `log()`. Bridges JS promise resolution to Kotlin CDP responses via `@JavascriptInterface`.
4. **BrowserScriptTool** — ToolSpec implementation that validates input, invokes the runner, returns truncated results to the LLM, and gates registration on Shizuku availability.

Helper functions (`pageJs`, `newTab`, `listTabs`, `switchTab`, `waitForLoad`, `screenshot`, `clickAt`, `typeText`) are injected JS, not separate tools.

---

## What Already Exists

| Area | What's There | Relevance |
|------|-------------|-----------|
| **Tool system** | `ToolSpec`/`ToolInvocation`/`ToolRegistry`/`ToolRouter` with policy engine, observation capture, cancellation tokens | BrowserScriptTool plugs directly into this. Registration in `SessionToolingBootstrapper` is one line. |
| **Shizuku integration** | `ShizukuClient`, `ShizukuRuntimeGateway`, `ShizukuShellExecutor`, binder proxy infrastructure, hidden API bypass | Permission checking and binder lifecycle are solved. But the existing Shizuku code is oriented toward display/input binder proxies — no socket-level or UserService pattern exists yet. |
| **Shell execution** | `ShellTool` with timeout, output capping, cancellation | Good pattern reference for BrowserScriptTool's timeout/output-capping behavior. |
| **Trace system** | `TraceRecorder` with `storeText`/`storeBytes`/`emit` | BrowserCdpTrace can use `TraceRecorder` directly — no need for a separate trace subsystem. |
| **OkHttp** | 4.12.0 in dependencies | WebSocket client is available out of the box (`OkHttpClient.newWebSocket`). |
| **WebView** | No current WebView usage in the app | First introduction — needs careful lifecycle management. |
| **ToolName** | Sealed class with `isScreenChanging` property | `browser_script` needs a new entry. It is screen-changing (it can click, navigate, modify page state in Chrome). |
| **ToolObservation** | `ScreenState` and `TextOutput` variants | BrowserScriptTool should return `TextOutput`. No accessibility tree equivalent for browser content. |

---

## Findings

### F1 — Shizuku Socket Transport Is the Critical Unknown (severity: high, risk)

The design correctly identifies this as the riskiest piece. Chrome's `chrome_devtools_remote` is an abstract Unix socket typically protected by SELinux policy (`untrusted_app` cannot connect to it). The spike-first approach is right, but the design needs a clearer decision tree:

1. **Direct `LocalSocket` from app process** — likely blocked by SELinux (`neverallow` on most ROMs for `chrome_devtools_remote_socket`).
2. **Shizuku UserService** — runs as shell UID (2000). Shell can connect to Chrome's DevTools socket on most stock ROMs. This is the probable path.
3. **`ShizukuShellExecutor` + socat/forwarding** — design says "no external binaries," but if UserService binder callbacks prove too complex for WebSocket framing, a shell-based port-forward (using only `sh` built-ins or toybox) could bridge to a localhost TCP socket that OkHttp connects to.

**Recommendation:** Budget the spike to test all three paths explicitly, and document the SELinux context required. Also verify on at least two ROM families (stock Pixel + Samsung OneUI) since SELinux policy varies.

### F2 — Chrome Must Be Launched with `--enable-remote-debugging` (severity: high, blocker risk)

The design assumes `chrome_devtools_remote` socket exists. On user devices, Chrome does **not** expose this socket by default. It requires either:

- `chrome://flags#enable-remote-debugging` (user action, persists)
- Launch Chrome with `--enable-remote-debugging` via `am start` (requires shell, and flags don't persist across app restarts on most builds)
- WebView-based Chrome builds sometimes expose it; the main Chrome app does not without opt-in

This is not mentioned anywhere in the design. If the socket doesn't exist, the entire architecture is dead. The design must address: **how does Chrome get into a debuggable state?**

Options:
- Require the user to enable the flag manually (document in onboarding)
- Use `ShizukuShellExecutor` to set the flag via `am` or `content provider` at session start
- Detect and prompt

This must be resolved before the CDP bridge spike.

### F3 — WebView JS Host Has Threading Constraints (severity: medium)

`WebView` must be created and operated on a thread with a `Looper` (typically the main thread). The design's `BrowserScriptRunner` uses a hidden WebView as a JS runtime. Key constraints:

- `@JavascriptInterface` callbacks run on a WebView internal thread, not the main thread or the coroutine dispatcher.
- `evaluateJavascript()` must be called from the main thread.
- Bridging CDP responses back into the WebView (`__cdpResolve`/`__cdpReject`) requires `evaluateJavascript()` calls, which must be posted to the main thread.

The prelude code handles promise resolution correctly in concept, but the Kotlin side needs to `post` resolution calls to the main looper. This is solvable but not trivial — a missed thread switch will crash or silently fail.

**Recommendation:** Consider creating the WebView on a dedicated `HandlerThread` with its own `Looper` to avoid blocking the main thread during long scripts. Test that `evaluateJavascript` works on a non-main `Looper` thread (it does on API 31+, which is our minSdk).

### F4 — WebSocket Lifecycle vs Session Lifecycle (severity: medium)

The design defines a state machine (Idle → Starting → Connected → RunningScript → Broken) but doesn't specify where the CDP client lives relative to `AgentSession`. Key questions:

- Does the CDP connection persist across tool calls within a session? (Design implies yes — "Connected" state persists.)
- Does it survive session pause/resume?
- What happens on Shizuku binder death mid-script?
- Who cleans up the WebSocket and WebView on session shutdown?

The existing `AgentSession` lifecycle (Created → Running → Idle/Paused → Shutdown) needs explicit mapping. The CDP client should probably be scoped to the session and cleaned up in the `Shutdown` transition, similar to how `VirtualDisplayPlatform` manages its lifecycle through `VdLifecycleArbiter`.

**Recommendation:** Add a lifecycle section that maps CDP states to session states. The `BrowserScriptRunner` instance should be held by something session-scoped (e.g., `SessionServices` or a new `BrowserSessionManager`), not by the tool instance itself.

### F5 — Output Truncation Strategy Is Underspecified (severity: medium)

The design says "Return compact text output" and "Oversized outputs are truncated with an explicit truncation marker." But:

- What's the max output size? (ShellTool uses 4096 chars.)
- How are screenshots handled in the output? Base64 PNG can be 100KB+. The design says "Store screenshots as trace artifacts" but doesn't say whether a reference or thumbnail goes to the LLM.
- `log()` calls accumulate — unbounded log output could bloat the result.
- CDP responses for DOM queries can be very large (full document HTML).

**Recommendation:** Define explicit limits: max result JSON size (e.g., 8KB for text, with a note that screenshots go to trace only and the LLM gets a `[screenshot saved]` marker), max log entries, and a truncation strategy for large CDP responses (first N chars + truncation marker, matching ShellTool's pattern).

### F6 — BrowserCdpTrace Should Use Existing TraceRecorder (severity: low, simplification)

The design proposes a separate `browser/trace/BrowserCdpTrace` component. The existing `TraceRecorder` interface already supports `storeText`, `storeBytes`, and structured event recording. CDP commands/responses can be stored as trace events with `type = "cdp_command"` / `type = "cdp_response"`, and screenshots as byte artifacts.

**Recommendation:** Drop the separate `BrowserCdpTrace` class. Instead, have `ChromeCdpClient` accept a `TraceRecorder` instance and use `emit()` + `storeBytes()` directly. This avoids a parallel trace system and keeps all trace data in the same run folder.

### F7 — PolicyEngine Integration Needs Thought (severity: medium)

`browser_script` can navigate to any URL including banking sites, read cookies, execute arbitrary page JS, and exfiltrate data. The design says "No policy matrix in v0" and gates on Shizuku availability only.

This is acceptable for an experimental gate, but consider:

- `isScreenChanging` should be `true` for `browser_script` (it mutates Chrome state).
- The tool should probably be classified as requiring approval in `SMART` mode at minimum, since it's more powerful than any existing tool.
- Consider whether `AppClassifier` should treat Chrome package name as the context for policy decisions during browser tool calls.

**Recommendation:** Even without a full policy matrix, register `browser_script` as screen-changing and default to `AskUser` in SMART mode. This is one line in `PolicyEngine` and prevents accidental auto-approval of arbitrary web automation.

### F8 — `@JavascriptInterface` Security Surface (severity: medium)

The `AndroidBrowserScript` interface exposed to the WebView runs agent-authored JavaScript. The agent's script is LLM-generated. If the LLM is manipulated (prompt injection via page content), a malicious script could:

- Call `AndroidBrowserScript.send()` with crafted CDP commands
- Call `AndroidBrowserScript.done()` to return false success
- Exploit any other methods on the interface

The design's interface (`send`, `done`, `log`) is minimal, which is good. But ensure:

- No other methods are exposed on the `@JavascriptInterface` object.
- The WebView has no network access (it's a local JS runtime, not a browser).
- `WebSettings.setAllowFileAccess(false)`, `setAllowContentAccess(false)`, etc.

**Recommendation:** Add a "Security hardening" subsection to the BrowserScriptRunner section listing the WebView lockdown settings.

### F9 — Conditional Tool Registration Pattern (severity: low, design alignment)

The design says "Register only when Shizuku is available or when an experimental setting is on." The existing tools are all registered eagerly in `SessionToolingBootstrapper.registerBuiltInTools()`. Conditional registration is new.

Options:
- Register conditionally in `registerBuiltInTools()` with a guard (simple, follows existing pattern)
- Register lazily like `DelegateTaskTool` and `AskUserTool` (registered in `SessionAgentRunner.start()`)
- Always register but fail at execution time with a clear error

**Recommendation:** Use the lazy registration pattern (matching `DelegateTaskTool`). The tool should not appear in the LLM's tool list if the capability isn't available — it wastes prompt tokens and confuses the model.

### F10 — File Layout Creates a New Top-Level Package (severity: low, convention)

The design places everything under `ai.closepaw.browser.*`. Existing packages are `ai.closepaw.tool.*`, `ai.closepaw.platform.*`, `ai.closepaw.trace.*`, etc. A new `browser` top-level package is reasonable given the scope, but:

- `browser/cdp/shizuku/` contains Shizuku code separate from `platform/virtualdisplay/` where all existing Shizuku code lives.
- `browser/trace/` (if kept) would be parallel to the existing `trace/` package.

**Recommendation:** Keep `ai.closepaw.browser.*` as the top-level package — the scope justifies it. But share the Shizuku permission-checking code from `ShizukuRuntimeGateway` rather than duplicating it. And use the existing `TraceRecorder` (see F6).

### F11 — Helper Library Ordering (severity: low, task sequencing)

The task graph puts `helper-library-v0` after `browser-script-tool`. This means the tool ships before helpers like `pageJs`, `screenshot`, `clickAt` exist. The agent would need to use raw CDP calls until that task completes.

This is fine for incremental development, but consider: the SKILL.md / tool description that teaches the agent how to use `browser_script` should be drafted in the `browser-script-tool` task with raw CDP examples, then updated in `helper-library-v0` to reference helpers. Otherwise the agent has no guidance.

---

## Test Plan

### Unit Tests (JVM, no device)

| Test | What It Verifies |
|------|-----------------|
| `BrowserScriptPreludeTest` | Prelude JS generates correct `cdp()`, `sleep()`, `log()` functions; wrapper handles return values and exceptions |
| `BrowserScriptHelpersTest` | `pageJs` wraps expressions vs function bodies correctly (top-level return detection); `newTab` uses create-blank-then-navigate; `listTabs` filters internal targets; `screenshot` caps dimensions |
| `ChromeCdpResultFormatterTest` | Handles `unserializableValue` (NaN, Infinity, -0, BigInt); JS error formatting with line/column; snippet truncation at 160 chars |
| `ChromeCdpCommandRoutingTest` | `Target.*` and `Browser.*` route without page session; other domains use active session; `options.sessionId` overrides; `options.targetId` attaches then routes |
| `StaleSessionRecoveryTest` | "Session with given id not found" triggers reattach; retries command once; does not retry on other errors |
| `BrowserScriptToolTest` | Validates empty script; caps timeout; truncates oversized output |
| `InternalTargetFilterTest` | Filters `chrome://`, `devtools://`, `chrome-extension://`, `about:` targets |

### Integration Tests (device, requires Shizuku + Chrome)

| Test | What It Verifies |
|------|-----------------|
| CDP bridge spike | `LocalSocket` or UserService can connect to `chrome_devtools_remote`; `/json/version` returns valid JSON |
| Page target listing | `/json/list` returns targets; filtering removes internal pages |
| Basic CDP round-trip | `Runtime.evaluate("1+1")` returns `{result: {value: 2}}` through the full stack |
| Script execution E2E | `browser_script({ script: 'return await pageJs("document.title")' })` on `example.com` returns the title |
| New tab + navigation | `newTab("https://example.com")` creates tab, navigates, `waitForLoad` resolves |
| Screenshot capture | `screenshot()` returns base64 data; dimensions are within `maxDim` |
| Stale session recovery | Close a tab externally, next CDP command recovers to a new tab |
| Timeout enforcement | Script with `await sleep(60000)` is killed at the tool timeout |

### Manual QA Checklist

- [ ] Full flow on stock Pixel (Android 14+): enable Chrome debugging, Shizuku running, execute multi-step script
- [ ] Full flow on Samsung OneUI: verify SELinux doesn't block the socket path
- [ ] Chrome not running: tool returns clear error, does not crash
- [ ] Shizuku not available: tool is not registered / returns clear error
- [ ] Chrome has JS dialog open: script reports dialog state before attempting `Runtime.evaluate`
- [ ] Session shutdown during active script: no leaked WebView or WebSocket
- [ ] Binder death during script: transitions to Broken, next call attempts reconnect

---

## Not In Scope

Per the design doc, these are explicitly deferred:

- **Policy/permission matrix for web content** — no URL allow/deny lists, no cookie protection rules. Acceptable for experimental gate; required before any production rollout.
- **Termux integration** — no external runtime dependency.
- **Playwright/Puppeteer wrapper API** — raw CDP only.
- **Multi-tool surface** (`browser_click`, `browser_type`, etc.) — single `browser_script` tool.
- **Cloud browser / profile sync** — local Chrome only.

Additionally not in scope but worth noting:

- **Chrome debugging auto-enablement** — the design assumes the socket exists (see F2).
- **Multi-browser support** — only Chrome's DevTools socket is targeted.
- **Concurrent script execution** — one script at a time implied by the state machine.

---

## Must-Decide-Before-Implementation

1. **How does Chrome become debuggable?** (F2) — The `chrome_devtools_remote` socket doesn't exist by default. Decide: user-manual flag, automated enablement via Shizuku, or both. This blocks the CDP bridge spike.

2. **Direct socket vs Shizuku UserService** (F1) — The spike should test both, but the design should commit to a primary path and fallback before `cdp-client-core` starts. If UserService is needed, that's a new AIDL interface and binder service — non-trivial scope.

3. **Where does the BrowserScriptRunner live in the session lifecycle?** (F4) — Decide whether it's session-scoped (created on first use, destroyed on shutdown) or tool-scoped (created per invocation). Session-scoped is clearly better but needs an owner. Propose: add it to `SessionServices` or create a `BrowserSessionManager` injected into the tool.

4. **Output size limits** (F5) — Decide max text output, screenshot handling (trace-only vs inline), and log cap before implementing the tool. This affects the tool description the LLM sees.

5. **PolicyEngine classification** (F7) — Decide `isScreenChanging` value and default approval mode for `browser_script` before registering the tool. Recommend: screen-changing = true, SMART mode = AskUser.

---

## Next Step

**Run the CDP bridge spike (`cdp-bridge-spike` task) with an explicit prerequisite: verify Chrome debugging socket availability.** Before writing any CDP client code:

1. On a test device with Shizuku, confirm whether `chrome_devtools_remote` socket exists with Chrome's remote debugging flag enabled.
2. Test `LocalSocket` connection from the app process — expect SELinux denial.
3. Test connection from a Shizuku UserService (shell UID) — expect success.
4. Document the exact steps the user must take to enable Chrome debugging.

Only after the socket transport is proven should work begin on `cdp-client-core`. Everything else in the design is sound architecture on top of an unproven transport.
