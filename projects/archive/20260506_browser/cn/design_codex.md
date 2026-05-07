# ClosePaw 的 Browser CDP Script Runtime

Status: draft v1

Reference checkout: `.reference/browser-harness`

## 目标

给 ClosePaw 增加一条最小、可编程的 browser automation 路径，面向已经启用 Shizuku 并授予 ClosePaw Shizuku permission 的用户。

目标能力是 browser-harness 风格地通过 CDP 控制用户真实的 Chrome：打开页面、检查 DOM/page state、运行 page JavaScript、通过 Chrome input domains click/type、capture screenshots，以及管理 tabs。

这不是 OpenWeb，不是 Custom Tabs，也不是 WebView browser automation。browser 是带着用户真实 profile 的 Android Chrome。

## Reference Model

`browser-harness` 有意保持很薄：

- `SKILL.md` 告诉 agent 使用一个 script command，而不是很大的 tool surface。
- `helpers.py` 暴露 `cdp("Domain.method", params)` 作为 raw escape hatch。
- `daemon.py` 拥有 CDP websocket、default session、event buffer、dialog state、stale-session recovery 和 real-tab attachment。
- interaction skills 记录 tab handling、screenshot coordinate caveats 等稳定机制。

ClosePaw 应该复制形状，而不是复制 Python runtime：

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

- v1 不依赖 Termux。
- 不提供 Playwright/Puppeteer wrapper API。
- 不做 `browser_click`、`browser_type`、`browser_open` 这类 many-tool surface。
- 不做 thick built-in browser helper runtime。helper snippets 放在 installed `browser-use/scripts/`，由 `browser-use/SKILL.md` 指引 agent 按需读取。
- 不集成 OpenWeb package/runtime。
- v0 不做 policy matrix。把它当作由明确 Shizuku setup gate 住的 experimental/debug capability。transport 跑通后再加完整的 URL/method policy。
- 不做 cloud browser/profile sync。

## Review Decisions

Claude 在 `eng_plan_review_claude.md` 里的 engineering review 基本接受，但有一个修正：Android Chrome CDP 应被视为官方 Android remote-debugging socket path，而不是 desktop Chrome 的 `--remote-debugging-port` launch model。

Accepted changes:

- 先 spike Shizuku socket bridge，并明确区分 app process socket failure 和 Shizuku UserService success/failure。
- browser runtime scope 到 session，而不是 tool instance。
- 每个 session 同一时间只允许一个 script。
- 即使 Chrome 不在 foreground，也把 `browser_script` 标为 screen-changing，因为它会 mutate external browser/profile state，并需要独占 browser automation lease。
- 使用现有 `TraceRecorder`，不另建 browser trace subsystem。
- 实现前定义 prompt-output caps。
- 锁住 hidden WebView script host。
- runtime prelude 保持最小，只向 agent scripts 暴露 `cdp()`。
- 增加 `browser-use` skill 承载 richer guidance 和 reusable snippets，但 native tool 不做 skill-gated loading。

Rejected or rewritten:

- product design 中不要求 Termux 或外部 `socat` fallback。
- 不把 Chrome Android setup 建模为用 `--enable-remote-debugging` 启动 desktop-style Chrome。spike 必须检测 `chrome_devtools_remote` 是否可用；不可用时返回精确的 setup error。

## Agent-Facing Tool

只暴露一个 tool：

```text
browser_script
```

v1 中 `browser_script` always registered / always load schema。不要为了 lazy schema exposure 改动 turn/tool loading 架构。capability 仍由 execution-time gate 控制：

- experimental browser automation setting 必须开启。
- Shizuku 必须可用且已授权。
- Chrome DevTools socket 必须可用。
- `browser_script` 是 screen-changing，SMART mode 默认需要 user approval。

tool description 保持极简，只说明 raw CDP usage，并提示 richer usage 放在 `browser-use` skill：

- installed `browser-use/SKILL.md`: 使用策略、raw CDP examples、tab/dialog/screenshot coordinate caveats、verification guidance，以及 snippets 文件索引。
- installed `browser-use/scripts/`: 可复制/改写的 JavaScript snippet bundles，按工作流分组，而不是一函数一文件。

`browser-use` skill 不替代 native runtime。Kotlin/Shizuku/WebView/CDP transport、permission、lifecycle、timeout、trace、policy 和 exclusive lease 仍由 app 实现并对 agent 隐藏。

