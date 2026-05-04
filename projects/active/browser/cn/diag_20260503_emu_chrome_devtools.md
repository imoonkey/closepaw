# Diagnostic — Unlocking Chrome stable's `chrome_devtools_remote` socket on emulator-5556 via Local-State flag toggle

**Date**: 2026-05-03
**Device**: emulator-5556 (Google Play AVD, Android 13, x86_64, `userdebug`, `adbd` root)
**Chrome**: `com.android.chrome` 109.0.5414.123 (Chrome stable)
**Branch**: `investigate/emu-chrome` (worktree `.worktrees/emu-chrome`)
**Result**: **PASS** — full agent chain `browser_script → CDP → Page document.title` returns `"Example Domain"` from the open `https://example.com` tab.

## Why this matters

The previous emulator follow-up (`real_device_qa.md` lines 91–114) marked the CDP chain UNTESTED on emulator because the prevailing belief — inherited from Phase 2 archived notes — was that **stock Chrome on Android only honors `/data/local/tmp/chrome-command-line` after the user manually toggles `chrome://flags#enable-command-line-on-non-rooted-devices` in the UI**. That UI toggle is per-Chrome-profile and was assumed to be impossible to flip programmatically without a debuggable Chrome build.

This round disproves that assumption. The flag is just a string in Chrome's `Local State` JSON, and `Local State` is a regular file under `/data/data/com.android.chrome/app_chrome/`. On any device where shell uid (or higher) can write that file — i.e. every userdebug emulator and any rooted device — flipping the flag is a one-line JSON edit. Chrome reads `Local State` on its next cold start, applies the experiments, and from that point the cmdline file is honored. No APK swap, no UI navigation through the flags page (which is itself a special URL whose UI cannot be tapped under accessibility automation reliably), no Chrome Beta install needed.

