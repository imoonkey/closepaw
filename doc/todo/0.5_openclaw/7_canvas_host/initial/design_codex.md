# 7 Canvas Host - Codex Design

## Goal

Add an agent-driven intermediate UI layer between plain chat text and full device control.

Step 1 should let the agent present native structured interaction when text is inefficient:
- ask for a typed answer
- ask the user to pick one option
- ask for confirmation
- ask the user to perform a manual action and resume

This must stay inside the existing Android Agent architecture: Compose UI, `Op`/`AgentEvent` unidirectional flow, session-scoped tools, and persisted chat/session history. WebView is explicitly out of scope for Step 1.

## Architecture Constraints Found

1. The repo already has a blocking user-help path.
   `ask_user` suspends through `UserResponseChannel`, emits `AskUser`, and the Smart Capsule collects the response. Today it only supports `QUESTION` and `ACTION`.

2. The current interactive surface is overlay-centric, not transcript-centric.
   `CapsuleStateHolder` has `WaitingForInput` and `WaitingForAction`, but chat/history do not have a first-class interaction block. A pending `ask_user` request is visible in the capsule, not in the message transcript.

3. Chat persistence only understands `Text` and `Action`.
   `ChatMessage.Agent.contentBlocks`, `ContentBlockRecord`, `MessageConverter`, and `AgentMessageBuffer` only model text and tool cards. Any richer interaction needs a new persisted block type.

4. The app is already split into two UI hosts.
   The same session may render in the main app chat or in system overlays. In `MAIN_APP`, system overlays must stay hidden. In overlay contexts, the capsule is the only interactive host. A design that only works in chat is incomplete.

5. The protocol is strongly typed and unidirectional.
   New interaction behavior should stay inside `Op` and `AgentEvent`, not side channels. The clean seam is the existing `ask_user` tool path.

6. Runtime history and persisted chat are separate systems.
   The LLM sees `ResponseItem` function call/output pairs. The user sees `SessionRecordingService` snapshots. Step 1 must update both systems or the agent and user transcripts will diverge.

7. Pending user-interaction recovery is weak today.
   Session reload restores history/todos/scratchpad, but not a suspended `ask_user` invocation. Step 1 should not pretend to solve process-death recovery for in-flight interaction requests.

## Proposed Approach

Make Canvas Host a native, schema-driven interaction system built on top of the existing `ask_user` tool path.

The core idea is:
- keep `ask_user` as the tool name
- replace the current binary request model with a typed interaction spec
- render that same spec in whichever host is active: chat in the main app, capsule in overlay contexts
- persist the interaction request in the transcript

This gives us the OpenClaw benefit, but in native Compose instead of WebView.

## Step 1 Scope

Step 1 supports only blocking interaction requests:

```kotlin
sealed interface InteractionSpec {
    data class TextInput(
        val prompt: String,
        val placeholder: String? = null,
        val submitLabel: String = "Send"
    ) : InteractionSpec

    data class SingleChoice(
        val prompt: String,
        val options: List<ChoiceOption>
    ) : InteractionSpec

    data class Confirmation(
        val prompt: String,
        val details: List<DetailRow> = emptyList(),
        val confirmLabel: String = "Confirm",
        val cancelLabel: String = "Cancel"
    ) : InteractionSpec

    data class ActionRequired(
        val instruction: String,
        val doneLabel: String = "Done"
    ) : InteractionSpec
}
```

Responses are typed too:

```kotlin
sealed interface InteractionResponse {
    data class Text(val value: String) : InteractionResponse
    data class Choice(val optionId: String) : InteractionResponse
    data class Confirmation(val confirmed: Boolean) : InteractionResponse
    data object Done : InteractionResponse
}
```

This collapses the current special cases into one canonical flow: one pending interaction, one typed response, one renderer model.

## Component Design

### 1. Protocol

Replace the narrow `AskUserType + message + callId` event payload with a typed request:

```kotlin
data class UserInteractionRequested(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val callId: String,
    val spec: InteractionSpec
) : UserInteractionDomainEvent

data class UserInteractionResolved(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val callId: String,
    val response: InteractionResponse,
    val displayText: String
) : UserInteractionDomainEvent
```

`Op.UserResponse` should carry `InteractionResponse` instead of raw `String`.

### 2. Tool Layer

`ask_user` remains the LLM-facing tool, but its schema becomes structured:

```json
{
  "kind": "single_choice",
  "prompt": "Found 3 flights. Which one should I book?",
  "options": [
    { "id": "ca1234", "label": "CA1234 08:00", "description": "$320 · nonstop" }
  ]
}
```

Rules:
- exactly one pending request per session, same as today
- tool blocks until a typed response arrives or timeout occurs
- tool returns a normalized machine-readable observation string for the LLM

Examples:
- text input -> `User response: {"kind":"text_input","text":"..."}`  
- choice -> `User response: {"kind":"single_choice","option_id":"ca1234"}`  
- confirmation -> `User response: {"kind":"confirmation","confirmed":true}`  
- action required -> `User response: {"kind":"action_required","done":true}`

This keeps the agent runtime simple: the user-facing UI gets a rich schema, while the LLM still receives a compact tool result.

### 3. Session Runtime

`UserResponseChannel` changes from `CompletableDeferred<String>` to `CompletableDeferred<InteractionResponse>`.

