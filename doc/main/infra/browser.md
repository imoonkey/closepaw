# Browser CDP Runtime

> Session-scoped `browser_script` runtime: `BrowserSessionManager`, Chrome CDP client, the
> two-transport Shizuku/wireless-ADB cascade, the localhost-relay token gate, settings, and
> policy.
> Last updated: 2026-05-04 (browser-cdp-runtime + browser-phase5/6 milestones)

Design source of truth: [projects/active/browser/cn/design_codex.md](../../../projects/active/browser/cn/design_codex.md).

## Overview

`browser_script` is the single agent-facing entry point for browser automation. The tool runs
JavaScript inside an app-owned hidden WebView, but the WebView is only the script host. The target
browser is the user's real Android Chrome profile reached through Chrome DevTools Protocol (CDP)
over a Shizuku- or wireless-ADB-mediated transport that terminates at Chrome's
`chrome_devtools_remote` abstract Unix socket.

The runtime exposes one primitive to scripts:

```javascript
await cdp(method, params = {}, options = {})
```

There are no native `browser_click`, `browser_type`, or helper APIs. Loops, retries, parsing, and
workflow-specific helpers live in the submitted JavaScript or installed agent skill snippets.

The bundled `browser-use` Agent Skill packages the reusable snippets. APK assets live under
`app/src/main/assets/agent_skills/browser-use/`, but session runtime loads only the installed copy
under `context.filesDir/skills/browser-use/`. Installed `SKILL.md` contains absolute paths to:

- `scripts/page.js` — `pageJs`, navigation/load wait, `screenshot()` (writes to the trace artifact
  store via `storeArtifact` and returns the relative path; never returns base64 bytes), page info
- `scripts/tabs.js` — list/current/switch/new tab helpers
- `scripts/input.js` — click/type/key/scroll helpers

## Transport Cascade

`ShizukuChromeDevtoolsBridge` tries two transports in order. Each transport produces a localhost
TCP relay (`127.0.0.1:0` ephemeral port) that pumps bytes between the OkHttp WebSocket client in
`ChromeCdpClient` and Chrome's `@chrome_devtools_remote` abstract socket. There is no APP_PROCESS
transport, no host-mediated PC relay, and no `--remote-debugging-port` TCP loopback — those were
all removed in the Phase 5 cleanup after each was empirically blocked on stock Android.

| Order | Label | When it works |
|-------|-------|---------------|
| 1 | `USER_SERVICE` | Shizuku is running and ClosePaw has been authorized — proxies through `ChromeDevtoolsUserService` running under shell UID |
| 2 | `WIRELESS_ADB_SELF_PAIR` | Wireless debugging is enabled in Developer Options on Android 11+; ClosePaw self-pairs and speaks the ADB wire protocol from inside the app |

`UserServiceTransport` is single-flight (`ShizukuUserServiceProvider` keeps one binder per process)
and pair-once (when authorized once, subsequent sessions reuse the binder until it dies). The
wireless transport keeps the paired ADB key in app-private storage and self-pairs through
`AdbPairingClient` (TLS-PSK SPAKE2-25519 over an mTLS handshake), then speaks the post-mTLS ADB
wire protocol via `AdbWireProtocolClient` to open the abstract socket.

Each cascade attempt that fails surfaces a distinct `DevtoolsSetupError` so the agent and UI can
compose actionable setup guidance instead of a generic error.

## Wireless ADB Self-Pair

Package: `app/src/main/kotlin/ai/closepaw/browser/cdp/wireless/`

- `Spake25519.kt` — pure-Kotlin port of BoringSSL `spake25519.c` over `net.i2p.crypto:eddsa`
  (CC0). 183 lines of glue. Replaces the previous JitPack JNI dep
  `com.github.MuntashirAkon.spake2-java:spake2-android` (LGPL-3.0).
- `AdbPairingClient.kt`, `AdbPairingTls.kt`, `AdbPairingPacket.kt` — pairing handshake over the
  TLS-PSK channel; the PSK is the pairing code (6-digit) the user types from Developer Options.
- `AdbCryptoKeyStore.kt`, `AndroidPubkey.kt` — RSA-2048 keypair persisted in app-private storage,
  encoded in the AOSP `adb_keys` blob format the device matches against on subsequent connects.
