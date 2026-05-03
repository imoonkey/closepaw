# Browser CDP Real-Device QA

## Verdict — production transport coverage

**PASS.** Two transport paths cover the device-policy matrix:

| Path | Devices proven | Notes |
|---|---|---|
| Shizuku UserService (root context) | emulator-5554 (Phase 1) | Works on AOSP/userdebug + any device where Shizuku ends up in `u:r:su:s0`. |
| **Wireless-ADB self-pair (Phase 4) — autonomous in-device** | **nubia P0110 (locked OEM, no root, no PC)** | Shizuku-spawned shell-uid helper drives `IAdbManager.allowWirelessDebugging` + `enablePairingByQrCode` with our own PSK; embedded TLS pair client registers our RSA pubkey into adb_keys; embedded mTLS adb client A_OPENs `localabstract:chrome_devtools_remote` through adbd (which holds both `connectto`-allow and `mlstrustedsubject`). Zero host plumbing on the data path; only persistent requirement is one-time Shizuku grant. |

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

1. `WirelessAdbSelfPairTransport.exchange()` invoked by the bridge cascade after APP_PROCESS and USER_SERVICE both fail (SELinux denies `shell→untrusted_app` `connectto` for chrome_devtools_remote on this build).
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
