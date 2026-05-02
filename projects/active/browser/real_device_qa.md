# Browser CDP Real-Device QA

## Phase 1 — AOSP emulator (emulator-5554) — PASS

### Device

- Emulator: `emulator-5554`, `google/sdk_gphone64_x86_64/emu64x:13/TE1A.240213.009/12342917:userdebug/dev-keys`.
- Android 13, kernel `6.6.56-android15-8 #1 SMP PREEMPT`.
- `ro.debuggable=1`, `getenforce=Enforcing`.
- Chrome: `com.android.chrome` versionName `109.0.5414.123` (versionCode 541412337).
- Shizuku: `moe.shizuku.privileged.api` 13.5, `shizuku_server` running as `u:r:su:s0` (root via `adb root` bootstrap; standard Shizuku startup path on `userdebug` builds).
- ClosePaw: debug APK from `app/build/outputs/apk/debug/app-debug.apk` (versionName 1.0, includes the new TCP-relay UserService and HTTP Content-Length-aware reader).

### Use case

Run one complete agent-driven `browser_script` call through the production Shizuku Chrome DevTools transport to open `https://example.com` and return the page `title`, with **no debug TCP fallback** and **no host-side `adb forward`**.

### Setup

1. `adb -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-debug.apk` — installed.
2. Enabled accessibility service for `ai.closepaw/.app.AgentService` (was already enabled).
3. `pm grant ai.closepaw moe.shizuku.manager.permission.API_V23` — Android-level grant.
4. Chrome had no `chrome_devtools_remote` socket by default. Wrote `/data/local/tmp/chrome-command-line` with `chrome --remote-debugging-socket-name=chrome_devtools_remote --enable-remote-debugging --no-first-run --disable-fre --no-default-browser-check`. On `userdebug` Chrome reads this file without the per-profile `enable-command-line-on-non-rooted-devices` flag.
5. `adb -s emulator-5554 install -r -t eval/tools/shizuku.apk` — Shizuku 13.5 installed.
6. Bootstrapped Shizuku as the standard adb-root flow does (the official `start.sh` invokes `libshizuku.so`, which in turn execs `app_process moe.shizuku.starter.ServiceStarter`):
   ```
   adb shell /data/app/.../moe.shizuku.privileged.api-.../lib/x86_64/libshizuku.so
   ```
   `shizuku_server pid=...` confirmed in `u:r:su:s0`.
7. Granted ClosePaw via Shizuku's UI dialog ("Allow all the time"). Shizuku app showed `Authorized 1 application`.
8. Ran `adb -s emulator-5554 forward --list` — empty. **No host involvement.**

### Steps

1. Ran:
   ```
   ANDROID_SERIAL=emulator-5554 \
   DEBUG_AUTO_APPROVE=true \
   DEBUG_BROWSER_SCRIPT_ENABLED=true \
   DEBUG_BROWSER_TCP_FALLBACK=false \
   DEBUG_MAX_WAIT_SECONDS=180 \
   ./scripts/debug-run.sh --basic --accessibility-only \
     "Use the browser_script tool to open https://example.com and tell me the page title. Return the title in your final answer."
   ```
2. Turn 1: agent took a screenshot of the previous chat.
3. Turn 2: LLM emitted `browser_script` with a script that called `Browser.getVersion`, `Target.getTargets`, `Target.attachToTarget`, `Page.enable`, `Runtime.enable`, `Page.navigate('https://example.com')`, `Runtime.evaluate('document.title')`.
4. The bridge ran the production transport path:
   - `AppProcessLocalSocketTransport` failed (app context denied — expected; AVC `scontext=u:r:untrusted_app:s0:c195,...` `tcontext=u:r:untrusted_app:s0:c125,...` `connectto unix_stream_socket`).
   - `ShizukuUserServiceProvider.bindUserService` spawned `ai.closepaw:chrome_devtools` in `u:r:su:s0`.
   - `UserServiceTransport.exchange` round-tripped `GET /json/version` and `GET /json/list` over the abstract socket — returned valid JSON.
   - The bridge called `UserServiceTransport.ensureRelayPortSuspend()` → UserService bound `127.0.0.1:37903` and started accept loop (`I ChromeDevtoolsUS: TCP relay started on 127.0.0.1:37903`).
   - `BrowserSessionManager.rewriteHost` rewrote Chrome's `webSocketDebuggerUrl` (port-less `ws://localhost/devtools/...`) to `ws://127.0.0.1:37903/devtools/...`.
   - OkHttp opened the WebSocket against the relay; UserService accepted, opened a second `LocalSocket` to `@chrome_devtools_remote`, and pumped bytes both ways.
   - CDP commands flowed through the relay; `Page.navigate` opened `https://example.com`, `Runtime.evaluate('document.title')` returned `Example Domain`.
