# Browser CDP Script Runtime for ClosePaw

Status: draft v1

Reference checkout: `.reference/browser-harness`

## Goal

Add a minimal, programmable browser automation path to ClosePaw for users who
have enabled Shizuku and granted ClosePaw Shizuku permission.

The target capability is browser-harness-style control of the user's real
Chrome through CDP: open pages, inspect DOM/page state, run page JavaScript,
click/type through Chrome input domains, capture screenshots, and manage tabs.

This is not OpenWeb. This is not Custom Tabs. This is not WebView browser
automation. The browser is Android Chrome with the user's real profile.

## Reference Model

`browser-harness` is intentionally thin:

- `SKILL.md` tells the agent to use one script command, not a large tool surface.
- `helpers.py` exposes `cdp("Domain.method", params)` as the raw escape hatch.
- `daemon.py` owns the CDP websocket, default session, event buffer, dialog
  state, stale-session recovery, and real-tab attachment.
- Interaction skills document durable mechanics such as tab handling and
  screenshot coordinate caveats.

ClosePaw should copy the shape, not the Python runtime:

```text
Agent
  -> browser_script(script)
      -> JS automation host inside ClosePaw
          -> cdp(method, params, options)
              -> Kotlin CDP client
                  -> Shizuku Chrome DevTools bridge
                      -> Android Chrome CDP
```

## Non-Goals

- No Termux dependency for v1.
- No Playwright/Puppeteer wrapper API.
- No many-tool surface such as `browser_click`, `browser_type`, or
  `browser_open`.
- No thick built-in browser helper runtime. Helper snippets live in installed
  `browser-use/scripts/`, and `browser-use/SKILL.md` guides the agent to read
  them when needed.
- No OpenWeb package/runtime integration.
- No policy matrix in v0. Treat this as an experimental/debug capability gated
  by explicit Shizuku setup. Add a full URL/method policy after the transport
  works.
- No cloud browser/profile sync.

## Review Decisions

Claude's engineering review in `eng_plan_review_claude.md` is mostly accepted,
with one correction: Android Chrome CDP should be treated as the official
Android remote-debugging socket path, not as desktop Chrome's
`--remote-debugging-port` launch model.

Accepted changes:

- Make the Shizuku socket bridge the first spike and explicitly distinguish app
  process socket failure from Shizuku UserService success/failure.
- Scope the browser runtime to a session, not to a tool instance.
- Enforce one script at a time per session.
- Mark `browser_script` as screen-changing even if Chrome is not foreground,
  because it mutates external browser/profile state and requires an exclusive
  browser automation lease.
- Use existing `TraceRecorder` instead of a separate browser trace subsystem.
- Define prompt-output caps before implementation.
- Lock down the hidden WebView script host.
- Keep the runtime prelude minimal and expose only `cdp()` to agent scripts.
- Add a `browser-use` skill for richer guidance and reusable snippets, but do
  not make native tool loading skill-gated.

Rejected or rewritten:

- Do not require Termux or external `socat` fallback in the product design.
- Do not model Chrome Android setup as launching desktop-style Chrome with
  `--enable-remote-debugging`. The spike must detect whether
  `chrome_devtools_remote` is available and return a precise setup error if it
  is not.

## Agent-Facing Tool

Expose one tool:

```text
browser_script
```

In v1, `browser_script` is always registered and its schema is always loaded. Do
not change turn/tool loading architecture just to support lazy schema exposure.
The capability is still controlled by execution-time gates:

- The experimental browser automation setting must be enabled.
- Shizuku must be available and authorized.
- The Chrome DevTools socket must be available.
- `browser_script` is screen-changing, so SMART mode asks the user by default.

The tool description stays minimal: explain raw CDP usage and point richer usage
to the `browser-use` skill:

- Installed `browser-use/SKILL.md`: usage strategy, raw CDP examples,
  tab/dialog/screenshot coordinate caveats, verification guidance, and a snippet
  file index.
- Installed `browser-use/scripts/`: JavaScript snippet bundles the agent can
  copy or modify, grouped by workflow rather than one function per file.

The `browser-use` skill does not replace the native runtime.
Kotlin/Shizuku/WebView/CDP transport, permission, lifecycle, timeout, trace,
policy, and the exclusive lease are still implemented by the app and hidden from
the agent.

Skill storage rule: runtime skills are loaded only from `context.filesDir/skills`.
APK assets are only the seed source. On install/startup, copy bundled
`browser-use/SKILL.md` and `browser-use/scripts/` to
`context.filesDir/skills/browser-use/`. `activate_skill` returns only the
`SKILL.md` body, but `SKILL.md` lists absolute paths for installed snippets, so
the agent can use `shell` with a single `cat <skillDir>/scripts/<name>.js` to
read a needed snippet. The current ShellTool permits this file-oriented `cat`
use and does not require shell metacharacters.

Schema:

```json
{
  "type": "object",
  "properties": {
    "script": {
      "type": "string",
      "description": "JavaScript automation script. Use await cdp(method, params, options) to call Chrome DevTools Protocol. For richer examples and reusable snippets, activate/read the browser-use skill."
    },
    "timeout_ms": {
      "type": "integer",
      "description": "Execution timeout. Defaults to 30000; capped by the runtime."
    }
  },
  "required": ["script"],
  "additionalProperties": false
}
```

The script environment provides:

```js
await cdp(method, params = {}, options = {})
```

`cdp(method, params, options)` is the raw CDP escape hatch. Do not wrap each CDP
capability one by one. If Android Chrome exposes the CDP domain/method and
routing is correct, it can be called. Practical boundaries:

- `Target.*` and `Browser.*` are browser-level methods and do not use the page
  session.
- Most page-level domains use the active page session by default.
- To target a specific tab/session, use `options.targetId` or
  `options.sessionId`.
- Android Chrome's CDP surface may differ from desktop Chrome.
- The v1 transport connects only to the user's real Chrome profile. It does not
  provide extra OS/file/provider APIs.
- Production still needs tool/policy restrictions for high-risk URLs/methods.
  Raw capability does not mean every call is allowed by default.

Helpers do not enter the runtime prelude and do not become separate tools. When
the agent needs a helper, it reads, copies, or modifies snippets from installed
`browser-use/scripts/` and includes them in the current `script`. v1 ships only a
few bundle files:

```text
page.js    # pageJs, waitForLoad, screenshot, tiny sleep helper if needed
tabs.js    # listTabs, currentTab, switchTab, newTab
input.js   # clickAt, typeText
```

KISS rule: the runtime prelude only provides `cdp()`. `sleep()`, `pageJs()`, and
similar helpers live as snippets in installed `browser-use/scripts/`, where the
agent can read and inline them when needed. Do not split short, tightly related
helpers into separate files.

## Why Script Instead Of Raw Single-Command CDP Tool

`chrome_cdp(method, params)` would force the LLM to make many turns for loops,
waits, retries, extraction, and verification. `browser_script` lets one tool call
perform control flow:

```js
const response = await cdp("Runtime.evaluate", {
  expression: "document.title",
  returnByValue: true
})
const title = response.result.value
return { title }
```

The browser capability is still raw CDP. The extra value is local control flow in
JavaScript: loops, branching, parsing, and retries without round-tripping through
the LLM after every CDP command. More complex helpers come from
`browser-use/scripts/`, not from the runtime API.

## Runtime Components

### BrowserScriptTool

Location: `app/src/main/kotlin/ai/closepaw/tool/impl/BrowserScriptTool.kt`

Responsibilities:

- Validate script is non-empty.
- Cap timeout.
- Check experimental browser automation setting, Shizuku readiness, and Chrome
  DevTools socket availability; fail closed if any gate is not satisfied.
- Invoke `BrowserScriptRunner`.
- Return compact text output plus raw result metadata for trace/debug.
- In v1, always register and always load the schema. Do not implement
  skill-gated schema exposure.
- Tell the agent in the tool description to use the `browser-use` skill for
  richer examples and reusable snippets.

### BrowserScriptRunner

Location: `app/src/main/kotlin/ai/closepaw/browser/script/`

Responsibilities:

- Own a hidden local WebView used only as a JavaScript runtime.
- Load a blank local page and inject the automation prelude.
- Wrap agent script in an async function.
- Bridge `cdp()` calls to Kotlin via `@JavascriptInterface`.
- Resolve/reject JS promises when CDP results return.
- Enforce tool-call timeout and cancellation.

The WebView is not the browser being automated. It is only the automation script
host. The target browser remains Android Chrome.

Security hardening for the hidden WebView:

- Load only a local blank document.
- Disable network loads.
- Disable file access and content access.
- Disable mixed content and universal file URL access.
- Expose exactly one `@JavascriptInterface` object with only `send` and `done`.
- Correlate all JS calls with the current tool-call id; ignore late callbacks
  after timeout or cancellation.
- Post every `evaluateJavascript` call to the WebView's owning Looper.

The bridge is a runtime implementation detail: the agent script calls
`cdp(method, params, options)` inside the hidden WebView; `cdp()` serializes the
request to JSON and sends it to Kotlin via `@JavascriptInterface`; Kotlin calls
`ChromeCdpClient`; when the result returns, Kotlin uses
`evaluateJavascript("__cdpResolve(...)")` or `__cdpReject(...)` to settle the JS
Promise. The agent-facing prelude exposes only `cdp()`.

Script wrapper:

```js
(async () => {
  try {
    const __result = await (async () => {
      // agent script here
    })()
    AndroidBrowserScript.done(JSON.stringify({ ok: true, result: __result }))
  } catch (e) {
    AndroidBrowserScript.done(JSON.stringify({
      ok: false,
      error: { message: e.message || String(e), stack: e.stack || "" }
    }))
  }
})()
```

Prelude:

```js
const __pending = new Map()
let __nextId = 1

globalThis.cdp = function(method, params = {}, options = {}) {
  const id = __nextId++
  return new Promise((resolve, reject) => {
    __pending.set(id, { resolve, reject })
    AndroidBrowserScript.send(JSON.stringify({ id, method, params, options }))
  })
}

globalThis.__cdpResolve = function(id, result) {
  const pending = __pending.get(id)
  if (!pending) return
  __pending.delete(id)
  pending.resolve(result)
}

globalThis.__cdpReject = function(id, error) {
  const pending = __pending.get(id)
  if (!pending) return
  __pending.delete(id)
  pending.reject(new Error(error.message || String(error)))
}
```

### ChromeCdpClient

Location: `app/src/main/kotlin/ai/closepaw/browser/cdp/`

Responsibilities:

- Maintain the browser websocket and page sessions.
- Allocate CDP command ids.
- Match command responses by id.
- Buffer recent events.
- Track pending JavaScript dialogs.
- Attach to the first real page after connect.
- Reattach after stale session errors.
- Route browser-level methods without a page session.
- Route page-level methods through the active flattened session by default.

Routing rules copied from browser-harness:

- `Target.*` is browser-level and must not use a page session by default.
- `Browser.*` is browser-level.
- Most other domains use the active page session unless `options.sessionId` is
  provided.
- `options.targetId` attaches with `Target.attachToTarget(flatten=true)` and
  sends the command through that session.

### ShizukuChromeDevtoolsBridge

Location: `app/src/main/kotlin/ai/closepaw/browser/cdp/shizuku/`

Responsibilities:

- Use Shizuku privileges to run the Chrome DevTools socket access path.
- Connect to Chrome's abstract Unix socket `chrome_devtools_remote`.
- Resolve `/json/version` and `/json/list` during bootstrap.
- Hold/proxy the CDP websocket.
- Distinguish setup failures:
  - Shizuku unavailable
  - Shizuku permission missing
  - Chrome not running
  - DevTools socket missing
  - socket inaccessible from app process
  - socket inaccessible from Shizuku UserService
  - malformed HTTP/WebSocket response

Preferred product shape:

```text
ClosePaw app process
  -> Shizuku UserService binder
      -> LocalSocket(localabstract:chrome_devtools_remote)
      -> HTTP bootstrap + WebSocket framing
      -> Binder callbacks for command responses/events
```

Spike shortcut:

- First try `LocalSocket` directly from the app process. If SELinux/package
  isolation blocks it, immediately switch to UserService.
- Do not depend on `socat`, Termux, or external binaries.

Android Chrome setup note:

- The official Android CDP path uses Chrome's abstract socket, equivalent to
  `adb forward tcp:9222 localabstract:chrome_devtools_remote`.
- v0 does not promise to automatically enable Chrome debugging. It only detects
  whether the socket is available and returns an actionable setup error.
- The spike must verify the exact user/device prerequisites on target devices
  instead of assuming the socket always exists.
- If the socket is missing, the tool must fail closed and tell the user to open
  Chrome and enable the required Android/Chrome debugging path. It must never
  silently fall back to WebView or Custom Tabs.

### Trace Integration

Use existing `TraceRecorder`. Do not create a parallel browser trace subsystem.

Responsibilities:

- Persist raw CDP commands/responses/events outside prompt context, with
  structured event names `cdp_command`, `cdp_response`, and `cdp_event`.
- Return truncated summaries to the LLM.
- Store screenshots with `TraceRecorder.storeBytes`.
- Store large JSON payloads with `TraceRecorder.storeText`.

Prompt-facing output caps:

- Final tool output: max 8192 chars.
- Individual CDP response summaries: max 4096 chars.
- Screenshots are never put into tool output as base64. The LLM gets only a
  compact marker with dimensions and trace artifact id.
- Every truncation must include an explicit marker, for example
  `[truncated: original_chars=123456]`.

## Browser-Harness Details

These details come from stable browser-harness behavior. Parts directly tied to
CDP transport, raw result formatting, routing, and session recovery go into the
native runtime. Helper-style behavior goes into installed `browser-use/scripts/`,
where the agent can read, trim, or modify snippets for `browser_script`.

### Raw CDP Result Formatting

Native `cdp()` stays protocol-raw. When `Runtime.evaluate` returns
`exceptionDetails`, that is still a successful CDP response; the native runtime
does not rewrite it into a tool failure. The native formatter only:

- Distinguishes CDP protocol errors from successful responses.
- Preserves payloads such as `exceptionDetails` and `result`.
- Summarizes/truncates large JSON.
- Preserves `unserializableValue` visibly instead of silently coercing it.

### `pageJs` Error Formatting

`pageJs` is a snippet in `browser-use/scripts/page.js`, not a native runtime API.
The snippet can turn `Runtime.evaluate` `exceptionDetails` into a clearer
JavaScript evaluation error. It should inspect:

- `exceptionDetails.text`
- `exceptionDetails.exception.description`
- `exceptionDetails.exception.value`
- result `subtype == "error"`
- result `description`

The snippet returns a clear error:

```text
JavaScript evaluation failed at line X, column Y: <description>; expression: <snippet>
```

The snippet should follow browser-harness behavior:

```kotlin
fun jsSnippet(expression: String, limit: Int = 160): String {
    val snippet = expression.trim().replace("\n", "\\n")
    return if (snippet.length > limit) snippet.take(limit - 3) + "..." else snippet
}
```

This prevents multiline agent JS from polluting errors while keeping enough
context to debug the failing expression.

### Unserializable JavaScript Values

Handle CDP `unserializableValue` values:

- `NaN`
- `Infinity`
- `-Infinity`
- `-0`
- BigInt strings ending in `n`

In v0, return them as tagged strings. Do not silently coerce them to null.

### `pageJs` Snippet

`browser-use/scripts/page.js` can provide a `pageJs(expression)` snippet that
supports both expressions and function bodies:

```js
await pageJs("document.title")
await pageJs("const x = document.title; return x")
```

Use a small scanner similar to browser-harness `_has_return_statement`: first
ignore strings, template literals, line comments, and block comments, then decide
whether to wrap the body as:

```js
(function(){ ... })()
```

### `newTab` Snippet

In helpers, do not call `Target.createTarget` directly with the final URL. Create
blank, attach, then navigate:

```js
const { targetId } = await cdp("Target.createTarget", { url: "about:blank" })
await switchTab(targetId)
if (url !== "about:blank") await cdp("Page.navigate", { url })
```

This avoids the race where `about:blank` has already completed and a later
`waitForLoad()` returns before the target navigation starts.

### Internal Target Filtering

Filter internal targets by default:

```text
chrome://
chrome-untrusted://
devtools://
chrome-extension://
about:
```

Expect fake/ephemeral targets such as omnibox popups. If there is no real page,
create `about:blank`.

### Stale Session Recovery

If Chrome returns "Session with given id not found" for the default active
session:

1. Re-run `Target.getTargets`.
2. Select the first real page.
3. `Target.attachToTarget(flatten=true)`.
4. Retry the command once.

v0 does not add a general retry framework. This stale-session recovery path is
canonical because tab/session invalidation is normal browser behavior.

### Dialog Tracking

Enable `Page` events and track:

- `Page.javascriptDialogOpening`
- `Page.javascriptDialogClosed`

`pageInfo()` or future helpers should return dialog state before attempting
`Runtime.evaluate`, because alert/confirm/prompt can freeze page JS.

### Screenshots And Coordinates

`Page.captureScreenshot` returns device pixels. CDP `Input.dispatchMouseEvent`
uses CSS pixels. If a screenshot is measured in device pixels, divide by
`window.devicePixelRatio` before clicking.

Add `screenshot({ maxDim: 1800 })` to avoid oversized artifacts. Downscale only
when needed.

### Visible Verification

After meaningful visible actions, scripts should screenshot again or re-read page
state instead of assuming success. This belongs in the tool description and
`browser-use/SKILL.md` guidance, not in a runtime retry framework.

## Execution Flow

```text
Agent calls browser_script(script)
  -> Tool validates input
  -> BrowserSessionManager acquires exclusive script lease
  -> BrowserScriptRunner.ensureStarted()
      -> ChromeCdpClient.ensureConnected()
          -> ShizukuChromeDevtoolsBridge.ensureConnected()
          -> resolve /json/version
          -> open browser websocket
          -> attach first real page
          -> enable Page/Runtime/DOM/Network on active session
  -> hidden WebView runs async wrapper
      -> JS calls cdp(method, params, options)
      -> Kotlin sends command to ChromeCdpClient
      -> response resolves JS Promise
  -> script returns result or error
  -> result is truncated for LLM and raw payload goes to trace
  -> BrowserSessionManager releases exclusive script lease
```

## Session Lifecycle

The browser runtime is session-scoped.

Owner:

```text
SessionServices
  -> BrowserSessionManager? (created only when experimental browser capability is enabled)
      -> BrowserScriptRunner
      -> ChromeCdpClient
      -> ShizukuChromeDevtoolsBridge
```

Lifecycle mapping:

- Session `Created`: allocate no browser resources.
- First `browser_script` call: lazily create `BrowserSessionManager` resources.
- Session `Running` / `Idle`: keep the CDP connection warm if healthy.
- Session `Paused`: reject new scripts; cancel any in-flight script through the
  normal tool cancellation path.
- Session `Shutdown`: cancel active script, close WebView, close WebSocket, and
  unbind the Shizuku bridge.
- Shizuku binder death or WebSocket close: runtime transitions to `Broken`; the
  next call attempts one reconnect.

Concurrency:

- At most one `browser_script` runs per session at a time.
- If a second invocation collides with a running script, return a clear busy
  error; if the caller has already serialized work, it waits behind the
  ToolRouter execution queue.
- Exclusivity is required even when Chrome is not foreground. CDP targets, active
  session state, dialogs, page navigation, and user profile state are shared
  mutable browser state.

## State Machine

```text
Idle
  -> Starting
      guard: Shizuku available and permission granted
      side effect: bind UserService / connect localabstract socket
  -> Connected
      side effect: attach first real target, enable core domains
  -> RunningScript
      side effect: execute WebView JS wrapper
  -> Connected
      side effect: clear pending script promises
  -> Broken
      side effect: close websocket, unbind bridge
```

