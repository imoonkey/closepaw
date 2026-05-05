# Browser CDP Runtime — Implementation Summary

> Snapshot of the Browser CDP runtime project at milestone close.
> Source of truth for design decisions: `cn/design_codex.md` (do not edit from here).
> Last updated: 2026-05-04 (browser-cdp-runtime + browser-phase5/6 milestones DONE)

## Goal

Give ClosePaw a minimal, programmable browser-automation path against the user's **real** Chrome
(with their profile and logged-in state), driven via Chrome DevTools Protocol over a transport
that does not require root, a connected PC, or external network. Single agent-facing tool:
`browser_script`. Helpers/snippets live in the bundled `browser-use` agent skill, not in the
runtime prelude.

## Status

- **`browser-cdp-runtime`** — DONE 2026-05-04. All 16 leaves done.
- **`browser-phase5`** (cleanup + production hardening) — DONE 2026-05-03. 10 leaves (1 cancelled).
- **`browser-phase6`** (security + dependency hardening from codex review) — DONE 2026-05-04.

Real-device evidence: nubia P0110 (Android 13, stock) and AOSP `emulator-5556` both PASS the full
agent chain via `debug-run.sh`. Test ledger in `real_device_qa.md`. Production-ready.

## Final Architecture

- **One tool, raw CDP** — `browser_script` is the only agent-facing surface. Helpers live in the
  bundled `browser-use` skill, not in the runtime prelude. Schema is always loaded so the agent
  knows the capability exists; capability gate fires per-call.
- **Two-transport cascade** — `USER_SERVICE` (Shizuku) → `WIRELESS_ADB_SELF_PAIR` (in-app TLS-PSK
  SPAKE2-25519 + mTLS over ADB wire protocol). The earlier `APP_PROCESS`,
  `HOST_MEDIATED_RELAY`, and `CHROME_TCP_LOOPBACK` transports were each empirically blocked on
  stock Android and removed in Phase 5 (-1791 net source lines).
- **Token-gated localhost relays** — both transports bind `127.0.0.1:0`. Per-session 32-byte hex
  `RelayAuthToken` carried as `X-ClosePaw-Token` on the WS Upgrade gates accept; constant-time
  verify; total pre-auth deadline (5s) defeats slowloris; 4 KiB header buffer cap.
- **Atomic target switching** — `ChromeCdpClient.switchMutex` serializes target switches and a
  per-`LiveConnection` pending map keeps stranded replies from misrouting.
- **Session-scoped storeArtifact quota** — cumulative decoded-byte counter on
  `BrowserSessionManager` (atomic CAS) so reconnects don't reset and the cap covers the whole
  session, not per-call.
- **Pure-Kotlin SPAKE2-25519** — `Spake25519.kt` (183 lines) over `net.i2p.crypto:eddsa` (CC0).
  Replaces the previous LGPL-3.0 JitPack JNI dep `MuntashirAkon/spake2-android`. KAT vectors
  pinned. ISC notice carried.
- **Dependency hardening** — BouncyCastle bumped to 1.84 (CVE). BC + Conscrypt providers added
  and removed at narrow scope to avoid app-wide JSSE clashes.
- **Hardened script host** — cancellation guard in `ScriptHostWebViewClient` page-load and
  prelude-eval callbacks blocks queued callbacks from running arbitrary agent JS after timeout.
- **Screenshot returns artifact path** — `screenshot()` writes to the trace artifact store via
  `storeArtifact` and returns a relative path; never returns base64 bytes inflating the JS
  result.

## Where Things Live

| Concern | File |
|---------|------|
| Tool entry point | `tool/impl/BrowserScriptTool.kt` |
| Capability gate | `tool/impl/DefaultBrowserScriptCapabilityGate.kt` |
| Session ownership | `browser/script/BrowserSessionManager.kt`, `session/SessionServices.kt` |
| Hidden WebView host | `browser/script/BrowserScriptRunner.kt` + `BrowserScriptPrelude.kt` + `BrowserScriptJsInterface.kt` |
| CDP client | `browser/cdp/ChromeCdpClient.kt` + `ChromeCdpCommand.kt` + `ChromeCdpTarget.kt` |
| Transport cascade | `browser/cdp/shizuku/ShizukuChromeDevtoolsBridge.kt` |
| USER_SERVICE transport | `browser/cdp/shizuku/ChromeDevtoolsUserService.kt` + `UserServiceTransport.kt` + `ShizukuUserServiceProvider.kt` |
| Wireless self-pair transport | `browser/cdp/wireless/WirelessAdbSelfPairTransport.kt` (+ pairing/wire/crypto under `wireless/`) |
| Localhost relay token | `browser/cdp/RelayAuthToken.kt` |
| Bundled skill | `app/src/main/assets/agent_skills/browser-use/**` (seeded into `filesDir/skills/browser-use/`) |
| Policy rule | `tool/PolicyEngine.kt` (Chrome SMART asks even at NORMAL tier) |
| Settings | `AppSettingsStore.browser_script_enabled` (default false), Settings UI under Experimental |
| Architecture doc | `doc/main/infra/browser.md` |
