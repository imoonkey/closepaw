# settings_platform_mode — QA Report

Date: 2026-04-19
Device: EP0110MZ0BC101266W (1264x2800)
Branch: main, build: assembleDebug

## PART A — Instrumented tests (`DisplayModeSettingsTest`)

`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.closepaw.qa.DisplayModeSettingsTest` → **3/3 passed**.

| # | Test | Result |
|---|------|--------|
| a | `selector_toggle_invokes_callback_with_target_mode` — taps Virtual Display (Ready) and Accessibility, asserts callback fires with the matching `PlatformMode`. | PASS |
| b | `virtual_display_option_is_noop_when_shizuku_unavailable` — Status=Unavailable, taps VD, asserts callback count remains 0; "Shizuku not running" rendered. | PASS |
| c | `home_subtitle_reflects_effective_platform_mode` — `null` → no chip; `VIRTUAL_DISPLAY` → ` · VD`; `ACCESSIBILITY` → ` · A11y`. | PASS |

Refactor: extracted a stateless `DisplayModeSection(... status: ShizukuStatus, ...)` overload so tests inject status without touching real Shizuku. Behavior-preserving — production overload retains old signature.

Build & lint: `./gradlew :app:assembleDebug :app:compileDebugAndroidTestKotlin` → SUCCESS. `./gradlew :app:lintDebug` → SUCCESS, no new errors.

## PART B — UX visual debug

Install: `./scripts/setup.sh` (a11y + overlay restored). Onboarding bypassed via eval intent (`--ez fresh_session true --es goal noop`).

| # | Step | Screenshot | Result |
|---|------|------------|--------|
| 1 | Settings home renders | `/tmp/spm-qa-spm-5b-settings-home.png` | PASS — three rows, Permissions subtitle `All granted · Debug off` (no chip, see step 7). |
| 2 | Open Permissions & Advanced | `/tmp/spm-qa-spm-6-perms-page.png` | PASS — page rendered with proper header. |
| 3 | Display Mode section between Permissions and Debug | `/tmp/spm-qa-spm-6-perms-page.png` | PASS — order is Permissions → Display Mode → Debug → Data & Storage. |
| 4 | Selector defaults to Accessibility | `/tmp/spm-qa-spm-6-perms-page.png` | PASS — Accessibility shown selected (filled), VD dimmed. |
| 5 | Shizuku status row | `/tmp/spm-qa-spm-6-perms-page.png` | PASS — device has Shizuku installed, no permission → row reads "Shizuku running, permission needed" with "Grant" button. |
| 6 | Tap VD when status ≠ Ready is no-op | `/tmp/spm-qa-spm-7-vd-tap.png` | PASS — UI unchanged after tap (VD remained disabled, no toast/error, Accessibility stays selected). |
| 7 | Home subtitle has no mode chip before any session | `/tmp/spm-qa-spm-5b-settings-home.png` | PASS — `Permissions & Advanced` subtitle is `All granted · Debug off`, no ` · VD` / ` · A11y` suffix. |
| 8 | Screenshots captured | `/tmp/spm-qa-spm-*.png` (10 files) | PASS |
| 9 | Intent path toggles platformMode | `/tmp/spm-qa-spm-11-perms-vd.png` | PASS — `am start … --es platform_mode VIRTUAL_DISPLAY` delivered to running activity; logcat confirms `MainActivity: Platform mode set from intent: VIRTUAL_DISPLAY`. Permissions page shows VD now selected (still disabled since Shizuku NeedsPermission, displayed with primaryContainer alpha 0.5). |

Notes:
- Effective-mode chip on home subtitle only appears once an agent session runs (drives `effectivePlatformMode` non-null). Persisting via intent does not by itself populate `effectivePlatformMode`; this matches the intended split between `persistedMode` and `effectiveMode`.

## Summary

QA PASS

## PART C — Real-device follow-up QA (2026-04-19)

Device: EP0110MZ0BC101266W. Build: `scripts/setup.sh` (assembleDebug, install, a11y enabled). Bypass: `--ez fresh_session true --es goal noop`. Initial Shizuku state on device: **Ready**.

