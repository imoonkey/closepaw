# Voice-First Design

## Goal

Add a practical voice loop to Android Agent so the user can:
- speak a new task instead of typing,
- answer `ask_user` prompts by voice,
- send spoken supplements while the agent is running,
- hear short spoken feedback when the agent needs attention or finishes.

Success for the first implementation:
- voice uses the existing session model instead of adding a parallel command path,
- the same behavior works in the main app and the Smart Capsule overlay,
- speech output is short and interruptible,
- all failure modes degrade cleanly back to text.

Out of scope for now:
- wake word,
- full-duplex conversation,
- reading streaming assistant text aloud,
- remote TTS vendors or agent-authored voice styles.

## Approach

The simplest shape is:
1. keep one canonical user-input model,
2. keep voice state separate from task state,
3. scope voice runtime to the active session,
4. make Smart Capsule the only interaction surface.

That means:
- final speech transcripts become the same text payloads the app already uses,
- `CapsuleMode` and `SessionState` stay intact,
- the active `AgentSession` owns voice listen/speak lifecycle and tears it down naturally on shutdown,
- both typed and spoken input go through one shared resolver so they cannot drift.

## Components

### New package

Create `app/src/main/kotlin/com/moonkey/androidagent/voice/` with:

- `VoiceSessionController.kt`
  - session-scoped orchestration for STT, TTS, audio focus, and voice UI state,
  - survives through Hot Idle with the session,
  - resets automatically on session shutdown or new session creation.

- `SpeechRecognizerAdapter.kt`
  - wraps `SpeechRecognizer`,
  - exposes partial transcript, final transcript, timeout, and structured errors,
  - no app-specific routing logic.

- `TextToSpeechAdapter.kt`
  - wraps `TextToSpeech`,
  - supports `speak(summary)` and `stop()`,
  - reports init/runtime failures explicitly.

- `VoiceUiState.kt`
  - `Disabled`, `Idle`, `Listening`, `Thinking`, `Speaking`, `Error`,
  - independent from `CapsuleMode`.

- `SpokenReplyPolicy.kt`
  - builds short utterances from session events:
    - `TaskCompleted`,
    - `AskUser`,
    - `SessionError`,
    - takeover/resume confirmation.

- `CapsuleInputActionResolver.kt`
  - shared typed/spoken routing:
    - new task,
    - supplement,
    - user response,
    - action completion.

### Existing code changes

- `app/src/main/AndroidManifest.xml`
  - add `android.permission.RECORD_AUDIO`.

- `app/src/main/kotlin/com/moonkey/androidagent/session/SessionServices.kt`
  - create the session-scoped `VoiceSessionController`.

- `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt`
  - expose enough lifecycle hooks for voice start/stop on task/session boundaries.

- `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt`
  - bind overlay callbacks to the active session's `VoiceSessionController`,
  - stop speech when service is destroyed.

- `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt`
  - request runtime microphone permission before starting listening,
  - delegate actual voice actions to the active session.

- `app/src/main/kotlin/com/moonkey/androidagent/ui/capsule/surface/SmartCapsuleSurface.kt`
  - add mic affordance,
  - show listening/speaking state without changing `CapsuleMode`,
  - keep transcript draft in the same input field used for typed text.

- `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsState.kt`
  - add:
    - `voiceInputEnabled: Boolean`,
    - `spokenFeedbackMode: OFF | SUMMARY_ONLY`.

## Interactions

### 1. Input flow

Push-to-talk is the first and only active input mode in v1.

Flow:
1. user presses mic in Smart Capsule,
2. app checks `RECORD_AUDIO`,
3. current TTS is stopped,
4. `SpeechRecognizerAdapter` starts listening,
5. partial transcript updates the existing capsule input draft,
6. final transcript is routed by `CapsuleInputActionResolver`,
7. the same submit path used by typed input is called.

Routing rules:
- `Hidden`, `Done`, `Error` -> send new task,
- `WaitingForInput` -> `Op.UserResponse(callId, transcript)`,
- `WaitingForAction` -> accept `"done"` or send as user response,
- `Running`, `TakeoverPending`, `Takeover` -> supplement.

No new voice-specific `Op` is added.

### 2. Output flow

Spoken output is summary-only:
- `AskUser(question)` speaks the question,
- `TaskCompleted` speaks the completion summary,
- `SessionError` speaks a short error line,
- takeover/resume may speak a one-line confirmation.

The system never reads:
- full assistant message streams,
- tool cards,
- chain-of-thought style status text.

### 3. Voice state machine

Voice transport state:

`Disabled -> Idle -> Listening -> Thinking -> Speaking -> Idle`

Extra transitions:
- `Listening -> Idle` on cancel or empty result,
- `Speaking -> Listening` if the user presses mic while audio is playing,
- `Any -> Error -> Idle` after surfacing the failure,
- `Any -> Disabled` if permission or platform support is missing.

This keeps the canonical task state machine unchanged.

### 4. Session lifecycle coupling

Voice belongs to the active session:
- fresh session creates a fresh `VoiceSessionController`,
- Hot Idle keeps voice ready for follow-up,
- `Shutdown` stops listening, stops TTS, and clears voice state,
- reloaded sessions recreate voice cleanly from current settings instead of restoring transient audio state.

This avoids orphan voice state after session replacement.

### 5. Failure handling

- no mic permission:
  - disable mic button,
  - show explicit rationale in UI/settings,
  - keep text input fully usable.

- recognizer unavailable:
  - disable voice input only.

- TTS unavailable:
  - keep speech input,
  - disable spoken summaries only.

- recognition error after partial text:
  - keep partial text in draft so the user can edit or send manually.

## Trade-offs

- **Session-scoped voice vs service-scoped voice**
  - Session-scoped wins because voice semantics track conversation semantics. Shutdown, reload, and Hot Idle already exist; voice should follow those boundaries instead of inventing its own lifetime.

- **Shared input resolver vs voice-only router**
  - Shared resolver wins because typed and spoken input must never disagree about what the same capsule state means.

- **Summary-only TTS vs full assistant playback**
  - Summary-only wins because the product needs fast, useful feedback, not long spoken transcripts.

- **Push-to-talk vs auto-open microphone**
  - Push-to-talk wins because it is safer, simpler, and fits current permission and overlay patterns.

- **Wake word later vs now**
  - Later wins because the core value is the voice loop, not passive activation. Wake word should layer on top after the loop is reliable.

## Self-review

This design stays close to the current architecture: session lifecycle remains canonical, Smart Capsule remains the only user-facing control surface, and speech falls back to the same text pathways already used today. The main risk is that session-scoping voice adds some wiring inside `SessionServices`, but that cost buys a cleaner lifecycle than a loose app-global audio controller.