- `AdbTlsClient.kt`, `AdbWireProtocolClient.kt`, `AdbProtocol.kt` — A_STLS upgrade, mTLS, then the
  CNXN/AUTH/OPEN ADB wire protocol that opens the `localabstract:chrome_devtools_remote` channel.
- `WirelessAdbProviders.kt` — provider scoping (BC + Conscrypt are added/removed at narrow scope
  to avoid clashing with app-wide JSSE).
- `WirelessAdbSelfPairTransport.kt` — wires it all together and exposes the same `start()`
  contract as `UserServiceTransport` so the bridge cascade is symmetric.
- `ProcNetTcpListeners.kt` — discovers the wireless-debugging port from `/proc/net/tcp`+`tcp6`
  (Android does not expose it through any public API).

The `adb_keys` ceiling (currently 8 entries) prunes stale paired keys before adding a new one so
pairing slots don't fill up after repeated reinstalls. Each new key carries metadata (timestamp +
version) so the prune helper can pick the oldest entry deterministically.

## Localhost Relay Token Gate

Both relays bind `127.0.0.1:0` so any other app on the device could otherwise dial the same port
and drive Chrome DevTools the moment a script is running.

`RelayAuthToken` (`browser/cdp/RelayAuthToken.kt`) closes that hole:

- `BrowserSessionManager` generates a fresh 32-byte hex (256-bit) token at construction.
- The same token is baked into the OkHttp client (sent as the `X-ClosePaw-Token` header on the WS
  Upgrade) and into both relays' accept loops.
- The relay's accept loop reads the HTTP request line + headers under a TOTAL pre-auth deadline
  (5 s) — not a per-read idle timeout — so a slowloris client dribbling bytes just under any
  per-read cap is still timed out. Headers exceeding 4 KiB are 403'd; missing/wrong tokens get
  403; deadline exceeded gets 408. The buffered request bytes are forwarded verbatim once auth
  succeeds; Chrome silently ignores `X-ClosePaw-Token`.
- `verify` is constant-time over equal-length byte arrays (length mismatch can short-circuit
  safely, byte comparison runs to completion to deny timing oracles).
- After auth, the relay restores `soTimeout = 0` so the long-lived proxied stream isn't capped.

## Session Ownership

`SessionServices.create()` owns browser runtime wiring:

- Creates a session-scoped `BrowserSessionManager` with `context.applicationContext` and the
  session coroutine scope. Construction is cheap — no WebView, CDP socket, or Shizuku UserService
  binding is created until capability preflight or the first script call needs them.
- Installs bundled Agent Skill seeds before the skill catalog is constructed; `browser-use`
  installation rewrites `{{SKILL_DIR}}` and writes `.install-complete` as the success sentinel.
- Registers `BrowserScriptTool` in `ToolRegistry`.
- Wires `DefaultBrowserScriptCapabilityGate` to `AppSettingsStore`, `ShizukuStatusAdapter`,
  `BrowserSessionManager.preflight()`, and a `BrowserScriptInvoker`.
- Writes `browser_script` trace metadata through the session `TraceRecorder`.

`SessionToolingBootstrapper` stays Android-free. It creates policy, state, core built-in tools,
and the router, but it does not receive `Context`, construct WebViews, or own Shizuku/CDP
resources.

## Lifecycle

- Session created: manager exists, browser transport is idle, token is generated.
- Capability gate: checks experimental setting, Shizuku availability, Shizuku permission, then
  bridge preflight (which runs the cascade once to confirm the path to Chrome).
- First `browser_script`: acquires the per-session script lease, fetches Chrome `/json/version`
  and page targets, opens the CDP WebSocket through the cascade-selected transport, attaches the
  first real page, enables core domains, and runs the script host.
- Target switching: `ChromeCdpClient` serializes target switches under `switchMutex` and tracks
  pending commands per `LiveConnection` so a switch doesn't strand or misroute pending replies.
- Running/Idle: healthy CDP resources stay warm for follow-up calls.
- Transport failure, synchronous send failure, or server WebSocket close: manager marks the
  runtime broken, closes both CDP and Shizuku/wireless bridge resources, and the next call
  rebuilds from scratch.