Skill storage rule: runtime skills 只从 `context.filesDir/skills` 加载。APK assets 只是 seed source。安装/启动时把 bundled `browser-use/SKILL.md` 和 `browser-use/scripts/` 复制到 `context.filesDir/skills/browser-use/`；`activate_skill` 只返回 `SKILL.md` body，但 `SKILL.md` 会列出 installed snippets 的绝对路径，agent 可用 `shell` 执行单条 `cat <skillDir>/scripts/<name>.js` 读取需要的 snippet。ShellTool 当前允许这种 file-oriented `cat`，且不需要 shell metacharacters。

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

script environment 提供：

```js
await cdp(method, params = {}, options = {})
```

`cdp(method, params, options)` 是 raw CDP escape hatch，不按能力逐个封 wrapper。只要 Android Chrome 暴露该 CDP domain/method，且 routing 正确，就可以调用。实际边界：

- `Target.*` 和 `Browser.*` 是 browser-level methods，不走 page session。
- 大多数 page-level domains 默认走 active page session。
- 需要指定 tab/session 时，用 `options.targetId` 或 `options.sessionId`。
- Android Chrome 的 CDP 支持面可能和 desktop Chrome 不完全一致。
- v1 transport 只连接用户真实 Chrome profile，不提供额外 OS/file/provider API。
- production 仍要在 tool/policy 层限制高风险 URL/method；raw capability 不等于默认允许所有调用。

helpers 不进入 runtime prelude，也不变成独立 tools。需要 helper 时，agent 从 installed `browser-use/scripts/` 读取、复制或改写 snippets 后放进本次 `script`。v1 只放少数 bundle files：

```text
page.js    # pageJs, waitForLoad, screenshot, tiny sleep helper if needed
tabs.js    # listTabs, currentTab, switchTab, newTab
input.js   # clickAt, typeText
```

KISS rule: runtime prelude 只提供 `cdp()`。`sleep()`、`pageJs()` 等都作为 snippets 放到 installed `browser-use/scripts/`，由 agent 按需读取并内联。不要把短且强相关的 helpers 拆成独立文件。

## Why Script Instead Of Raw Single-Command CDP Tool

`chrome_cdp(method, params)` 会迫使 LLM 为 loops、waits、retries、extraction 和 verification 发起很多 turns。`browser_script` 让一次 tool call 就能执行 control flow：

```js
const response = await cdp("Runtime.evaluate", {
  expression: "document.title",
  returnByValue: true
})
const title = response.result.value
return { title }
```

browser capability 仍然是 raw CDP。额外价值是 JavaScript 中的本地 control flow：loops、branching、parsing 和 retries，而不需要每个 CDP command 后都 round-trip 到 LLM。更复杂的 helpers 来自 `browser-use/scripts/`，不是 runtime API。

## Runtime Components

### BrowserScriptTool

Location: `app/src/main/kotlin/ai/closepaw/tool/impl/BrowserScriptTool.kt`

Responsibilities:

- Validate script is non-empty。
- Cap timeout。
- 检查 experimental browser automation setting、Shizuku readiness、Chrome DevTools socket availability；不满足时 fail closed。
- 调用 `BrowserScriptRunner`。
- 返回 compact text output，加上用于 trace/debug 的 raw result metadata。
- v1 always registered / always load schema。不要做 skill-gated schema exposure。
- Tool description 提醒 agent 使用 `browser-use` skill 获取 richer examples 和 reusable snippets。

### BrowserScriptRunner

Location: `app/src/main/kotlin/ai/closepaw/browser/script/`

Responsibilities:

- 拥有一个 hidden local WebView，只作为 JavaScript runtime。
- 加载 blank local page 并注入 automation prelude。
- 将 agent script 包在 async function 中。
- 通过 `@JavascriptInterface` 把 `cdp()` calls bridge 到 Kotlin。
- CDP results 返回时 resolve/reject JS promises。
- 强制执行 tool-call timeout 和 cancellation。

WebView 不是被 automation 的 browser。它只是 automation script host。target browser 仍然是 Android Chrome。

hidden WebView 的 security hardening：

- 只加载 local blank document。
- 禁止 network loads。
- 禁止 file access 和 content access。
- 禁止 mixed content 和 universal file URL access。
- 只暴露一个 `@JavascriptInterface` object，且只包含 `send` 和 `done`。
- 所有 JS calls 都和当前 tool-call id 关联；timeout 或 cancellation 后忽略 late callbacks。
- 所有 `evaluateJavascript` calls 都 post 到 WebView 所属的 Looper。

这里的 bridge 是 runtime implementation detail：agent script 在 hidden WebView 里调用 `cdp(method, params, options)`；`cdp()` 将请求序列化成 JSON，通过 `@JavascriptInterface` 发给 Kotlin；Kotlin 调 `ChromeCdpClient`；结果回来后，Kotlin 再用 `evaluateJavascript("__cdpResolve(...)")` 或 `__cdpReject(...)` 回填 JS Promise。agent-facing prelude 只暴露 `cdp()`。

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

