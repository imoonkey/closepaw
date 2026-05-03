# Phase 4 Handoff — Wireless-ADB Self-Pair

**Date**: 2026-05-03
**Status**: PASS on nubia P0110 (locked OEM, no root, no PC). Production code merged on `main`.
**Last commit**: `530d63ae chore(scripts): setup.sh — auto-start Shizuku server`

This is a sequel to the spike at `projects/active/browser/cn/diag_20260502_wireless_adb_spike/SPIKE_RESULT.md`. The spike proved feasibility with five Java-via-`app_process` tools driven by external `adb pair` / `adb` commands. This handoff covers porting that path to in-app Kotlin, the seven production-only bugs that surfaced along the way, what is verified working, and what remains.

---

## What landed

### 9 commits on `main`, no force-pushes, no remote pushes

| SHA | Subject |
|---|---|
| `94483e2e` | feat(browser): add wireless-ADB self-pair foundation |
| `abd80146` | feat(browser): add ADB TLS pairing + mTLS wire protocol clients |
| `ec4d2e92` | feat(browser): wire wireless-ADB self-pair transport into bridge cascade |
| `2116d8b7` | fix(browser): wireless self-pair — BC + Conscrypt provider conflicts (PARTIAL) |
| `e26f2e49` | fix(browser): wireless self-pair — A_STLS upgrade + AndroidPubkey + Shizuku binder retry |
| `028ace80` | fix(browser): wireless self-pair — post-mTLS adb wire protocol contract |
| `cfa007f3` | fix(browser): wireless self-pair — return early on HTTP Content-Length |
| `bdf622ca` | docs(browser): record Phase 4 PASS — wireless-ADB self-pair on nubia, no PC, no root |
| `530d63ae` | chore(scripts): setup.sh — auto-start Shizuku server + verify ClosePaw API_V23 grant |

### Production code added (`app/src/main/kotlin/ai/closepaw/browser/cdp/wireless/`)

