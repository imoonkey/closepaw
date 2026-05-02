# Browser CDP Real-Device QA

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

## Verdict — PASS or FAIL, single line; if PASS, write the literal word PASS

FAIL
