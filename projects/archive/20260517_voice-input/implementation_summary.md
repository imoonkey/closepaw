# Voice Input — Implementation Summary

> Adds a microphone `leadingIcon` to `CapsuleInputBar`. Streams partial speech-to-text into the draft. Works in MAIN_APP and overlay capsule contexts.
> Status: **done** on branch `task/voice-input`. Commits `b929a93b..34463e6b`.

## What shipped

| Layer | File | Purpose |
|---|---|---|
| Manifest | `AndroidManifest.xml` | `RECORD_AUDIO` + `<intent>android.speech.RecognitionService</intent>` query (API 30+ visibility). No new exported action. |
| Recognizer adapter | `ui/capsule/voice/Recognizer.kt` | `Recognizer` / `RecognizerCallbacks` / `RecognizerFactory` / `VoiceError` (JVM-clean) + `AndroidRecognizerFactory` + `AndroidRecognizer`. The **only** file in the app that imports `android.speech.*`. |
| Controller | `ui/capsule/voice/VoiceInputController.kt` | State machine `Idle/Listening/Stopping/Unavailable`. Generation counter race guards. `partialAtStop` snapshot. Hard-error → terminal `Unavailable` on first-callback failure. |
| Permission gate | `ui/capsule/voice/VoicePermissionGate.kt` | Mirrors `RunCommandPermissionGate` shape (`Granted/Request/OpenAppSettings` + `pending` flag). |
| UI | `ui/capsule/surface/CapsuleInputBar.kt` | Mic `leadingIcon` driven by `VoiceState`. Send button + `submit()` lambda both gated on `voiceState !in {Listening, Stopping}` (IME Send key guard). Typing during `Listening` cancels recognizer + preserves visible text. |
| Wiring (MAIN_APP) | `ui/chat/ChatScreen.kt`, `ui/capsule/surface/SmartCapsuleSurface.kt` | Threads `VoiceMicDeps` through. |
| Wiring (overlay) | `ui/overlay/compose/CapsuleOverlayHost.kt` | Builds deps with `activity = null` + `requestOverlayPermission = AgentService::requestVoicePermissionViaMainActivity`. Overlay-granted path short-circuits via `isPermissionGranted()`. |
| Cold-start route | `app/MainActivity.kt`, `app/MainActivityContent.kt`, `app/AgentService.kt` | `EXTRA_REQUEST_VOICE_PERMISSION` consumed in BOTH `onCreate` and `onNewIntent` via dedicated helper (bypassing `handleIntent`). `LaunchedEffect(pendingRequest)` fires the launcher once attached. |
| Tests (JVM) | `app/src/test/.../VoiceInputControllerTest.kt` | 12 cases against `FakeRecognizer`. |
| Tests (instrumented) | `app/src/androidTest/.../CapsuleVoiceInputTest.kt` | 8 Compose UI cases with `FakeRecognizerFactory` (7 active, 1 `@Ignore` for non-deterministic system permission dialog). |

## Key design decisions

1. **`SpeechRecognizer` not `RecognizerIntent`** — `RecognizerIntent` needs an Activity context (dead in overlay), is final-result oriented, and pops its own UI that steals focus from the Capsule.
2. **JVM-clean `Recognizer` interface** — controller is plain Kotlin with no `android.*` deps so the state machine is unit-testable without Robolectric. Only `AndroidRecognizer` touches the framework.
3. **State as Compose `mutableStateOf`** — controller exposes `state`/`lastPartial`/`partialAtStop` as Compose `State`. Earlier 60ms polling design was refactored away (`e5eb58b5`).
4. **Generation counter on every callback** — kills late callbacks after `cancel()`, after natural `Listening→Idle`, and after `dispose()`. Don't bump on `Listening→Stopping` (we still want that session's terminal callback).
5. **Overlay-granted short-circuit** (`208b36dd`) — `onMicTap` in overlay context consults `VoiceMicDeps.isPermissionGranted()` first; only routes through MainActivity if NOT granted. Earlier behavior bounced unconditionally and never started listening for the granted case.
6. **Terminal `Unavailable` on hard error before any callback** (`00f4f175`) — on OEM/ROM combinations where `SpeechRecognizer.isRecognitionAvailable()` returns true but the service is unbindable, the first `ServiceDied`/`Unknown`/`Busy` error promotes to terminal `Unavailable` so the mic icon hides instead of toasting on every tap.

## Verification

- `./gradlew test` — 12/12 `VoiceInputControllerTest` PASS.
- `./gradlew assembleDebug` — clean.
- `./gradlew lint` — clean.
- Instrumented `CapsuleVoiceInputTest` on emulator (`AndroidWorldAvd2`, API 33) — 7 active + 1 skipped, 0 failures.
- End-to-end on emulator-5556: MAIN_APP permission dialog → grant → Listening → silent NO_MATCH timeout (no toast); overlay-granted → recognizer starts in-place without MainActivity bounce.
- Real-device end-to-end on OnePlus Pad → see `device_qa.md` (requires environmental fixes: enabling GMS + sideloading a Google TTS APK that bundles `GoogleTTSRecognitionService` + granting RECORD_AUDIO to that package + downloading the offline language pack).

## Out of scope / followups

- TTS, hot-word activation, custom acoustic models, multi-language UI picker.
- Voice-language Setting in ClosePaw (decouple from system `Locale`) — useful for users with one offline language pack installed but a different system language.
- Chinese-OEM ROMs without GMS infrastructure — would benefit from option-3 in-app STT (embedded Whisper or Vosk) so the feature doesn't depend on Google Speech Services. Out of scope for this milestone.

## Docs

- Architecture: `doc/main/ui/capsule/architecture.md` (line 15 reference to `voice.md`)
- Feature doc: `doc/main/ui/capsule/voice.md` (new)
- Overlay cold-start: `doc/main/ui/overlay.md` (new section "Voice permission cold-start")
- Design: `projects/active/voice_input/design_claude.md`
- Device QA: `projects/active/voice_input/device_qa.md`