- 维护 browser websocket 和 page sessions。
- 分配 CDP command ids。
- 按 id 匹配 command responses。
- buffer recent events。
- 跟踪 pending JavaScript dialogs。
- connect 后 attach 到第一个 real page。
- stale session errors 后 reattach。
- browser-level methods 不带 page session route。
- page-level methods 默认 route 到 active flattened session。

从 browser-harness 复制的 routing rule：

- `Target.*` 是 browser-level，默认不能使用 page session。
- `Browser.*` 是 browser-level。
- 大多数其他 domains 使用 active page session，除非提供了 `options.sessionId`。
- `options.targetId` 会用 `Target.attachToTarget(flatten=true)` attach，并用该 session 发送 command。

### ShizukuChromeDevtoolsBridge

Location: `app/src/main/kotlin/ai/closepaw/browser/cdp/shizuku/`

Responsibilities:

- 用 Shizuku privileges 运行 Chrome DevTools socket access path。
- 连接 Chrome 的 abstract Unix socket `chrome_devtools_remote`。
- bootstrap 期间 resolve `/json/version` 和 `/json/list`。
- hold/proxy CDP websocket。
- 区分 setup failures：
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

- 先从 app process 直接尝试 `LocalSocket`。如果 SELinux/package isolation 阻挡，马上切到 UserService。
- 不依赖 `socat`、Termux 或 external binaries。

Android Chrome setup note:

- 官方 Android CDP path 使用 Chrome 的 abstract socket，相当于 `adb forward tcp:9222 localabstract:chrome_devtools_remote`。
- v0 不承诺自动启用 Chrome debugging。它只检测 socket 是否可用，并返回 actionable setup error。
- spike 必须在 target devices 上验证精确的 user/device prerequisite，而不是假设 socket 总是存在。
- 如果 socket 缺失，tool 应 fail closed，并提示用户打开 Chrome 且启用所需 Android/Chrome debugging path。它绝不能 silent fallback 到 WebView 或 Custom Tab。

### Trace Integration

使用现有 `TraceRecorder`。不要创建并行的 browser trace subsystem。

Responsibilities:

- 在 prompt context 外持久化 raw CDP commands/responses/events，structured event names 使用 `cdp_command`、`cdp_response` 和 `cdp_event`。
- 向 LLM 返回 truncated summaries。
- 用 `TraceRecorder.storeBytes` 存 screenshots。
- 用 `TraceRecorder.storeText` 存 large JSON payloads。

Prompt-facing output caps:

- Final tool output: max 8192 chars。
- Individual CDP response summaries: max 4096 chars。
- Screenshots 绝不以 base64 放进 tool output。LLM 只拿到带 dimensions 和 trace artifact id 的 compact marker。
- 每次 truncation 必须包含明确 marker，例如 `[truncated: original_chars=123456]`。

## Browser-Harness Details

以下内容来自 browser-harness 的稳定经验。和 CDP transport、raw result formatting、routing、session recovery 直接相关的部分进入 native runtime；helper-style 内容进入 installed `browser-use/scripts/`，由 agent 按需读取、裁剪或改写进 `browser_script`。

### Raw CDP Result Formatting

native `cdp()` 保持 protocol-raw。`Runtime.evaluate` 返回 `exceptionDetails` 时，这仍然是成功的 CDP response；native runtime 不把它改写成 tool failure。native formatter 只负责：

- 区分 CDP protocol error 和 successful response。
- 保留 `exceptionDetails`、`result` 等 payload。
- 对大型 JSON 做 summary/truncation。
- 对 `unserializableValue` 做可见保留，不 silent coerce。

### `pageJs` Error Formatting

`pageJs` 是 `browser-use/scripts/page.js` 中的 snippet，不是 native runtime API。该 snippet 可以把 `Runtime.evaluate` 的 `exceptionDetails` 转成更清晰的 JavaScript evaluation error。它应检查：

- `exceptionDetails.text`
- `exceptionDetails.exception.description`
- `exceptionDetails.exception.value`
- result `subtype == "error"`
- result `description`

snippet 返回清晰错误：

```text
JavaScript evaluation failed at line X, column Y: <description>; expression: <snippet>
```

snippet 应遵循 browser-harness 行为：

```kotlin
fun jsSnippet(expression: String, limit: Int = 160): String {
    val snippet = expression.trim().replace("\n", "\\n")
    return if (snippet.length > limit) snippet.take(limit - 3) + "..." else snippet
}
```

这样可以避免 multiline agent JS 污染 errors，同时保留足够 context 来 debug failing expression。

### Unserializable JavaScript Values

