# Voice-First Alignment

## Goal

Add a voice-first loop to Android Agent so the user can:
- start a task by speaking,
- answer `ask_user` prompts by voice,
- send spoken follow-ups while the agent is active,
- hear short spoken feedback when the agent finishes, needs attention, or fails.

Version 1 must work in both places the product already operates:
- in the main app,
- in the accessibility overlay Smart Capsule.

Version 1 is not trying to do:
- wake word,
- full-duplex conversation,
- reading streaming assistant text aloud,
- agent-authored voice style control,
- a second voice-only session model.

## Current Code Constraints

The design has to match the code that exists today.

1. `AgentService` already owns the overlay runtime, the active overlay-facing `CapsuleStateHolder`, and the single session event collector through `observeSession()` plus `AgentServiceEventHandler`.
2. `MainActivity` owns `SessionCoordinator`, but overlay-first entry does not go through `SessionCoordinator`. `AgentService.runAgent()` can already create a new session directly.
3. `SmartCapsuleSurface` already defines the canonical typed-input behavior by `CapsuleMode`:
   - `Hidden` -> new task,
   - `WaitingForInput` -> `Op.UserResponse(callId, text)`,
   - everything else currently goes through supplement,
   - `WaitingForAction` completion is currently an explicit `"done"` action on Row 2, not free-form `UserResponse`.
4. `CapsuleMode` already carries the routing context voice needs, including `callId` for `WaitingForInput` and `WaitingForAction`.

Those facts rule out a pure session-scoped voice owner. The first spoken task in overlay mode exists before any session exists, so a session-owned voice controller cannot be the only runtime owner without adding a second bootstrap layer. That is unnecessary complexity.

## Core Decision

Use a split that matches the existing architecture:

- `AgentService` owns voice transport runtime.
- Session and capsule state own voice meaning.

In practice:
- STT, TTS, audio focus, listen/speak interruption, and mic availability live in a service-scoped controller.
- Transcript routing uses the same capsule/session rules as typed input.
- Spoken output is derived from existing agent events, not a new model output channel.

This keeps first-utterance bootstrap simple while preserving one canonical conversation model.

## Architecture

### 1. Voice runtime owner

Add `voice/VoiceInteractionManager.kt` owned by `AgentService`.

Responsibilities:
- start and stop `SpeechRecognizer`,
- start and stop `TextToSpeech`,
- own transient audio state,
- stop TTS before starting STT,
- expose voice UI state for both main-app and overlay capsule renderers,
- accept the final transcript and route it through the shared resolver,
- react to high-value agent events for spoken summaries.

This object is service-scoped because:
- the overlay lives in the service,
- the first spoken task may happen before a session exists,
- only one active session exists at a time,
- the current event fan-out path is already in the service.

This object is not allowed to own durable conversation semantics. It reads the current session and capsule state, but it does not invent a second state machine for tasks.

### 2. Voice state stays orthogonal to `CapsuleMode`

Keep task state and voice transport state separate.

Add a small voice UI state:
- `Disabled`
- `Ready`
- `Listening`
- `Processing`
- `Speaking`
- `Error`

`CapsuleMode` remains the task state machine. Voice state is rendered as a mic/speaking affordance on top of the existing Smart Capsule surface.

This is consistent with the current codebase, where `CapsuleStateHolder` is the canonical task UI state holder and already keeps some transient UI flags outside `CapsuleMode`.

### 3. One shared input resolver

Typed and spoken input must go through one resolver. Do not add a voice-only command router.

Add a shared resolver, for example `ui/capsule/CapsuleInputResolver.kt`, used by both:
- `SmartCapsuleSurface` typed submission,
- `VoiceInteractionManager` final transcript submission.

Resolver rules:
- `Hidden`, `Done`, `Error` -> start/send new task,
- `WaitingForInput` -> `Op.UserResponse(callId, transcript)`,
- `WaitingForAction`:
  - exact normalized `"done"` -> `Op.UserResponse(callId, "done")`,
  - otherwise -> `Op.Supplement(transcript)`,
- `Running`, `TakeoverPending`, `Takeover` -> `Op.Supplement(transcript)`.

This matches the current typed UI better than either initial draft:
- the app already treats `WaitingForAction` completion as explicit `"done"`,
- free-form text in non-question active states is supplement, not a separate response channel.

### 4. No local voice control commands in v1

Do not client-side map transcripts like `"stop"`, `"take over"`, or `"resume"` to control ops in version 1.

Reasons:
- speech transcripts are noisy,
- those controls already have explicit buttons,
- local keyword matching creates a second, fragile intent path that typed input does not have,
- it increases false positives for ordinary task text.

If voice control commands are needed later, they should be added deliberately and validated with strong evidence, not inferred from a few local string matches.

### 5. Single event subscription point

Keep `AgentServiceEventHandler` as the single fan-out point for session events.

Extend that path so high-value events update both:
- overlay/main-app capsule state,
- `VoiceInteractionManager` spoken feedback.

Do not create a second independent event collector for voice. The service already has one collector, and duplicating it would increase drift and race risk.

High-value spoken events in v1:
- `AskUser`,
- `TaskCompleted`,
- `SessionError`,
- `SessionCompleted` if no better task-completion message exists,
- takeover/resume confirmation only if the UX needs it after implementation review.

Do not speak:
- `MessageDelta`,
- tool/action noise,
- intermediate thought updates,
- long status streams.

## Components

Create `app/src/main/kotlin/com/moonkey/androidagent/voice/`:

- `VoiceInteractionManager.kt`
  - service-scoped orchestrator,
  - owns STT, TTS, audio focus, and voice UI state,
  - routes transcripts through the shared resolver,
  - receives high-value agent events from `AgentServiceEventHandler`.

