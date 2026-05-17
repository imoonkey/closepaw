# Voice Input

> Microphone `leadingIcon` on `CapsuleInputBar`. Streams partial speech results into the draft field; works in MAIN_APP and overlay contexts.
> Last updated: 2026-05-17

## Components

All in `app/src/main/kotlin/ai/closepaw/ui/capsule/voice/`.

- **Recognizer.kt** — JVM-clean interfaces (`Recognizer`, `RecognizerCallbacks`, `VoiceError`, `RecognizerFactory`) plus `AndroidRecognizerFactory` / `AndroidRecognizer`. **The only file in the app that may import `android.speech.*`** — this isolation lets the controller stay JVM-testable. `AndroidRecognizer` translates `RecognitionListener` + `Bundle` payloads + raw error ints into the framework-free callback surface.
- **VoiceInputController.kt** — plain Kotlin class wrapping a `Recognizer`. States: `Idle`, `Listening`, `Stopping`, `Unavailable`. Exposes `state`/`lastPartial`/`partialAtStop` as Compose `mutableStateOf`. `@Composable fun rememberVoiceInputController(...)` wraps with `DisposableEffect { onDispose { dispose() } }`. Plain class never imports `androidx.compose.*` outside that one factory.
- **VoicePermissionGate.kt** — mirrors `RunCommandPermissionGate` shape. Disposition: `Granted` / `Request` / `OpenAppSettings`. The `pending` flag prevents rapid-tap launcher stacking. `@Composable rememberVoicePermissionGate(activity, onResult)` attaches a `RequestPermission` launcher.
- **VoiceMicDeps** (in `ui/capsule/surface/CapsuleInputBar.kt`) — value type the bar takes as `voice: VoiceMicDeps?`. Fields: `factory: RecognizerFactory`, `activity: Activity?` (null in overlay), `fun isPermissionGranted(): Boolean`, `fun requestOverlayPermission()`. Null `voice` ⇒ mic icon hidden.

## State machine

```
Idle ──tap mic──▶ Listening ──tap stop──▶ Stopping ──onResults / onError──▶ Idle
                       │                       │
                       ├ onPartialResults ─▶ Listening (inputText updated, lastPartial cached)
                       ├ onResults  ───────▶ Idle (commit final)
                       ├ onError ──────────▶ Idle OR Unavailable (see below)
                       └ user types  ──────▶ Idle (cancel recognizer, keep visible text)
```

**Commit policy on exit:**

| Transition | Action |
|---|---|
| `Listening → onResults(final)` | `inputText = join(baseText, final)` |
| `Stopping → onResults(final)` | final wins over `partialAtStop` |
| `Stopping → ERROR_NO_MATCH \| SPEECH_TIMEOUT` | commit `partialAtStop` if non-empty, else restore `baseText` silently |
| `Listening → ERROR_NO_MATCH \| SPEECH_TIMEOUT` | restore `baseText` silently (common case, no toast) |
| `Listening → ERROR_LANGUAGE_*` | toast, transition to `Unavailable` for the session |
| `Listening → ERROR_NETWORK*` | toast, restore `baseText` |
| `Listening → ERROR_BUSY \| SERVICE_DIED \| UNKNOWN` (first session, no callbacks yet) | **terminal `Unavailable`** — mic icon hides |
| `Listening → ERROR_BUSY \| SERVICE_DIED \| UNKNOWN` (after partial/final seen) | toast, restore `baseText` (transient) |
| typing during `Listening` | cancel recognizer, keep visible text |

**Generation rule (Codex round 3).** A `generation: Int` counter is captured per session and checked in every callback. Bump on `cancel()`, `dispose()`, and **after** processing any terminal callback (`Listening→Idle` natural, `Stopping→Idle`, error→Idle/Unavailable). **Don't** bump on `Listening→Stopping` — the terminal callback we're waiting for must match the current generation.

**`partialAtStop`.** Snapshot of `lastPartial` taken at user-stop. Frozen during `Stopping` — partials arriving between `stopListening()` and the terminal callback do not mutate it.

**Session-callback gate for terminal `Unavailable`.** A `sessionGotAnyCallback: Boolean` flag is set true on the first `onPartial`/`onFinal`. If `Busy`/`ServiceDied`/`Unknown` fires before any callback, the recognizer is unusable on this hardware/config and we promote to `VoiceState.Unavailable` (icon hides) instead of toasting on every tap.

## Permission flow

Reuses the `RunCommandPermissionGate` classifier shape:

```
tap mic:
  isPermissionGranted? ─yes─▶ controller.start(baseText)
                       ─no──▶ classify(isGranted, hasAttempted, shouldShowRationale)
                                Granted         → controller.start(baseText)
                                Request         → MAIN_APP: launcher.launch(RECORD_AUDIO)
                                                  overlay : route via AgentService → MainActivity
                                OpenAppSettings → openAppSettings()
```

**MAIN_APP path.** `ChatScreen` builds `VoiceMicDeps(factory = AndroidRecognizerFactory(appCtx), activity = current activity, isPermissionGranted = ContextCompat.checkSelfPermission(...))`. The launcher is `rememberLauncherForActivityResult(RequestPermission())`.

**Overlay path.** `CapsuleOverlayHost` builds `VoiceMicDeps(activity = null, isPermissionGranted = ContextCompat.checkSelfPermission(appCtx, RECORD_AUDIO) == GRANTED, requestOverlayPermission = { AgentService.instance?.requestVoicePermissionViaMainActivity() })`. If permission is already granted, `onMicTap` short-circuits to `controller.start(...)` and listening begins **in the overlay** without bouncing through MainActivity. If not granted, the overlay routes via `AgentService.requestVoicePermissionViaMainActivity()` which fires a `MainActivity` intent with the internal extra `EXTRA_REQUEST_VOICE_PERMISSION = true`. See [overlay.md § Voice permission cold-start](../overlay.md#voice-permission-cold-start) for the cold-start race detail.

## Recognizer error mapping

`AndroidRecognizer` maps `SpeechRecognizer.ERROR_*` ints to the framework-free `VoiceError` enum. The mapping table lives in `Recognizer.kt` and the controller's `handleError(VoiceError)` decides UI behavior per the state-machine commit-policy table above.

## Availability

`RecognizerFactory.isAvailable()` is re-checked on every `start()` (not just at construction) because availability can flap mid-session (user disables Google App, recognition service dies). `AndroidRecognizerFactory.isAvailable()` wraps `SpeechRecognizer.isRecognitionAvailable(context)` — note this returns `true` whenever any `RecognitionService` is registered, even if no default is selected or the service is unbindable. The hard-error → terminal-`Unavailable` rule above is the runtime corrective for that gap.

## Language

`languageTag` defaults to `Locale.getDefault().toLanguageTag()`. Override at `rememberVoiceInputController` site if needed.

## Out of scope

TTS, hot-word activation, multi-language switching from UI, custom acoustic models.

## Tests

- **JVM** — `app/src/test/kotlin/ai/closepaw/ui/capsule/voice/VoiceInputControllerTest.kt`: 12 cases against `FakeRecognizer` covering generation rule, `partialAtStop`, double-start, dispose-mid-listen, availability flip, hard-error-before-callback Unavailable promotion.
- **Instrumented** — `app/src/androidTest/kotlin/ai/closepaw/qa/CapsuleVoiceInputTest.kt`: Compose UI tests with `FakeRecognizerFactory` injected covering mic visibility, overlay permission routing, partial streaming, typing-cancel, Send-disabled-during-listening.
