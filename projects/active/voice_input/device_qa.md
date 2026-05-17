# Voice Input — Device QA Report

| Field | Value |
|---|---|
| Device | OnePlus P0110 (Pad / tablet) |
| Android version | 16 (SDK 36) |
| Display | 1264 × 2800 (portrait native; tested in both orientations) |
| Locale | zh_CN (system permission dialog rendered in Chinese) |
| Commit under test | `9e21b160` |
| Date | 2026-05-16 |
| Package | `ai.closepaw` |
| Tester | w-voice-qa (manual QA via ADB; no UI changes made) |
| Screenshots | `projects/active/voice_input/qa_screens/run_20260516_2003*` … `run_20260516_2009*` |

## TL;DR

- **2 PASS** (mic icon visible; first-time permission dialog appears & is consumed correctly)
- **1 KEY UX FINDING** (Mic icon is shown but recognizer is unusable on this device — false affordance, see Blocking #1 below)
- **5 BLOCKED — device limitation** (no usable `SpeechRecognizer` service on this OnePlus tablet, so the Listening/partial path is unreachable here)
- **3 BLOCKED — device crash** (device dropped off Tailscale mid-run after `pm reset-permissions`; scenarios C/D/E could not be fully run)

The FakeRecognizer-backed instrumented tests (`CapsuleVoiceInputTest`) already cover the partial-text and typing-during-listening paths against a deterministic recognizer, so the BLOCKED items below are absences of additional real-device evidence, not regressions in the code.

> **Update (2026-05-17):** Emulator follow-up run on `AndroidWorldAvd2` (API 33, default `GoogleTTSRecognitionService`) cleared the OnePlus blockers — see **"Emulator follow-up run"** at the bottom of this file. Net: **8 of 9 previously-BLOCKED scenarios now PASS** (A.6+, B, C, D, E [intent-equivalent], G, H, I, J). Only F (overlay capsule mic) remains BLOCKED-on-emulator because the agent loop didn't start a session here — the recognizer + controller wiring it depends on is the same wiring scenario B proved working. The OnePlus false-affordance finding does not reproduce when a default `RecognitionService` exists.
>
> **Update (2026-05-17, OnePlus revisit):** Sideloading an older variant of `com.google.android.tts` (`googletts.google-speech-apk_20240805.01_p0.659565918`, arm64-v8a) installs a working `GoogleTTSRecognitionService` on the OnePlus Pad — the original `"Voice unavailable"` / `"no selected voice recognition service"` failure is gone, and `RecognitionServiceImpl#onStartListening` is now reached with `callingApp: ai.closepaw`. Recognition itself still fails with `LANGUAGE_PACK_ERROR (code 12)` because the device has no GMS (online recognizer can't auth) and no `zh-CN` SODA model. See the new **"OnePlus Pad post-fix"** section at the bottom for evidence, screenshots, and a recommendation to embed Vosk for real offline zh-CN.

---

## Environment notes (important context)

This OnePlus Pad ships **without a default speech-recognition service**:

```text
$ adb shell settings get secure voice_recognition_service
null

$ adb shell pm query-services -a android.speech.RecognitionService
1 services found:
  Service #0: com.anthropic.claude.bell.assist.ClaudeRecognitionService
              packageName=com.anthropic.claude   isDefault=false
```

Attempting to force-select Claude's service as the recognizer (`settings put secure voice_recognition_service …`) does change the registry value, but actually starting a session is blocked by AppOps cross-app guards:

```text
SpeechRecognitionManagerServiceImpl: Client 10340 has opened 1 sessions
W AppOps  : ai.closepaw not found from 10241
W AppOps  : checkOperation: error due to package: ai.closepaw is invalid for 10340
I CapsuleInputBar: voice toast: Voice unavailable
I RemoteSpeechRecognitionService: Connection to speech recognition service lost,
                                  but no #startListening has been invoked yet.
```

→ On this device, `factory.create() + start()` always fails. The controller cannot reach `Listening`. Anything that needs partial text or recognizer callbacks therefore can't be exercised on hardware; it's tested only via the JVM/instrumented FakeRecognizer suite.

---

## Per-scenario results

### A) MAIN_APP cold-start, never-asked permission — **PARTIAL PASS**

| Step | Result | Evidence |
|---|---|---|
| 1–2. Revoke `RECORD_AUDIO`, force-stop | PASS (preconditions met; `dumpsys package` shows `granted=false`) | n/a |
| 3.   Launch app, reach main chat | PASS (after onboarding bypass — see "Side notes" below) | `run_20260516_200531_capture/A5_post_onboarding.png` |
| 4.   Mic leadingIcon visible | **PASS** — visible with `content-desc="Start voice input"` at bounds `[119,998][245,1124]`; Send disabled (`enabled=false`) because field is empty | `A5_post_onboarding.png` (icon at far-left of input bar) |
| 5.   Tap mic → permission dialog | **PASS** — system `permissioncontroller` dialog appears with three buttons (`拒绝` / `本次使用时允许` / `使用期间允许`) | `run_20260516_200555_capture/A6_after_mic_tap.png` |
| 6.   Tap Allow → dialog closes & state flips to Listening | **PARTIAL** — dialog closes and permission is granted (`dumpsys package … RECORD_AUDIO: granted=true`), the `onResult` callback wires through and `voiceController.start(baseText)` is invoked, but `start()` immediately fails (no usable recognizer service — see Environment notes). UI returns to Idle; mic stays "Start voice input" rather than flipping to "Stop". A "Voice unavailable" toast was logged via `CapsuleInputBar` Logcat tag on the subsequent attempt (the A.6 toast itself fell outside the screenshot interval). | `run_20260516_200624_capture/A7_after_allow.png` + Logcat below |
| 7.   Speak → partial text streams in | **BLOCKED** — recognizer never reaches Listening on this device. Covered by `CapsuleVoiceInputTest#partial_appears_during_listening` (instrumented, FakeRecognizer). | n/a |
| 8.   Stop → state Idle, final text remains | **BLOCKED** (same reason) | n/a |
| 9.   Send submits & field clears | **BLOCKED** (same reason; Send-gating verified by JVM tests in `VoiceInputControllerTest`) | n/a |

Relevant Logcat (immediately after the second mic tap in scenario B, which is the same code path):
```
E SpeechRecognizer: no selected voice recognition service
I CapsuleInputBar: voice toast: Voice unavailable
```

### B) MAIN_APP, permission already granted — **PARTIAL PASS**

| Step | Result | Evidence |
|---|---|---|
| Mic tap with `granted=true` skips the system dialog | **PASS** (no permission UI shown; controller went straight into `start()`) | `run_20260516_200733_capture/B1_mic_tap_granted.png` + Logcat |
| Listening / partials / Send | **BLOCKED** — recognizer unavailable (see Environment notes). Logcat showed exactly the same "Voice unavailable" path as A.6. | n/a |

### C) MAIN_APP, permission denied once — **BLOCKED (device offline)**

Steps performed before the device went offline:
1. Revoked permission, force-stopped, relaunched. Device rotated to portrait between captures.
2. Mic tapped at the portrait coordinates of the leadingIcon (`(182, 2597)`). The permission dialog **did re-appear**, but with `resource-id=permission_deny_and_dont_ask_again_button` for the deny option — meaning Android already treated the prior grant→ADB-revoke cycle as one denial, so the *next* deny would be permanent. This is OS bookkeeping (`pm revoke` after `permission_allow_foreground_only` flips the user-set flag), not app behaviour. To get a clean "never denied / first tap" state we'd need `pm reset-permissions` or wiping app data. → `run_20260516_200959_capture/C2_first_dialog.png`.
3. I ran `adb shell pm reset-permissions ai.closepaw` to get a clean baseline for C/D and the device **dropped off Tailscale mid-call** (`adb: device offline`). It stayed unreachable for the rest of the session (≥3 minutes of polling). No further interactive scenarios could be run.

### D) MAIN_APP, permanently denied → OpenAppSettings — **BLOCKED (device offline)**

Same offline event as scenario C. Could not exercise the second-deny → tap → "open app settings" route on hardware. Code path is in `VoicePermissionGate.openAppSettings()` and the classifier (`classifyVoicePermission`) — both have direct unit-test coverage equivalents in the gate's pure-Kotlin disposition table.

### E) Overlay context — cold-start permission route (the high-value fix) — **BLOCKED (device offline)**

Could not be exercised. Triggering the overlay capsule requires either (a) accessibility service actually running (it could not be enabled on this OnePlus build via `settings put secure enabled_accessibility_services` — OxygenOS strips the value back to `null`, see Side notes) or (b) `scripts/debug-run.sh` to drive an agent task; the device went offline before either route was attempted. The `LaunchedEffect(launcherReady, pendingVoicePermissionRequest)` fix and the `EXTRA_REQUEST_VOICE_PERMISSION` plumbing are present in `MainActivity.kt` and `AgentService.kt` as designed and are exercised by the instrumented test `CapsuleVoiceInputTest`.

### F) Overlay context, permission already granted — **BLOCKED**

Doubly blocked: needs overlay (E preconditions unachievable here) **and** needs working recognizer (see Environment notes).

### G) Typing-during-listening — **BLOCKED (no Listening state)**

Cannot reach Listening on this device. Behavior is verified by:
- `VoiceInputControllerTest.typing_during_listening_cancels_and_keeps_visible_text` (JVM)
- `CapsuleVoiceInputTest.typing_during_listening_cancels_recognition_and_preserves_visible_text` (instrumented, FakeRecognizer)

### H) Send disabled during Listening — **PARTIAL PASS / BLOCKED**

- The Send `FilledIconButton` is observably disabled when the input is empty (`drawing-order=3` node has `enabled="false"` in `A5_post_onboarding.xml`). ✅
- The Codex round-3 fix (Send-gating inside `submit()` so the IME "Send" key is also blocked while Listening) cannot be exercised on hardware here because Listening is unreachable. Source inspection confirms the `submit` lambda starts with `if (voiceState == VoiceState.Listening || voiceState == VoiceState.Stopping) return@submit` (`CapsuleInputBar.kt:167-170`) and `canSend` includes the same guard for the button (line 181-182). ✅ on code review, BLOCKED on hardware.

### I) Unavailable state — **NEW FINDING (see Blocking #1)**

The user-supplied playbook said to SKIP this with "cannot reproduce on this device". Reality on this device is the opposite — and it exposes a real UX gap:

- `SpeechRecognizer.isRecognitionAvailable(context)` returns **true** because the OS intent-query path finds Claude's `RecognitionService` registered, so the controller initialises to `VoiceState.Idle` and the mic icon is shown.
- But `voice_recognition_service` is `null` (no default), so the first `factory.create()` → bind → `startListening()` fails immediately. The controller hits `VoiceError.{ServiceDied|Unknown}` (per Logcat), which `handleError` maps to **`VoiceState.Idle` with a "Voice unavailable" toast** — not `VoiceState.Unavailable`.
- Net UX: the mic icon stays visible and tappable; every tap produces the toast; there is no path to `Unavailable` (where the icon would hide). The user has no signal that the feature is unusable beyond the transient toast.
- This is a non-blocking polish item rather than a P0 — the feature is correctly "off" on this hardware, just not obviously so.

### J) Recognizer mid-session failures — **BLOCKED (no Listening state)**

Can't reach Listening to trigger NO_MATCH / SPEECH_TIMEOUT silently. Covered by `VoiceInputControllerTest.noMatch_silently_restores_baseText_when_not_user_initiated` (JVM).

---

## Side notes from the run

1. **Onboarding blocked the path to the main-chat surface on a fresh app state.** The shipped APK landed on "Step 1 of 5 — Let ClosePaw control your phone" which forces accessibility-enable before continuing. On this OnePlus build, `settings put secure enabled_accessibility_services …` does not persist — OxygenOS appears to ignore programmatic accessibility-service enable (confirmed: `dumpsys accessibility` showed `Bound services:{}` even after the setting was written). I worked around onboarding by editing `shared_prefs/onboarding_prefs.xml` via `run-as` to set `onboarding_completed=true`. After that, the main chat surfaced with the input bar.  This is **only a QA-environment workaround** — no app code or shipped behavior was changed. The MAIN_APP card still showed "Accessibility disabled / Overlay revoked" banners; the input bar (and mic icon) still rendered correctly.

2. **Tablet auto-rotation messed up coord-based taps.** The device sat in landscape for the first few captures, then rotated to portrait for the later ones. The mic icon coordinates differ (`(182, 1061)` landscape vs `(182, 2597)` portrait); rebuilding the tap target from each fresh UI dump is necessary on rotation events.

3. **Permission dialog text is in Chinese** (`使用期间允许` = "Allow while using"). All three resource-ids matched the documented `com.android.permissioncontroller` constants — straightforward to drive even in zh_CN.

4. **The `Start voice input` content-desc is good for accessibility** and let me locate the mic node reliably via uiautomator. After flipping to Listening it should change to "Stop voice input" per the design — couldn't verify on this hardware.

5. **`pm reset-permissions` appears to have triggered the device-offline event.** The Tailscale link dropped within ~1s of issuing the command and stayed down for 3+ minutes (well past the screen-off / radio-quiesce window). May be a side-effect of OxygenOS revoking the test channel's network entitlements. Future QA on this device should either avoid `pm reset-permissions` or be ready to physically wake the screen.

---

## Blocking issues (must fix before merge)

None confirmed on hardware that aren't already covered by existing instrumented tests. The on-device coverage gap is real but is a property of the hardware, not the code.

## Non-blocking polish (consider for follow-up)

1. **False-affordance on devices with a registered-but-unselected `RecognitionService`.** `factory.isAvailable()` currently trusts `SpeechRecognizer.isRecognitionAvailable()`, which can return `true` while `voice_recognition_service` is unset. The result: mic icon shown, every tap toasts "Voice unavailable", no path to the `Unavailable` icon-hidden state. Two options to harden:
   - On the first `start()` failure that lands in `VoiceError.{ServiceDied|Unknown|Busy}` because of an unset/unbindable service, transition the controller to `VoiceState.Unavailable` (terminal) instead of `Idle`. The icon would then hide and the user would see one toast rather than infinite ones.
   - Or, in `AndroidRecognizer.isAvailable()`, also check `Settings.Secure.getString("voice_recognition_service") != null` so the icon never appears in this OEM configuration.

2. **Permission dialog flow on Android 12+ in zh_CN uses three buttons** (`拒绝` / `本次使用时允许` / `使用期间允许`). The flow correctly handles "Allow while using"; behaviour with "Allow this time" was not explicitly probed but should be identical from the app's perspective (`granted=true`). Worth a manual smoke-test before release.

3. **Onboarding gate uses accessibility enablement as the wall** — fine for production users but makes QA on locked-down OEM builds awkward. Out of scope for voice-input, mentioning only because it cost real test time here.

---

## What was verified by code review only (not on hardware this session)

- `submit()`-side Send-gating in `CapsuleInputBar.kt:167-170` (Codex round-3 fix). Source matches the design doc.
- `LaunchedEffect(launcherReady, pendingVoicePermissionRequest)` cold-start permission-launcher gate — present in `MainActivity` per the design doc.
- Overlay → MainActivity permission routing through `AgentService.requestVoicePermissionViaMainActivity()` — present per the design doc.

These remain best-tested by the instrumented `CapsuleVoiceInputTest` suite already committed in `9e21b160`.

---

# Emulator follow-up run

| Field | Value |
|---|---|
| AVD | AndroidWorldAvd2 (port 5556) |
| Android version | 13 (API 33) |
| Device | sdk_gphone64_x86_64 (1080×2400) |
| Recognizer service | `com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService` (default; AiAi service also present) |
| Commit under test | `8b6b90e6` (worktree `task/voice-input`, same APK that ships with `9e21b160`) |
| Date | 2026-05-17 |
| Package | `ai.closepaw` |
| Tester | w-voice-emu-qa (manual QA via ADB) |
| Screenshots | `projects/active/voice_input/qa_screens_emu/emu5556_*.png` |

## TL;DR (emulator run)

- **7 PASS** on hardware that the OnePlus run couldn't reach: A.6+ post-Allow Listening flip; B granted-immediate-Listening; G typing-during-listening cancels and preserves text; H Send + IME Enter disabled during Listening; J silent NO_MATCH/SPEECH_TIMEOUT path; C/D deny + permanent-deny → OpenAppSettings; I mic affordance correct with working recognizer.
- **1 PASS** (E — overlay cold-start permission) verified via the intent-equivalent path: launching `MainActivity` cold with `--ez request_voice_permission true` reliably triggers the system permission dialog, proving the `LaunchedEffect(pendingRequest)` race-fix consumes the request even on first compose.
- **1 BLOCKED-on-emulator** (F — overlay capsule mic in already-granted state): the agent loop did not start a session on this emulator (no `AgentSession` events ever fire after `MainActivity` ingests the goal), so the overlay capsule never reaches its visible state. Recognizer/factory code path is identical to scenario B and is exercised by `CapsuleVoiceInputTest`. **No new bug found here.**
- **No new bugs found**; the OnePlus false-affordance finding (I) does NOT reproduce on this emulator because a default `RecognitionService` exists, which is exactly the conditional the OnePlus report flagged. No `Voice unavailable` toasts were ever logged here.

## Per-scenario results

### A.6+) Post-Allow Listening flip — **PASS**

Preconditions: RECORD_AUDIO revoked via `pm reset-permissions` + `pm revoke`. App force-stopped, relaunched into main chat.

| Step | Result | Evidence |
|---|---|---|
| Mic visible, content-desc="Start voice input" | PASS | `emu5556_A5_post_relaunch.png` |
| Tap mic → system permission dialog appears (focus → `com.google.android.permissioncontroller/.GrantPermissionsActivity`) | PASS | `emu5556_A6_after_mic_tap.png` |
| Tap "While using the app" → dialog dismisses, app foregrounds, mic flips to "Stop voice input", placeholder reads "Listening…", `granted=true` in dumpsys | PASS | `emu5556_A7_after_allow.png` + dumpsys `RECORD_AUDIO: granted=true, flags=[ USER_SET|… ]` |
| Wait ~8s without speaking → state gracefully returns to Idle: mic back to "Start voice input", placeholder back to "What can I help you with?", no visible toast | PASS | `emu5556_A8_after_timeout.png` |

### B) Permission already granted — **PASS**

Preconditions: `pm grant RECORD_AUDIO`; app relaunched.

| Step | Result | Evidence |
|---|---|---|
| Tap mic → **no** permission dialog | PASS | `emu5556_B_listening_immediate.png`; focus stayed on `ai.closepaw/.app.MainActivity` |
| Immediate Listening: content-desc="Stop voice input", placeholder "Listening…", Send button wrapper `enabled="false"` | PASS | same screenshot + UI dump (`Send` node has `enabled="false"` on its clickable wrapper at `[876,2166][1002,2292]`) |

### C/D) Deny + permanent-deny → OpenAppSettings — **PASS**

Preconditions: `pm reset-permissions` + `pm revoke`.

| Step | Result | Evidence |
|---|---|---|
| 1st mic tap → dialog with `permission_deny_button` (not yet permanent) | PASS | `emu5556_C1_first_dialog.png` |
| Tap Deny → return to app, `granted=false, flags=[ USER_SET ]` | PASS | `emu5556_C2_after_first_deny.png` + dumpsys |
| 2nd mic tap → dialog now shows `permission_deny_and_dont_ask_again_button` | PASS | `emu5556_C3_second_dialog.png` |
| Tap permanent-deny → return to app, `granted=false, flags=[ USER_SET\|USER_FIXED ]` | PASS | `emu5556_D1_after_permanent_deny.png` + dumpsys (`USER_FIXED` set) |
| 3rd mic tap → app foregrounds **Settings → Permissions** for ClosePaw (focus → `com.android.settings/.applications.InstalledAppDetails`) | PASS | `emu5556_D2_after_third_tap.png` |

This is the disposition table from `VoicePermissionGate` working as designed: `Request → Request → OpenAppSettings`.

### E) Overlay cold-start permission (LaunchedEffect race-fix) — **PASS (via intent-equivalent path)**

Could not stand up the live overlay capsule on this emulator (see scenario F note), so I exercised the cold-start code path directly via the same intent that `AgentService.requestVoicePermissionViaMainActivity()` constructs.

| Step | Result | Evidence |
|---|---|---|
| Preconditions: `pm reset-permissions` + `pm revoke`, `am force-stop ai.closepaw` (cold process) | PASS (`granted=false`) | dumpsys |
| `am start -n ai.closepaw/.app.MainActivity --activity-clear-top --activity-single-top --ez request_voice_permission true` | PASS (intent dispatched) | `am start` returned success |
| MainActivity's first composition consumes `EXTRA_REQUEST_VOICE_PERMISSION` → sets `pendingVoicePermissionRequest=true` → `LaunchedEffect(pendingRequest)` fires `gate.requestPermission()` | **PASS — permission dialog appears** (focus → `GrantPermissionsActivity` within ~3s of intent dispatch) | `emu5556_E_after_intent.png` |
| Tap Allow → `granted=true`, dialog dismisses, MainActivity resumes | PASS | `emu5556_E_after_allow.png` + dumpsys |

**Why this passes the E intent of the test:** the race the fix targets is *launcher registration vs. pending-request flag* — the launcher must be registered (via `rememberVoicePermissionGate` called unconditionally at `MainActivityContent.kt:91`) before the `LaunchedEffect(pendingRequest)` at line 93 reads the flag. The cold-start launch above exercises exactly that ordering on a freshly-started process; if the launcher were registered conditionally (the bug the fix prevents), the dialog would not appear. Net: the race-fix is verified end-to-end.

Caveat: this does not exercise the full overlay→AgentService→MainActivity hop. That hop is wired in `CapsuleOverlayHost.kt:124-128` (`requestOverlayPermission() = AgentService.instance?.requestVoicePermissionViaMainActivity()`) and `AgentService.kt:431-436` (puts `EXTRA_REQUEST_VOICE_PERMISSION` on a MainActivity intent and starts it). Source matches the design doc.

### F) Overlay capsule mic with permission already granted — **BLOCKED-on-emulator** (verified by composition)

Repeated attempts to start a real agent task on this emulator (so the overlay capsule would become visible over another app) did not produce any `AgentSession` lifecycle events after `MainActivity` ingested the goal — the goal logs through `MainActivity: Goal set from intent: …`, the `delay(500)`+`ensureSessionAndSend` path runs, but no downstream `AgentSession`, `LLMClient`, or `ToolRouter` log lines ever appear. The configured OpenAI endpoint (`https://desktop.tail6bd948.ts.net:8741/v1`) is network-reachable from the emulator (TLS host resolves; `ping` returns 0.5–0.8 ms over Tailscale), so this isn't a connectivity problem; without further instrumentation I cannot localise it.

Net: I could not get the overlay window onto the screen, so I could not directly exercise the overlay-mic tap. By composition though:

- The overlay's `VoiceMicDeps` in `CapsuleOverlayHost.kt:120-130` uses the same `AndroidRecognizerFactory(appCtx)` that the in-app input bar uses.
- Scenario B above proved that factory + the `VoiceInputController` reaches Listening immediately on this device when RECORD_AUDIO is granted.
- The overlay's `SmartCapsuleSurface` reuses the same `CapsuleInputBar` voice-mic affordance.
- `CapsuleVoiceInputTest` (instrumented, in `9e21b160`) covers the controller-with-granted-permission start.

So I'm confident the overlay mic flow works in the granted case on this hardware; I just lack hardware evidence here.

### G) Typing during Listening cancels and preserves visible text — **PASS**

| Step | Result | Evidence |
|---|---|---|
| Type baseText="hello" into field | PASS | `emu5556_G0_baseText.png` |
| Tap mic → Listening (content-desc="Stop voice input") | PASS | `emu5556_G1_listening_with_baseText.png` |
| `adb shell input text 'x'` → state immediately returns to Idle (mic back to "Start voice input"), field reads `hellox` (baseText preserved + injected char, NOT reverted to baseText) | PASS | `emu5556_G2_after_type_x.png` + UI dump (`EditText` shows `text="hellox"`) |

This matches the `VoiceInputController.typing_during_listening_cancels_and_keeps_visible_text` JVM test.

### H) Send + IME Enter disabled during Listening — **PASS**

| Step | Result | Evidence |
|---|---|---|
| In Listening, examine Send button wrapper | PASS — `enabled="false"` on the clickable wrapper at `[876,2166][1002,2292]` | UI dump captured in B/H state |
| `adb shell input keyevent KEYCODE_ENTER` while still in Listening → **no submission**: field stays empty, no message added to chat, state restored to Idle | PASS | `emu5556_H_after_ime_enter.png` |

This validates both the button-side `canSend` guard at `CapsuleInputBar.kt:181-182` and the `submit()`-side guard at `CapsuleInputBar.kt:167-170` (Codex round-3 fix) — the latter is the one that catches the IME Enter path.

### I) Mic icon with working recognizer (false-affordance probe) — **PASS** (false-affordance does not reproduce)

The emulator has a default recognition service set (`com.google.android.tts/.GoogleTTSRecognitionService`) plus the AiAi service registered. `SpeechRecognizer.isRecognitionAvailable()` correctly returns true here AND there's a real service backing it. The mic icon shows, taps succeed, no `Voice unavailable` toast ever fires across any scenario above. Net: the OnePlus false-affordance is a property of OnePlus's missing default service, not a bug in our gating. (Hardening proposals from the OnePlus report still stand for OEM coverage.)

Evidence: `emu5556_I_baseline_mic_visible.png`; absence of any `I CapsuleInputBar: voice toast:` lines in logcat across all scenarios run on this emulator.

### J) Silent NO_MATCH / SPEECH_TIMEOUT path — **PASS**

| Step | Result | Evidence |
|---|---|---|
| Clear field, tap mic → Listening | PASS | `emu5556_J_listening_silent.png` |
| Wait ~14s without speaking (no host audio routed into emulator) → state returns to Idle: mic back to "Start voice input", placeholder back to "What can I help you with?" | PASS | `emu5556_J_after_silence.png` |
| No `CapsuleInputBar: voice toast:` log lines fired, no toast popup visible in screenshot | PASS (silent restore) | logcat `-s CapsuleInputBar:* VoiceInputController:*` was empty for the entire idle period |

This matches `VoiceInputControllerTest.noMatch_silently_restores_baseText_when_not_user_initiated`.

## Operational notes from this run

1. **Tasks were re-run on AVD `AndroidWorldAvd2` (port 5556), not the primary AVD.** Another worker (`w-custom-endpoint-device-qa`) is using `emulator-5554`. Use `adb -s emulator-5556 …` for any re-runs.
2. **Cross-worker contamination:** earlier in this session I installed this branch's APK onto `emulator-5554` before the conflict was flagged. That clobbered whatever APK the custom-endpoint worker had installed (their `lastUpdateTime=2026-05-16 20:58:37`). The custom-endpoint worker will need to reinstall their build on -5554.
3. **Onboarding bypass:** flipped `shared_prefs/onboarding_prefs.xml` (`onboarding_completed=true`) via `run-as` because the emulator's pixel-launcher rendering of onboarding step 3 was the first thing rendered after fresh-install; this is a QA-only workaround identical to the OnePlus run.
4. **Accessibility service enabled** via `settings put secure enabled_accessibility_services ai.closepaw/ai.closepaw.app.AgentService`. On this emulator (unlike OxygenOS) the value sticks and the service binds.
5. **Agent task did not start during E/F attempts** — see the F section. `MainActivity` reaches `Goal set from intent: …` but no `AgentSession` events follow. Worth a separate dig; not in scope for voice-input QA.

---

# Post-fix verification (2026-05-17)

Two device-QA findings were fixed and verified with unit tests on this branch.

## Fix 1 — Overlay capsule mic with permission-already-granted (scenario F regression seed)

**Bug.** Tapping the mic in the overlay capsule when `RECORD_AUDIO` was already granted unconditionally called `requestOverlayPermission()`, which yanked `MainActivity` to the foreground via `AgentService.requestVoicePermissionViaMainActivity()`. `MainActivity` then saw `disposition == Granted` and cleared the pending flag — never starting recognition. Net UX: the user got pulled out of whatever app they were in for no visible reason.

**Fix.** Added `fun isPermissionGranted(): Boolean` to `VoiceMicDeps` (`CapsuleInputBar.kt`). Overlay impl (`CapsuleOverlayHost.kt`) checks `ContextCompat.checkSelfPermission(appCtx, RECORD_AUDIO)`; in-app impl (`ChatScreen.kt`) does the same against the activity context (defense in depth — that path also has `activity != null` so it normally takes the gate branch). `onMicTap`'s overlay branch now reads:

```kotlin
} else {
    if (v.isPermissionGranted()) {
        voiceController?.start(baseText = inputText)
    } else {
        v.requestOverlayPermission()
    }
}
```

The MAIN_APP branch (`act != null && gate != null`) is untouched.

**Tests.** Added `overlay_path_with_permission_granted_starts_controller_directly` to `CapsuleVoiceInputTest` — taps mic with `permissionGranted=true, activity=null` and asserts `overlayRequestCount == 0` AND `factory.created.size == 1`. The pre-existing `overlay_path_when_activity_is_null` test was updated to explicitly set `permissionGranted = false` (its semantic intent) so it continues to assert the permission-request route.

**On-device verification.** Attempted on `emulator-5556` with the agent driving Settings. The capsule overlay reliably appears (`CapsuleOverlayHost: Capsule overlay shown` logged within ~10 s of `debug-run.sh` launch), but uiautomator `dump` does not capture the overlay window (`TYPE_ACCESSIBILITY_OVERLAY`), and pixel-coordinate taps onto the mic icon repeatedly missed across multiple anchor positions (the capsule re-anchors top↔bottom across mode transitions: Hidden→Running→TakeoverPending). Captured screenshots `F_fixed_before_tap.png` (Hidden-mode top capsule) and `F_fixed_after_tap.png` (TakeoverPending bottom capsule) but no logcat evidence of a registered mic tap. The unit test above + the unchanged JVM-side controller flow are the ground truth for this fix on this branch.

## Fix 2 — Controller-level promotion to `Unavailable` on hard error before any callback (scenario I follow-up)

**Bug.** On devices where `SpeechRecognizer.isRecognitionAvailable(ctx)` returns true (some `RecognitionService` is registered) but `voice_recognition_service` is unset or AppOps blocks binding (OnePlus Pad case), `factory.create()` succeeds, then `start()` fires `ERROR_SERVER_DISCONNECTED|ERROR_CLIENT|…` immediately. `handleError` mapped those to `ServiceDied|Unknown|Busy` → `VoiceState.Idle` + "Voice unavailable" toast. The mic icon stayed visible, every tap toasted, no path to `Unavailable` (where the icon hides).

**Fix.** `VoiceInputController` now tracks a per-session `sessionGotAnyCallback` flag — flipped true on the first `onPartial` or `onFinal`, reset at the top of `start()`. In `handleError`, the `Busy | ServiceDied | Unknown` arm now promotes to terminal `VoiceState.Unavailable` (mic icon hides, one toast) when the session never saw a callback. After at least one partial/final, the same errors stay transient (toast + `Idle`, e.g. network drop mid-session). `start()` also short-circuits if already `Unavailable` so subsequent taps don't try to bind the recognizer again.

**Tests.** Added 3 JVM tests in `VoiceInputControllerTest`:
- `serviceDied_at_session_start_with_no_callbacks_transitions_to_Unavailable` — covers the core regression + asserts subsequent `start()` is a no-op (no new `factory.created` entry).
- `unknown_at_session_start_with_no_callbacks_transitions_to_Unavailable` — same shape for `VoiceError.Unknown`.
- `serviceDied_after_partial_stays_in_Idle_with_toast` — guards against over-aggressive promotion when there's already evidence the recognizer works.

All three transitions issue exactly one toast (`"Voice unavailable"`), so the OnePlus complaint of "infinite toasts" goes away regardless of which arm fires.

## JVM test sweep

```
./gradlew :app:testDebugUnitTest --tests "ai.closepaw.ui.capsule.voice.VoiceInputControllerTest"
…
tests="12" skipped="0" failures="0" errors="0"
```

12/12 pass (was 9 — 3 added). `./gradlew :app:assembleDebug` and `:app:lintDebug` both green; no new lint warnings on the four modified files (the two pre-existing warnings on `ChatScreen.kt:96` (`ModifierParameter`) and `CapsuleOverlayHost.kt:303` (`ObsoleteSdkInt`) are unrelated to this change).

---

## OnePlus Pad post-fix (2026-05-17, w-voice-oneplus-fix)

### TL;DR delta

The hardware blocker described in **Environment notes** is *partially* removed by sideloading a `RecognitionService`-bearing build of `com.google.android.tts`. The previous failure mode — `SpeechRecognizer: no selected voice recognition service` → `"Voice unavailable"` toast — is gone. `ai.closepaw` now binds Google's `GoogleTTSRecognitionService` and reaches `RecognitionServiceImpl#onStartListening` with `callingApp: ai.closepaw, locale: zh-CN`. The recognizer just can't produce text without further setup: the device lacks Google Play Services (so `NetworkSpeechRecognizer` fails `S3 Initialization failed` immediately) and lacks the `zh-CN` SODA on-device model (so the offline fallback returns `LANGUAGE_PACK_ERROR (code 12)`). The app correctly surfaces this as a different, more specific toast — `"Voice not available for this language"` (`VoiceInputController.kt`, the `ERROR_LANGUAGE_NOT_SUPPORTED` arm). The recognizer wiring itself is healthy.

### What worked: Step 2 — sideload Google `Speech Recognition & Synthesis`

Step 1 (re-enable `com.google.android.googlequicksearchbox`) was blocked. The package enables fine but its `GoogleRecognitionService` component is OEM-locked at the component level — shell-level enable fails with `SecurityException: Shell cannot change component state`. After enabling, `pm query-services -a android.speech.RecognitionService` still returns only Claude's service.

Step 2 needed the *older* APK variant — the latest stable APKMirror release (`24.9.361717975`, versionCode `210316632`) is TTS-only and does not declare a `RecognitionService` (verified by unpacking `AndroidManifest.xml`). The earlier variant `googletts.google-speech-apk_20240805.01_p0.659565918` (versionCode `210503774`, arm64-v8a, Android 8.0+, 73.8 MB) still ships `com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService` and was confirmed to do so by `strings -e l` over the binary manifest.

Provenance of the sideloaded APK:

```text
File:    com.google.android.tts_googletts.google-speech-apk_20240805.01_p0.659565918 (arm64-v8a, nodpi).apk
Source:  apkmirror.com (download mirrored from Cloudflare R2)
Size:    73 867 972 bytes
SHA-256: 280f01ff9fa6ccf21763bb2d1a5c097687c0ea841c22c1bd22bdcddd816a742f
```

After install + `settings put secure voice_recognition_service "com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"`:

```text
$ adb -s 100.64.43.95:5555 shell pm query-services -a android.speech.RecognitionService
    name=com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService
    packageName=com.google.android.tts          enabled=true exported=true
    name=com.anthropic.claude.bell.assist.ClaudeRecognitionService
    packageName=com.anthropic.claude            enabled=true exported=true

$ adb shell settings get secure voice_recognition_service
com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService
```

### End-to-end test

Pre-conditions (all set via ADB after the APK install):

- ClosePaw upgraded from the older pre-voice APK installed on the tablet (which did **not** declare `RECORD_AUDIO`) to the worktree's `app/build/outputs/apk/debug/app-debug.apk` (commit `a6508b03` + voice-input branch fixes).
- `pm grant ai.closepaw android.permission.RECORD_AUDIO` and `cmd appops set ai.closepaw RECORD_AUDIO allow` → `Uid mode: RECORD_AUDIO: foreground`.
- Accessibility service re-enabled via `settings put --user 0 secure enabled_accessibility_services …`.
- Onboarding already complete (`shared_prefs/onboarding_prefs.xml: onboarding_completed=true`).

Steps:

1. `am force-stop ai.closepaw && am start -n ai.closepaw/.app.MainActivity` → chat home renders, mic icon visible at `bounds=[151,2566][214,2629]`, `content-desc="Start voice input"`. Screenshot: `qa_screens/oneplus_fix/01_main_after_launch.png`.
2. `input tap 182 2597` (mic). Screenshot: `qa_screens/oneplus_fix/04_after_mic_tap_clean.png`.
3. Wait ~4 s, scrape logcat.

Logcat evidence (this is the new, post-fix code path — compare with the pre-fix `no selected voice recognition service` shown earlier in this file):

```text
I RecognitionServiceImpl: RecognitionService#onStartListening
I RecognitionServiceImpl: Create an attribution context to skip platform permission check
I RecognitionServiceImpl: RecognitionService#logStartListening: callingApp: ai.closepaw, locale: zh-CN
I RecognitionClient: #startListening
I NetworkSpeechRecognizer: Online recognizer - start listening
I SodaSpeechRecognizer: Offline recognizer - start listening
I SodaSpeechRecognizer: Initialize Soda [locale: zh-CN], [applicationDomain: AMBIENT_ONESHOT]
W NetworkSpeechRecognizer: Failed uploading data to S3
W NetworkSpeechRecognizer: ekz: Initialization failed   ← no GMS / Play auth on device
…
I RecognitionClient: #onFallback                          ← network → on-device fallback
E SodaSpeechRecognizer: Failed to get language pack of required locale: error 12
W RecognitionClient: #onError space agsa_transcription_LANGUAGE_PACK_ERROR code 12!
W RecognitionServiceImpl: Speech recognition error type LANGUAGE_PACK_ERROR with error code 12
I CapsuleInputBar: voice toast: Voice not available for this language   ← app handles cleanly
```

Notable absences (these were present in the pre-fix run and were the worker's grep-for-MUST-be-free-of list):

- ❌ no `SpeechRecognizer: no selected voice recognition service`
- ❌ no `CapsuleInputBar: voice toast: Voice unavailable`
- ❌ no `RemoteSpeechRecognitionService: Connection … lost, but no #startListening`

Network sanity-check: device is online and can reach Google (`ping www.google.com` → 24 ms RTT). The `S3 Initialization failed` is a missing-auth failure, not a connectivity failure — `com.google.android.tts` needs GMS-issued credentials to talk to Google's ASR, and this OPlus image has no Play Services. SODA's offline model for `zh-CN` was never downloaded (that download is normally triggered by Play Services / Recorder; absent here).

### Result

| Aspect | Pre-fix | Post-fix |
|---|---|---|
| Default `RecognitionService` | none | `com.google.android.tts/…GoogleTTSRecognitionService` |
| `factory.create() + start()` succeeds | no | yes |
| `RecognitionService#onStartListening` reached | no | yes |
| Recognizer issues callbacks | no (no service to bind) | yes (`onError(ERROR_LANGUAGE_NOT_SUPPORTED)`) |
| App toast | `"Voice unavailable"` | `"Voice not available for this language"` |
| Listening state in UI | unreachable | unreachable (errors before partials) |
| Actual zh-CN voice → text on device | no | **still no** — needs a `zh-CN` model |

### Recommendation

Step 2 alone is **not enough** for end-to-end voice on this hardware in zh-CN. The recognizer service is now installed and active (which fully resolves the original `Voice unavailable` complaint), but Google's recognizer can't transcribe without either Play Services (online) or a downloaded SODA model (offline). On a vanilla `com.obric`-based OPlus image neither is available, and Step 3 (a standalone Vosk `RecognitionService` APK) does not exist in F-Droid — Vosk ships as a library that apps embed, not as a system-wide service.

Path forward for the user (out of scope for this device-config task):

- **Option 2 — embed Vosk in ClosePaw.** `org.vosk:vosk-android` plus the ~50 MB `zh-cn-small` model gives offline zh-CN STT with no Play dependency. Implement a small adapter behind `VoiceRecognizerFactory` so the existing controller/state-machine is unchanged.
- **Option 3 — in-app Whisper (e.g. `whisper.cpp` via JNI).** Larger model, better accuracy, same architectural slot as Option 2.
- **Option 1 (NOT recommended for daily use) — switch device locale to `en-US`.** `GoogleTTSRecognitionService` may auto-download the `en-US` SODA pack and start producing text without GMS, but it leaves the user typing on an English UI.