Transitions:

- `Idle -> Starting`: first tool call.
- `Connected -> RunningScript`: valid `browser_script` call.
- `RunningScript -> Connected`: success, failure, timeout, or cancellation.
- `Connected -> Broken`: Shizuku binder death, websocket close, or Chrome socket
  loss.
- `Broken -> Starting`: next tool call attempts reconnect once.

## File Layout

```text
app/src/main/kotlin/ai/closepaw/browser/cdp/
  ChromeCdpClient.kt
  ChromeCdpCommand.kt
  ChromeCdpTarget.kt
  ChromeCdpEventBuffer.kt
  ChromeCdpResultFormatter.kt

app/src/main/kotlin/ai/closepaw/browser/cdp/shizuku/
  ShizukuChromeDevtoolsBridge.kt
  ChromeDevtoolsUserService.kt

app/src/main/kotlin/ai/closepaw/browser/script/
  BrowserSessionManager.kt
  BrowserScriptRunner.kt
  BrowserScriptBridge.kt
  BrowserScriptPrelude.kt

app/src/main/kotlin/ai/closepaw/tool/impl/
  BrowserScriptTool.kt

app/src/main/assets/agent_skills/browser-use/
  SKILL.md        # bundled seed; includes snippet index and {{SKILL_DIR}} placeholder
  scripts/
    page.js
    tabs.js
    input.js

context.filesDir/skills/browser-use/
  SKILL.md        # installed runtime copy read by AgentSkillManager; contains absolute script paths
  scripts/        # installed runtime snippets readable by shell cat

app/src/test/kotlin/ai/closepaw/browser/
  BrowserScriptPreludeTest.kt
  ChromeCdpResultFormatterTest.kt
```

## Tasks

### browser-reference

Scope: `.reference/browser-harness/**`, `projects/active/browser/**`

Acceptance criteria:

- Reference repo is cloned to `.reference/browser-harness`.
- The design document records the browser-harness behaviors ClosePaw should
  copy.

Dependencies: none.

### cdp-bridge-spike

Scope:

- `app/src/main/kotlin/ai/closepaw/browser/cdp/shizuku/**`
- Existing Shizuku client files only when needed.

Acceptance criteria:

- When Shizuku is ready and Chrome is running, code can prove whether
  `chrome_devtools_remote` is available.
- If available, code can read `/json/version`.
- Code can list page targets.
- Failure modes must distinguish:
  - Shizuku unavailable
  - Shizuku permission missing
  - Chrome not running
  - DevTools socket missing
  - socket inaccessible from app process
  - socket inaccessible from Shizuku UserService
  - malformed HTTP/WebSocket response
- The spike records exact Android/Chrome setup steps required on the tested
  device.

Dependencies: browser-reference.

### cdp-client-core

Scope: `app/src/main/kotlin/ai/closepaw/browser/cdp/**`

Acceptance criteria:

- Open the CDP websocket.
- Send raw commands and match responses by id.
- Attach to the first real page.
- Route `Target.*` and `Browser.*` without a page session.
- Route page domains through the active session.
- Recover once from a stale active session.

Dependencies: cdp-bridge-spike.

### script-host

Scope: `app/src/main/kotlin/ai/closepaw/browser/script/**`

Acceptance criteria:

- Hidden WebView can run an async script.
- Prelude exposes only `cdp()`.
- CDP success resolves JS promises.
- CDP error rejects JS promises with useful messages.
- Timeout and cancellation end the tool call cleanly.
- `sleep()`, `log()`, `pageJs()`, and similar helpers do not enter the runtime
  prelude.

Dependencies: cdp-client-core.

### browser-script-tool

Scope:

- `app/src/main/kotlin/ai/closepaw/tool/impl/BrowserScriptTool.kt`

Acceptance criteria:

- Experimental setting, Shizuku readiness, and Chrome socket availability are
  checked at execution time; if missing, the tool fails closed.
- Tool description tells the agent to use the `browser-use` skill for richer
  examples and reusable snippets.
- Tool returns compact JSON/text result.
- Oversized outputs are truncated with an explicit truncation marker.
- Raw result is available to trace/debug plumbing.

Dependencies: script-host.

### browser-session-integration

Scope:

- `app/src/main/kotlin/ai/closepaw/session/SessionServices.kt`
- `app/src/main/kotlin/ai/closepaw/session/SessionToolingBootstrapper.kt`
- `app/src/main/kotlin/ai/closepaw/app/AppSettingsStore.kt`
- `app/src/main/kotlin/ai/closepaw/tool/PolicyEngine.kt`
- `app/src/main/kotlin/ai/closepaw/tool/ToolName.kt`

Acceptance criteria:

- `SessionServices` lazily owns `BrowserSessionManager?` and passes it to
  `BrowserScriptTool` without making `SessionToolingBootstrapper` own Android
  `Context`/WebView details.
- `BrowserSessionManager` receives the dependencies it needs: `Context`, session
  `CoroutineScope`, `TraceRecorder`, experimental browser automation setting,
  Shizuku bridge factory, and cleanup hook.
