# Voice-First Interaction Design

## Goal

Add a voice-first interaction loop to Android Agent so the user can speak a request, let the agent execute it, and hear a short spoken result without leaving the existing session/capsule model.

Success means:
- Voice input works in both the main app and the accessibility overlay.
- Recognized speech reuses the current session pipeline (`Op.UserInput`, `Op.Supplement`, `Op.UserResponse`) instead of creating a parallel command path.
- Spoken output is brief and useful: completion summaries, `ask_user` prompts, errors, and takeover/resume cues.
- TTS is interruptible: starting to listen always stops current speech.
- The design can grow into auto-talk and wake-word modes later without rewriting session or capsule state.

Non-goals for the first delivery:
- Always-on wake word
- Rich agent-controlled voice style directives
- Reading full agent message streams aloud

## Approach

The simplest design is to treat voice as an input/output transport around the existing session system, not as a new agent mode.

1. Put voice runtime ownership in `app/AgentService.kt`.
   `AgentService` already survives outside the main app, owns the overlay, and observes the active `AgentSession`. Voice should live beside that lifecycle so the same controller works in-app and out-of-app.

2. Keep voice state orthogonal to `CapsuleMode`.
   `CapsuleMode` already models task UI well. Adding listening/speaking/error variants there would mix task state with transport state and make the capsule state machine harder to reason about. Add a separate `VoiceUiState` (`Disabled`, `Ready`, `Listening`, `Processing`, `Speaking`, `Error`) and render it as mic/speaker affordances on top of the current capsule UI.

3. Route transcripts into existing ops through one resolver.
   Today text input already has a canonical meaning by capsule state:
   - `Hidden` / `Done` / `Error` -> new task
   - `WaitingForInput` -> `Op.UserResponse`
   - other active modes -> `Op.Supplement`

   Voice should use the same rule. A shared resolver prevents text and voice from diverging.

4. Use Android system speech APIs first.
   - STT: `SpeechRecognizer`
   - TTS: `TextToSpeech`

   This matches the brief: low cost, native support, minimal infrastructure. It also keeps the first version inside repo scope with no new backend dependency.

5. Deliver in two compatible steps.
   - Phase 1: push-to-talk plus brief spoken feedback
   - Phase 2: optional auto-talk loop that re-arms listening after TTS or after an `ask_user` question

   Wake word is a later layer on top of the same controller, not part of the base architecture.

## Components

### New package

Create `app/src/main/kotlin/com/moonkey/androidagent/voice/`:

- `VoiceInteractionManager.kt`
  - Service-scoped orchestrator
  - Owns STT, TTS, audio focus, barge-in, and current `VoiceUiState`
  - Observes agent events and turns them into spoken feedback
  - Exposes simple actions: `startListening()`, `stopListening()`, `stopSpeaking()`, `handleMicTap()`

- `SpeechRecognizerController.kt`
  - Thin wrapper over `SpeechRecognizer`
  - Converts callbacks into structured results: partial text, final text, error, timeout
  - No session logic inside

- `SpeechOutputController.kt`
  - Thin wrapper over `TextToSpeech`
  - Speaks one utterance at a time
  - Emits start/done/error callbacks
  - `speak()` and `stop()` are explicit; no silent failures

- `VoiceCommandRouter.kt`
  - Maps normalized transcript + current `CapsuleMode` to existing actions
  - Control words handled before generic text routing:
    - `"stop"` -> `Op.Interrupt`
    - `"take over"` -> `Op.Takeover`
    - `"resume"` -> `Op.Resume`
    - `"done"` in `WaitingForAction` -> `Op.UserResponse(callId, "done")`
  - Everything else falls back to the normal text rules

- `VoiceSummaryFormatter.kt`
  - Produces short TTS strings from existing events
  - Examples:
    - `TaskCompleted(result="Email sent", GOAL_ACHIEVED)` -> `"Email sent."`
    - empty success result -> `"Task completed."`
    - `AskUser(question)` -> speak the question
    - `SessionError` -> brief error summary

- `VoiceStateHolder.kt`
  - Single source of truth for UI-facing voice state
  - Shared by main app and overlay UI

### Existing code changes

- `app/src/main/AndroidManifest.xml`
  - Add `android.permission.RECORD_AUDIO`

- `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt`
  - Create and own `VoiceInteractionManager`
  - Forward active session observation to it
  - Dispose it with the service

- `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt`
  - Request runtime mic permission when needed
  - Delegate voice actions to `AgentService.instance`

