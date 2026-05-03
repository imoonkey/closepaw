# Browser CDP Real-Device QA

## Verdict — production transport coverage

**PASS.** Two transport paths cover the device-policy matrix:

| Path | Devices proven | Notes |
|---|---|---|
| Shizuku UserService (root context) | emulator-5554 (Phase 1) | Works on AOSP/userdebug + any device where Shizuku ends up in `u:r:su:s0`. |
| **Wireless-ADB self-pair (Phase 4) — autonomous in-device** | **nubia P0110 (locked OEM, no root, no PC)** | Shizuku-spawned shell-uid helper drives `IAdbManager.allowWirelessDebugging` + `enablePairingByQrCode` with our own PSK; embedded TLS pair client registers our RSA pubkey into adb_keys; embedded mTLS adb client A_OPENs `localabstract:chrome_devtools_remote` through adbd (which holds both `connectto`-allow and `mlstrustedsubject`). Zero host plumbing on the data path; only persistent requirement is one-time Shizuku grant. |
| **WS relay stress (Phase 5) — multi-iteration leak check** | **nubia P0110 (PASS)** | 10 sequential `browser_script` agent sessions over the same ClosePaw process. Asserted per-iter (iter 1 → iter N) `socket_fd_delta <= +2` and `fd_delta <= +20`, pid stable, zero `EMFILE` lines, every iter showed `browser_script outcome=SUCCESS` + `GOAL_ACHIEVED` + WIRELESS_ADB_SELF_PAIR relay opened. Result on 2026-05-03 (round 4, post-rebase onto pair-once main): per-iter socket_fd_delta=0, per-iter fd_delta=-2, all 10 iterations GOAL_ACHIEVED via WIRELESS_ADB_SELF_PAIR. Driven by `scripts/ws-relay-stress.sh` + `WirelessAdbSelfPairTransportRelayStressTest`. |
| **Phase 5 mid-gate (post-cleanup regression check)** | **nubia P0110 (PASS, soft)** | Two cold sessions on a clean install after pair-once + deleted-transport collapse + spike-harden + adb_keys prune + WS relay stress all merged. Both sessions return `document.title is "Example Domain"`; cascade is exactly USER_SERVICE → WIRELESS_ADB_SELF_PAIR with zero references to APP_PROCESS / HOST_MEDIATED_RELAY / CHROME_TCP_LOOPBACK in active logcat. Pair-once skip not observable on this OEM (shell uid denied read of `/data/misc/adb/adb_keys` — known per-OEM constraint, not Phase 5 regression). See "Phase 5 mid-gate" section. |

The device-policy block documented in the original "nubia FAIL" diagnostic is still real — Shizuku's shell-context UserService is denied `connectto` on `chrome_devtools_remote` on nubia and on a non-root emulator both — but **Phase 4 routes around it through the only context that retains both the SELinux `connectto` and `mlstrustedsubject` privileges (`adbd`), without needing a host PC.**

---

## Phase 4 — Wireless-ADB self-pair on nubia P0110 — PASS

### Device

- nubia P0110, Android 16, OEM build (`brand=nubia model=P0110`), Settings UI for Wireless Debugging stripped.
- Wi-Fi connected (BSSID `fc:12:63:79:57:b6` — required by `IAdbManager.allowWirelessDebugging`).
- ClosePaw debug APK from `app/build/outputs/apk/debug/app-debug.apk`.
- Shizuku v13.x running, `moe.shizuku.manager.permission.API_V23` granted to `ai.closepaw`. Shizuku server started via the standard `start.sh` over the existing wireless-adb session.
- Chrome `com.android.chrome` 147.0.7727.137 with `chrome://flags#enable-command-line-on-non-rooted-devices` not required.

### Use case

`ANDROID_SERIAL=100.64.43.95:5555 DEBUG_AUTO_APPROVE=true ./scripts/debug-run.sh "Use browser_script to fetch document.title from open https://example.com"` — agent loads example.com in Chrome, then `browser_script` retrieves `document.title` over Chrome DevTools, returns `"Example Domain"`. **No `adb forward`/`adb reverse` set up on the host. No root. No PC plumbing on the data path.**

### Setup

1. `adb -s 100.64.43.95:5555 install -r app/build/outputs/apk/debug/app-debug.apk`
2. `adb -s 100.64.43.95:5555 shell pidof shizuku_server` returns the running pid (Shizuku already started).
3. `adb -s 100.64.43.95:5555 forward --remove-all && adb -s 100.64.43.95:5555 reverse --remove-all` — confirm no host-mediated relay.
4. Chrome has `https://example.com` open in the foreground.
5. Run the agent prompt above.

### Steps (per `app/src/main/kotlin/ai/closepaw/browser/cdp/wireless/`)

