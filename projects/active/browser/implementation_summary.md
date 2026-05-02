# Browser CDP Runtime — Implementation Summary

> Snapshot of the active Browser CDP runtime project.
> Source of truth for design decisions: `cn/design_codex.md` (do not edit from here).
> Last updated: 2026-05-02

## Goal

Give ClosePaw a minimal, programmable browser-automation path against the user's **real** Chrome (with their profile and logged-in state), driven via Chrome DevTools Protocol over a Shizuku-mediated transport. Single agent-facing tool: `browser_script`. Helpers/snippets live in the bundled `browser-use` agent skill, not in the runtime prelude.

## Task Graph (per design_codex.md)

| # | Task ID | Status | Scope |
|---|---------|--------|-------|
| 1 | `browser-reference` | ✅ done | `.reference/browser-harness` checked out, design doc captures behaviors to copy |
| 2 | `cdp-bridge-spike` | ✅ accepted | `browser/cdp/shizuku/` — Shizuku status, Chrome socket probe, app-process LocalSocket transport, UserService fallback, distinct `DevtoolsSetupError` taxonomy |
| 3 | `cdp-client-core` | ✅ accepted | `browser/cdp/` — `ChromeCdpClient` with command id mux, page-session routing (`Target.*`/`Browser.*` browser-level), stale-session recovery |
| 4 | `script-host` | ✅ accepted | `browser/script/` — hidden hardened WebView, `BrowserScriptPrelude` (only `globalThis.cdp` exposed), `BrowserScriptBridge` (parse send → CDP → resolve/reject + cancel/timeout), `BrowserScriptJsInterface` (only `send`/`done`), `BrowserScriptRunner` lifecycle |
| 5 | `browser-script-tool` | ✅ accepted | `tool/impl/BrowserScriptTool.kt` + types + concrete capability gate + tests |
| 6 | **`browser-session-integration`** | ✅ **this snapshot** | Session-scoped `BrowserSessionManager`, tool registration, settings flag, UI toggle, policy rule, shutdown cleanup |
| 7 | `browser-use-skill-v0` | ⏳ pending | Bundle `browser-use/SKILL.md` + `scripts/{page,tabs,input}.js` into assets; install/seed into `context.filesDir/skills/browser-use/`; rewrite `{{SKILL_DIR}}` placeholder |
| 8 | `verification` | ⏳ pending | Snippet syntax checks, install/seed test, broader unit-test sweep |
| 9 | `holistic-review` | ⏳ pending | Cross-cut review of the whole change set against design |
| 10 | `real-device-qa` | ⏳ pending | One end-to-end use case on a real Android device with Chrome + Shizuku permission |

## What Just Landed (browser-session-integration)

- **`BrowserSessionManager`** — session-scoped owner for Shizuku bridge, CDP client, hidden-WebView runner, exclusive script lease, Broken-state teardown, and reconnect on the next call after transport failure.
- **Session wiring** — `SessionServices.create()` constructs the manager with `context.applicationContext`, registers `BrowserScriptTool`, injects `DefaultBrowserScriptCapabilityGate`, and closes browser resources during cleanup. `SessionToolingBootstrapper` stays Android-free.
- **Settings and policy** — `browserScriptEnabled` defaults false, persists through `AppSettingsStore`, and is exposed as **Browser automation (experimental)** in Settings. `ToolName.BrowserScript` is screen-changing. `PolicyEngine` asks in SMART for `browser_script` even when Chrome is `NORMAL`, and the user allow-list does not bypass that rule.
- **Regression coverage** — policy, settings round-trip, tool registry wiring, cleanup after browser use, transport failure, synchronous send failure, server WebSocket close, CDP/bridge teardown, reconnect, and androidTest signature coverage.

## Key Decisions

- **One tool, raw CDP** — `browser_script` is the only agent-facing surface. No `browser_click` / `browser_type` / `browser_open` wrapper tools. JS control flow inside the script collapses many CDP round-trips into one tool call.
- **Always-loaded schema, execution-time gate** — schema appears in function-calling prompts whether the gates pass or not, so the agent knows the capability exists. The gate is consulted on every `execute()` call, fail-closed.
- **`BrowserScriptRunner` is owned by `BrowserSessionManager`**, not by the tool. The tool only sees a `BrowserScriptInvoker` lambda the gate hands back when capability is available — keeps the tool Android-context-free and unit-testable.
- **Trace gets the full `ScriptResult`** — `rawResultJson` always carries the serialized runner outcome (success payload OR error JSON), never the truncated user-facing text. The prompt context stays bounded; the debug pipeline sees everything.
- **Mid-execution cancellation reports real elapsed time** — the watchdog approach captures `started = clock()` *before* the scope, so the cancellation trace records actual duration, not 0ms.

## Open Items For Next Task (`browser-use-skill-v0`)

- Bundle `browser-use/SKILL.md` and `scripts/{page,tabs,input}.js`.
- Seed bundled skill files into `context.filesDir/skills/browser-use/`.
- Keep native runtime helpers out of the WebView prelude; snippets are read and inlined by the agent when needed.
