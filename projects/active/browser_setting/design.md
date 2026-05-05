# Browser Tool Settings — design

**Date**: 2026-05-04
**Status**: PLANNED, not implemented
**Parent**: post-Phase-6 follow-up. Phase 5+6 made `browser_script` production-ready on the wire; this task makes it discoverable + recoverable from the user-facing Settings UI.

## Why

After Phase 6, `browser_script` works end-to-end on:
- Chrome non-stable (Beta/Dev/Canary) — auto-works once Shizuku gives shell uid
- Chrome stable + rooted device — auto-works
- userdebug emulator (e.g. emulator-5556) — auto-works after a one-time `chrome://flags` toggle

But for **the most common case — Chrome stable on a stock non-rooted phone** — the agent's `browser_script` call fails silently because Chrome doesn't bind `chrome_devtools_remote` socket. Reason: Chromium's `DevToolsServer::IsAllowed()` requires either app-debuggable, root, or `chrome://flags#enable-command-line-on-non-rooted-devices` toggled (per-profile UI flag). The flag is one-time but invisible to users.

ClosePaw can't write Chrome's private `Local State` JSON via Shizuku (mode 0700, owned by Chrome's uid; shell uid blocked). So we can't fully automate the unlock without root.

KISS solution: don't try to automate. **Detect the state and surface a one-tap CTA to the right `chrome://flags` page** when the user is already in ClosePaw Settings enabling the tool.

## Where it lives

`AgentBehaviorSettingsPage.kt` (currently has Max Turns / Execution / Perception sections — 48 LOC). Add a fourth section:

```
Agent Behavior
├── Max Turns
├── Execution
├── Perception
└── Tools                                             ← NEW
    └── ToolToggleRow("browser_script", enabled=...)  ← NEW (first tool; future tools join here)
        └── (when toggle ON) BrowserCdpStatusRow      ← NEW
            ├── status text: "✓ Active" / "✗ Chrome devtools not enabled"
            └── (when not Active) CTA: "Open Chrome flag page"
```

**Existing onboarding flow is NOT touched.** Detection only runs when user opens this Settings page.

## UX (mockup)

```
┌─ Tools ────────────────────────────────────────────┐
│ ┌────────────────────────────────────────────────┐ │
│ │ browser_script                       [●] On   │ │  ← toggle row
│ └────────────────────────────────────────────────┘ │
│                                                    │
│   Status: ✗ Chrome devtools socket not exposed     │  ← status sub-row (only when toggle ON)
│                                                    │
│   To use browser_script, enable Chrome's           │
│   "Enable command line on non-rooted devices"      │
│   flag, then restart Chrome.                       │
│                                                    │
│   ┌──────────────────────────────────────────┐     │
│   │  Open chrome://flags →                   │     │  ← CTA (only when status ✗)
│   └──────────────────────────────────────────┘     │
│                                                    │
│   ┌──────────────────────────────────────────┐     │
│   │  Re-check                                │     │  ← retry button
│   └──────────────────────────────────────────┘     │
└────────────────────────────────────────────────────┘
```

When status is ✓ Active, hide the explanation + CTA + retry button; just show one-line `✓ Active`.

## Detection

Run the check via Shizuku shell uid (already wired through `ChromeDevtoolsUserService`). Cheapest: read `/proc/net/unix` and look for `@chrome_devtools_remote` (or the per-target `webview_devtools_remote_<pid>` if the agent ever switches to a WebView target).

```kotlin
suspend fun chromeCdpSocketBound(): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val output = userServiceProvider.obtain().exec("cat /proc/net/unix")
        output.lineSequence().any { it.contains("@chrome_devtools_remote") }
    }.getOrDefault(false)
}
```

Run on:
- Settings page first composition
- After user taps Re-check
- (Optional) DEBOUNCED on toggle flip — but keep simple: just on first compose + manual retry

## CTA: opening chrome://flags

Tried in order; fall back if the prior fails:

1. **External Intent**:
   ```kotlin
   Intent(Intent.ACTION_VIEW, Uri.parse("chrome://flags/#enable-command-line-on-non-rooted-devices"))
       .setPackage("com.android.chrome")
   ```
   Chrome typically blocks `chrome://` from external intents for security. Will likely fail with `ActivityNotFoundException` or be silently dropped. Try first because it's free.

2. **Shizuku-mediated `am start`**: bypass external-intent restriction.
   ```bash
   am start -d "chrome://flags/#enable-command-line-on-non-rooted-devices" \
            -n com.android.chrome/com.google.android.apps.chrome.Main
   ```
   Run via `ChromeDevtoolsUserService.exec()`. The `am` shell command runs as shell uid, so external-intent blocks don't apply.

3. **Clipboard fallback**: copy URL to clipboard + Toast "URL copied — paste in Chrome's address bar". Only if both above fail.

## Auto-actions on toggle flip

When the user flips browser_script ON, ClosePaw should also (silently, no UI):
1. Write `/data/local/tmp/chrome-command-line` with `_ --remote-debugging-socket-name=chrome_devtools_remote --enable-features=NetworkService` via Shizuku shell. Idempotent — overwrite is harmless.
2. Don't force-stop Chrome. Let user decide when to restart it (after they toggle the flag).

When user flips browser_script OFF: leave the cmdline file alone (it's a no-op for Chrome unless the flag is on, and overwrites by us are not causing user harm).

## Files affected (estimate)

New:
- `app/src/main/kotlin/ai/closepaw/ui/settings/ToolsSection.kt` — section + ToolToggleRow + BrowserCdpStatusRow composables
- `app/src/main/kotlin/ai/closepaw/browser/setup/ChromeCdpProbe.kt` — detection helper, depends on ChromeDevtoolsUserService
- `app/src/main/kotlin/ai/closepaw/browser/setup/ChromeFlagDeepLink.kt` — Intent + Shizuku-am fallback + clipboard fallback

Changed:
- `app/src/main/kotlin/ai/closepaw/ui/settings/AgentBehaviorSettingsPage.kt` — add Tools section
- `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsModels.kt` — wire toolToggle state + cdpStatus state
- `app/src/main/kotlin/ai/closepaw/SessionViewModel.kt` (or whichever VM owns settings state) — load/save browser_script toggle, expose cdpStatus flow
- `app/src/main/kotlin/ai/closepaw/agent/definition/StandaloneAgentDef.kt` (or wherever the tool allowlist lives) — gate browser_script tool inclusion on the user toggle

Not touched:
- Onboarding flow
- Any code under `app/src/main/kotlin/ai/closepaw/browser/cdp/` (the runtime is fine; this is purely UX)
- Any code under `app/src/main/kotlin/ai/closepaw/browser/script/`
- ShizukuChromeDevtoolsBridge cascade (still works the same)

## Out of scope (deliberately)

- **Auto chrome unlock via root path** — won't ship root-only feature
- **Auto Chrome Beta install detection / installer** — too pushy; if the user has Beta they're already covered
- **Per-Chrome-variant detection** (com.android.chrome vs com.chrome.beta etc.) — start with stable; future task can broaden
- **Webview target option** — Phase 7+ if we ever expose ClosePaw's hidden WebView as a CDP target

## Acceptance criteria (when this gets dispatched)

- New Tools section visible in AgentBehavior settings
- browser_script toggle wired to user pref (persisted, default off so we don't surprise users)
- When toggle ON: status row appears within 500ms (after Shizuku bind)
- Status row shows ✓ when `chrome_devtools_remote` is in `/proc/net/unix`, ✗ otherwise
- When ✗: CTA opens chrome://flags page (any of the 3 fallback methods works)
- Re-check button refreshes status without leaving the page
- Toggle ON automatically writes /data/local/tmp/chrome-command-line via Shizuku
- After user toggles the chrome flag + restarts chrome, Re-check shows ✓
- Toggle OFF removes browser_script from the agent's tool allowlist
- ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lint passes
- UI screenshots captured for the 3 states (toggle off / toggle on + ✓ / toggle on + ✗ + CTA)

## Estimate

m (4-6h hands-on). Most of the surface is composable plumbing + state plumbing; detection + CTA are small.

## Open questions for the implementer

- Where does the tool allowlist live? StandaloneAgentDef? RoleDef? Need to find the existing wiring before adding the toggle.
- How is ShizukuChromeDevtoolsBridge.userServiceProvider exposed for ad-hoc shell exec? May need a thin adapter on top.
- Persistence: SharedPreferences or DataStore? Whatever existing settings use.

---

## Future iterations (not this task)

- After this lands: "Browser status" Settings sub-page with deeper diagnostics (which transport is currently in use, last error if any, link to debug-output run dir)
- Telemetry: count how often each cascade leg fires, which devices are stuck on USER_SERVICE-fail / WIRELESS_ADB_SELF_PAIR-success
- If Chrome variant detection ever lands: auto-pick the right package + cmdline path
