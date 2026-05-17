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
