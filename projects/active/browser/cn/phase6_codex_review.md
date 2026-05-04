# Phase 6 Codex Review

1. Token-gate relays: YES. The session token is generated and attached to OkHttp WS upgrades, and both relays verify it before opening upstream CDP (`BrowserSessionManager.kt:55`, `BrowserSessionManager.kt:74`, `ChromeDevtoolsUserService.kt:278`, `ChromeDevtoolsUserService.kt:294`, `WirelessAdbSelfPairTransport.kt:208`, `WirelessAdbSelfPairTransport.kt:224`).

2. WS handshake vs idle: YES. The TLS channel exposes idle-timeout reset, and `openOnChannel` clears it after ADB open completes (`AdbTlsClient.kt:43`, `AdbTlsClient.kt:118`, `AdbWireProtocolClient.kt:50`, `AdbWireProtocolClient.kt:58`).

3. CDP target switch races: YES. Switches are serialized, stale WS callbacks are ignored, and each live connection owns/drains its own pending map (`ChromeCdpClient.kt:37`, `ChromeCdpClient.kt:181`, `ChromeCdpClient.kt:195`, `ChromeCdpClient.kt:247`, `ChromeCdpClient.kt:314`, `ChromeCdpClient.kt:342`).

4. storeArtifact DoS: NO. The byte counter lives inside `BrowserScriptJsInterface`, but a fresh interface is created on every `BrowserScriptRunner.run()`, so the advertised per-session quota resets between `browser_script` calls (`BrowserScriptJsInterface.kt:16`, `BrowserScriptJsInterface.kt:83`, `BrowserScriptRunner.kt:35`, `BrowserScriptRunner.kt:73`). The quota check/add is also not an atomic reservation (`BrowserScriptJsInterface.kt:47`, `BrowserScriptJsInterface.kt:63`).

5. BouncyCastle CVE: YES. Both BouncyCastle artifacts are now pinned to 1.84 (`app/build.gradle.kts:140`, `app/build.gradle.kts:142`, `app/build.gradle.kts:143`).

6. SPAKE2 / LGPL purge: YES for the licensing/dependency finding. JitPack is gone, `eddsa` is the only SPAKE arithmetic dependency, and the pure Kotlin SPAKE implementation is wired into pairing (`settings.gradle.kts:8`, `settings.gradle.kts:10`, `app/build.gradle.kts:145`, `app/build.gradle.kts:150`, `AdbPairingClient.kt:45`, `Spake25519.kt:56`). Crypto quality nit: the KATs are explicitly self-pinned rather than independent reference vectors (`Spake25519Test.kt:11`, `Spake25519Test.kt:30`, `Spake25519Test.kt:129`).

New Critical/High: High remains for `storeArtifact`; a long session can write up to 500 MiB per `browser_script` invocation, not per session (`BrowserScriptJsInterface.kt:92`, `BrowserScriptRunner.kt:75`). I did not find a new Critical in relay auth, slowloris handling, target switching, BC bump, or SPAKE wiring; the relay path bounds pre-auth reads, verifies before upstream connect, replays buffered bytes, and restores stream idle behavior (`RelayAuthToken.kt:47`, `RelayAuthToken.kt:81`, `ChromeDevtoolsUserService.kt:299`, `WirelessAdbSelfPairTransport.kt:229`, `WirelessAdbSelfPairTransport.kt:240`).

Verdict: REJECT. Blocker: make the artifact quota owned by session-scoped runtime state, with atomic reservation semantics, before shipping Phase 6.