处理 CDP `unserializableValue` values：

- `NaN`
- `Infinity`
- `-Infinity`
- `-0`
- BigInt strings ending in `n`

v0 中可以把它们作为 tagged strings 返回。不要 silent coerce to null。

### `pageJs` Snippet

`browser-use/scripts/page.js` 可以提供 `pageJs(expression)` snippet，同时支持 expressions 和 function bodies：

```js
await pageJs("document.title")
await pageJs("const x = document.title; return x")
```

使用一个类似 browser-harness `_has_return_statement` 的小 scanner：先忽略 strings、template literals、line comments 和 block comments，再决定是否 wrap 成：

```js
(function(){ ... })()
```

### `newTab` Snippet

helper 中不要直接用 final URL 调 `Target.createTarget`。先 create blank、attach，再 navigate：

```js
const { targetId } = await cdp("Target.createTarget", { url: "about:blank" })
await switchTab(targetId)
if (url !== "about:blank") await cdp("Page.navigate", { url })
```

这样可以避免 `about:blank` 已经 complete，导致后续 `waitForLoad()` 在目标 navigation 开始前就返回的 race。

### Internal Target Filtering

默认过滤 internal targets：

```text
chrome://
chrome-untrusted://
devtools://
chrome-extension://
about:
```

同时预期会遇到 omnibox popups 这类 fake/ephemeral targets。如果没有 real page，就创建 `about:blank`。

### Stale Session Recovery

如果 Chrome 对 default active session 返回 "Session with given id not found"：

1. Re-run `Target.getTargets`。
2. 选择第一个 real page。
3. `Target.attachToTarget(flatten=true)`。
4. retry command 一次。

v0 不做 general retry framework。这个 stale-session recovery path 是 canonical，因为 tab/session invalidation 是正常 browser behavior。

### Dialog Tracking

Enable `Page` events 并跟踪：

- `Page.javascriptDialogOpening`
- `Page.javascriptDialogClosed`

`pageInfo()` 或未来 helpers 应在尝试 `Runtime.evaluate` 前返回 dialog state，因为 alert/confirm/prompt 可能 freeze page JS。

### Screenshots And Coordinates

`Page.captureScreenshot` 返回 device pixels。CDP `Input.dispatchMouseEvent` 使用 CSS pixels。如果 screenshot 是按 device pixels 测量的，click 前要除以 `window.devicePixelRatio`。

加入 `screenshot({ maxDim: 1800 })` 以避免 oversized artifacts。只在需要时 downscale。

### Visible Verification

执行有意义的 visible actions 后，scripts 应重新 screenshot 或重新读取 page state，而不是直接假设成功。这属于 tool description 和 `browser-use/SKILL.md` 的 guidance，不是 runtime retry framework。

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

browser runtime 是 session-scoped。

Owner:

```text
SessionServices
  -> BrowserSessionManager? (created only when experimental browser capability is enabled)
      -> BrowserScriptRunner
      -> ChromeCdpClient
      -> ShizukuChromeDevtoolsBridge
```

Lifecycle mapping:

- Session `Created`: 不分配 browser resources。
- 第一次 `browser_script` call: lazily create `BrowserSessionManager` resources。
- Session `Running` / `Idle`: 如果健康，保持 CDP connection warm。
- Session `Paused`: reject new scripts；通过正常 tool cancellation path cancel any in-flight script。
- Session `Shutdown`: cancel active script、close WebView、close WebSocket、unbind Shizuku bridge。
- Shizuku binder death 或 WebSocket close: runtime transition 到 `Broken`；下次 call 尝试一次 reconnect。

Concurrency:

- 每个 session 同一时间最多运行一个 `browser_script`。
- 第二次 invocation 如果撞上正在运行的 script，则返回清晰 busy error；如果 caller 已经 serialized，则在 ToolRouter execution queue 后等待。
- 即使 Chrome 不在 foreground，也需要独占。CDP targets、active session state、dialogs、page navigation 和 user profile state 都是 shared mutable browser state。

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

- `Idle -> Starting`: first tool call。
- `Connected -> RunningScript`: valid `browser_script` call。
- `RunningScript -> Connected`: success、failure、timeout 或 cancellation。
- `Connected -> Broken`: Shizuku binder death、websocket close、Chrome socket loss。
- `Broken -> Starting`: next tool call attempts reconnect once。

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

- Reference repo cloned 到 `.reference/browser-harness`。
- design document 记录 ClosePaw 要复制的 browser-harness behaviors。

Dependencies: none.

### cdp-bridge-spike

Scope:

- `app/src/main/kotlin/ai/closepaw/browser/cdp/shizuku/**`
- 仅在需要时触碰 existing Shizuku client files