| # | Scenario | Result | Evidence |
|---|----------|--------|----------|
| A | Baseline at Permissions & Advanced. Shizuku status row shows "Shizuku ready"; selector defaults to Accessibility (darker bg, VD lighter). | PASS | `/tmp/spm-rqa-A-baseline.png` |
| B1 | **User question**: With Shizuku=Ready, tap **Virtual Display** → selector flips, `agent_prefs.platform_mode = VIRTUAL_DISPLAY`. | PASS | `/tmp/spm-rqa-B-vd2.png`; prefs dump confirms `VIRTUAL_DISPLAY`. |
| B2 | **User question**: With Shizuku=Ready, tap **Accessibility** → selector flips back, `agent_prefs.platform_mode = ACCESSIBILITY`. **Confirms user CAN opt out of VD even when Shizuku is connected.** | PASS | `/tmp/spm-rqa-B-a11y.png`; prefs dump confirms `ACCESSIBILITY`. |
| C | Shizuku=NeedsPermission scenario. | N/A | Device Shizuku is Ready; cannot synthesize NeedsPermission state without uninstalling/restarting Shizuku. Covered by instrumented test PART A. |
| D | Shizuku=Unavailable scenario. | N/A | Same — covered by `virtual_display_option_is_noop_when_shizuku_unavailable` instrumented test. |
| E | Intent path: `--es platform_mode VIRTUAL_DISPLAY` updates persisted pref + emits MainActivity log. | PASS | `agent_prefs.platform_mode=VIRTUAL_DISPLAY` post-intent; logcat: `MainActivity: Platform mode set from intent: VIRTUAL_DISPLAY`. |
| F | Home Settings subtitle includes effective-mode chip after a session has run (` · A11y` observed; mechanism verified). VD-chip variant covered by instrumented test `home_subtitle_reflects_effective_platform_mode`. | PASS | `/tmp/spm-rqa-settings.png` shows `Permissions & Advanced` row subtitle = `All granted · Debug on · A11y`. |

**Direct answer to user's question:** YES. When Shizuku is connected/Ready, tapping the Accessibility tile in the selector immediately switches `platform_mode` back to `ACCESSIBILITY` and persists it (verified via SharedPreferences dump). The user is never forced into Virtual Display.

REAL QA DONE

## Bug repro — in-app capsule missing in VD mode

Date: 2026-04-19, device EP0110MZ0BC101266W (1264x2800), build assembleDebug, Shizuku Ready, a11y service `ai.closepaw/.app.AgentService` enabled (re-enabled with `scripts/setup.sh` after force-stop wiped it).

### Setup
- `agent_prefs.platform_mode = VIRTUAL_DISPLAY` (set via `--es platform_mode VIRTUAL_DISPLAY`).
- Eval intent: `am start -n ai.closepaw/.app.MainActivity --ez fresh_session true --es goal "<goal>" --es platform_mode VIRTUAL_DISPLAY --es llm_backend openai --es agent_mode basic --es perception_mode accessibility_only --ez auto_start true --ez debug_mode true`.
- Confirmed VD path: `dumpsys activity activities` reports two `topResumedActivity` entries → real display 0 = `ai.closepaw/.app.MainActivity`, secondary display = `com.android.launcher3/.secondarydisplay.SecondaryDisplayLauncher` (the virtual display the agent drives).

### H4 — Foreground activity (user on ChatScreen?) → DISPROVED
Real-display top activity during a VD session is `ai.closepaw/.app.MainActivity` (ChatScreen). The system Settings/Accessibility activity that earlier showed up was the agent's a11y prerequisite prompt, not part of the running-session state. Once a11y was enabled and the session started, the user remains on ChatScreen on display 0. Evidence: `/tmp/spm-bug-vd-running.png`, `/tmp/spm-bug-vd-r-3.png`, `/tmp/spm-bug-vd-iter-3.png` — all show ClosePaw chat header + chat bubbles + chat input on the real screen.