This unlock procedure is reproducible end-to-end via adb on userdebug emulators, and would be reproducible on rooted production devices via the same writes (or via a Shizuku UserService running in a context with write access to Chrome's data dir; not the default shell context, which is denied by SELinux on stock Android).

## The procedure (4 commands + agent run)

Prereqs: `adb -s emulator-5556 root` succeeds (userdebug build).

```bash
# 1. Stop Chrome so it doesn't overwrite Local State on shutdown.
adb -s emulator-5556 shell am force-stop com.android.chrome

# 2. Pull, edit, push Local State. The key path is browser.enabled_labs_experiments.
adb -s emulator-5556 shell "cat '/data/data/com.android.chrome/app_chrome/Local State'" > /tmp/ls.json
python3 -c "
import json
d = json.load(open('/tmp/ls.json'))
d.setdefault('browser', {}).setdefault('enabled_labs_experiments', []).append(
    'enable-command-line-on-non-rooted-devices@1'
)
json.dump(d, open('/tmp/ls.json', 'w'))
"
adb -s emulator-5556 push /tmp/ls.json /data/local/tmp/ls.json
adb -s emulator-5556 shell "cp /data/local/tmp/ls.json '/data/data/com.android.chrome/app_chrome/Local State' \
  && chown u0_a126:u0_a126 '/data/data/com.android.chrome/app_chrome/Local State' \
  && chmod 600 '/data/data/com.android.chrome/app_chrome/Local State' \
  && restorecon '/data/data/com.android.chrome/app_chrome/Local State'"

# 3. Drop the Chrome cmdline file. The leading `_` is required (program-name placeholder).
adb -s emulator-5556 shell "echo '_ --remote-debugging-socket-name=chrome_devtools_remote --enable-features=NetworkService' > /data/local/tmp/chrome-command-line"

# 4. Cold-launch Chrome to a real URL (FRE will appear once on a fresh profile —
#    it must be dismissed once with "Use without an account" via UI tap, after
#    which the flag's "unsupported feature flag" snackbar appears, confirming
#    the cmdline file was read).
adb -s emulator-5556 shell "am start -a android.intent.action.VIEW -d https://example.com com.android.chrome"

# 5. Verify socket is bound.
adb -s emulator-5556 shell "cat /proc/net/unix | grep devtools"
# Expected: ... @chrome_devtools_remote
```

The chown uid (`u0_a126` here) is whatever Chrome's runtime uid is on this image. Pull it with `stat -c '%U:%G' '/data/data/com.android.chrome/app_chrome/Local State'` before editing.

## Agent run that proved the chain

```
ANDROID_SERIAL=emulator-5556 DEBUG_AUTO_APPROVE=true DEBUG_BROWSER_SCRIPT_ENABLED=true \
  ./scripts/debug-run.sh "Use browser_script to fetch document.title from open https://example.com"
```

Output trace at `debug-output/run_20260503_213022/`:

| Turn | Tool | Outcome |
|---|---|---|
| 1 | `activate_skill(browser-use)` | Success |
| 2 | `open_app(Chrome)` | Success — Chrome foregrounded with `https://example.com` |
| 3 | `browser_script` — CDP `Runtime.evaluate({title: document.title, href: location.href})` | Success in 6.2 s |
| 4 | `complete_task` | `document.title is "Example Domain".` |

Key logcat markers:

```
TurnExecutionPhase: Executing tool: browser_script with args: {"script":"const info = await cdp(\"Runtime.evaluate\", {...
ToolRouter: State: call_CN8XtUYXdW45GJ3bb2T2uNXW -> Success
AgentEventDispatcher: ActionExecuted: browser_script outcome=SUCCESS
Turn 4: agent_thought = Complete (success): document.title is "Example Domain".
```

## Two non-Chrome bring-up gotchas hit during this round (pre-existing, not Phase 5 regressions)

These weren't in `scripts/setup.sh`. They cost time during the run; documenting so future rounds skip them.

1. **Accessibility service silently disables on app upgrade / reinstall on this AVD.** Verified: `dumpsys accessibility` showed `Enabled services:{}` after a `pm install -r` cycle, even though the prior session had it bound. Re-enable with:
   ```
   adb -s emulator-5556 shell "settings put secure enabled_accessibility_services ai.closepaw/.app.AgentService"
   adb -s emulator-5556 shell "settings put secure accessibility_enabled 1"
   ```
   Symptom when not enabled: agent never starts, `MainActivity` shows a `"Please enable the Accessibility Service"` toast and the run timeouts at `--max-wait`.

2. **`debug-run.sh` race after `am force-stop ai.closepaw`.** When the script launches `MainActivity` with `fresh_session=true` and the AccessibilityService process was just killed, `AgentService.instance` is null at the moment `ensureSessionAndSend` is called, so the goal goes to `pendingAutoStartGoal` and never auto-resumes (the script's intent doesn't re-fire the auto-start path on the resumed activity). Workaround: between `am force-stop ai.closepaw` and the script invocation, **toggle the accessibility service** so the system rebinds it cleanly:
   ```
   adb -s emulator-5556 shell "settings put secure enabled_accessibility_services ''"
   sleep 1
   adb -s emulator-5556 shell "settings put secure enabled_accessibility_services ai.closepaw/.app.AgentService"
   sleep 5  # wait for binding
   adb -s emulator-5556 shell "dumpsys accessibility | grep 'Bound services'"
   # expect: Service[label=ClosePaw, ...]
   ```
   With the service Bound before the script launch, the ensureSessionAndSend path proceeds and the agent starts on the goal as intended.

A separate latent race in `debug-run.sh` itself (line 509 — the script treats the `TaskCompleted(USER_STOPPED)` emitted while clearing the previous session as the new task's completion, then broadcasts `STOP_AGENT` and kills the *new* session ~250 ms after it starts) is worth filing as a follow-up but did not block this round once we ensured Chrome was already pre-warmed and the AccessibilityService was Bound before the script ran.

## What this changes in the broader QA story

- The "Phase 4 emulator coverage is PARTIAL because Chrome stable can't be unlocked" claim is no longer true. The unlock procedure above gives full-chain agent PASS on emulator-5556, with the same Phase-4 wireless-ADB-self-pair transport that runs on nubia P0110.
- The Phase 2 archived "Chrome stable can't be programmatically unlocked" finding was incomplete — it's true that the flag can't be flipped from inside Chrome by an external process, but it *can* be flipped by writing Chrome's preferences file from outside Chrome with sufficient FS privilege. On userdebug images and rooted devices, that's trivial via adb. On stock production devices, it would require Shizuku to run in a context with write access to Chrome's data dir, which is the same SELinux gate that took out the original `connectto` path on `chrome_devtools_remote` from a shell-uid Shizuku UserService — so on stock production devices the wireless-ADB-self-pair path remains the only viable option.
- The full emulator unlock procedure does **not** generalize to nubia P0110 or any other locked OEM device — those need the Phase 4 wireless-ADB-self-pair path. But for emulator regression coverage and any future userdebug device, this is now the simplest path: 4 adb commands, ~30 seconds, no APK swap.