Acceptance criteria:

- Shizuku ready 且 Chrome running 时，代码可以证明 `chrome_devtools_remote` 是否可用。
- 如果可用，代码可以读取 `/json/version`。
- 代码可以 list page targets。
- failure modes 必须精确区分：
  - Shizuku unavailable
  - Shizuku permission missing
  - Chrome not running
  - DevTools socket missing
  - socket inaccessible from app process
  - socket inaccessible from Shizuku UserService
  - malformed HTTP/WebSocket response
- spike 记录 tested device 上精确所需的 Android/Chrome setup steps。

Dependencies: browser-reference.

### cdp-client-core

Scope: `app/src/main/kotlin/ai/closepaw/browser/cdp/**`

Acceptance criteria:

- Open CDP websocket。
- Send raw commands and match responses by id。
- Attach 到第一个 real page。
- `Target.*` 和 `Browser.*` route 时不带 page session。
- page domains 通过 active session route。
- 从 stale active session recover once。

Dependencies: cdp-bridge-spike.

### script-host

Scope: `app/src/main/kotlin/ai/closepaw/browser/script/**`

Acceptance criteria:

- Hidden WebView 可以运行 async script。
- prelude 只暴露 `cdp()`。
- CDP success resolves JS promises。
- CDP error rejects JS promises，并带 useful messages。
- Timeout 和 cancellation 能干净结束 tool call。
- `sleep()`、`log()`、`pageJs()` 等不进入 runtime prelude。

Dependencies: cdp-client-core.

### browser-script-tool

Scope:

- `app/src/main/kotlin/ai/closepaw/tool/impl/BrowserScriptTool.kt`

Acceptance criteria:

- experimental setting、Shizuku readiness、Chrome socket availability 在 execution-time 检查；不满足时 fail closed。
- Tool description 提醒 agent 使用 `browser-use` skill 获取 richer examples 和 reusable snippets。
- Tool 返回 compact JSON/text result。
- Oversized outputs 用明确 truncation marker 截断。
- Raw result 可被 trace/debug plumbing 访问。

Dependencies: script-host.

### browser-session-integration

Scope:

- `app/src/main/kotlin/ai/closepaw/session/SessionServices.kt`
- `app/src/main/kotlin/ai/closepaw/session/SessionToolingBootstrapper.kt`
- `app/src/main/kotlin/ai/closepaw/app/AppSettingsStore.kt`
- `app/src/main/kotlin/ai/closepaw/tool/PolicyEngine.kt`
- `app/src/main/kotlin/ai/closepaw/tool/ToolName.kt`

Acceptance criteria:

- `SessionServices` lazily owns `BrowserSessionManager?` and passes it to `BrowserScriptTool` without making `SessionToolingBootstrapper` own Android `Context`/WebView details.
- `BrowserSessionManager` receives the dependencies it needs: `Context`, session `CoroutineScope`, `TraceRecorder`, experimental browser automation setting, Shizuku bridge factory, and cleanup hook.
- Session shutdown closes active script, hidden WebView, CDP WebSocket, and Shizuku UserService binding.
- `browser_script` is registered into `ToolRegistry` and always appears in LLM function schemas.
- `ToolName` adds explicit `BrowserScript` and marks it screen-changing.
- `PolicyEngine` adds an explicit `browser_script` rule: in SMART, ask user by default regardless of foreground app tier or Chrome being `NORMAL`; in ALWAYS_ASK, ask user; in AUTO_APPROVE, allow after experimental/Shizuku/socket gates; if an explicit browser automation approval override exists, document and test its exact behavior.
- Existing app allow-list must not silently bypass the browser-specific SMART approval rule.
- Tests cover SMART mode for `browser_script` on `com.android.chrome` and verify it asks even when Chrome is `NORMAL`.

Dependencies: browser-script-tool.

### browser-use-skill-v0

Scope:

- `app/src/main/assets/agent_skills/browser-use/SKILL.md`
- `app/src/main/assets/agent_skills/browser-use/scripts/**`
- runtime install/copy path `context.filesDir/skills/browser-use/**`

Acceptance criteria:

- App bundles `browser-use/SKILL.md` and `browser-use/scripts/**`, then installs/seeds the whole directory into the actual `AgentSkillManager` directory (`context.filesDir/skills`) before catalog construction.
- Runtime skill source of truth is `context.filesDir/skills/browser-use/**`; assets are only the bundled seed/update source.
- Built-in `browser-use` is non-user-editable in v1: overwrite installed `browser-use` files from bundled assets on each app start/session bootstrap. Custom user variants must use a different skill name.
- When installing `SKILL.md`, replace a `{{SKILL_DIR}}` placeholder with the installed absolute path, e.g. `/data/user/0/ai.closepaw/files/skills/browser-use`, so the agent can run `shell(command="cat <path>/scripts/page.js")`.
- `SKILL.md` 说明何时使用 `browser_script`，并强调先用 raw CDP，必要时用 `shell cat` 读取 snippets。
- Essential snippets 按工作流分组放在 `scripts/` 中：
  - `page.js`: `pageJs`、`waitForLoad`、`screenshot`、小型 timing helpers。
  - `tabs.js`: `listTabs`、`currentTab`、`switchTab`、`newTab`。
  - `input.js`: `clickAt`、`typeText`。
- Snippets 不依赖 hidden runtime API，除 `cdp()` 外都在 snippet 内自包含。
- `pageJs` snippet 负责 JS exception formatting，保留 line/column 和 newline-escaped snippets。
- Screenshot snippet 能 cap max dimension。
- Skill docs 包含 raw-CDP examples 和 snippet examples。

Dependencies: browser-session-integration.

### verification

Scope:

- `app/src/test/kotlin/ai/closepaw/browser/**`
- optional debug receiver/activity if needed

Acceptance criteria:

- Unit tests 覆盖 snippet truncation、result formatting、command routing、stale-session recovery decision 和 `cdp()` prelude bridge。
- `browser-use/scripts/` snippets 至少做 syntax/static checks；如果有 snippet test harness，则覆盖 `pageJs` top-level return detection 和 helper generation。
- Test the seed/install path copies `scripts/` into `context.filesDir/skills/browser-use/` and rewrites `{{SKILL_DIR}}` in installed `SKILL.md`.

Dependencies: browser-use-skill-v0.

### holistic-review

Scope:

- `projects/active/browser/holistic_review.md`

Acceptance criteria:

- Review the full Browser CDP runtime change set against this design.
- Cover KISS, security/policy, session lifecycle, Shizuku/CDP transport, WebView host hardening, `browser-use` skill packaging, tests, and docs.
- Document findings in `projects/active/browser/holistic_review.md`.
- The review must explicitly say whether there are open critical/high findings; critical/high findings must be fixed or converted into follow-up tasks before this task is done.

Dependencies: verification.

### real-device-qa

Scope:

- `projects/active/browser/real_device_qa.md`

Acceptance criteria:

- On a real Android device with Chrome and Shizuku permission, exercise one browser use case through `browser_script` and the installed `browser-use` snippets.
- The use case must open a page, read title/content through CDP, capture screenshot evidence, create or switch tabs, and return to the original tab.
- Document device/app build, setup state, exact use case steps, observed result, and evidence in `projects/active/browser/real_device_qa.md`.
- If the environment is unavailable, document the exact blocking reason and leave the task blocked rather than marking it done.

Dependencies: holistic-review.

## Trade-Offs

### One `browser_script` Tool vs Many Browser Tools

一个 script tool 胜出。它保持 prompt surface 小，并让 agent 直接使用 CDP。很多 wrappers 会重建一个 partial Playwright API，并成为 maintenance burden。richer usage 放在 installed `browser-use/scripts/` snippets，而不是增加 native tools。

### Always-Loaded Tool vs Skill-Gated Schema

v1 选择 always-loaded `browser_script` schema。skill-gated schema exposure 更省 prompt surface，但需要改动 turn/tool loading 和 activated skill allowlist 数据流；当前阶段不值得。`browser-use` skill 只承载 instruction 和 snippets 索引，不控制 tool 是否出现在 function schemas 中。

### JS Host vs Termux

v1 选择 hidden WebView JS host。它内置于 Android，不需要外部安装，并且可以通过 `JavascriptInterface` 调 Kotlin。Termux 适合 prototyping 或 Python/npm ecosystems，但不是 product runtime。

### Raw CDP vs Typed Kotlin Wrappers

Raw CDP 胜出。CDP 在 method-name/JSON boundary 上已经是 typed protocol。typed wrappers 会增加 LOC，却不增加 browser capability。重复且 error-prone 的 browser 操作放进 installed `browser-use/scripts/` snippets，而不是 Kotlin wrappers 或 runtime helper API。

### Shizuku UserService vs Shell Bridge

product 选择 UserService。shell bridge 依赖 external binaries 或 long-running shell process。UserService 让 ClosePaw 在 Kotlin 中拥有 lifecycle、transport 和 errors。

### Native Tool vs Shell CLI

product path 选择 native `BrowserScriptTool`。shell CLI 可以作为 spike/debug shortcut，但不适合作为 agent-facing runtime：browser automation 需要 session lifecycle、approval/policy、trace、cancellation、exclusive lease、Shizuku binder state 和 WebView JS host Looper，这些都已经在 app process 内。