### H6 — Bottom strip clipped by IME / window-insets → DISPROVED
Bottom strip (y≈2467–2772) of the VD ChatScreen contains the chat input box and Send arrow only — no IME open, no missing pixels above the input. There is simply no SmartCapsule node above the input. UI dump (`/tmp/u-3.xml`, `/tmp/uvd-3.xml`):
- `grep -ciE 'capsule|smartcapsule' …` → **0** matches in any captured dump.
- The only descendants of the chat root in the bottom strip are `EditText` ("What can I help you with?"), `Send →` button, and the chat-bubble column above. No `SmartCapsule*` semantics node, no zero-bounds capsule shell — the composable resolves to empty, not clipped.

### H5 — `CapsuleStateHolder._mode` reset to Hidden → CONFIRMED (most likely)
`AgentSession` reaches `Running`: logcat shows `D AgentSession: Received Op: Shutdown (current state: Running)` at session end (proves the session was in Running). Despite this, in **every** VD-mode dump captured (Wait task, Calculator task, multi-iter snapshots), the SmartCapsule UI node is absent. Combined with H6 above (the layout slot exists, nothing's clipping it), this matches "the in-app `CapsuleStateHolder` consumed by `SmartCapsuleCompose(context = MAIN_APP)` is in Hidden state while the agent's session-side state is Running" — i.e. the Running event reaches the overlay/service path but the in-app holder used by ChatScreen never sees `setMode(Running)`. Direct CapsuleStateHolder transition logs are not emitted (no `CapsuleStateHolder` / `setMode` lines anywhere in logcat), so the precise transition cannot be reconstructed from logs alone — but the UI evidence is unambiguous.

Note: no `setMode(Hidden)` lines appear either, consistent with the in-app holder simply never being driven (default Hidden) rather than being driven Running and then reset.

### Comparison shots
- VD running with chat foreground (no capsule): `/tmp/spm-bug-vd-running.png`, `/tmp/spm-bug-vd-r-{4,7,10}.png`, `/tmp/spm-bug-vd-iter-{1..5}.png`.
- VD with privacy-gate sheet (running, awaiting approval; capsule slot occupied by sheet): `/tmp/spm-bug-vd-running3.png`.
- ACCESSIBILITY mode pre-a11y-grant (a11y prompt, not a capsule comparison): `/tmp/spm-bug-baseline-a11y.png`. (A clean a11y-mode baseline-with-capsule was not captured because, in a11y mode, the agent immediately drives the real screen away from chat — overlay capsule, not in-app capsule, is the relevant surface there. The in-app capsule path under test is VD-specific.)

### Conclusion
- H4: DISPROVED — user is on ChatScreen during a VD session.
- H6: DISPROVED — input box renders cleanly at the bottom; capsule slot is simply empty (no node).
- H5: CONFIRMED — UI dumps contain no SmartCapsule node while `AgentSession` is Running, indicating the in-app `CapsuleStateHolder` driving `SmartCapsuleCompose(context = MAIN_APP)` never enters Running for VD sessions.

**BUG ROOT CAUSE:** In VD-mode sessions the in-app `CapsuleStateHolder` consumed by `ChatScreen`'s `SmartCapsuleCompose(MAIN_APP)` never receives `setMode(Running)` — the Running transition reaches the overlay/service-side holder only, leaving the chat-screen capsule rendering its empty Hidden state for the entire session.

---

## Root cause confirmation (2026-04-19, instrumented re-test)

**Verdict: NEITHER LEAD A NOR LEAD B. The originally-reported "consumed mode is Hidden during VD Running" symptom does not reproduce on current HEAD (`f4a699b9`).**

### Method
Added two temporary `Log.d("CSH", ...)` lines (now reverted, see `git diff` — clean):
- `CapsuleStateHolder.setMode()` logs `setMode(<mode>) holder=<hashCode>`
- `ChatScreen` (line 84) logs `ChatScreen reads holder=<hashCode> svc=<svcHash>`

Built `:app:assembleDebug`, ran `scripts/setup.sh`, then in VD platform mode (`agent_prefs.platform_mode=VIRTUAL_DISPLAY`) launched a multi-step task via `scripts/debug-run.sh --virtual-display "Tap home then open Settings then open Calculator"` and brought `MainActivity` (ChatScreen) to the foreground while the agent was still Running. Captured logcat + screenshots.

### Logcat (filter `CSH`)
```
12:50:32.507 D CSH: ChatScreen reads holder=81636366 svc=251129604
12:50:33.282 D CSH: setMode(Running(thought=Open Settings)) holder=81636366 thread=main
12:50:37.257 D CSH: setMode(Running(thought=Open the requested app directly.)) holder=81636366 thread=main
12:50:42.407 D CSH: setMode(Running(thought=Complete (success): Settings is open.)) holder=81636366 thread=main
12:50:42.649 D CSH: setMode(Done(message=Settings is open.)) holder=81636366 thread=main
12:50:43.409 D CSH: setMode(Hidden) holder=81636366 thread=main
```
Second run (multi-step VD task, ChatScreen kept in foreground):
```
12:52:29.028 D CSH: setMode(Running(thought=Tap home then open Settings then open Ca...)) holder=81636366
12:52:32.225 D CSH: setMode(Running(thought=Go to the home screen as requested befor...)) holder=81636366
12:52:36.437 D CSH: setMode(Running(thought=Open Settings as requested.)) holder=81636366
```

### Cross-check against the two leads
- **LEAD B (two `CapsuleStateHolder` instances): DISPROVED.** `ChatScreen reads holder=81636366` and every `setMode(...)` fires on `holder=81636366` — bit-identical hashCodes throughout the VD session. There is exactly one holder, constructed once in `ServiceOverlayController.<init>` (`ServiceOverlayController.kt:59`) and exposed via `AgentService.capsuleStateHolder` getter (`AgentService.kt:80`) which simply returns `overlayController?.stateHolder`. No reassignment, no platform-mode-conditional construction.
- **LEAD A (singleton timing leaves ChatScreen pinned to `fallbackMode`): DISPROVED in this run.** At first composition, `AgentService.instance` was already non-null (svc hash logged), so `(stateHolder?.mode ?: fallbackMode)` resolved to the real `holder.mode` flow on the first read; `collectAsStateWithLifecycle` bound to it directly and observed every `setMode(Running)` emission. The theoretical race still exists — if the very first composition of ChatScreen happens before `AgentService.onServiceConnected()` runs, `stateHolder` would be null and the fallback Hidden flow would be collected — but it requires no subsequent state-driven recomposition for the rest of the session, which is unlikely in practice (any `viewModel.uiState` / `messages` / `pendingInput` change re-evaluates line 84).

### UI evidence
Screenshot `/tmp/csh2.png` (taken with `MainActivity` foreground while agent Running, VD mode) shows the in-app SmartCapsule rendering thought "Go to the home screen as requested..." with **Takeover / Stop / viewer** buttons above the input — i.e. `mode=Running` is reaching `SmartCapsuleCompose(MAIN_APP)` and rendering correctly. The earlier qa_report H5 conclusion ("UI dumps contain no SmartCapsule node") no longer holds on `f4a699b9`.

### Specific code lines
- Single source of truth: `app/src/main/kotlin/ai/closepaw/app/ServiceOverlayController.kt:59` — `val stateHolder = CapsuleStateHolder(scope)`.
- Single accessor: `app/src/main/kotlin/ai/closepaw/app/AgentService.kt:80` — `val capsuleStateHolder get() = overlayController?.stateHolder`.
- Reader: `app/src/main/kotlin/ai/closepaw/ui/chat/ChatScreen.kt:84,88` — verified to bind to the same instance the producer mutates.

### Proposed minimal fix
**No production fix required for "Hidden during Running"** — current build already drives the in-app capsule into Running and ChatScreen renders it. If we still want to harden against the residual LEAD A first-composition race (svc null at first read, no subsequent VM-state change), the smallest change is to make `AgentService.instance` Compose-observable (e.g. expose a `StateFlow<AgentService?>` or use `produceState` keyed on a service-presence flow) so ChatScreen automatically re-binds when the accessibility service connects after first composition; alternatively, hoist the `CapsuleStateHolder` out of the service into a process-scope holder injected via the application/DI graph so the reader never sees a null.

ROOT CAUSE CONFIRMED