- `SpeechRecognizerAdapter.kt`
  - thin wrapper around `SpeechRecognizer`,
  - emits partial transcript, final transcript, timeout, and structured error,
  - no session logic.

- `SpeechOutputAdapter.kt`
  - thin wrapper around `TextToSpeech`,
  - explicit `speak()` and `stop()`,
  - reports init/runtime failure explicitly.

- `VoiceUiState.kt`
  - sealed type for transient voice transport state.

- `VoiceSummaryFormatter.kt`
  - converts existing events into short utterances.

Likely existing-file changes:

- `app/src/main/AndroidManifest.xml`
  - add `android.permission.RECORD_AUDIO`.

- `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt`
  - create and own `VoiceInteractionManager`,
  - expose voice actions to overlay and main app,
  - keep voice alive across session creation/reload,
  - stop voice runtime on service destroy.

- `app/src/main/kotlin/com/moonkey/androidagent/app/AgentServiceEventHandler.kt`
  - forward high-value session events to voice summary handling.

- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt`
  - continue exposing `CapsuleMode` and `callId` as routing context,
  - do not absorb voice transport state.

- `app/src/main/kotlin/com/moonkey/androidagent/ui/capsule/surface/SmartCapsuleSurface.kt`
  - use the shared resolver for typed input too,
  - render mic affordance and voice status,
  - optionally show partial transcript in the existing input draft.

- `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt`
  - request runtime mic permission,
  - delegate mic actions into `AgentService.instance`,
  - open settings or request permission when needed.

- `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsState.kt`
  - add voice-related settings.

## Interaction Model

### Input flow

Version 1 is push-to-talk only.

Flow:
1. User taps mic in Smart Capsule.
2. App checks mic permission and recognizer availability.
3. Current TTS stops immediately.
4. STT starts listening.
5. Partial transcript may update the existing draft UI.
6. Final transcript goes through the shared capsule input resolver.
7. Resolver submits the same op path typed input would have used.

There is no voice-specific session protocol.

### Output flow

Spoken output is short and summary-only.

Examples:
- `AskUser(question)` -> speak the question.
- Successful completion with result text -> speak the result.
- Successful completion without useful result text -> `"Task completed."`
- Error -> brief spoken error summary.

TTS must always be interruptible. Starting a listen action always stops any current speech first.

### `WaitingForAction`

This state needs to follow the existing UI contract instead of inventing a new one.

Rule:
- `"done"` means the user completed the requested action and submits `Op.UserResponse(callId, "done")`.
- Any other transcript is treated as supplement, not as a special action response.

That stays aligned with the current Row 2 `Done` button and current typed free-form behavior.

One UX detail remains open:
- after a non-`done` transcript in `WaitingForAction`, should voice auto-submit supplement immediately, or only place it in draft text for confirmation?

Default recommendation: auto-submit as supplement, because that matches push-to-talk behavior in the rest of the capsule and keeps voice useful without an extra tap.

### Permission and failure handling

- Missing `RECORD_AUDIO` in main app:
  - request runtime permission before listening.

- Missing `RECORD_AUDIO` while user is outside the app:
  - show explicit capsule error,
  - deep-link to `MainActivity` so permission can be granted there.

- No recognizer:
  - disable voice input,
  - keep text input and TTS if available.

- No TTS:
  - keep voice input,
  - disable spoken feedback only.

- Audio focus denied:
  - show a transient visible error,
  - treat it as a per-attempt failure, not a sticky permanent disable.

- Recognition error after partial transcript:
  - preserve partial text in draft if practical so the user can edit or send manually.

No failure should silently disappear.

## Settings

Keep settings simple.

Recommended fields:
- `voiceInputMode: OFF | PUSH_TO_TALK | AUTO_TALK`
- `spokenFeedbackEnabled: Boolean`

Recommended defaults for v1:
- `voiceInputMode = OFF`
- `spokenFeedbackEnabled = OFF`

Reason:
- the app runs as an accessibility service and can surface in public contexts,
- unexpected speech is higher-cost than silent capability,
- voice is an opt-in feature and should not surprise users.

When the user first enables voice input, the UI may suggest turning spoken feedback on. `AUTO_TALK` stays disabled by default and is phase 2.

## Rollout

### Phase 1

- push-to-talk mic in main app and overlay,
- shared typed/spoken input resolver,
- concise spoken summaries,
- barge-in by stopping TTS before STT,
- explicit permission and failure handling.

### Phase 2

- `AUTO_TALK` mode,
- optional auto-rearm after TTS or after `AskUser`,
- better partial transcript presentation and tuning.

### Phase 3

- wake word or another passive trigger, layered on top of the same service-owned transport manager.

## Why This Is The Right Merge

This merge keeps the strong parts from both initial drafts and drops the weak parts.

Kept:
- voice state is separate from `CapsuleMode`,
- typed and spoken input share one routing model,
- spoken output is summary-only,
- push-to-talk comes before auto-talk and wake word.

Dropped:
- pure session-scoped voice ownership, because it fails the first spoken task without extra bootstrap machinery,
- client-side voice control keywords, because they create a fragile second command path,
- free-form `WaitingForAction` voice responses as `UserResponse`, because the current UI does not work that way.

The result is simpler than either original extreme:
- one transport owner,
- one conversation model,
- one event fan-out point,
- one input resolver.

## Open Questions

1. On `WaitingForAction`, should non-`done` voice transcripts auto-submit as supplement immediately, or land in editable draft first?
2. When the user first enables voice input, should spoken feedback remain off until separately enabled, or auto-enable once with a visible notice?

Neither question blocks the architecture.