### No Policy In v0

这只对 URL/method-level policy matrix 可接受。最小 tool-level policy 仍然必须有：

- `browser_script` 是 screen-changing。
- SMART mode 默认 ask for approval。
- 即使 tool schema always loaded，execution 也必须在 experimental browser automation 启用且 Shizuku ready 时才允许继续。
- `browser_script` 需要 PolicyEngine 显式 rule；不能只依赖 Chrome app tier，因为 `com.android.chrome` 当前是 `NORMAL`。

CDP 可以读取和 mutate logged-in pages，所以 production 必须增加 URL/method-level policy。但该 policy 不应阻塞 transport spike。

## Self-Review

- design 暴露一个 tool，并保持 CDP raw。
- `browser_script` schema 在 v1 always loaded；`browser-use` skill 只提供 richer usage 和 snippets 索引。
- runtime prelude 只暴露 `cdp()`；`sleep()`、`log()` 和 browser helpers 不进入 native runtime。
- native `cdp()` 不重写 `Runtime.evaluate` exceptionDetails；`pageJs` snippet 负责 JS exception formatting。
- WebView 只是 JS automation host，不是被 automation 的 browser。
- `/json/version`、`/json/list`、`/json/new` 和 `/json/activate` 是 runtime bootstrap details，不是 agent tools。
- review feedback 已处理：session lifecycle、output caps、`TraceRecorder` reuse、WebView hardening 和 screen-changing classification 都已明确。
- 捕获了能防止真实 bugs 的 browser-harness details：newline-escaped JS snippets、JS exception extraction、create-blank-then-navigate、internal target filtering、stale-session recovery、dialog tracking 和 screenshot coordinate caveats；其中 helper-style 内容放在 installed `browser-use/scripts/`。
- 最大未知风险仍是 Android Chrome DevTools socket availability 以及 Shizuku 下的 transport。这正确地排在 task list 第一位。

## Appendix: Discussion Reference

这些讨论已反映到正文设计中。实现时只以正文为准；本 appendix 仅保留决策背景。下面的历史 reply 中有些中间方案已被正文 superseded，尤其是 skill-gated schema、只把 snippets inline 到 `SKILL.md`、以及 prelude 注入 `sleep()`/`log()`。

### Skill Layer

Comment:

> 我们能不能把它实现成一个skill? 我们的很多设计类似的。跟browser_harness类似。我们的系统现在支持agentskill.io format的skill了，刚刚实现的功能。design doc was not aware of it yet。

Reply:

可以，而且应该加一层 agentskill.io format 的 skill。但它不替代 native `browser_script` tool/runtime。边界应是：Kotlin/Shizuku/WebView/CDP transport 仍由 app 实现并做 permission、lifecycle、timeout、trace 和 policy；skill 负责 agent-facing guidance，包括什么时候用 `browser_script`、raw CDP examples、常见 tab/dialog/screenshot coordinate caveats，以及可引用的 script snippets。这样结构更接近 browser-harness：core capability 很薄，agent 的使用方法和可演化经验放在 skill 里。

### Tool Loading Shape

Comment:

> 这样的做法可能未必常见，但是能不能这样：这个tool的schema变成文本放到skill里面。这样这个tool不是always loaded, it is loaded only when the skill is loaded。可以吗？背后实现，它还是作为一个tool存在的。
> 另一种做法是，把它做成一个native shell (current shell tool)可以用的cli。然后skill里提到这个cli。这个cli的实现其实更自由了？只要把binary打包了就行了？
> 你帮我想想哪种更可行？

Reply:

更可行的是“native tool implementation + skill-gated schema exposure”。也就是 `BrowserScriptTool` 仍然是 session-scoped native tool，注册在 `ToolRegistry` 或 lazy registry 里，但默认不出现在 LLM function schemas 中；`browser` skill 被 `activate_skill` 激活后，下一轮 turn 才把 `browser_script` 加进 allowed tools 并暴露真实 structured schema。schema 不应只作为 markdown/text 放在 skill 里，因为 function calling 需要 API-side structured schema；skill 可以描述 schema、examples 和 usage policy，但执行入口仍应来自 `ToolSpec`。当前代码已经有 `allowed-tools` frontmatter parsing 和 per-turn `allowedToolNames` filter，缺的是把 activated skill 的 `allowed-tools: [browser_script]` 接到 turn request 的 tool allowlist 上。