| File | LOC | What it does |
|---|---|---|
| `AdbCryptoKeyStore.kt` | 168 | Persistent RSA-2048 + self-signed X.509 (`CN=Adb,O=Android,C=US`). Sentinel-protected atomic-rename persistence under `filesDir/adb_self_pair/`. |
| `AndroidPubkey.kt` | 56 | 524-byte AOSP `android_pubkey` encoder (LE u32 size, n0inv, modulus, rr, exponent) + `<base64> <name>` line for `adb_keys`. Apache-2.0 attribution to libadb-android. |
| `WirelessAdbProviders.kt` | 50 | Idempotent JCE provider install: real BouncyCastle (replaces Android's stripped stub) + bundled Conscrypt at priority 1. |
| `AdbWirelessManager.kt` | 80 | App-side facade for IAdbManager binder calls (allowWirelessDebugging, enablePairingByQrCode, getAdbWirelessPort, disablePairing, isPubkeyAuthorized). `runInterruptible(IO)` for coroutine-cancellable binder transactions. |
| `ProcNetTcpListeners.kt` | 48 | `/proc/net/tcp[6]` LISTEN-port snapshot helper. Used by enablePairingByQrCode to discover the pair port via diff. |
| `AdbPairingPacket.kt` | 60 | 6-byte big-endian header (version=1, type, BE u32 length) + payload. |
| `TlsExporter.kt` | 25 | Bundled-Conscrypt `Conscrypt.exportKeyingMaterial` (RFC 5705) — used to bind the SPAKE2 password to the TLS session. |
| `AdbPairingTls.kt` | 121 | TLS 1.3 + throwaway self-signed cert + trust-all for the pair handshake. |
| `AdbPairingClient.kt` | 136 | AOSP pair protocol: TLS 1.3 → TLS exporter → SPAKE2-25519 (Alice role, NUL-terminated names) → HKDF-SHA256 → AES-128-GCM-encrypted PeerInfo (8192 bytes, type=ADB_RSA_PUB_KEY then base64 pubkey, zero-padded). |
| `AdbProtocol.kt` | 127 | 24-byte LE wire header (cmd, arg0, arg1, len, checksum=0, magic=cmd^~0). A_CNXN/A_OPEN/A_OKAY/A_WRTE/A_CLSE/A_AUTH/A_STLS magic numbers. |
| `AdbTlsClient.kt` | 163 | Plain TCP → A_CNXN→A_STLS plaintext preamble → mTLS 1.3 with persisted cert. Returns a `TlsChannel` interface. |
| `AdbWireProtocolClient.kt` | ~290 | Read peer A_CNXN (daemon speaks first post-mTLS), 200ms settle, A_OPEN(localabstract:…), A_OKAY ack, sendChunked WRTE/OKAY, `readUntilHttpComplete` (Content-Length aware) for HTTP path; `AdbStream` for duplex use. |
| `WirelessAdbSelfPairTransport.kt` | ~270 | Orchestrator implementing `DevtoolsSocketTransport` + `WirelessAdbRelayHost`. Lazy bootstrap (enable→port→pair), exchange via wireClient, in-process TCP relay (`ServerSocket` accept loop → AdbStream pump pair) for the WebSocket case. |

### Production code modified

- `app/src/main/aidl/ai/closepaw/browser/cdp/shizuku/IChromeDevtoolsUserService.aidl` — added 6 IAdbManager-passthrough methods.
- `app/src/main/kotlin/ai/closepaw/browser/cdp/shizuku/ChromeDevtoolsUserService.kt` — implementations of the new AIDL methods, runs in shell-uid context.
- `app/src/main/kotlin/ai/closepaw/browser/cdp/shizuku/IAdbManagerReflection.kt` — cached reflection wrapper around `android.debug.IAdbManager`.
- `app/src/main/kotlin/ai/closepaw/browser/cdp/shizuku/AppProcessLocalSocketTransport.kt` — `UserServiceTransport.binder` made public (read-only) so `AdbWirelessManager` can share the same Shizuku binder.
- `app/src/main/kotlin/ai/closepaw/browser/cdp/shizuku/ShizukuStatusAdapter.kt` — `isAvailable()` retry loop (up to 2s) for Shizuku binder propagation after `adb install -r`.
- `app/src/main/kotlin/ai/closepaw/browser/cdp/shizuku/ShizukuChromeDevtoolsBridge.kt` — added `wirelessAdbSelfPairTransport` slot in cascade (`APP_PROCESS → USER_SERVICE → WIRELESS_ADB_SELF_PAIR → HOST_MEDIATED_RELAY → CHROME_TCP_LOOPBACK`); `resolveWebSocketHost()` extended; preflight extended to accept Shizuku-only configurations; `DEFAULT_TIMEOUT_MS` 4_000 → 30_000.
- `app/src/main/kotlin/ai/closepaw/browser/cdp/shizuku/DevtoolsSetupError.kt` — new `WirelessAdbSelfPairUnavailable` variant.
- `app/src/main/kotlin/ai/closepaw/browser/script/BrowserSessionManager.kt` — `createDefaultBridge()` wires `WirelessAdbSelfPairTransport` with shared keyStore + Shizuku-binder-provider lambda.
- `app/build.gradle.kts` — `bcpkix-jdk18on:1.78.1`, `bcprov-jdk18on:1.78.1`, `org.conscrypt:conscrypt-android:2.5.2`, `com.github.MuntashirAkon.spake2-java:spake2-android:2.2.1`. Packaging exclude for `META-INF/versions/9/OSGI-INF/MANIFEST.MF` (BouncyCastle resource collision).
- `settings.gradle.kts` — JitPack repo for spake2-android.
- `scripts/setup.sh` — auto-`pidof shizuku_server` + `start.sh` if absent + verify `API_V23` grant on ClosePaw.

### Tests

| File | Status |
|---|---|
| `AndroidPubkeyTest.kt` | 3 tests, all green |
| `AdbCryptoKeyStoreTest.kt` | 4 tests, all green |
| `AdbWirelessManagerTest.kt` | 12 tests, all green (mockk-based) |
| `AdbPairingPacketTest.kt` | 9 tests, all green |
| `AdbPairingClientTest.kt` | 7 tests, all green (AES-GCM IV scheme, HKDF determinism, PeerInfo layout) |
| `AdbProtocolTest.kt` | 9 tests, all green |
| `AdbWireProtocolClientTest.kt` | 3 tests, all green (in-process FakeAdbd over PipedStreams; matches the post-mTLS daemon-speaks-first contract incl. ready-OKAY handshake) |
| `AdbPairingClientInstrumentedTest.kt` | 2 tests (compile-checked; not run on host JVM — verifies SPAKE2 + Conscrypt classes resolve) |

`./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lint` is fully green at HEAD.

### Real-device QA

`projects/active/browser/real_device_qa.md` "Phase 4 — Wireless-ADB self-pair on nubia P0110 — PASS" section landed in commit `bdf622ca`. Top-level Verdict table updated to four transport paths.

Evidence files:
- `debug-output/run_20260503_004020/` — first end-to-end agent run (full pair flow + chrome HTTP exchange + agent answers `document.title is "Example Domain"`).
- `debug-output/run_20260503_004131/` — second run, same answer (re-pairs idempotently — see Outstanding #1).
- `projects/active/browser/cn/diag_20260502_wireless_adb_spike/qa_phase4_*.txt` — early diagnostic captures during the fix-and-test cycle.

---

## End-to-end flow that works on nubia P0110 today

```
ClosePaw browser_script
  └─ ShizukuChromeDevtoolsBridge.httpGet
      ├─ APP_PROCESS LocalSocket          → SELinux denied (untrusted_app cannot connectto chrome_devtools_remote)
      ├─ USER_SERVICE Shizuku LocalSocket → SELinux denied (shell cannot connectto untrusted_app socket on this OEM)
      └─ WIRELESS_ADB_SELF_PAIR (NEW)
          ├─ AdbWirelessManager (Shizuku UserService binder, shell uid)
          │   ├─ IAdbManager.allowWirelessDebugging(true, currentBssid)        → wireless adb on
          │   ├─ IAdbManager.getAdbWirelessPort()                              → 41089
          │   └─ IAdbManager.enablePairingByQrCode("ClosePaw", randomPsk)      → pair port via /proc/net/tcp diff
          ├─ AdbPairingClient (in-app, app uid)
          │   ├─ TLS 1.3 to localhost:pairPort with throwaway cert + trust-all
          │   ├─ TLS exporter "adb-label\0" length=64, mixed into PSK
          │   ├─ SPAKE2-25519 (Alice; "adb pair client\0", "adb pair server\0")
          │   ├─ HKDF-SHA256 info="adb pairing_auth aes-128-gcm key" → 16-byte AES key
          │   └─ AES-128-GCM PeerInfo (8192 bytes; type=ADB_RSA_PUB_KEY + base64 of our 524-byte AndroidPubkey + zero-pad)
          │   → adbd: "Pairing succeeded key=QAAAA... ClosePaw@P0110"
          │   → /data/misc/adb/adb_keys gains our pubkey
          └─ AdbWireProtocolClient (in-app, app uid)
              ├─ Plain TCP to localhost:41089
              ├─ Plain A_CNXN("host::features=…") → adbd → A_STLS
              ├─ Plain A_STLS reply
              ├─ mTLS 1.3 (Conscrypt) with persisted RSA + self-signed X.509
              │   → adbd: "Handshake succeeded" / "adbd_wifi_secure_connect: connected"
              ├─ Read adbd's A_CNXN bannerLen=378 (daemon speaks first post-mTLS — critical)
              ├─ 200ms settle, then A_OPEN("localabstract:chrome_devtools_remote") → A_OKAY remoteId=N
              ├─ Ready OKAY (engages adbd's local_socket FDE_READ on chrome side)
              ├─ A_WRTE("GET /json/version HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
              ├─ readUntilHttpComplete: A_WRTE chunks → ack each → return on Content-Length
              └─ A_CLSE (best-effort cleanup)
```

`browser_script` returns `document.title is "Example Domain"` end-to-end without any host adb forward / reverse.

---

## Bug audit — seven production-only failures the spike's external-tool path masked

For posterity / future debugging:

1. **BouncyCastle stripped-stub provider conflict** — Android ships an empty "BC" provider name; `Security.addProvider(BouncyCastleProvider())` silently no-ops because the name collides. Fixed in `WirelessAdbProviders.installBouncyCastle()` via `removeProvider` + `insertProviderAt(realBC, 1)`.
2. **Conscrypt platform-Conscrypt unreachable on emulator userdebug** — `HiddenApiBypass.addHiddenApiExemptions("Lorg/conscrypt/")` fails on certain class loaders. Fixed by bundling `org.conscrypt:conscrypt-android:2.5.2` (~3MB AAR).
3. **Shizuku binder timing race** — after `adb install -r` ClosePaw's process loses the cached binder until ShizukuProvider's broadcast re-fires (1–2s window). `Shizuku.pingBinder()` returns false immediately, breaking preflight. Fixed in `ShizukuStatusAdapter.isAvailable()` with a 2s retry loop.
4. **`AndroidPubkey.encodeWithName` trailing `\n`** — `AdbDebuggingManager` already appends its own newline when persisting to `adb_keys`; the trailing `\n` in our PeerInfo created blank lines that adbd later iterated and logged as `Invalid base64 key `. Fixed in `AndroidPubkey.encodeWithName` (drop the `\n`).
5. **The spike's "no A_STLS on TLS port" claim was wrong** — the wireless TLS adb port speaks plain adb wire-protocol first: client A_CNXN → server A_STLS → client A_STLS → both sides upgrade to TLS. Fixed in `AdbTlsClient.connectWithStls`. AOSP `docs/dev/adb_wifi.md` section "Trusted device" is the canonical source.
6. **Post-mTLS daemon speaks first** — `adbd_wifi_secure_connect` calls `send_connect()` writing A_CNXN to us through TLS. Pre-emptively writing our own A_CNXN here triggers adbd's `handle_packet` for duplicate CNXN → `handle_offline()` fires the disconnect callback and emits a stray A_STLS over the encrypted channel (which is what we kept reading as "unexpected reply to OPEN: cmd=0x534c5453"). Fixed in `AdbWireProtocolClient.handshakeAndOpen` (read peer A_CNXN, do not write our own).
7. **Chrome's `/json/version` ignores `Connection: close`** — chrome's HTTP server leaves the socket idle after responding, so adbd never sees EOF, never sends A_CLSE, and `readUntilClose` waits forever. Curl on the host-adb-forward path works because curl reads up to Content-Length and tears its side down (causing local EOF that adbd then forwards). Fixed by switching the synchronous DevTools HTTP path to `readUntilHttpComplete` which returns as soon as Content-Length body is read. The streaming `AdbStream` (used for the WebSocket relay) keeps A_CLSE detection.

---

## Outstanding work

### 1. Pair-once optimization — currently re-pairs every cold session

**Symptom**: Every fresh ClosePaw process re-runs the full TLS-PSK SPAKE2 pair handshake even though our RSA keypair is persisted under `filesDir/adb_self_pair/` and adbd already has the pubkey in `/data/misc/adb/adb_keys`.

**Cost**: ~1s extra latency per cold session + one extra entry in `/data/misc/adb/adb_keys` per run (adbd treats re-pair as idempotent — never reduces over time, will eventually grow large but no functional impact).

**Root cause**: `AdbWirelessManager.isPubkeyAuthorized(fingerprint)` always returns false. The fingerprint we pass is `SHA-256(keypair.public.encoded)` (X.509 SubjectPublicKeyInfo DER) but adbd hashes a different byte sequence. Even after the cleanest implementation candidate (`ChromeDevtoolsUserService.isPubkeyInAdbKeys` reads `adb_keys`, base64-decodes each line, SHA-256s, compares) the formats don't line up — that path was abandoned during this round.

**Fix sketch (~30min)**:
- Change `AdbCryptoKeyStore.fingerprint()` to return `SHA-256(AndroidPubkey.encode(pubkey))` (524-byte adbd-format input). Or better: have it return the raw base64-encoded AndroidPubkey blob.
- Change `AdbWirelessManager.isPubkeyAuthorized` to take that base64 string and do a substring match against the `adb_keys` file content (read via shell-uid binder).
- Validate by running twice in succession; second run should log `wireless-adb pair skipped: pubkey already authorized` instead of `wireless-adb pairing complete`.

### 2. Emulator Phase 4 QA — emulator-5554 was bricked offline mid-debug

**Symptom**: `emulator-5554` shows `offline` in `adb devices` since I tried `adb shell setprop persist.adb.tls.trace 1; stop adbd; start adbd` to enable adbd verbose logging. `adb reconnect` doesn't recover.

**Fix**: Restart the AVD via Android Studio's AVD Manager (or `~/Library/Android/sdk/emulator/emulator @<avd_name> -no-snapshot`), then run the same flow we ran on nubia. The wireless self-pair code is generic AOSP and was previously seen pairing successfully on this emulator (`debug-output/run_20260502_201243/`) — only the post-mTLS bytes were broken at that point, and those are fixed now.

**Note**: emulator's Wi-Fi is the synthetic AndroidWifi network, BSSID `02:15:b2:00:01:00`. `IAdbManager.allowWirelessDebugging` requires Wi-Fi to be connected; if it isn't, our path returns `IOException("No Wi-Fi BSSID available; cannot enable wireless ADB")`. The spike's `emu_step3_connect_wifi.txt` shows the standard `cmd wifi connect-network AndroidWifi open` setup if needed.

### 3. WebSocket relay path — wired but not e2e-validated on real device

**State**: `WirelessAdbSelfPairTransport.ensureWebSocketRelayPort()` is implemented and `ShizukuChromeDevtoolsBridge.resolveWebSocketHost()` dispatches to it for the WIRELESS_ADB_SELF_PAIR case. The relay logic mirrors `ChromeDevtoolsUserService.startTcpRelay` (in-process `ServerSocket` accept loop, one `AdbStream` per accepted TCP connection, bidirectional pumps).

**Risk**: Real-device QA so far only exercised the synchronous `exchange` path (HTTP `/json/version` + `/json/list`). The WebSocket path runs after `bridgeHandle.bridge.resolveWebSocketHost()` returns the relay port and the agent's CDP WebSocket client connects through it.

**Test**: The agent runs that produced "Example Domain" did go through Page.navigate / Runtime.evaluate over CDP, which goes through the WebSocket — so the relay does work in practice. But there's no explicit "1 connect, N round trips, no leaks" stress test. A 5-minute run with multiple sequential `browser_script` calls on the same `BrowserSessionManager` would catch any leak.

### 4. Adb_keys cleanup utility (low priority)

Since the pair-once optimization currently re-pairs every cold session, `/data/misc/adb/adb_keys` accumulates one entry per run. There's no built-in cleanup. Eventually adbd's iteration over the file becomes O(N) per handshake. Not urgent but worth adding a `disablePairing` + `unpairDevice` helper that prunes our old entries.

### 5. Other devices not yet tested

Verified only on nubia P0110 (Android 16, OEM build with stripped Wireless Debugging UI). The spike research argued the path should work on any Android 11+ device that grants `MANAGE_DEBUGGING` to `com.android.shell` — that's stock AOSP plus the OEMs we care about (Samsung, Xiaomi, OPPO, OnePlus, Vivo, Honor). No empirical validation across that set yet.

### 6. Soft TODOs noted in the diff

- `AdbWireProtocolClient.kt:69` — `runCatching { Message.write(out, A_CLSE, …) }` is best-effort cleanup. Consider whether to surface the close failure in tests.
- `WirelessAdbSelfPairTransport.kt:73` — the retry-once on cached-port-rotation is defensive; if observed in production, log the rate.
- `AdbPairingClient.kt` — uses LGPL spake2-android via JitPack. License-clean for a proprietary app (dynamic linking) but if license policy ever tightens, port BoringSSL `spake25519.c` to Apache-2.0 (~600 lines of crypto).

### 7. Delete the host-mediated relay path entirely

`HOST_MEDIATED_RELAY` (`scripts/setup-cdp-relay.sh` + `HostMediatedCdpRelayTransport`) was the only working transport on locked OEMs *before* Phase 4. It's no longer a production path: it requires the user's PC to keep ADB attached, which contradicts the entire point of an in-device autonomous agent. With Phase 4 PASS on nubia, the cascade should be:

```
APP_PROCESS → USER_SERVICE → WIRELESS_ADB_SELF_PAIR → (fail loudly)
```

Files to delete:
- `app/src/main/kotlin/ai/closepaw/browser/cdp/shizuku/HostMediatedCdpRelayTransport.kt`
- `app/src/test/kotlin/ai/closepaw/browser/cdp/shizuku/HostMediatedCdpRelayTransportTest.kt`
- `scripts/setup-cdp-relay.sh` (and any docs that reference it)
- `DevtoolsSetupError.HostMediatedRelayUnreachable` and `HostMediatedRelayUnreachableException`
- `hostMediatedRelayTransport` constructor parameter on `ShizukuChromeDevtoolsBridge`, all related cascade legs in `httpGet`, all related branches in `resolveWebSocketHost` / preflight / final-error block
- `BrowserSessionManager.createDefaultBridge()` reference to `HostMediatedCdpRelayTransport()`
- The `host_mediated_relay_unreachable` user-facing error code (replace surfacing with `WirelessAdbSelfPairUnavailable` or a new "no transport viable" code)
- `real_device_qa.md` Phase 3 section can become a historical note ("documented for archaeology, removed in commit X")

Cleanup will also let `ShizukuChromeDevtoolsBridge` lose two of its three optional transport slots — it can collapse to `(appProcess, userService, wirelessSelfPair)` triple, and the cascade-end-block error logic gets ~30 lines simpler.

### 8. Full review + simplification of the entire `browser_script` implementation

In the same spirit as #7 — this implementation grew through several rounds of "spike → assumption → discover assumption was wrong → patch around it". The cumulative result is correct but carries scars from intermediate wrong-turns that should be cleaned up before this code stops being shippable-but-fresh and starts being shippable-but-encrusted.

Specific things to re-examine:
- **`ChromeRemoteDebugPortTransport` (Phase 2)** — empirically blocked (per `real_device_qa.md`); kept "wired but null in production." Same removal argument as #7 — delete the class, the AIDL methods (`ensureChromeRemoteDebugPort`), the `chrome-command-line` writing, the AIDL passthrough, the `ChromeRemoteDebuggingFlagNotEnabled` error variant.
- **`ShizukuChromeDevtoolsBridge`** — multi-step cascade with try/catch/runCatching layering, three optional transport slots, end-block error-priority logic that touches `appError` / `userError` / `wirelessError` / `relayUnreachable` separately. Once #7 + Phase 2 removal lands, the cascade is `app → user → wireless` and the error logic collapses to "first thing that produced a `DevtoolsSetupError` wins, otherwise WirelessAdbSelfPairUnavailable."
- **`DevtoolsSetupError`** — 9 sealed subclasses today; after deletions it should be 4-5. Audit for dead error codes the agent / UI / capability gate no longer need to surface.
- **Diagnostic logging** added during the bug-cascade debug — `WirelessAdbSelfPairTransport.exchange`'s outer try/catch with `Log.e` re-throw was added because the bridge's `runCatching` swallowed exceptions; re-evaluate whether this is still needed once the cascade is simpler.
- **`WirelessAdbSelfPairTransport` `isReachable()`** — currently does a `currentBssid()` shell-out via Shizuku binder *during preflight*, which is non-trivially expensive. After #7, preflight no longer needs to compare three transports' reachability — collapse to a single check.
- **`AdbWirelessManager.isPubkeyAuthorized`** — the always-returns-false function. Either fix it (#1) or delete it.
- **Bridge's 200ms `POST_CNXN_SETTLE_MS` and 500ms `POST_PAIR_SETTLE_MS`** — defensive sleeps added during debug. Re-evaluate whether they're still needed; if so, document why; if not, delete.
- **`UserServiceTransport.binder` made public** in commit `ec4d2e92` to expose the Shizuku binder to `AdbWirelessManager`. Either keep with a real internal contract (e.g., named accessor with comment) or refactor `AdbWirelessManager` to take the same `UserServiceProvider` the bridge uses.
- **`ShizukuChromeDevtoolsBridge.DEFAULT_TIMEOUT_MS`** bumped 4_000 → 30_000 because chrome's `/json/version` doesn't EOF. With `readUntilHttpComplete` this isn't needed for the wireless path; restore 4s as the per-step default and let the script-level timeout cap the outer budget.
- **Tests** — `AdbWireProtocolClientTest`'s in-process `FakeAdbd` mirrors the post-mTLS contract perfectly. Add an integration-shape test for `readUntilHttpComplete` covering: Content-Length response, no-Content-Length response, A_CLSE before headers complete, A_CLSE after partial body.
- **Comments + docs** — several files have multi-paragraph comments documenting "why this is the way it is" referencing AOSP source line numbers. Keep the load-bearing ones, prune the ones that just narrate. Per project CLAUDE.md: "Default to writing no comments. Only add one when the WHY is non-obvious."

Suggested cadence: do #7 first (delete host-mediated), then Phase 2 removal, then a single `refactor(browser): simplify CDP cascade` commit that collapses the bridge's error block + slot count + redundant transports + dead errors. Then a second pass that re-evaluates the diagnostic noise. Should be ~200-300 lines of NET deletion.

---

## Pre-flight requirements for testing

For both the (legacy, scheduled-for-deletion) host-mediated relay path and the new wireless self-pair path:

1. ClosePaw debug APK installed (`./scripts/setup.sh` handles this — also auto-starts Shizuku and verifies API_V23 grant since commit `530d63ae`).
2. Shizuku app installed AND `shizuku_server` process running (`adb shell pidof shizuku_server` must return a pid).
3. ClosePaw granted `moe.shizuku.manager.permission.API_V23` via the Shizuku app's UI (it is a signature permission; `pm grant` doesn't work).
4. Wi-Fi connected (any network — BSSID just needs to be queryable via `dumpsys wifi`).
5. Chrome stable (`com.android.chrome`) installed.

For wireless self-pair specifically, no further setup is required — the path discovers everything else dynamically. The host-mediated relay (still wired today, slated for removal in outstanding item #7) requires running `scripts/setup-cdp-relay.sh` on the host.

---

## How to invoke / debug

```bash
# One-time:
./scripts/setup.sh                 # builds APK, installs, starts Shizuku, grants what it can.

# Test cold pair:
adb -s <serial> shell run-as ai.closepaw rm -rf files/adb_self_pair    # force fresh pair
adb -s <serial> forward --remove-all && adb -s <serial> reverse --remove-all  # prove no host plumbing
adb -s <serial> logcat -c
ANDROID_SERIAL=<serial> DEBUG_AUTO_APPROVE=true ./scripts/debug-run.sh \
    "Use browser_script to fetch document.title from open https://example.com"

# Inspect logs:
LATEST=$(ls -1td debug-output/run_* | head -1)
grep -E 'WirelessAdbSelfPair|AdbWireProto|complete_task.*answer' "$LATEST"/{agent,logcat_full}.log
```

Success markers in logcat:

```
WirelessAdbSelfPair: wireless-adb bootstrap: starting
WirelessAdbSelfPair: wireless-adb bootstrap: (a) enableWirelessDebugging ok
WirelessAdbSelfPair: wireless-adb bootstrap: (b) tls port = <port>
WirelessAdbSelfPair: wireless-adb pairing complete (fp=<hex>…)
WirelessAdbSelfPair: wireless-adb bootstrap: complete
AdbTlsClient:        TLS session: protocol=TLSv1.3 cipher=TLS_AES_128_GCM_SHA256
AdbWireProto:        post-TLS got A_CNXN bannerLen=378
AdbWireProto:        A_OPEN("chrome_devtools_remote") -> OKAY remoteId=<id>
TurnExecutionPhase:  Executing tool: complete_task with args: {"status":"success","answer":"document.title is \"Example Domain\"."}
```

adbd-side (visible in `adb -s <serial> shell logcat`):

```
adbd: New TLS connection [fd=N]
adbd: host-N: Received STLS packet. Stopping read thread.
adbd: [server]: Initializing adbwifi TlsConnection
adbd: adbd_auth: loading keys from /data/misc/adb/adb_keys
adbd: fingerprint=[<hex>]   ← our cert's SHA-256
adbd: [server]: Handshake succeeded.
adbd: adbd_wifi_secure_connect: connected host-N
AdbDebuggingManager: Pairing succeeded key=QAAAA... ClosePaw@<MODEL>
```

Failure markers (if any future regression appears):

| Logcat line | Diagnosis |
|---|---|
| `host_mediated_relay_unreachable` (and not WIRELESS errors) | Shizuku binder isn't reachable. Check `pidof shizuku_server` and ClosePaw's API_V23 grant. |
| `wireless_adb_self_pair_unavailable` | The transport itself failed. Look just above for the actual `WirelessAdbSelfPair: wireless-adb transport failed: …` stack. |
| `AdbDebuggingManager: Invalid base64 key ` | Stale entries from older runs in adb_keys with the trailing-\n bug — already fixed but old entries remain. Harmless. |
| `adbd: SSL_read failed [invalid library (0)]` | We sent post-handshake bytes that aren't valid TLS records. Most likely the SSLSocket isn't actually wrapping the underlying socket — check the `AdbTlsClient.connectWithStls` SSLContext setup hasn't regressed. |
| `unexpected reply to OPEN: cmd=0x534c5453` (A_STLS) | Pre-emptive A_CNXN regression. Make sure `handshakeAndOpen` is reading the daemon's A_CNXN, not writing its own. |

---

## Files for the next worker to read first

In order:

1. `projects/active/browser/cn/diag_20260502_wireless_adb_spike/SPIKE_RESULT.md` — original spike, why this approach.
2. `projects/active/browser/real_device_qa.md` — Phase 4 PASS section (`bdf622ca`), end-to-end evidence.
3. `app/src/main/kotlin/ai/closepaw/browser/cdp/wireless/WirelessAdbSelfPairTransport.kt` — orchestrator, top of the call graph.
4. `app/src/main/kotlin/ai/closepaw/browser/cdp/wireless/AdbTlsClient.kt` — comments document the daemon-speaks-first contract.
5. `app/src/main/kotlin/ai/closepaw/browser/cdp/wireless/AdbWireProtocolClient.kt` — comments document why we don't pre-emptively send A_CNXN and why we use Content-Length (not A_CLSE) for HTTP.
6. `app/src/main/kotlin/ai/closepaw/browser/cdp/shizuku/ShizukuChromeDevtoolsBridge.kt` — cascade insertion point, error mapping.
7. AOSP refs:
   - `https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/dev/adb_wifi.md`
   - `https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/daemon/adb_wifi.cpp`
   - `https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/sockets.cpp`
   - `https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/pairing_connection/pairing_connection.cpp`
   - `https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/pairing_auth/pairing_auth.cpp`
   - `https://github.com/MuntashirAkon/libadb-android` (the Apache-2.0 reference implementation we ported pieces from)