- Session shutdown closes the active script, hidden WebView, CDP WebSocket, and
  Shizuku UserService binding.
- `browser_script` is registered into `ToolRegistry` and always appears in LLM
  function schemas.
- `ToolName` adds explicit `BrowserScript` and marks it screen-changing.
- `PolicyEngine` adds an explicit `browser_script` rule: in SMART, ask user by
  default regardless of foreground app tier or Chrome being `NORMAL`; in
  ALWAYS_ASK, ask user; in AUTO_APPROVE, allow after experimental/Shizuku/socket
  gates; if an explicit browser automation approval override exists, document
  and test its exact behavior.
- Existing app allow-list must not silently bypass the browser-specific SMART
  approval rule.
- Tests cover SMART mode for `browser_script` on `com.android.chrome` and verify
  it asks even when Chrome is `NORMAL`.

Dependencies: browser-script-tool.

### browser-use-skill-v0

Scope:

- `app/src/main/assets/agent_skills/browser-use/SKILL.md`
- `app/src/main/assets/agent_skills/browser-use/scripts/**`
- Runtime install/copy path `context.filesDir/skills/browser-use/**`

Acceptance criteria:

- App bundles `browser-use/SKILL.md` and `browser-use/scripts/**`, then
  installs/seeds the whole directory into the actual `AgentSkillManager`
  directory (`context.filesDir/skills`) before catalog construction.
- Runtime skill source of truth is `context.filesDir/skills/browser-use/**`;
  assets are only the bundled seed/update source.
- Built-in `browser-use` is non-user-editable in v1: overwrite installed
  `browser-use` files from bundled assets on each app start/session bootstrap.
  Custom user variants must use a different skill name.
- When installing `SKILL.md`, replace a `{{SKILL_DIR}}` placeholder with the
  installed absolute path, for example
  `/data/user/0/ai.closepaw/files/skills/browser-use`, so the agent can run
  `shell(command="cat <path>/scripts/page.js")`.
- `SKILL.md` explains when to use `browser_script` and emphasizes using raw CDP
  first, reading snippets with `shell cat` only when needed.
- Essential snippets are grouped by workflow under `scripts/`:
  - `page.js`: `pageJs`, `waitForLoad`, `screenshot`, and tiny timing helpers.
  - `tabs.js`: `listTabs`, `currentTab`, `switchTab`, and `newTab`.
  - `input.js`: `clickAt` and `typeText`.
- Snippets do not depend on hidden runtime APIs; except for `cdp()`, each
  snippet is self-contained.
- The `pageJs` snippet handles JS exception formatting and preserves line/column
  plus newline-escaped snippets.
- The screenshot snippet can cap max dimension.
- Skill docs include raw-CDP examples and snippet examples.

Dependencies: browser-session-integration.

### verification

Scope:

- `app/src/test/kotlin/ai/closepaw/browser/**`
- Optional debug receiver/activity if needed.

Acceptance criteria:

- Unit tests cover snippet truncation, result formatting, command routing,
  stale-session recovery decision, and the `cdp()` prelude bridge.
- `browser-use/scripts/` snippets have at least syntax/static checks; if there is
  a snippet test harness, cover `pageJs` top-level return detection and helper
  generation.
- Test that the seed/install path copies `scripts/` into
  `context.filesDir/skills/browser-use/` and rewrites `{{SKILL_DIR}}` in the
  installed `SKILL.md`.

Dependencies: browser-use-skill-v0.

### holistic-review

Scope:

- `projects/active/browser/holistic_review.md`

Acceptance criteria:

- Review the full Browser CDP runtime change set against this design.
- Cover KISS, security/policy, session lifecycle, Shizuku/CDP transport, WebView
  host hardening, `browser-use` skill packaging, tests, and docs.
- Document findings in `projects/active/browser/holistic_review.md`.
- The review must explicitly say whether there are open critical/high findings;
  critical/high findings must be fixed or converted into follow-up tasks before
  this task is done.

Dependencies: verification.

### real-device-qa

Scope:

- `projects/active/browser/real_device_qa.md`

Acceptance criteria:

- On a real Android device with Chrome and Shizuku permission, exercise one
  browser use case through `browser_script` and the installed `browser-use`
  snippets.
- The use case must open a page, read title/content through CDP, capture
  screenshot evidence, create or switch tabs, and return to the original tab.
- Document device/app build, setup state, exact use case steps, observed result,
  and evidence in `projects/active/browser/real_device_qa.md`.
- If the environment is unavailable, document the exact blocking reason and leave
  the task blocked rather than marking it done.

Dependencies: holistic-review.

## Trade-Offs

### One `browser_script` Tool vs Many Browser Tools

One script tool wins. It keeps prompt surface small and lets the agent use CDP
directly. Many wrappers would recreate a partial Playwright API and become a
maintenance burden. Richer usage belongs in installed `browser-use/scripts/`
snippets, not in more native tools.

### Always-Loaded Tool vs Skill-Gated Schema