- Session cleanup: `SessionServices.cleanup()` closes `browserSessionManager` before platform,
  LLM, and trace teardown.

Only one `browser_script` can run per session at a time. A concurrent invocation returns a clear
busy failure instead of sharing mutable Chrome target/session state.

## Artifact Storage And Quotas

`storeArtifact(category, fileName, base64Bytes, mimeType)` is exposed to scripts via
`BrowserScriptJsInterface` and writes through the session `TraceRecorder`. `screenshot()` in the
`browser-use` skill uses it so screenshots return a stable relative path instead of inflating the
JS string return value with base64.

The cumulative decoded-byte counter (`sessionDecodedBytes`) lives on `BrowserSessionManager` so
it survives `markBroken` reconnects and is shared across every call within a session. The cap is
enforced via atomic CAS in `BrowserScriptJsInterface` (see `MAX_BYTES_PER_SESSION`); calls past
the cap are rejected synchronously without writing partial files.

## Settings And Policy

`browserScriptEnabled` is persisted in `AppSettingsStore` as `browser_script_enabled`, defaults
to `false`, and is exposed in Settings under **Permissions & Advanced → Experimental → Browser
automation (experimental)**.

`ToolName.BrowserScript` is screen-changing because CDP can mutate Chrome tabs, profile state,
dialogs, navigation, and page content even when Chrome is not foreground. `PolicyEngine` applies
a browser-specific rule after the BLOCKED-app floor and before user allow-lists:

| Approval mode | `browser_script` decision |
|---------------|---------------------------|
| `ALWAYS_ASK` | Ask |
| `AUTO_APPROVE` | Allow at policy; the tool still applies runtime capability gates |
| `SMART` | Ask, including for `com.android.chrome` even though Chrome is `NORMAL` |

The session and persistent allow-lists do not bypass the SMART-mode browser prompt.

## Verification

JVM coverage:

- `PolicyEngineTest` — Chrome SMART-mode ask rule and allow-list bypass guard.
- `SessionBrowserIntegrationTest` — `browser_script` registration.
- `SessionServicesCleanupTest` — shutdown cleanup after browser use.
- `BundledAgentSkillInstallerTest`, `BrowserUseSkillAssetTest`,
  `SessionServicesBundledSkillInstallTest` — bundled `browser-use` copy, snippet assets,
  placeholder substitution, and sentinel-gated refresh fallback.
- `BrowserSessionManagerTest` — transport failure, synchronous send failure, server close, full
  CDP/bridge teardown, and reconnect on the next run.
- `RelayAuthTokenTest` — generate/verify constant-time, header parse, deadline enforcement,
  403/408 wire bytes.
- `ChromeCdpClientTest`, `ChromeCdpRecoveryTest` — command id mux, target switch atomicity,
  stale-session recovery.
- `ShizukuUserServiceProviderTest`, `ShizukuChromeDevtoolsBridgeTest`,
  `ChromeDevtoolsUserServiceRelayTest`, `DevtoolsHttpProtocolTest` — UserService transport.
- `Spake25519Test` (incl. KAT vectors), `AdbPairingClientTest`, `AdbPairingPacketTest`,
  `AdbCryptoKeyStoreTest`, `AndroidPubkeyTest`, `AdbProtocolTest`, `AdbWireProtocolClientTest`,
  `AdbWirelessManagerTest`, `WirelessAdbSelfPairTransportRelayStressTest` — wireless ADB
  self-pair stack including a relay stress harness.
- `AppSettingsStoreTest` — experimental flag default and round-trip persistence.

androidTest coverage:

- `BrowserScriptRunnerInstrumentedTest` — hidden-WebView prelude and runner lifecycle.

Real-device evidence:

- nubia P0110 (Android 13, stock) — Phase 5 final-gate PASS via wireless-ADB self-pair, full
  agent chain through `debug-run.sh`.
- AOSP `emulator-5556` — Phase 5 final-gate PASS via `USER_SERVICE` after the chrome://flags Local
  State unlock procedure (Chrome stable on AOSP defaults disable the DevTools socket; the
  procedure is recorded in `projects/active/browser/cn/diag_20260503_emu_chrome_devtools.md`).