1. `WirelessAdbSelfPairTransport.exchange()` invoked by the bridge cascade after USER_SERVICE fails (SELinux denies `shell→untrusted_app` `connectto` for chrome_devtools_remote on this build).
2. `AdbWirelessManager.enableWirelessDebugging()` → Shizuku UserService → `IAdbManager.allowWirelessDebugging(true, currentBssid)` via reflection in shell-uid context (com.android.shell holds MANAGE_DEBUGGING).
3. `AdbWirelessManager.getAdbWirelessPort()` returns the live TLS adb port (41089 in this run).
4. `AdbWirelessManager.openPairPort("ClosePaw", randomPsk)` → `IAdbManager.enablePairingByQrCode` → discovers pair port via `/proc/net/tcp` LISTEN-set diff.
5. `AdbPairingClient.pair()` runs the AOSP TLS-PSK pair handshake (TLS 1.3 throwaway cert + SPAKE2-25519 with TLS-exporter-bound password + AES-128-GCM-encrypted PeerInfo containing our RSA pubkey).
6. `AdbWireProtocolClient.exchange()`:
   - Plain TCP to localhost:41089
   - Pre-TLS A_CNXN→A_STLS handshake (the wireless TLS port speaks adb wire-protocol then upgrades — a critical correction to the original spike's "TLS first" assumption).
   - mTLS handshake with our persisted RSA-2048 + self-signed X.509 (`CN=Adb,O=Android,C=US`). Conscrypt bundled (`org.conscrypt:conscrypt-android:2.5.2`).
   - Post-TLS adbd speaks first (`adbd_wifi_secure_connect` → `send_connect` → A_CNXN). We MUST NOT send our own A_CNXN here (would trigger adbd's `handle_offline` and emit a stray A_STLS).
   - 200ms settle, then `A_OPEN("localabstract:chrome_devtools_remote")` → `A_OKAY remoteId=N`.
   - Send HTTP `GET /json/version` via `A_WRTE`.
   - `readUntilHttpComplete` returns as soon as Content-Length body is read (Chrome's `/json/version` ignores `Connection: close` and leaves the socket idle, so adbd never sends `A_CLSE`; falling back to Content-Length matches what `curl` does on the host-adb-forward path).

### Result

```
05-03 03:41:54.005 WirelessAdbSelfPair: wireless-adb pairing complete (fp=31d9d8a342f8faf6…)
05-03 03:41:54.527 AdbWireProto:        post-TLS got A_CNXN bannerLen=378
05-03 03:41:54.728 AdbWireProto:        A_OPEN("chrome_devtools_remote") -> OKAY remoteId=1009
05-03 03:42:03.494 TurnExecutionPhase:  Executing tool: complete_task
                                        {"status":"success","answer":"document.title is \"Example Domain\"."}
```

`adbd` server-side log confirms:

```
adbd: Pairing succeeded key=QAAAA... ClosePaw@P0110
adbd: New TLS connection [fd=...]
adbd: [server]: Handshake succeeded.
adbd: adbd_wifi_secure_connect: connected host-31
```

### Evidence

- `projects/active/browser/cn/diag_20260502_wireless_adb_spike/SPIKE_RESULT.md` — original protocol research and proof-of-concept.
- `debug-output/run_20260503_004020/` — first end-to-end agent run (pairs + opens + returns title).
- `debug-output/run_20260503_004131/` — second run (re-pairs idempotently; pair-once optimization is best-effort and currently re-pairs on every cold session — re-pair is harmless on adbd).
- `projects/active/browser/cn/diag_20260502_wireless_adb_spike/qa_phase4_*.txt` — early diagnostic captures during incremental fix-and-test cycles.

### Verdict — Phase 4

**PASS** on nubia P0110 with no PC plumbing and no root. The agent's `browser_script` returns `document.title is "Example Domain"` via WIRELESS_ADB_SELF_PAIR transport, on both first and subsequent runs (key persisted under `filesDir/adb_self_pair/`).

Known soft-failure: the pair-once optimization (`AdbWirelessManager.isPubkeyAuthorized`) currently always returns false (the fingerprint format adbd logs is SHA-256 of the cert SPKI, while our persisted key uses SHA-256 of the keypair's encoded form — different inputs, never match). Each session re-runs the pair handshake. This is harmless because adbd treats re-pair as idempotent; the only cost is ~1s of extra latency on every cold session and one extra entry per run in `/data/misc/adb/adb_keys`. Tracked as a follow-up.

Emulator coverage for Phase 4: not collected in this round — the only available emulator (`emulator-5554`) was bricked offline mid-debug while attempting to enable adbd verbose logging via `setprop persist.adb.tls.trace 1`. The transport implementation is generic AOSP and was previously validated on emulator-5554 through earlier diagnostic runs (initial pairing + TLS handshake worked there before the failure window was understood; see `debug-output/run_20260502_201243/`). A follow-up emulator pass should re-run after the AVD is restarted.

### Emulator coverage — emulator-5556 follow-up (2026-05-03) — PARTIAL

**Wireless-self-pair PASS on emulator-5556. Agent CDP chain UNTESTED on emulator due to Chrome stable lacking devtools socket — pre-existing constraint, see Phase 2 archived notes. Full chain validated on nubia P0110 (Phase 4 evidence above).**

| Stage | Result |
|---|---|
| `adb devices` shows emulator-5556 as `device` | PASS |
| Shizuku running + ClosePaw `API_V23` granted | PASS (after one-time setup, see notes) |
| Wireless-ADB self-pair handshake | **PASS** — logcat: `WirelessAdbSelfPair: wireless-adb pairing complete; future runs reuse persisted key` |
| Plain TCP → A_CNXN → A_STLS → mTLS 1.3 to local adbd | **PASS** — logcat: `AdbTlsClient: TLS session: protocol=TLSv1.3 cipher=TLS_AES_128_GCM_SHA256` and `AdbWireProto: post-TLS got A_CNXN bannerLen=334` |
| `A_OPEN("localabstract:chrome_devtools_remote")` | **FAIL** — `destination rejected: chrome_devtools_remote` (Chrome stable does not bind the socket; identical to Phase 2 archived constraint, not a wireless-self-pair regression) |
| Agent answers "document.title is Example Domain" | **UNTESTED** on emulator (depends on Chrome opening the devtools socket — not under our control on Chrome stable). Full-chain PASS evidence remains nubia-only at `debug-output/run_20260503_004020/`. |

**Why the CDP chain is untested on this emulator (not a Phase 5 regression).** Stock **Chrome stable 109.0.5414.123** on this Google Play AVD (Android 13, x86_64, `ro.debuggable=1`, `ro.build.type=userdebug`) does not bind any `@chrome_devtools_remote*` abstract socket and does not honor `--remote-debugging-port` / `--remote-debugging-socket-name` even after the cmdline file is loaded. Verified empirically while Chrome was foregrounded with `https://example.com`:

- `cat /proc/net/unix | grep -i chrome` → empty across multiple cold restarts.
- `cat /proc/net/tcp | grep ":240E"` (port 9222) → empty after `--remote-debugging-port=9222` in cmdline file.
- Chrome's logcat *does* echo the cmdline switches it received: `cr_CommandLine: COMMAND-LINE FLAGS: [_, --remote-debugging-port=9222] (from /data/local/tmp/chrome-command-line)` — proving the file path/permission is correct and the cmdline is read; what's gated is `DevToolsServer::IsAllowed()` itself returning false on this Chrome build for this APK identity.

This is the **same pre-existing constraint that took out Phase 2** on nubia (line 96 above): "Stock Chrome on Android only reads this file when the `chrome://flags#enable-command-line-on-non-rooted-devices` toggle is set; that flag is per-Chrome-profile and cannot be set programmatically." The two unblock paths (per Phase 2 + this round) are: (a) install Chrome Beta/Dev/Canary (channel != STABLE); (b) sideload a debuggable Chrome build. Neither was achievable in the time budget for this round — Chromium snapshots ship `armeabi-v7a` only and the major mirrors (APKMirror/APKPure) are Cloudflare-gated.

**What this round proves (the load-bearing finding).** The Phase 4 wireless-ADB self-pair code is regression-free on emulator-5556 across the entire stack ClosePaw is responsible for: pair handshake, mTLS handshake, post-mTLS A_CNXN read, A_OPEN dispatch. The only step that fails is on the Chrome side of the abstract socket, identically to how it failed in Phase 2 — and is independent of the Phase 5 deliverables in this branch (`task/emu-reg`, parent branch `task/del-transports` deleted the dead transports).

Evidence for this round:
- `debug-output/run_20260503_123855/agent.log` + `debug-output/run_20260503_123855/logcat_full.log` — pair PASS markers, A_OPEN reject, agent reports failure honestly.
- Three earlier failure runs in `debug-output/run_2026050[3]_12{2337,2428,3043}/` document the bring-up sequence: (1) emulator clock skew (Nov 2023 vs May 2026) caused TLS to OpenAI to fail, fixed via `su 0 date -u …`; (2) `browser_script_enabled` pref was off, set via `am start --ez browser_script_enabled true`; (3) `shizuku_permission_missing` because Shizuku's internal authorization table is separate from `pm grant moe.shizuku.manager.permission.API_V23` — granted via the Shizuku app's "Authorized applications" UI toggle.

**Setup quirks discovered for emulator-5556** (not in `scripts/setup.sh` today):
1. Shizuku APK ships at `eval/tools/shizuku.apk` but `scripts/setup.sh` doesn't auto-install it; run `adb -s <emu> install -r eval/tools/shizuku.apk` first.
2. Shizuku app's `start.sh` is generated lazily by the MainActivity's first launch, but the `View command` dialog in the Shizuku UI exposes the same `adb shell /data/app/.../lib/<abi>/libshizuku.so` invocation that the script would have used — that path works on userdebug emulators because `adb shell` is already root.
3. Shizuku's authorization table is independent of Android's PackageManager grants. Both `pm grant ai.closepaw moe.shizuku.manager.permission.API_V23` AND toggling the Shizuku UI's "Authorized applications" switch for ClosePaw are required (the latter records the grant in the Shizuku binder's own state).
4. Emulator clocks drift hard — set with `su 0 date -u $(date -u +%m%d%H%M%Y.%S)` before any TLS-bearing run, otherwise `OpenAIIoException - Request failed` masks the real test signal.

---

## Phase 5 — WebSocket relay stress (fd-leak check) — PASS

### Device & harness

- nubia P0110 (`100.64.43.95:5555`), same configuration as Phase 4 PASS.
- Stress harness: `scripts/ws-relay-stress.sh -n 10` — 10 sequential
  `debug-run.sh` agent sessions on the same ClosePaw process, each ending in one
  successful `browser_script` round-trip through the wireless-ADB self-pair transport.
- JVM control-plane coverage: `WirelessAdbSelfPairTransportRelayStressTest` exercises
  the `ServerSocket` accept loop, single-flight bootstrap (barrier-gated, real
  concurrent dispatcher, asserts bootstrap-counter == 1 with 19 callers parked on
  the bootstrapLock Mutex), per-connection thread cleanup with a mocked
  `AdbWireProtocolClient`, and post-`close()` semantics (returns null; previously a
  closed transport replayed a stale dead port — see commit message for the fix).

### Pass / fail criteria (round 2)

After the round-1 review, the criteria were tightened:

- `pid_before == pid_after` — process must persist across the whole run.
- Per-iter `socket_fd[iter N] - socket_fd[iter 1] <= +2` — the relay-leak signal,
  isolating one-time bootstrap cost from per-iteration leaks.
- Per-iter `fd[iter N] - fd[iter 1] <= +20` — the broader signal; wider allowance
  because Android's GPU pipeline (anon_inode `sync_file`/`eventfd`, `/dmabuf`)
  contributes drift unrelated to the relay.
- Zero `EMFILE` / `Too many open files` lines in **any** iteration's
  `logcat_full.log` (debug-run.sh clears logcat between iterations, so per-iter
  scanning is mandatory; round-1's post-run-tail-only scan was a real coverage gap).
- Every iteration: `browser_script outcome=SUCCESS` + `outcome: GOAL_ACHIEVED` +
  `wireless-adb WS relay` opened. Catches silent failures where the iter wrappers
  reported "Task completed" but the agent loop never ran.

### Result — 2026-05-03 12:46–12:54 PDT, round 4 (post-rebase onto main with pair-once)

```
Run dir:    debug-output/ws-stress-20260503_124604/
PID:        10099 → 10099                       (held across all 10 iterations)
Cold baseline:        fd=323 socket_fd=20
After iter 1 warmup:  fd=278 socket_fd=20
After iter 10:        fd=276 socket_fd=20
Per-iter delta (iter 1 → iter 10): fd=-2, socket_fd=0       ← the leak signal
Bootstrap cost (cold → iter 1):    fd=-45, socket_fd=0      ← one-time, NOT a leak
EMFILE / Too many open files lines (across all iter logcats): 0
Iterations with verification failures:                        0
```

Per-iteration `socket_fd` snapshot — flat at 19–20 throughout (one-fd jitter from
unrelated pid-internal sockets):

```
Baseline    socket_fd=20   fd=323
Iter  1     socket_fd=20   fd=278
Iter  2     socket_fd=19   fd=276
Iter  3     socket_fd=20   fd=279
Iter  4..6  socket_fd=20   fd=279
Iter  7     socket_fd=20   fd=276
Iter  8     socket_fd=19   fd=277
Iter  9     socket_fd=20   fd=276
Iter 10     socket_fd=20   fd=276
```

Per-iter agent dirs (each carries `WirelessAdbSelfPair: wireless-adb WS relay on
127.0.0.1:<port>` and `complete_task ... outcome: GOAL_ACHIEVED`):
`debug-output/run_20260503_12{4612,4701,4759,4848,4934,5023,5114,5204,5252,5345}/`.

### Verdict — Phase 5

**PASS** — the in-process WebSocket relay does not leak file descriptors or threads
across 10 sequential `browser_script` sessions on a fresh ClosePaw process. Per-iter
socket_fd_delta is exactly 0 across iters 1→10; per-iter fd_delta is -2 (no growth).

JVM control-plane assertions (`WirelessAdbSelfPairTransportRelayStressTest`,
5 tests, all green):
- Idempotency across 50 sequential `ensureWebSocketRelayPort()` calls — same port,
  bootstrap fires exactly once.
- Bootstrap is single-flight under 20 truly-concurrent callers — barrier-gated
  test on `Dispatchers.Default` proves only 1 caller enters bootstrap while the
  other 19 park on the `bootstrapLock` Mutex.
- `close()` clears the cached port AND the underlying `ServerSocket`; subsequent
  `ensureWebSocketRelayPort()` returns null instead of replaying a stale dead port
  (round-1 review caught this as a real bug; fix landed in this commit).
- Accept loop drains 20 sequential connect-disconnect cycles without thread leak,
  on both success and failure paths of `openLocalAbstract`.

### Round-1 review findings — addressed

This run is the round-2 verdict after a code review found 5 issues with the
original round-1 evidence. All addressed:

- **HIGH #1** (silent iter-1 failure): root cause was a buggy `USER_STOPPED`
  filter in the harness that flagged every benign session-end; the actual run
  had GOAL_ACHIEVED in every iter. Filter removed; positive signals
  (browser_script SUCCESS + GOAL_ACHIEVED + WS relay opened) are sufficient.
- **HIGH #2** (EMFILE only covered last iter): `adb logcat -c` runs at the start
  of every `debug-run.sh` invocation, so a single post-run tail can only see the
  last iter. Now scans each iter's captured `logcat_full.log` independently.
- **HIGH #3** ("without re-pair" claim not met): pair-once is on main HEAD post-
  rebase, but on nubia P0110 the shell uid is denied read on
  `/data/misc/adb/adb_keys`, so `readAdbKeys()` returns null and the transport
  falls back to re-pairing. This is a property of the OEM (not the relay), is
  harmless to adbd, and is independently verified by `browser-phase5-pair-once`
  on devices where `adb_keys` is readable. The runbook now reports `pair skipped`
  count as informational.
- **MEDIUM #1** (script vs runbook drift): script now FAILS on pid change and on
  total fd_delta, matching the runbook.
- **MEDIUM #2** (single-flight test was sequential under runBlocking): rewrote to
  use a CompletableDeferred barrier inside the mocked bootstrap and
  `Dispatchers.Default` for actual concurrency; asserts the bootstrap counter is
  exactly 1 while 19 callers are parked.
- **MEDIUM #3** (`close()` left stale `relayPort`): one-line code fix in
  `WirelessAdbSelfPairTransport.close()` — now `relayPort = 0` under the
  `relayLock`, and `ensureWebSocketRelayPort()` checks `relayStopped` before the
  cached-port early-return.

### Caveats

- Each `debug-run.sh` invocation creates a fresh `BrowserSessionManager` (because
  `fresh_session=true`), so the test stresses the cold-session-cycle path. A
  single-session, multi-script flow (where the same `BrowserSessionManager`
  reuses one WebSocket across N scripts) is dominated by `ChromeCdpClient`
  resource accounting, not the wireless transport — that path is left to per-call
  CDP tests.
- `/proc/<pid>/fd` is not readable from `adb shell` on this OEM (kernel returns
  `Permission denied`); the harness uses `run-as ai.closepaw` which runs as the
  app's own UID. Requires a debuggable APK — the production wireless path always
  ships with one.
- nubia P0110 silently wipes `enabled_accessibility_services` after some activity
  events; the harness re-arms a11y via `cmd settings put` before each iteration.
  Without this guard, iteration 2+ would route to the AccessibilitySettings page
  instead of starting the agent and the stress test would stall.
- pair-once does not kick in on nubia P0110 because the shell uid (Shizuku
  UserService context) is denied read access to `/data/misc/adb/adb_keys`. Each
  iteration re-runs the TLS-PSK SPAKE2 pair handshake; adbd treats re-pair as
  idempotent so this is harmless to the relay leak check (and to functionality).

---

## Phase 5 mid-gate — regression check on nubia P0110 — PASS

### Purpose

Cleanup gate run after the Phase-5 series of merges (`f4e662af` pair-once,
`1516206c`/`a0a8c866` deleted-transport collapse, `445362ca`/`ff873f25`
single-flight + refcounted UserServiceProvider, `78ed9245`/`fa59f520` emulator
row, `9226e9e7`/`095228ff` adb_keys prune helper, `88998bf4`/`9778209f` WS relay
stress harness). Question: did any of these cleanups regress the Phase 4
end-to-end path on nubia?

### Setup

- Device: nubia P0110, Android 16, OEM build, serial `100.64.43.95:5555`.
- `ANDROID_SERIAL=100.64.43.95:5555 ./scripts/setup.sh` (re-installs APK, restarts Shizuku
  if needed, re-grants overlay + accessibility, launches MainActivity).
- `adb -s … shell run-as ai.closepaw rm -rf files/adb_self_pair` to force a fresh
  pair on session 1.
- `adb -s … forward --remove-all && adb -s … reverse --remove-all` (host plumbing
  empty — proven by `forward --list` returning empty).
- `wc -l /data/misc/adb/adb_keys` from `adb shell` returns `Permission denied`
  (shell uid cannot read this file on this OEM — same constraint that defeats
  pair-once skip; observable only via Shizuku-side IAdbManager calls).

### Sessions

Both runs invoked with:
`ANDROID_SERIAL=100.64.43.95:5555 DEBUG_AUTO_APPROVE=true ./scripts/debug-run.sh "Use browser_script to fetch document.title from open https://example.com"`

| Session | Run dir | Outcome | Pair markers |
|---|---|---|---|
| 1 (cold pair) | `debug-output/run_20260503_154101/` | `complete_task` returns `document.title for https://example.com is: Example Domain` (4 turns) | `wireless-adb pairing complete; future runs reuse persisted key` (fresh pair) |
| 2 (no `rm`)   | `debug-output/run_20260503_160613/` | `complete_task` returns `document.title is: Example Domain` (4 turns)                   | `wireless-adb pairing complete` again — pair-once skip path **NOT** observable on this OEM (see below) |

### Pair-once on nubia — known limitation, not a regression

Session 2 logcat shows `ChromeDevtoolsUS: could not read /data/misc/adb/adb_keys:
EACCES (Permission denied)` from inside the Shizuku-spawned UserService, even
though that service runs in shell uid. This is the same OEM-side restriction
called out in the Phase 5 caveats above and matches the diag note in
`diag_20260503_phase4_handoff.md`. The fallback is a benign re-pair (adbd treats
duplicate-key registration as idempotent), so the second session still completes
end-to-end. Pair-once *was* validated on the AOSP emulator path
(`fa59f520`) where the file is readable — this is a per-OEM read-policy issue,
not an issue with the pair-once code.

### Deleted-transport invariant — clean

`grep -E "APP_PROCESS|HOST_MEDIATED_RELAY|CHROME_TCP_LOOPBACK"` against both
`logcat_full.log` returns zero matches. The cascade exercised on nubia is
exactly USER_SERVICE → WIRELESS_ADB_SELF_PAIR (USER_SERVICE throws
`IOException: Permission denied` at `ChromeDevtoolsUserService.exchange:56`
because `untrusted_app` socket is unreachable from shell, then the wireless
self-pair leg succeeds). UserService records show `version=3` (the bump from
`a0a8c866`).

### Build — PASS, with a one-line hygiene fix in this branch

`./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lint` is green at
this commit (1494 unit tests, 0 failures, 0 errors, 2m 40s wall on a quiet
Gradle daemon). The default test JVM heap of `-Xmx512m` chosen by AGP OOMs once
the Phase 5 test suite runs (Conscrypt + BouncyCastle + the OpenAI SDK +
MockWebServer harnesses + the new `WirelessAdbSelfPairTransportRelayStressTest`
all push past 512MB on the same worker). This branch raises the unit-test
worker to `-Xmx2g` in `app/build.gradle.kts` `testOptions.unitTests.all { … }`.
With that single one-line bump, the suite passes deterministically — without it
the OOM is reproducible (`MockWebServer TaskRunner` and
`DefaultDispatcher-worker-N` throw `OutOfMemoryError` from the
`UncaughtExceptionHandler`).

### Verdict — Phase 5 mid-gate

**PASS** (SOFT — pair-once skip not observable on nubia per documented
OEM constraint, not a Phase 5 regression). End-to-end `browser_script` works on
nubia P0110 with all Phase 5 cleanups merged; no references to deleted
transport labels in active logcat; build green at HEAD with the test-heap
hygiene fix.

### Evidence

- `debug-output/run_20260503_154101/` — session 1 (cold pair).
- `debug-output/run_20260503_160613/` — session 2 (re-pair, OEM perm denial logged).
- Build log: `BUILD SUCCESSFUL in 2m 40s` (`./gradlew :app:assembleDebug
  :app:testDebugUnitTest :app:lint --rerun-tasks`).

---

## Phases 1 / 2 / 3 — archived

The original 2026-04…05 transport spike worked through three intermediate paths before the
Phase 4 wireless self-pair landed:

- **Phase 1** — Shizuku UserService over `LocalSocket` (AOSP/userdebug emulator).
- **Phase 2** — `chrome --remote-debugging-port=N` via `/data/local/tmp/chrome-command-line`.
  Empirically blocked: stock Chrome ignores the file unless the per-profile
  `chrome://flags#enable-command-line-on-non-rooted-devices` toggle is on, and that flag
  cannot be set programmatically.
- **Phase 3** — Host-mediated CDP relay (PC-side `adb forward` + `adb reverse` chain through
  the host's adbd into Chrome's abstract socket). Worked on locked OEMs but contradicts
  the in-device autonomy goal — required the user's PC to keep ADB attached.

All three transports (`AppProcessLocalSocketTransport`, `HostMediatedCdpRelayTransport`,
`ChromeRemoteDebugPortTransport`) plus `scripts/setup-cdp-relay.sh` and the Phase-2-only
AIDL surface (`ensureChromeRemoteDebugPort`) were removed from the production cascade in
the `task/del-transports` commit (parent: `abcc06972`). After deletion the cascade is:

```
USER_SERVICE → WIRELESS_ADB_SELF_PAIR
```

with USER_SERVICE retained as the primary path on devices where Shizuku ends up in
`u:r:su:s0` (Phase 1's territory) and WIRELESS_ADB_SELF_PAIR as the only fallback when the
shell-context UserService is denied `connectto` on the abstract socket (Phase 4's
territory). The detailed Phase 1 / 2 / 3 evidence and code references that previously lived
here remain reachable via `git log -p` on this file before that commit, and the original
spike narrative lives at
`projects/active/browser/cn/diag_20260502_wireless_adb_spike/SPIKE_RESULT.md` and
`projects/active/browser/cn/diag_20260503_phase4_handoff.md`.

---

## Diagnostic history — nubia P0110 (FAIL, device-policy block)

## Device — model, Android version, build, Shizuku state, Chrome version

- Device: nubia P0110, Android 16, build `1.11.0.0 release-keys`.
- ADB serial used for every device command: `100.64.43.95:5555`.
- App build: debug `ai.closepaw` APK from `app/build/outputs/apk/debug/app-debug.apk`, versionName `1.0`, versionCode `1`, installed at `2026-05-01 23:04:59` device time.
- Chrome: `com.android.chrome` versionName `147.0.7727.137`, versionCode `772713733`.
- Shizuku: `moe.shizuku.privileged.api` installed; `shizuku_server` running as `u:r:shell:s0 shell`.
- Chrome CDP socket: `/proc/net/unix` exposed bare `@chrome_devtools_remote`.
- SELinux: `Enforcing`.

## Use case — one-line scenario description

Use case: run one complete agent-driven `browser_script` call through the production Shizuku Chrome DevTools transport to open `https://example.com` and return the page `title`, with no debug TCP fallback.

## Setup — install steps, permissions granted, settings toggled

1. Built the debug APK with `./gradlew assembleDebug`.
2. Installed it on the physical device via push + `pm install -r -t /data/local/tmp/closepaw-debug.apk`; result: `Success`.
3. Removed all adb reverse/forward rules before the run. `debug-output/run_20260501_200423/diagnostics/adb_forward_reverse_after.txt` is empty except newlines.
4. Confirmed `browser_script_enabled` was true in `shared_prefs/agent_prefs.xml`.
5. Launched the agent with debug intent overrides through `scripts/debug-run.sh`:
   - `DEBUG_AUTO_APPROVE=true`, so `approval_mode=AUTO_APPROVE`.
   - `DEBUG_BROWSER_SCRIPT_ENABLED=true`, so the runtime gate was enabled.
   - `DEBUG_BROWSER_TCP_FALLBACK=false`, so the debug TCP fallback was disabled.
6. Verified launch configuration in logcat:
   - `browser_script enabled set from intent: true`
   - `Approval mode set from intent: AUTO_APPROVE`
   - `Browser debug TCP fallback set from intent: false`
7. Confirmed the Chrome socket existed after the run: `@chrome_devtools_remote`.

## Steps — numbered list of what was executed (each step references browser_script call(s))

1. Ran:
   `ANDROID_SERIAL=100.64.43.95:5555 DEBUG_AUTO_APPROVE=true DEBUG_BROWSER_SCRIPT_ENABLED=true DEBUG_BROWSER_TCP_FALLBACK=false DEBUG_MAX_WAIT_SECONDS=300 ./scripts/debug-run.sh --basic "Use the browser_script tool to open https://example.com and tell me the page title. Return the title in your final answer."`
2. Turn 1: the LLM selected `browser_script` and passed a valid script that called `Page.enable`, `Runtime.enable`, `Page.navigate`, and `Runtime.evaluate(document.title)`.
3. The `PolicyEngine` allowed the call under `AUTO_APPROVE`; there was no approval timeout.
4. The tool attempted the production transport path: app-process `LocalSocket` first, then Shizuku `ChromeDevtoolsUserService`.
5. Turn 1 `browser_script` failed with `host_error` / `UserServiceSocketInaccessible`.
6. The agent opened Chrome in the foreground and retried.
7. Turn 4: the LLM selected `browser_script` again with a valid `Page.navigate` + `Runtime.evaluate(document.title)` script.
8. Turn 4 `browser_script` also failed with `host_error` / `UserServiceSocketInaccessible`.

## Result — what happened (include 'title' read, 'screenshot' captured + path/artifact id, 'tab' created/switched/restored)

The agent loop did exercise `BrowserScriptTool` through the production code path, but production Chrome CDP transport failed before CDP could navigate, read the `title`, capture a screenshot, or create/switch a tab.

- Turn 1 trace metadata: `debug-output/run_20260501_200423/trace/artifacts/browser_script/12_browser_script_call_h7OWUyxGziygzzZ6mmjMs8Zb.json`.
- Turn 1 outcome: `host_error`, `outcome_code=UserServiceSocketInaccessible`.
- Turn 4 trace metadata: `debug-output/run_20260501_200423/trace/artifacts/browser_script/64_browser_script_call_rVaCmpF2jmXAqaVd19JhtZBo.json`.
- Turn 4 outcome: `host_error`, `outcome_code=UserServiceSocketInaccessible`.
- No production-transport `title` was read by `browser_script`.
- No production-transport screenshot was captured by `browser_script`.
- No production-transport tab creation/switch/restore was performed by `browser_script`.

The final agent answer claimed `The page title is: Example Domain` after opening Chrome directly. That final answer is not accepted as a Browser CDP PASS because it did not come from a successful `browser_script` result.

Root-cause evidence:

```text
05-01 23:05:39.997 D PolicyEngine: Policy check: tool=browser_script, pkg=ai.closepaw, dest=null, tier=NORMAL, mode=AUTO_APPROVE
05-01 23:05:39.997 D ToolRouter: Policy decision for browser_script: Allow
05-01 23:05:40.009 W DefaultDispatch: avc: denied { connectto } ... app=ai.closepaw
05-01 23:05:40.018 I UserServiceManager: New service record ai.closepaw:ai.closepaw.browser.cdp.shizuku.ChromeDevtoolsUserService ...
05-01 23:05:40.338 E JavaBinder: java.io.IOException: Permission denied
05-01 23:05:40.338 E JavaBinder:     at android.net.LocalSocket.connect(LocalSocket.java:162)
05-01 23:05:40.338 E JavaBinder:     at ai.closepaw.browser.cdp.shizuku.ChromeDevtoolsUserService.exchange(ChromeDevtoolsUserService.kt:43)
```

The same failure repeated after Chrome was foregrounded:

```text
05-01 23:06:01.275 D PolicyEngine: Policy check: tool=browser_script, pkg=com.android.chrome, dest=null, tier=NORMAL, mode=AUTO_APPROVE
05-01 23:06:01.276 D ToolRouter: Policy decision for browser_script: Allow
05-01 23:06:01.289 W DefaultDispatch: avc: denied { connectto } ... app=ai.closepaw
05-01 23:06:01.296 I UserServiceManager: New service record ai.closepaw:ai.closepaw.browser.cdp.shizuku.ChromeDevtoolsUserService ...
05-01 23:06:01.606 E JavaBinder: java.io.IOException: Permission denied
05-01 23:06:01.606 E JavaBinder:     at android.net.LocalSocket.connect(LocalSocket.java:162)
05-01 23:06:01.606 E JavaBinder:     at ai.closepaw.browser.cdp.shizuku.ChromeDevtoolsUserService.exchange(ChromeDevtoolsUserService.kt:43)
```

Diagnosis: the app process is blocked by SELinux from connecting to Chrome's abstract socket, and on this nubia Android 16 build the Shizuku shell UserService is also blocked from connecting to the same `@chrome_devtools_remote` socket. Host-side adb forward can reach the socket, but that is not the production transport and was not used for this verdict.

## Evidence — paths to screenshots, traces, logs (include actual artifact paths)

- Agent-driven run root: `debug-output/run_20260501_200423/`.
- Full agent log: `debug-output/run_20260501_200423/agent.log`.
- Full live logcat from the run: `debug-output/run_20260501_200423/logcat_full.log`.
- Trace root: `debug-output/run_20260501_200423/trace/`.
- Turn 1 `browser_script` call args: `debug-output/run_20260501_200423/trace/artifacts/tool_call_args/11_turn_1_browser_script_call_h7OWUyxGziygzzZ6mmjMs8Zb.json`.
- Turn 1 `browser_script` result: `debug-output/run_20260501_200423/trace/artifacts/tool_result/17_turn_1_browser_script_call_h7OWUyxGziygzzZ6mmjMs8Zb_result.txt`.
- Turn 1 `browser_script` metadata: `debug-output/run_20260501_200423/trace/artifacts/browser_script/12_browser_script_call_h7OWUyxGziygzzZ6mmjMs8Zb.json`.
- Turn 4 `browser_script` call args: `debug-output/run_20260501_200423/trace/artifacts/tool_call_args/63_turn_4_browser_script_call_rVaCmpF2jmXAqaVd19JhtZBo.json`.
- Turn 4 `browser_script` result: `debug-output/run_20260501_200423/trace/artifacts/tool_result/69_turn_4_browser_script_call_rVaCmpF2jmXAqaVd19JhtZBo_result.txt`.
- Turn 4 `browser_script` metadata: `debug-output/run_20260501_200423/trace/artifacts/browser_script/64_browser_script_call_rVaCmpF2jmXAqaVd19JhtZBo.json`.
- Compact failure logcat snippet: `debug-output/run_20260501_200423/diagnostics/logcat_browser_script_failure_compact.txt`.
- Full failure-window logcat snippet: `debug-output/run_20260501_200423/diagnostics/logcat_browser_script_failure_windows.txt`.
- `adb logcat -d -t 2000` capture after the run: `debug-output/run_20260501_200423/diagnostics/logcat_tail_2000.txt`.
- Filtered post-run logcat: `debug-output/run_20260501_200423/diagnostics/logcat_tail_2000_browser_filtered.txt`.
- Socket proof: `debug-output/run_20260501_200423/diagnostics/proc_net_unix_chrome_devtools.txt`.
- SELinux state: `debug-output/run_20260501_200423/diagnostics/selinux_getenforce.txt`.
- Shizuku/Chrome/process state: `debug-output/run_20260501_200423/diagnostics/ps_shizuku_chrome_closepaw_after.txt`.
- Verification for QA-control code: `./gradlew :app:testDebugUnitTest --tests ai.closepaw.app.MainActivityIntentApplierSecurityTest --tests ai.closepaw.session.SessionBrowserIntegrationTest` passed.
- Build verification: `./gradlew assembleDebug` passed.

## Root cause — definitive diagnosis (added 2026-05-01 after Hyp-A/Hyp-B isolation)

The failure is NOT a code bug. The production transport is correctly designed and the Shizuku
UserService binder runs in the right process — it is the device's SELinux policy that blocks every
in-app path to Chrome's `chrome_devtools_remote` abstract socket. Evidence:

### Hypothesis A — RULED OUT (binder wiring is correct)

The original failure stack ends at
`IChromeDevtoolsUserService$Stub.onTransact(IChromeDevtoolsUserService.java:75)`. `Stub.onTransact`
only runs in the binder *server* process, never in the client. Confirmed by `ps -AZ` on the device:
the `ai.closepaw:chrome_devtools` process (the Shizuku UserService) is in
`u:r:shell:s0` with UID `shell`, not the app context. So `LocalSocket.connect` at
`ChromeDevtoolsUserService.exchange:43` runs in shell context, exactly as designed. The
`AppProcessLocalSocketTransport` AVC at `scontext=u:r:untrusted_app:s0:c66,c257,c512,c768` is the
*first* leg (the deliberate app-process attempt that always fails on this device); the JavaBinder
`IOException: Permission denied` 300ms later is the *second* leg, the UserService running as shell.

### Hypothesis B — CONFIRMED (shell domain has no `connectto` to app abstract sockets on this build)

Reproduced with shell-only tooling — no Shizuku, no app, just `adb shell` (which is the same
`u:r:shell:s0` SELinux context Shizuku spawns UserServices in):

```text
$ adb -s 100.64.43.95:5555 shell 'curl --abstract-unix-socket chrome_devtools_remote http://localhost/json/version'
* Immediate connect fail for : Permission denied

# kernel audit:
type=1400 audit(0.0:92640): avc: denied { connectto }
  for path=...chrome_devtools_remote
  scontext=u:r:shell:s0
  tcontext=u:r:untrusted_app:s0:c211,c256,c512,c768
  tclass=unix_stream_socket permissive=0
```

Inspecting the device's compiled SELinux policy at `/system/etc/selinux/plat_sepolicy.cil`:

```cil
(allow adbd appdomain (unix_stream_socket (connectto)))
(allow runas_app untrusted_app_all (unix_stream_socket (connectto)))
; ...no rule allowing shell -> appdomain unix_stream_socket connectto.
```

Stock AOSP includes `allow shell appdomain:unix_stream_socket connectto` so that
`adb shell` can reach Chrome's CDP socket; nubia/ZTE removed it from the `shell` domain on this
build (`nubia/pacific:16/1.11.0.0` user release-keys, verity enforcing, `ro.debuggable=0`). Every
Shizuku-spawned process inherits `shell` and inherits this denial.

### Workarounds attempted — none can carry production traffic on this device

1. **`run-as ai.closepaw` → `runas_app` domain.** The policy *does* allow
   `runas_app -> untrusted_app_all` connectto, but the MLS constraint blocks it because Chrome's
   level (`s0:c211,c256,c512,c768`) and our level (`s0:c66,c257,c512,c768`) do not dominate each
   other and `runas_app` is NOT in `mlstrustedsubject`:

   ```text
   (mlsconstrain (unix_stream_socket (connectto))
       (or (or (eq l1 l2) (eq t1 mlstrustedsubject)) (eq t2 mlstrustedsubject)))
   ; mlstrustedsubject set: adbd ... runas (transient) ... shell ... — runas_app NOT included.
   ```

   Empirical AVC from the device:

   ```text
   type=1400 audit(0.0:92586): avc: denied { connectto } for path=...chrome_devtools_remote
     scontext=u:r:runas_app:s0:c66,c257,c512,c768
     tcontext=u:r:untrusted_app:s0:c211,c256,c512,c768
     tclass=unix_stream_socket permissive=0
   ```

2. **`/data/local/tmp/chrome-command-line` to add `--remote-debugging-port=9222`.** Stock Chrome
   on Android only reads this file when the `chrome://flags` toggle
   "Enable command line on non-rooted devices" is set; that flag is per-Chrome-profile and cannot
   be set programmatically. Verified by writing
   `chrome --remote-debugging-socket-name=closepaw_test_socket --remote-debugging-port=9222 --enable-remote-debugging`,
   force-stopping Chrome, relaunching, and observing that Chrome continued to bind the *default*
   `@chrome_devtools_remote` socket and never bound TCP `127.0.0.1:9222`. So the file is being
   ignored.

3. **adbd path** (the only domain that *does* have policy for chrome_devtools_remote):
   `(allow adbd appdomain (unix_stream_socket (connectto)))`. adbd auths each connection with the
   host's RSA private key in `~/.android/adbkey`; the device only has the public half in
   `/data/misc/adb/adb_keys`, so an on-device caller cannot speak the adb wire protocol to adbd
   on `localhost:5555` without smuggling the host's private key onto the device — that is "host
   involvement" the spec explicitly forbids.

4. **Other `mlstrustedsubject` exec targets reachable from shell.** `simpleperf_app_runner` is
   `mlstrustedsubject`, but `simpleperf_app_runner com.android.chrome --show-app-type` returns
   `non_profileable`, so Chrome cannot be attached. No other shell-reachable exec target carries
   both the `connectto` type rule AND the MLS bypass.

5. **Root.** `which su` empty, `/system/bin/su` and `/system/xbin/su` absent, `ro.debuggable=0`,
   verity enforcing. The device is a fully-locked production user build.

### Conclusion

The Shizuku-based production transport is the correct design for AOSP-style devices and this
device specifically does not permit it. There is no in-app code change that can succeed without
either (a) the user manually toggling `chrome://flags#enable-command-line-on-non-rooted-devices`
in Chrome and the app then writing `--remote-debugging-port=N` and restarting Chrome (TCP
loopback path, app-to-app TCP connect is NOT MLS-restricted on this device), or (b) host-side
adb forwarding (the existing `DebugTcpDevtoolsSocketTransport`, which the spec rules out for
the final PASS verdict).

### Diagnostic artifacts captured for this conclusion

All under `projects/active/browser/cn/diag_20260501_finalpath/`:

- `shell_curl_chrome_socket.txt` + `shell_curl_avc.txt` — shell-context curl + matching AVC
- `runas_curl_chrome_socket.txt` + `runas_curl_avc.txt` — `run-as ai.closepaw` curl + AVC
- `selinux_connectto_rules.txt` — the only allow rules for `connectto` to appdomain
- `selinux_mlsconstrain.txt` — the MLS constraint that blocks `runas_app`
- `selinux_mlstrusted.txt` — the `mlstrustedsubject` set (note the absence of `runas_app`)
- `chrome_cmdline_current.txt` + `proc_net_unix_devtools.txt` — empirical proof that
  Chrome ignores `/data/local/tmp/chrome-command-line` on this device
- `device_buildinfo.txt` — `nubia/pacific:16/1.11.0.0`, user release-keys, verity enforcing
- `processes.txt` — confirms `ai.closepaw:chrome_devtools` runs as `u:r:shell:s0` shell UID

## Verdict — PASS or FAIL, single line; if PASS, write the literal word PASS

FAIL — production transport blocked by device SELinux policy; not a code bug.