v1 chooses an always-loaded `browser_script` schema. Skill-gated schema exposure
would save prompt surface, but it requires changes to turn/tool loading and
activated skill allowlist data flow; that is not worth it at this stage. The
`browser-use` skill carries instructions and a snippet index only. It does not
control whether the tool appears in function schemas.

### JS Host vs Termux

v1 chooses the hidden WebView JS host. It is built into Android, needs no
external install, and can call Kotlin through `JavascriptInterface`. Termux is
useful for prototyping or Python/npm ecosystems, but it is not the product
runtime.

### Raw CDP vs Typed Kotlin Wrappers

Raw CDP wins. CDP is already a typed protocol at the method-name/JSON boundary.
Typed wrappers add LOC without adding browser capability. Repetitive or
error-prone browser operations belong in installed `browser-use/scripts/`
snippets, not in Kotlin wrappers or a runtime helper API.

### Shizuku UserService vs Shell Bridge

The product chooses UserService. A shell bridge depends on external binaries or a
long-running shell process. UserService gives ClosePaw lifecycle, transport, and
errors inside Kotlin.

### Native Tool vs Shell CLI

The product path chooses native `BrowserScriptTool`. A shell CLI can be useful as
a spike/debug shortcut, but it is not suitable as the agent-facing runtime:
browser automation needs session lifecycle, approval/policy, trace,
cancellation, exclusive lease, Shizuku binder state, and the WebView JS host
Looper. Those already live inside the app process.

### No Policy In v0

This is acceptable only for the URL/method-level policy matrix. Minimal
tool-level policy is still required:

- `browser_script` is screen-changing.
- SMART mode asks for approval by default.
- Even if the tool schema is always loaded, execution can continue only when
  experimental browser automation is enabled and Shizuku is ready.
- `browser_script` needs an explicit `PolicyEngine` rule; do not rely only on
  Chrome app tier, because `com.android.chrome` is currently `NORMAL`.

CDP can read and mutate logged-in pages, so production must add URL/method-level
policy. That policy should not block the transport spike.

## Self-Review

- The design exposes one tool and keeps CDP raw.
- `browser_script` schema is always loaded in v1; the `browser-use` skill only
  provides richer usage and a snippet index.
- The runtime prelude exposes only `cdp()`; `sleep()`, `log()`, and browser
  helpers do not enter the native runtime.
- Native `cdp()` does not rewrite `Runtime.evaluate` `exceptionDetails`; the
  `pageJs` snippet handles JS exception formatting.
- WebView is only the JS automation host, not the automated browser.
- `/json/version`, `/json/list`, `/json/new`, and `/json/activate` are runtime
  bootstrap details, not agent tools.
- Review feedback has been handled: session lifecycle, output caps,
  `TraceRecorder` reuse, WebView hardening, and screen-changing classification
  are explicit.
- The design captures browser-harness details that prevent real bugs:
  newline-escaped JS snippets, JS exception extraction,
  create-blank-then-navigate, internal target filtering, stale-session recovery,
  dialog tracking, and screenshot coordinate caveats. Helper-style content lives
  in installed `browser-use/scripts/`.
- The biggest unknown remains Android Chrome DevTools socket availability and
  transport under Shizuku. It is correctly first in the task list.

## Appendix: Discussion Reference

These discussions have been reflected in the main design. Implementation should
use the main body as source of truth; this appendix only preserves decision
background. Some intermediate proposals below have been superseded by the main
body, especially skill-gated schema loading, inlining all snippets into
`SKILL.md`, and injecting `sleep()`/`log()` into the prelude.

### Skill Layer

Comment:

> Can we implement this as a skill? Many parts of our design are similar. It is
> like browser_harness. Our system now supports agentskill.io format skills; that
> feature was just implemented. The design doc was not aware of it yet.

Reply:

Yes, and we should add an agentskill.io-format skill layer. But it does not
replace the native `browser_script` tool/runtime. The boundary should be:
Kotlin/Shizuku/WebView/CDP transport remains implemented by the app and handles
permission, lifecycle, timeout, trace, and policy; the skill owns agent-facing
guidance, including when to use `browser_script`, raw CDP examples, common
tab/dialog/screenshot coordinate caveats, and reusable script snippets. This is
closer to browser-harness: the core capability is thin, while usage guidance and
evolving experience live in the skill.

### Tool Loading Shape

Comment:

> This may not be common, but can we put this tool's schema as text inside the
> skill, so the tool is not always loaded and is loaded only when the skill is
> loaded? Behind the scenes it still exists as a tool.
> Another approach: implement it as a native CLI usable from the current shell
> tool, then mention the CLI in the skill. The CLI implementation may be freer,
> right? Just package the binary?
> Help me think through which is more feasible.

Reply:

The more feasible shape is "native tool implementation + skill-gated schema
exposure." `BrowserScriptTool` remains a session-scoped native tool registered in
`ToolRegistry` or a lazy registry, but by default it does not appear in LLM
function schemas. After the `browser` skill is activated through
`activate_skill`, the next turn adds `browser_script` to allowed tools and
exposes the real structured schema. The schema should not exist only as
markdown/text inside the skill, because function calling needs API-side
structured schema. The skill can describe schema, examples, and usage policy, but
the execution entrypoint should still come from `ToolSpec`. The current code
already parses `allowed-tools` frontmatter and has a per-turn `allowedToolNames`
filter; the missing piece is wiring an activated skill's
`allowed-tools: [browser_script]` into the turn request tool allowlist.

The CLI approach is better as a spike/debug tool, not as the product path.
Browser automation needs app-internal state: session lifecycle, approval/policy,
trace, cancellation, exclusive lease, Shizuku binder state, and the WebView JS
host Looper. Moving that into a shell CLI splits capability into another
process/binary, making permissions, ABI packaging, logging, timeout, and cleanup
harder. A native binary that directly connects to `chrome_devtools_remote` can be
useful for the transport spike, but the agent-facing path should use
skill-gated `browser_script`.

Follow-up:

> OK, then let's simplify. This tool can always load. The description can mention
> the browser-use skill, and browser-use/SKILL.md can have richer usage,
> including browser-use/scripts helpers. Is that OK?

Reply:

OK. This is more KISS. In v1, `browser_script` is always registered and its
schema is always loaded, while capability remains gated by experimental setting,
Shizuku readiness, and policy. The tool description should be minimal and say
"for richer usage, activate/read the `browser-use` skill." `browser-use/SKILL.md`
contains operating strategy, examples, and common caveats, and
`browser-use/scripts/` contains copyable helper snippets. This avoids changing
turn/tool loading architecture just for lazy schema exposure while preserving the
browser-harness style of an evolvable usage library.

### Helper Snippets

Comment:

> Can these helpers all become files that the agent can reference while writing
> scripts? Similar to browser-harness. See
> https://browser-use.com/posts/bitter-lesson-agent-harnesses. Our design
> philosophy should be the same: do not give the agent unnecessary scaffolding,
> because it can become a constraint. Of course Shizuku/WebView and similar
> pieces must be implemented well and hidden from the agent.

Reply:

Agreed. This should become "minimal runtime prelude + skill/reference files for
helper snippets." `BrowserScriptRunner` should inject only the stable `cdp()` and
promise bridge by default; `pageJs`, `newTab`, `waitForLoad`, `screenshot`,
`clickAt`, and `typeText` should not become a thick runtime API or a set of
agent tools. The better shape is to put them in browser skill JS files/snippets,
where the agent can reference, copy, or modify them as needed. Only mechanisms
that truly must exist across every script and are hard to rewrite safely should
enter the prelude. Shizuku/WebView/CDP transport, WebView hardening, trace, and
policy remain hidden from the agent.

### `sleep()` And Bridge

Comment:

> Why provide sleep()? Isn't it built into JavaScript?

Reply:

JavaScript has `setTimeout`, but no standard promise-based `sleep()`. So
`sleep()` is only a very thin convenience helper:
`ms => new Promise(resolve => setTimeout(resolve, ms))`. If we keep the runtime
prelude extremely small, we can move it from the mandatory prelude into a skill
snippet; the agent can write the one line when needed. My inclination was that it
would also be acceptable to keep it because it has zero permission, zero CDP
coupling, and stable behavior. But under the principle of not giving the agent
unnecessary scaffolding, the cleanest design is that the prelude only contains
`cdp()` and the bridge, while `sleep()` lives in browser skill snippets.

Follow-up:

> Agree, delete it. Keep KISS. What is this bridge?

Reply:

The bridge is the `@JavascriptInterface` + Kotlin callback glue layer. The agent
script runs inside the hidden WebView and calls `cdp(method, params)`. JS cannot
directly connect to the Chrome DevTools socket, so `cdp()` serializes the request
as JSON and sends it to an exposed Kotlin object. Kotlin then calls
`ChromeCdpClient`; after it receives the result, it calls
`evaluateJavascript("__cdpResolve(...)")` or `__cdpReject(...)` to settle the JS
Promise. With the KISS design, the doc should say: the prelude only provides
`cdp()`; `sleep()` is removed; the internal bridge is a runtime implementation
detail, not an agent-facing helper.

### Raw CDP Coverage

Comment:

> Does this `cdp(method, params)` interface access all capabilities provided by
> CDP?

Reply:

By design, yes. `cdp(method, params, options)` is the raw CDP escape hatch, not a
per-capability wrapper. If Chrome Android exposes that CDP domain/method and
routing is correct, it can be called. There are four practical boundaries. First,
browser-level methods such as `Target.*` and `Browser.*` need the browser
session; page-level domains use the active page session by default, and
`options.targetId`/`options.sessionId` can be used when needed. Second, Android
Chrome's CDP surface may differ from desktop Chrome, so some domains/methods may
not exist or may behave differently. Third, the v1 transport only connects to the
user's real Chrome profile and does not provide extra OS/file/provider APIs.
Fourth, production still needs tool/policy restrictions for high-risk
URLs/methods; raw capability does not mean every call is allowed by default.
