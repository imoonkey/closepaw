# Browser CDP Real-Device QA

## Device — model, Android version, build, Shizuku state, Chrome version

- Device: nubia P0110, Android 16, build `1.11.0.0 release-keys`.
- ADB serial used for every device command: `100.64.43.95:5555`.
- App build: debug `ai.closepaw` APK from `app/build/outputs/apk/debug/app-debug.apk`, versionName `1.0`, versionCode `1`.
- Chrome: `com.android.chrome` versionName `147.0.7727.137`, versionCode `772713733`.
- Shizuku: `moe.shizuku.privileged.api` installed and `shizuku_server` running. The ClosePaw `:chrome_devtools` UserService process ran as `u:r:shell:s0 shell`.
- Chrome CDP socket: bare `@chrome_devtools_remote` exposed in `/proc/net/unix`; no pid-suffixed socket was required on this build.

## Use case — one-line scenario description

Use case: run one real-device `browser_script` flow using installed `browser-use` `page.js` and `tabs.js` snippets to open `https://example.com`, read title/content, capture a screenshot, create a new tab for `https://example.org`, and return to the original tab.

## Setup — install steps, permissions granted, settings toggled

1. Installed the rebuilt debug app with `adb -s 100.64.43.95:5555 install -r -t app/build/outputs/apk/debug/app-debug.apk`; result: `Success`.
2. Installed the androidTest APK with `adb -s 100.64.43.95:5555 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`; result: `Success`.
3. Enabled `browserScriptEnabled` in the test setup through `AppSettingsStore(context).saveBrowserScriptEnabled(true)`.
4. Verified Chrome and Shizuku packages with `pm list packages | grep -E 'chrome|shizuku'`.
5. Verified Chrome CDP with host ADB forwarding to `localabstract:chrome_devtools_remote`; `/json/version` returned Chrome `147.0.7727.137`.
6. Confirmed the Shizuku UserService policy limitation: the shell UserService process exists, but `ChromeDevtoolsUserService.exchange` hit `LocalSocket.connect(...): java.io.IOException: Permission denied` on this device build.
7. For debug QA, used the new debug TCP fallback through a foreground host relay: device `127.0.0.1:9222` via `adb reverse`, host relay `127.0.0.1:9222 -> 127.0.0.1:9223`, and `adb forward tcp:9223 localabstract:chrome_devtools_remote`.
8. Confirmed the real device could read CDP through the relay before the QA run: device-side `curl http://127.0.0.1:9222/json/version` returned Chrome `147.0.7727.137`.

## Steps — numbered list of what was executed (each step references browser_script call(s))

1. Fixed the agent role allowlist so `StandaloneRoleDef.allowedTools` includes `browser_script`.
2. Ran the fallback instrumented real-device QA class `BrowserRealDeviceQaInstrumentedTest`, which invokes `BrowserScriptTool` directly and therefore executes a real `browser_script` call through `BrowserScriptRunner`.
3. The `browser_script` loaded installed snippets from `/data/user/0/ai.closepaw/files/skills/browser-use/scripts/page.js` and `/data/user/0/ai.closepaw/files/skills/browser-use/scripts/tabs.js`.
4. The `browser_script` enabled CDP domains, selected the original tab, navigated to `https://example.com`, read page `title` and body content, and captured a CDP `screenshot`.
5. The same `browser_script` created a new tab for `https://example.org`, waited for load, read the second page title, listed tabs, switched back to the original tab, and read the final title.
6. Command used: `adb -s 100.64.43.95:5555 shell am instrument -w -r -e class ai.closepaw.browser.script.BrowserRealDeviceQaInstrumentedTest ai.closepaw.test/androidx.test.runner.AndroidJUnitRunner`.

## Result — what happened (include 'title' read, 'screenshot' captured + path/artifact id, 'tab' created/switched/restored)

The real-device `browser_script` round trip completed successfully. It read the first page title as `Example Domain`, read body content beginning with `Example Domain`, captured a PNG screenshot, created a distinct new tab for `https://example.org`, switched to that tab, then restored the original tab.

- Original tab id: `9E2ABD290821D5CB77C0FEF3068EAE59`.
- New tab id: `082388684EC422994ECF5FDF5D46BB89`.
- Final restored tab id: `9E2ABD290821D5CB77C0FEF3068EAE59`.
- Screenshot artifact on device: `/storage/emulated/0/Android/data/ai.closepaw/files/debug-output/browser-real-device-qa/example_com_cdp_screenshot.png`.
- Pulled screenshot artifact: `debug-output/browser_real_device_qa_20260501_185919/artifacts/example_com_cdp_screenshot.png`.
- Pulled result JSON: `debug-output/browser_real_device_qa_20260501_185919/artifacts/browser_script_result.json`.

## Evidence — paths to screenshots, traces, logs (include actual artifact paths)

- Passing instrumentation output: `debug-output/browser_real_device_qa_20260501_185919/browser_real_device_instrumentation_pass_candidate.txt`.
- Browser script result JSON: `debug-output/browser_real_device_qa_20260501_185919/artifacts/browser_script_result.json`.
- Screenshot: `debug-output/browser_real_device_qa_20260501_185919/artifacts/example_com_cdp_screenshot.png`.
- Device/package diagnostics: `debug-output/browser_real_device_qa_20260501_185919/diagnostics_round2/`.
- CDP relay proof from device: `debug-output/browser_real_device_qa_20260501_185919/device_reverse_logged_proxy_cdp_version_before_direct_page_run.json`.
- Host relay/proxy log: `debug-output/browser_real_device_qa_20260501_185919/host_tcp_proxy_logged_9222_to_9223.log`.
- Post-pass logcat snippet: `debug-output/browser_real_device_qa_20260501_185919/logcat_after_pass.txt`.
- Verification: `./gradlew test` passed after the fixes.

## Verdict — PASS or FAIL, single line; if PASS, write the literal word PASS

PASS
