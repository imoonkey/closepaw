# Voice Input for CapsuleInputBar — Design (v3.1, ready)

> v3 incorporates round-2 Codex feedback: fix `Stopping` generation rule, disable Send during voice, snapshot `lastPartial` at user-stop, recheck availability on `start()`, fix cold-start permission flow, simplify `Recognizer` interface to JVM-friendly callbacks.
>
> v3.1: bake round-3 follow-ups into task acceptance (no redesign): bump generation on **any** terminal callback, gate `submit()` not just the Send button, expose `RecognizerFactory.isAvailable()` so the controller can re-check availability without importing `android.speech.*`.

## Goal

Let users dictate into the existing input bar instead of typing. Works in both the **Main app** and the **overlay Capsule** (Hidden / Takeover / Supplement / WaitingForInput). Streams partial results so the user sees text appear as they speak.

Out of scope: TTS, hot-word activation, multi-language switching, custom acoustic models.

## Approach

Use Android's stock `SpeechRecognizer`. Three reasons over `RecognizerIntent`:

1. `RecognizerIntent` needs an Activity context — dead in the overlay.
2. We want partial results streamed into `inputText`.
3. `RecognizerIntent` is final-result oriented; it pops its own UI which would steal focus from the Capsule.

Voice and keyboard coexist: mic is a `leadingIcon`, send stays `trailingIcon`. Voice **appends** to whatever the user has typed; typing while listening cancels recognition and **keeps the visible text as-is** (don't revert).

Availability is not assumed. On startup we check `SpeechRecognizer.isRecognitionAvailable(context)` and hide the mic when false (stripped ROMs, AOSP without Google services).

## State Machine

States owned by `VoiceInputController`:

```
            ┌──────────────────────────────┐
            │                              ▼
Idle ──tap mic──▶ Listening ──tap stop──▶ Stopping ──onResults / onError──▶ Idle
                       │                      │
                       ├ onPartialResults ─▶ Listening (inputText updated, lastPartial cached)
                       ├ onResults  ───────▶ Idle (commit final)
                       ├ onError ──────────▶ Idle (commit lastPartial if user-initiated stop, else restore baseText)
                       └ user types  ──────▶ Idle (cancel, keep current visible text)
```

Why `Stopping`: `SpeechRecognizer.stopListening()` is async — the recognizer is still busy until a terminal callback fires. Calling `startListening()` before that throws `ERROR_RECOGNIZER_BUSY`. `Stopping` disables the mic button until the recognizer is truly idle.

**Invariants**

- `baseText` = `inputText` snapshot at `start()`. Partials set `inputText = joinWithSpace(baseText, partial)`.
- `lastPartial` = most recent partial seen during `Listening`. Cleared on entering `Listening`.
- `partialAtStop` = `lastPartial` snapshotted at the moment the user taps stop. **Frozen** during `Stopping` — partials that arrive after stop do NOT update it (recognizer keeps emitting partials between `stopListening()` and the terminal callback; we don't want a late partial to overwrite what the user saw and decided was good).
- Every transition out of `Listening`/`Stopping` carries an explicit `commit policy`:
  - `Listening → onResults(final)` → `inputText = join(baseText, final)`.
  - `Stopping → onResults(final)` → `inputText = join(baseText, final)` (final still wins over `partialAtStop`).
  - `Stopping → ERROR_NO_MATCH | ERROR_SPEECH_TIMEOUT` → if `partialAtStop` non-empty, commit it; else restore `baseText` silently.
  - `Listening → onError (other)` → restore `baseText` (with toast per error matrix).
  - typing during `Listening` → cancel recognizer, **keep current `inputText` as visible** (the partial that the user is now editing).
- Send is **disabled** while state is `Listening` or `Stopping` (see Component Modifications). Voice and submit-clear-text don't race.
- Disposal: set `disposed = true`, increment `generation`, call `recognizer.destroy()`.

**Stale-callback guard — generation rule.**

A `generation: Int` counter, captured per session. The rule (corrected from v2):

- **Bump generation** on: `cancel()`, `destroy()`, **after** processing the terminal callback that exits `Stopping → Idle`.
- **Do NOT bump** on: `Listening → Stopping`. The terminal callback we're waiting for belongs to the current generation — bumping here would mark it stale and we'd never exit `Stopping`.

The internal listener captures `myGeneration = generation` at start. Every callback first checks `myGeneration == generation && !disposed`; mismatched callbacks are dropped. This kills three race classes:

1. Late `onPartialResults` after `cancel()` mutating the field.
2. `setRecognitionListener`-released listener still receiving in-flight callbacks after `destroy()`.
3. A new `start()` happening while a stale terminal callback from the previous session is in flight (the new session has a higher generation; the late callback is dropped).

## Error Handling

Recognizer errors mapped explicitly (no generic fallback):

| Error | Action |
|---|---|
| `ERROR_INSUFFICIENT_PERMISSIONS` | Treat as permission revoked mid-session: re-run gate, restore baseText. |
| `ERROR_LANGUAGE_NOT_SUPPORTED`, `ERROR_LANGUAGE_UNAVAILABLE` | Toast "Voice not available for this language", disable mic for the session. |
| `ERROR_NETWORK`, `ERROR_NETWORK_TIMEOUT` | Toast "Voice needs network for this language", restore baseText. |
| `ERROR_RECOGNIZER_BUSY` | Should not happen given `Stopping` guard; log + restore. |
| `ERROR_NO_MATCH`, `ERROR_SPEECH_TIMEOUT` | If user-initiated stop with `partialAtStop` non-empty → commit `partialAtStop`. Otherwise restore baseText silently (no toast — common case). |
| `ERROR_CLIENT`, `ERROR_AUDIO`, `ERROR_SERVER`, `ERROR_SERVER_DISCONNECTED`, default | Toast "Voice unavailable", restore baseText. |

Availability check runs in two places (Codex round 2: availability flaps mid-session — user can disable Google App, recognition service can die):

- **Construction**: `RecognizerFactory.create()` returns `null` if `SpeechRecognizer.isRecognitionAvailable(context) == false`. Controller starts in `Unavailable`.
- **`start()`**: re-check `isRecognitionAvailable`; if false now, transition to `Unavailable` and abort. Also any hard service error (`ERROR_CLIENT` + `createSpeechRecognizer` returning null on construction in the adapter) → `Unavailable`.

## Permission Flow

Reuses the pattern from `RunCommandPermissionGate` (`Granted` / `Request` / `OpenAppSettings`, plus `pending` flag).

```
tap mic:
  classify(isGranted, hasAttempted, shouldShowRationale)
    Granted         → controller.start(baseText)
    Request         → MAIN_APP: launcher.launch(RECORD_AUDIO)
                      overlay : route via AgentService → MainActivity (see below)
    OpenAppSettings → toast "Voice was denied — enable in Settings", offer button
```

**Overlay → MainActivity routing.** No new exported intent action. Reuse the existing `AgentService.bringMainAppToFront(extras)` pattern with an **internal** extra `EXTRA_REQUEST_VOICE_PERMISSION = true`.

Cold-start vs warm-start (Codex round 2): the extra must be consumed regardless of which entry point fires. Both `onCreate` and `onNewIntent` route through a single `consumeVoicePermissionRequestIfPresent(intent)` helper. The helper sets a `pendingVoicePermissionRequest: Boolean` Activity-level flag. The Compose tree, which registers the `ActivityResultLauncher`, checks the flag in a `LaunchedEffect` keyed on `(launcherReady, pendingVoicePermissionRequest)` — when both true, fire the launcher and clear the flag. This avoids the "launcher not yet registered" race on cold start.

The voice extra is handled by its own helper and **does not flow through** `handleIntent` / `MainActivityIntentPayloadApplier` (which has unrelated side effects around fresh sessions, eval intents, etc.).

(Rationale: runtime permissions require an Activity; we can't avoid the round-trip. But it should be an internal control-plane message, not a public deep link, and the dispatch must not be tied to a single entry point.)

## Components

**New**

- `app/src/main/kotlin/ai/closepaw/ui/capsule/voice/Recognizer.kt`
  - JVM-clean interface — no Android `RecognitionListener` or `Bundle` leak (project has no Robolectric; `app/build.gradle.kts:241`):
    ```kotlin
    interface RecognizerCallbacks {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(error: VoiceError)
    }
    interface Recognizer {
        fun start(languageTag: String, callbacks: RecognizerCallbacks)
        fun stop()
        fun cancel()
        fun destroy()
    }
    enum class VoiceError {
        NoMatch, SpeechTimeout, Network, NetworkTimeout,
        LanguageUnavailable, InsufficientPermissions, Busy, ServiceDied, Unknown,
    }
    interface RecognizerFactory {
        fun isAvailable(): Boolean         // re-checkable; controller calls this on start()
        fun create(): Recognizer?          // null if !isAvailable()
    }
    ```
  - `class AndroidRecognizer(context: Context): Recognizer` — internal: wraps `SpeechRecognizer`, translates `RecognitionListener` + `Bundle` payloads + raw error ints into `RecognizerCallbacks` + `VoiceError`. This is the only Android-aware file.

- `app/src/main/kotlin/ai/closepaw/ui/capsule/voice/VoiceInputController.kt`
  - Composable-scoped. API: `state: VoiceState`, `lastPartial: String`, `partialAtStop: String`, `start(baseText: String)`, `stop()`, `cancel()`, `onText: (text: String) -> Unit`.
  - Owns generation counter, disposed flag, holds a `RecognizerCallbacks` impl that delegates into the controller (no Android types imported).
  - `DisposableEffect` calls `destroy()`.

- `app/src/main/kotlin/ai/closepaw/ui/capsule/voice/VoicePermissionGate.kt`
  - Mirrors `RunCommandPermissionGate` shape: classifier + state holder.
  - `@Composable fun rememberVoicePermissionGate(context: CapsuleContext): VoicePermissionGate`.
  - MAIN_APP path: attaches `rememberLauncherForActivityResult(RequestPermission())`.
  - Overlay path: `request()` calls `AgentService.requestVoicePermissionViaMainActivity()` (new helper).

- `app/src/test/kotlin/ai/closepaw/ui/capsule/voice/VoiceInputControllerTest.kt`
  - JVM unit tests against `FakeRecognizer`: partial-after-cancel ignored, stop-then-NO_MATCH commits lastPartial, destroy-mid-listen no-ops late callbacks, double-start while Stopping is a no-op.

- `app/src/androidTest/kotlin/ai/closepaw/qa/CapsuleVoiceInputTest.kt`
  - Compose UI tests with an injected `FakeRecognizerFactory`: mic toggles, partial text streams, typing cancels, Unavailable hides mic.

**Modified**

- `app/src/main/AndroidManifest.xml`
  - Add `<uses-permission android:name="android.permission.RECORD_AUDIO" />`.
  - Add `<intent><action android:name="android.speech.RecognitionService" /></intent>` **inside the existing `<queries>` block** at line 11.
  - **No** new exported intent-filter.

- `app/src/main/kotlin/ai/closepaw/app/MainActivity.kt`
  - Add `consumeVoicePermissionRequestIfPresent(intent)` called from both `onCreate` and `onNewIntent`. Sets `pendingVoicePermissionRequest` flag. **Not** routed through `handleIntent`.
  - Compose tree registers the `RequestPermission` launcher; a `LaunchedEffect(launcherReady, pendingVoicePermissionRequest)` fires it when both are true and clears the flag.

- `app/src/main/kotlin/ai/closepaw/app/AgentService.kt`
  - Add `fun requestVoicePermissionViaMainActivity()` using the existing `Intent` + `FLAG_ACTIVITY_NEW_TASK` pattern.

- `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/CapsuleInputBar.kt`
  - Add `leadingIcon` driven by `VoiceInputController.state` and gate availability:
    - `Unavailable` → no icon (mic hidden).
    - `Idle` → mic icon, tap routes through gate.
    - `Listening` → animated stop icon, placeholder "Listening…".
    - `Stopping` → stop icon disabled.
  - `onValueChange`: if `state == Listening` and the delta is not the recognizer's own update (compare against `joinWithSpace(baseText, lastPartial)`), call `controller.cancel()` and keep the user's text.
  - **Send button disabled when `state in {Listening, Stopping}`**. Today `canSend = inputEnabled && inputText.isNotBlank()` (line 120); change to `canSend = inputEnabled && inputText.isNotBlank() && voiceState !in setOf(Listening, Stopping)`. This kills the "Send clears text while a late partial is in flight" race without needing to cancel-then-submit. Tap-to-stop, then tap-to-send is one extra tap — acceptable, and matches phone keyboard mic behavior.

## Interaction Sketch

```
User taps mic
  → gate.classify()
      Granted         → controller.start(baseText = inputText)
                          recognizer.start(intent, listener)
                          listener.onPartialResults(p)  // for each
                            → if generation match: inputText = join(baseText, p); lastPartial = p
                          listener.onResults(final)
                            → if match: inputText = join(baseText, final); state = Idle
      Request         → fire launcher (MAIN_APP) or AgentService route (overlay)
      OpenAppSettings → snackbar with "Open Settings"
User taps stop
  → controller.stop() → recognizer.stop(); state = Stopping
      onResults / onError handled with commit policy above; state = Idle
User types
  → controller.cancel() → bump generation; recognizer.cancel(); state = Idle; visible text untouched
Composable disposed
  → DisposableEffect → bump generation; disposed=true; recognizer.destroy()
```

`RecognizerIntent` extras: `LANGUAGE_MODEL_FREE_FORM`, `EXTRA_PARTIAL_RESULTS=true`, `EXTRA_LANGUAGE=Locale.getDefault().toLanguageTag()`.

## Tasks

1. **voice-manifest** — scope: `app/src/main/AndroidManifest.xml`. Accept: `RECORD_AUDIO` declared, `<intent>android.speech.RecognitionService</intent>` added inside existing `<queries>` block, no new exported actions. Deps: none.

2. **voice-recognizer-adapter** — scope: `app/src/main/kotlin/ai/closepaw/ui/capsule/voice/Recognizer.kt`. Accept: JVM-clean `Recognizer` + `RecognizerCallbacks` + `VoiceError` enum + `RecognizerFactory` (returns null when unavailable). Factory exposes `isAvailable(): Boolean` so the controller can re-check without importing `android.speech.*` (Codex round 3). `AndroidRecognizer` is the only file that imports `android.speech.*`. Deps: voice-manifest.

3. **voice-controller** — scope: `app/src/main/kotlin/ai/closepaw/ui/capsule/voice/VoiceInputController.kt`, `app/src/test/kotlin/ai/closepaw/ui/capsule/voice/VoiceInputControllerTest.kt`. Accept: full state machine (Idle/Listening/Stopping/Unavailable). Generation rule (Codex round 3): bump on cancel/destroy AND after processing **any** terminal callback (Listening→Idle natural completion, Stopping→Idle, error→Idle); **not** on Listening→Stopping. `partialAtStop` snapshot frozen during Stopping. Availability re-checked on `start()` via `factory.isAvailable()`. Full `VoiceError` matrix wired. JVM tests cover: partial-after-cancel dropped, late callback after natural Listening→Idle dropped, stop-then-partial doesn't overwrite `partialAtStop`, stop-then-NO_MATCH commits `partialAtStop`, stop-then-final still wins, destroy-mid-listen drops late callbacks, double-start while Stopping is a no-op, `start()` when availability flipped to false transitions to Unavailable. Deps: voice-recognizer-adapter.

4. **voice-permission-gate** — scope: `app/src/main/kotlin/ai/closepaw/ui/capsule/voice/VoicePermissionGate.kt`, `app/src/main/kotlin/ai/closepaw/app/MainActivity.kt`, `app/src/main/kotlin/ai/closepaw/app/AgentService.kt`. Accept: classifier mirrors `RunCommandPermissionGate` (`Granted/Request/OpenAppSettings` + `pending` flag). MAIN_APP path uses `rememberLauncherForActivityResult`. Overlay path calls internal `AgentService` helper (no exported intent). `MainActivity` consumes `EXTRA_REQUEST_VOICE_PERMISSION` in **both** `onCreate` and `onNewIntent` via a dedicated helper (not `handleIntent`); a `pendingVoicePermissionRequest` flag bridges the cold-start case where the launcher isn't yet registered. Deps: voice-manifest.

5. **voice-ui** — scope: `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/CapsuleInputBar.kt`. Accept: mic leading icon present, hidden when `Unavailable`, toggles state visuals, partials stream into `inputText`, typing-during-listening cancels and preserves visible text. **Send disabled while Listening or Stopping** — guard both the `FilledIconButton.enabled` AND inside `submit()` itself, because `KeyboardActions(onSend = { submit() })` (line 188) bypasses the button-enabled state when the user hits the IME Send key (Codex round 3). Deps: voice-controller, voice-permission-gate.

6. **voice-instrumented-tests** — scope: `app/src/androidTest/kotlin/ai/closepaw/qa/CapsuleVoiceInputTest.kt`. Accept: ≥4 Compose UI tests via injected `FakeRecognizerFactory`: mic toggle, permission-missing tap routes correctly per context, partial text appears, typing cancels and preserves text, Send disabled during Listening. Deps: voice-ui.

## Trade-offs

- **`RecognizerIntent` (rejected)** — final-result oriented, launches its own UI that steals focus from the Capsule, no overlay support.
- **Always-on recognition** — battery + privacy. Tap-to-speak is the universally expected pattern.
- **Auto-submit on final result** — breaks "dictate, then edit, then send". Send stays explicit.
- **Custom mic UI vs system dialog** — custom adds ~150 lines but matches our overlay styling.
- **Permission in onboarding vs lazy** — lazy. Voice is optional. Cost: one round-trip on first overlay use.
- **`Stopping` as explicit state vs `isStopping: Boolean` flag** — explicit state. Three reasons: (1) UI disable logic reads cleaner; (2) `start()` guard becomes a single `when` branch; (3) future "auto-restart after stop" feature has a place to hook in.
- **`Recognizer` adapter vs direct `SpeechRecognizer`** — adapter. Makes the controller JVM-testable; the cost is one tiny interface and one one-line impl.

## Open for Final Review

1. Should `Unavailable` show a disabled mic with a "voice unavailable on this device" tooltip, or hide the icon entirely? Current pick: hide (less clutter).
2. Locale strategy: `Locale.getDefault().toLanguageTag()`, no setting. Acceptable?
3. When recognition starts in the overlay, do we want any haptic feedback (since the user can't see a flashing red dot at the system bar)? Current pick: no haptic, ship without; revisit if user feedback says otherwise.