CLI 方案更适合作为 spike/debug tool，不适合作为 product path。原因是 browser automation 需要 session lifecycle、approval/policy、trace、cancellation、exclusive lease、Shizuku binder state、WebView JS host Looper 这些 app 内状态；把它塞进 shell CLI 会把这些能力拆到另一个 process/binary 里，权限、ABI packaging、日志、超时和 cleanup 都更难。native binary 直接连 `chrome_devtools_remote` 对 transport spike 有价值，但 agent-facing path 应走 skill-gated `browser_script`。

Follow-up:

> ok，那我们还是简化一下吧，这个tool一直load就行了。description里提一嘴browser-use skill，browser-use/SKILL.md可以有更丰富的用法，包括browser-use/scripts/可以有helper等等。这样ok吗？

Reply:

ok，这更 KISS。v1 就让 `browser_script` always registered / always load schema，但 capability 仍由 experimental setting、Shizuku readiness 和 policy gate 控制。tool description 只放最小说明，并提示“for richer usage, activate/read the `browser-use` skill”；`browser-use/SKILL.md` 放操作策略、examples、常见坑，`browser-use/scripts/` 放可复制的 helper snippets。这样不会为了 lazy schema exposure 改动 turn/tool loading 架构，也保留 browser-harness 风格的可演化用法库。

### Helper Snippets

Comment:

> 这些helper能不能都变成agent写scripts时可以引用的文件？类似browser-harness。可以参考这篇文章https://browser-use.com/posts/bitter-lesson-agent-harnesses。我们的设计哲学应该跟里面是一样的，不必给agent不必要的脚手架，因为这可能反而成为限制。当然shizuku/webview等等这些我们是要实现好，对agent隐藏起来的。

Reply:

同意。这里应改成“runtime prelude 最小化 + skill/reference files 提供 helper snippets”。`BrowserScriptRunner` 默认只注入稳定的 `cdp()`、`sleep()`、`log()` 和 promise bridge；`pageJs`、`newTab`、`waitForLoad`、`screenshot`、`clickAt`、`typeText` 这些不应被设计成厚 runtime API，也不应变成一组 agent tools。更好的形态是放进 browser skill 的可引用 JS files/snippets，由 agent 在 script 中按需引用、复制或改写。只有真正跨所有脚本都必须存在且很难安全重写的基础机制，才进入 prelude。Shizuku/WebView/CDP transport、WebView hardening、trace、policy 继续对 agent 隐藏。

### `sleep()` And Bridge

Comment:

> sleep()为什么要提供？这不是javascript自带的吗？

Reply:

JavaScript 自带 `setTimeout`，但没有标准的 promise-based `sleep()`。所以 `sleep()` 只是非常薄的 convenience helper：`ms => new Promise(resolve => setTimeout(resolve, ms))`。如果我们坚持 runtime prelude 极小化，可以把它从 mandatory prelude 移到 skill snippet；agent 需要时自己写一行即可。我的倾向是保留也可以，因为它零权限、零 CDP coupling、行为稳定；但按“不要给 agent 不必要脚手架”的原则，最纯的设计是 prelude 只保留 `cdp()` 和 bridge，`sleep()` 放到 browser skill 的 snippets 里。

Follow-up:

> 同意删掉。 保持KISS。这个briduge是什么？

Reply:

这里的 bridge 指的是 `@JavascriptInterface` + Kotlin callback 这层胶水：agent script 在 hidden WebView 里调用 `cdp(method, params)`，JS 不能直接连 Chrome DevTools socket，所以 `cdp()` 会把请求序列化成 JSON，发给 exposed Kotlin object；Kotlin 再调用 `ChromeCdpClient`，拿到结果后用 `evaluateJavascript("__cdpResolve(...)")` 或 `__cdpReject(...)` 回填 JS Promise。KISS 后可以这样表述：prelude 只提供 `cdp()`；`sleep()` 删除；内部 bridge 是 runtime implementation detail，不作为 agent-facing helper。

### Raw CDP Coverage

Comment:

> 这个cdp(method, params)的接口是可以access到现所有cdp提供的capability的是吧？

Reply:

设计上是的，`cdp(method, params, options)` 是 raw CDP escape hatch，不按能力逐个封 wrapper；只要 Chrome Android 暴露该 CDP domain/method，且 routing 正确，就能调用。实际边界有四个：第一，browser-level methods 如 `Target.*`、`Browser.*` 需要走 browser session；page-level domains 默认走 active page session，必要时用 `options.targetId`/`options.sessionId`。第二，Android Chrome 的 CDP 支持面可能和 desktop Chrome 不完全一致，某些 domains/methods 可能不存在或行为不同。第三，v1 transport 只连用户真实 Chrome profile，不提供额外 OS/file/provider API。第四，production 仍要在 tool/policy 层限制高风险 URL/method；raw capability 不等于默认允许所有调用。