- `app/src/main/kotlin/com/moonkey/androidagent/ui/capsule/surface/SmartCapsuleSurface.kt`
  - Add mic affordance and small voice status treatment
  - Reuse one submission resolver for typed and spoken text

- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt`
  - No new task states
  - Only expose enough mode/context for routing and UI composition

- `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsState.kt`
  - Add voice settings:
    - `voiceInputMode`: `OFF`, `PUSH_TO_TALK`, `AUTO_TALK`
    - `spokenFeedbackEnabled`: `Boolean`

### Settings policy

Default:
- `voiceInputMode = OFF`
- `spokenFeedbackEnabled = true`

Reason:
- microphone access is sensitive
- TTS summaries are low-risk and useful
- push-to-talk is the safest first active mode

## Interactions

### 1. Voice state machine

`VoiceUiState` is independent of `SessionState` and `CapsuleMode`:

`Disabled -> Ready -> Listening -> Processing -> Speaking -> Ready`

Extra transitions:
- `Listening -> Ready` on cancel/timeout
- `Speaking -> Listening` when auto-talk is enabled
- `Speaking -> Ready` when push-to-talk is enabled
- `Speaking -> Listening` immediately on barge-in
- `Any -> Error -> Ready` after surfacing the failure

This keeps task execution and audio transport separate.

### 2. Input routing

Final transcript routing:

| Capsule mode | Spoken transcript result |
| --- | --- |
| `Hidden`, `Done`, `Error` | start/send task |
| `WaitingForInput` | `Op.UserResponse(callId, transcript)` |
| `WaitingForAction` | `Op.UserResponse(callId, transcript.ifBlank { "done" })` |
| `Running`, `TakeoverPending`, `Takeover` | supplement unless transcript matches a local control command |

The important rule is that voice does not invent new session semantics.

### 3. Spoken output policy

Speak only high-value events:
- `AskUser` question/action prompt
- `TaskCompleted`
- `SessionError`
- takeover/resume confirmations
- optional short "Listening" / "Stopped" earcon-like prompts

Do not speak:
- every `MessageDelta`
- every tool/action event
- long chain-of-thought-like status updates

This follows the brief: voice feedback should be restrained.

### 4. Barge-in and audio focus

- Starting STT always calls `stopSpeaking()` first.
- TTS and STT both request transient audio focus.
- If focus is denied, surface a visible voice error and fall back to text-only interaction.
- No overlapping listen/speak states are allowed.

### 5. Permission and failure flow

- If `RECORD_AUDIO` is missing in main app, request it before listening.
- If the user is outside the app and permission is missing, show a capsule error and deep-link back to `MainActivity`.
- If `SpeechRecognizer` is unavailable, disable voice input but keep TTS if available.
- If `TextToSpeech` init fails, disable spoken feedback but keep STT.

Every degradation is explicit in UI/state; none are silent.

### 6. Phase rollout

Phase 1:
- mic button in main app and overlay
- push-to-talk STT
- concise TTS summaries
- interrupt TTS on listen

Phase 2:
- `AUTO_TALK` mode
- auto-rearm listening after spoken output or after `AskUser`
- better partial-transcript UI

Phase 3:
- foreground/always wake word as another trigger into `VoiceInteractionManager.startListening()`

## Trade-offs

- **Service-scoped voice runtime vs activity-scoped**
  - Service-scoped wins because the product must work outside the main app and already centralizes overlay/session ownership there.

- **Separate `VoiceUiState` vs expanding `CapsuleMode`**
  - Separate state wins because task and transport are different concerns. This preserves the current clean capsule state machine.

- **System STT/TTS vs third-party voice stack**
  - System APIs win for the first version because they are cheaper and align with the brief's "Phase 1 zero-cost" direction. Quality is lower and offline behavior varies by device, but the architecture can swap engines later.

- **Event-derived spoken summaries vs model-authored voice directives**
  - Event-derived summaries win first because they require no prompt/protocol expansion and stay deterministic. The cost is less expressive speech. If the loop proves valuable, optional voice directives can be added later as metadata rather than making v1 depend on them.

- **Push-to-talk first vs wake word first**
  - Push-to-talk wins because it is simpler, safer, and fits current permission/UI patterns. Wake word should be layered later once the basic voice loop is stable.

## Self-review

This design stays grounded in the current codebase: `AgentService` owns runtime behavior, `CapsuleMode` stays intact, and all voice input resolves back into existing ops and session flow. It deliberately avoids a second conversation pipeline, keeps failure modes explicit, and leaves a clean extension point for auto-talk and wake word without forcing those costs into the first implementation.