5. Turn 3: LLM called `complete_task` with `The page title is: Example Domain`.

### Result

- **`browser_script` outcome: `ok`**, `duration_ms: 7685`.
- **Tool result body:** `Success: {"title":"Example Domain"}`.
- **Trace metadata:** `{"call_id":"call_1gPZcWNk9Hq9IEeQXeRQfUp6","outcome":"ok","raw_result_json":"{\"kind\":\"ok\",\"result_json\":\"{\\\"title\\\":\\\"Example Domain\\\"}\"}"}`.
- **Agent outcome:** `Task completed: outcome: GOAL_ACHIEVED`, agent answer `The page title is: Example Domain`.
- **Title was read by `browser_script`** (real CDP), not by screen scraping.
- **No screenshot or tab-switch was needed for the verdict** — the goal was specifically the title via `browser_script`.

### Evidence

- Run root: `debug-output/run_20260501_211519/`.
- Snapshot copy of artifacts: `projects/active/browser/cn/aosp_emulator_pass_20260501/`.
- Key files in the snapshot:
  - `30_turn_2_browser_script_call_1gPZcWNk9Hq9IEeQXeRQfUp6_result.txt` — raw tool result body.
  - `29_browser_script_call_1gPZcWNk9Hq9IEeQXeRQfUp6.json` — outcome metadata (`outcome:"ok"`).
  - `key_log_lines.txt` — every `browser_script`, `UserServiceManager`, `TCP relay`, and final outcome line from agent.log + logcat.
  - `processes.txt` — confirms `shizuku_server` and `ai.closepaw:chrome_devtools` UserService context.
  - `proc_net_unix.txt` — confirms `@chrome_devtools_remote` socket bound.
  - `adb_forward_list.txt` — empty (proves no host involvement).
  - `chrome_command_line.txt` — the flag file used to expose Chrome's CDP socket.
  - `chrome_version.txt` — `109.0.5414.123`.
  - `closepaw_shizuku_perm.txt` — `moe.shizuku.manager.permission.API_V23: granted=true`.
  - `device_buildinfo.txt` — `userdebug/dev-keys`, Enforcing.

### Code changes that made this PASS possible

- `app/src/main/aidl/.../IChromeDevtoolsUserService.aidl` — added `int startTcpRelay()`.
- `app/src/main/kotlin/.../ChromeDevtoolsUserService.kt` — implemented:
  - HTTP Content-Length-aware reader (`readHttpResponse`); Chrome's `net::HttpServer` ignores `Connection: close` on the abstract socket and never EOFs after a single response, so reading until EOF hangs.
  - Removed `shutdownOutput()`; on this build it triggers an immediate connection RST with no response.
  - Device-side TCP relay: `startTcpRelay()` binds a free 127.0.0.1 port, spawns an accept loop, and bidirectionally pumps each accepted TCP connection to a fresh abstract socket. Idempotent; one relay per UserService process; tears down with the process.
- `app/src/main/kotlin/.../AppProcessLocalSocketTransport.kt` — same HTTP-aware reader; added `UserServiceTransport.ensureRelayPortSuspend()`.
- `app/src/main/kotlin/.../ShizukuChromeDevtoolsBridge.kt` — added `resolveWebSocketHost()` that returns `127.0.0.1:<relayPort>`.
- `app/src/main/kotlin/.../BrowserSessionManager.kt` — added `rewriteHost(...)` and called it before opening the OkHttp WebSocket so CDP traffic tunnels through the relay.

### Known limitation (Phase 2 scope)

When `shizuku_server` runs in `u:r:shell:s0` (the regular non-root ADB bootstrap, e.g. on locked production devices), the same SELinux denial documented for the nubia P0110 below blocks the UserService from connecting to `chrome_devtools_remote`. Verified on this same emulator after `adb unroot` + restart of `shizuku_server` in shell context: AVC `connectto` denial with `scontext=u:r:shell:s0` `tcontext=u:r:untrusted_app:s0:c125,...`. Phase 2 is the workaround for shell-context Shizuku on locked devices.

### Verdict — Phase 1

PASS — production Shizuku UserService transport drove an end-to-end `browser_script` round-trip on AOSP emulator-5554 with no host involvement and no debug TCP fallback.

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