The session flow becomes:

1. `ask_user` validates and builds `InteractionSpec`
2. tool emits `UserInteractionRequested`
3. UI renders the request
4. user submits `Op.UserResponse(callId, response)`
5. `AgentSession` delivers typed response to `UserResponseChannel`
6. session emits `UserInteractionResolved`
7. tool returns normalized output and the turn continues

No extra queue, no polling, no second UI state machine.

### 4. UI Host Model

Introduce a shared renderer model:

```kotlin
data class PendingInteraction(
    val callId: String,
    val spec: InteractionSpec,
    val status: InteractionStatus
)
```

Host policy:
- `MAIN_APP`: chat is the primary interactive host; capsule shows compact pending state and control actions
- overlay contexts: capsule is the primary interactive host

This avoids duplicate live controls while still keeping the transcript visible in the main app.

### 5. Capsule State Machine

Replace:
- `WaitingForInput`
- `WaitingForAction`

with:

```kotlin
data class WaitingForInteraction(val interaction: PendingInteraction) : CapsuleMode
```

This is simpler and scales naturally to choice/confirmation without adding more mode variants.

### 6. Chat Transcript

Extend chat content blocks:

```kotlin
sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
    data class Action(val data: ActionCardData) : ContentBlock
    data class Interaction(val data: InteractionCardData) : ContentBlock
}
```

`ChatEventReducer` behavior:
- `UserInteractionRequested` appends an interaction block to the current agent message
- `UserInteractionResolved` marks that block resolved and inserts a normal user bubble using `displayText`

Examples of `displayText`:
- `Selected: CA1234 08:00`
- `Confirmed`
- `Done`
- the raw text answer for text input

This keeps the transcript readable without creating a second user-message type.

### 7. Persistence

Add persisted interaction blocks to:
- `ContentBlockRecord`
- `MessageConverter`
- `AgentMessageBuffer`
- `SessionRecordingService`

Persisted interaction state should include:
- `callId`
- `spec`
- `status` (`pending`, `resolved`, `timed_out`, `cancelled`)
- optional `displayText`

This is required so session history and resumed transcript rendering match what the user actually saw.

## Interaction Flow

```text
LLM
  -> ask_user(kind=single_choice, ...)
  -> AskUserTool
  -> UserInteractionRequested(callId, spec)

Main app:
  -> ChatEventReducer adds Interaction block
  -> user taps a choice in chat
  -> Op.UserResponse(callId, Choice("ca1234"))

Overlay:
  -> CapsuleStateHolder enters WaitingForInteraction
  -> user interacts in capsule
  -> Op.UserResponse(callId, ...)

Session:
  -> UserResponseChannel completes
  -> UserInteractionResolved(callId, response, displayText)
  -> ask_user returns normalized tool output
  -> TurnExecutionPhaseRunner records FunctionCallOutput
```

## Non-Goals for Step 1

- WebView or arbitrary HTML/JS rendering
- a generic plugin/widget platform
- process-death restoration of an in-flight suspended interaction request
- non-blocking structured display cards that do not expect a response

Those can be added later only if the native schema becomes a bottleneck.

## Phase Plan

### Step 1

Implement the typed blocking interaction path described above.

This is independent and sufficient for the main use cases from the brief:
- choose among options
- confirm a planned action
- answer a targeted question
- complete a manual prerequisite

### Step 2

Add non-blocking structured transcript cards on the same rendering foundation:
- summaries
- key/value previews
- result lists

These should reuse the same `ContentBlock.Interaction` renderer family, but they do not block the agent.

### Step 3

Re-evaluate whether arbitrary canvas rendering is still needed.

Only introduce WebView if native schema-driven Compose clearly fails for real tasks. If that happens, WebView should be a second host implementation behind the same typed request model, not a separate parallel architecture.

## Trade-offs

### Why this wins

- fits the existing tool -> event -> UI -> op -> tool-result loop
- keeps one source of truth for interaction state
- works in both chat and overlay contexts
- keeps transcript/history aligned with what the user saw
- avoids browser runtime, JS bridge, and WebView security/focus issues

### Costs

- less expressive than arbitrary HTML
- every new widget type needs app code
- pending interaction reload across process death remains unsolved in Step 1

## Alternatives Considered

### 1. WebView first

Rejected.

The codebase is already Compose-native, overlay-heavy, and state-machine-driven. WebView would add a second rendering stack, JS bridge, lifecycle edge cases, and weaker transcript persistence before we have exhausted the simple native path.

### 2. Keep current `ask_user` and only add more enum values

Rejected.

Adding `QUESTION`, `ACTION`, `CHOICE`, `CONFIRMATION`, and more mode variants would keep spreading special cases through protocol, capsule state, and UI rendering. A typed `InteractionSpec` is simpler.

### 3. Chat-only rich messages

Rejected.

The agent often operates while the user is outside the main app. Overlay contexts still need an interaction surface, so the design must support both chat and capsule hosts.

## Self-Review

This design stays inside existing repo constraints:
- native Compose, not WebView
- one canonical blocking interaction path
- no new side-channel state
- transcript and runtime both updated
- main-app and overlay hosts both covered

The main deliberate limitation is process-death recovery for a pending request. That is acceptable for Step 1 because the current architecture does not recover suspended tool invocations either.
