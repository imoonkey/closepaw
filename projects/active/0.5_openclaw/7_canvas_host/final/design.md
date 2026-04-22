# Canvas Host Design

## Goal

Add an agent-driven native UI layer between plain chat text and direct device control so the agent can:
- show structured results
- ask the user to choose an option
- ask for confirmation
- collect a short text answer
- pause for a manual prerequisite and resume

This must stay inside the existing Android Agent architecture:
- Jetpack Compose UI
- `Op` and `AgentEvent` as the only state transport
- session-scoped tools and suspension
- persisted chat/session history

WebView is out of scope for this release.

## Design Summary

Step 1 introduces one shared schema, one canonical tool (`ask_user` extended), and two host policies:
- the main app chat is the primary canvas host
- the Smart Capsule remains a compact control surface, not a tiny general-purpose app

The resulting system supports both:
- display-only cards that return immediately
- blocking cards that suspend until the user responds or the request times out

## Key Decisions

### 1. One typed interaction schema

Replace the current `AskUserType` enum (`QUESTION`, `ACTION`) with one typed schema for all rich interactions:

```kotlin
sealed interface InteractionSpec {
    data class Summary(
        val title: String,
        val rows: List<DetailRow>
    ) : InteractionSpec

    data class SingleChoice(
        val title: String,
        val prompt: String,
        val options: List<ChoiceOption>
    ) : InteractionSpec

    data class Confirmation(
        val title: String,
        val prompt: String,
        val details: List<DetailRow> = emptyList(),
        val confirmLabel: String = "Confirm",
        val cancelLabel: String = "Cancel"
    ) : InteractionSpec

    data class TextInput(
        val title: String,
        val prompt: String,
        val placeholder: String? = null,
        val submitLabel: String = "Send"
    ) : InteractionSpec

    data class ActionRequired(
        val title: String,
        val instruction: String,
        val doneLabel: String = "Done"
    ) : InteractionSpec
}
```

Response types are also explicit:

```kotlin
sealed interface InteractionResponse {
    data class Choice(val optionId: String) : InteractionResponse
    data class Confirm(val confirmed: Boolean) : InteractionResponse
    data class Text(val value: String) : InteractionResponse
    data object Done : InteractionResponse
}
```

### 2. Extend `ask_user`, do not add a second tool

Widen `ask_user` to accept the full `InteractionSpec` schema. Delete the old `AskUserType` enum. One tool, one suspension channel, one response path.

Rationale:
- The product is pre-release — there is no backward compatibility cost.
- `ask_user` already has the suspension machinery (`UserResponseChannel`), call-id correlation, timeout, and cancellation. Adding a second tool (`show_canvas`) that needs the same machinery creates a parallel channel with duplicate plumbing.
- Both reviews flagged split-brain as the top risk of a two-tool design. One tool eliminates it.
- `Summary` (display-only) is a natural extension: `ask_user` shows something to the user and returns immediately. The LLM learns one tool name for all structured interaction.
- `Op.UserResponse` carries `InteractionResponse` instead of `String`. Same op, typed payload.

### 3. Display and blocking cards are both Step 1

Step 1 must cover the full brief:
- `Summary` is display-only and returns immediately
- `SingleChoice`, `Confirmation`, `TextInput`, and `ActionRequired` are blocking

Display-only cards do **not** occupy the session’s one pending blocking slot. Blocking cards do.

### 4. The capsule stays small

Host policy is explicit:

- `MAIN_APP`
  - chat renders every card type
  - user responds directly in the card

- overlay / viewer contexts
  - capsule may render inline only:
    - `TextInput`
    - `ActionRequired`
  - `SingleChoice`, `Confirmation`, and `Summary` do not render as full cards in the capsule
  - for those, the capsule shows a compact pending banner with an "Open app" affordance

This keeps Step 1 realistic. The app is the full canvas host. The capsule is the control strip and fallback entry point.

### 5. Rich cards are first-class transcript content

Extend the transcript model:

```kotlin
sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
    data class Action(val data: ActionCardData) : ContentBlock
    data class Interaction(val data: InteractionCardData) : ContentBlock
}
```

Persist rich cards through:
- `ContentBlockRecord`
- `MessageConverter`
- `AgentMessageBuffer`
- `SessionRecordingService`

Persist at least:
- `callId`
- `spec`
- `state`
- `responseSummary`

## Tool Contract

`ask_user` schema becomes:

```json
{
  "kind": "single_choice",
  "title": "Choose a flight",
  "prompt": "I found 3 reasonable options.",
  "options": [
    { "id": "ua1", "label": "UA123 8:20 AM", "description": "$320 · nonstop" }
  ]
}
```

Display-only example:

```json
{
  "kind": "summary",
  "title": "Search results",
  "rows": [
    { "label": "Top result", "value": "UA123 8:20 AM" }
  ]
}
```

Blocking vs display semantics are derived from `kind`, not from a separate flag.

Tool output examples:
- Choice: `User response: {"kind":"single_choice","option_id":"ca1234"}`
- Confirmation: `User response: {"kind":"confirmation","confirmed":true}`
- Text input: `User response: {"kind":"text_input","text":"..."}`
- Action done: `User response: {"kind":"action_required","done":true}`
- Summary: `Displayed summary: "Search results" (3 rows)`
- Timeout: `User did not respond within the timeout. Consider continuing without their input or trying a different approach.`

## Protocol

Replace `AskUser`, `AskUserType`, and `AskUserDomainEvent` with:

```kotlin
sealed interface InteractionDomainEvent : AgentEvent

data class InteractionRequested(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val callId: String?,        // null for Summary (display-only)
    val spec: InteractionSpec,
    val blocking: Boolean
) : InteractionDomainEvent

data class InteractionResolved(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val callId: String,
    val response: InteractionResponse?,
    val displayText: String,
    val outcome: InteractionOutcome
) : InteractionDomainEvent

enum class InteractionOutcome {
    RESOLVED,
    TIMED_OUT,
    CANCELLED
}
```

`Op.UserResponse` carries typed responses:

```kotlin
data class UserResponse(
    val callId: String,
    val response: InteractionResponse   // was: String
) : Op
```

Delete: `AskUser`, `AskUserType`, `AskUserDomainEvent`, `Op.CanvasResponse`.
No new Op type needed — `Op.UserResponse` is the single path.

`displayText` and `responseSummary` stay as separate fields on purpose:
- `displayText` is the transcript-facing sentence recorded as the resolved chat event
- `responseSummary` is the compact card-facing summary stored with `InteractionCardData`

They usually default to the same string, but they should not be collapsed because transcript copy and card copy can diverge without changing the underlying response payload.

## Runtime Behavior

### Display-only card

1. LLM calls `ask_user(kind=summary, ...)`
2. tool emits `InteractionRequested(blocking=false)`
3. transcript appends an `Interaction` block (state=`Display`)
4. tool returns immediately with a normalized summary output for runtime history

### Blocking card

1. LLM calls `ask_user(kind=single_choice|confirmation|text_input|action_required, ...)`
2. tool emits `InteractionRequested(blocking=true)`
3. UI renders the request in the current host according to host policy
4. user submits `Op.UserResponse(callId, InteractionResponse)`
5. `UserResponseChannel` completes the pending deferred
6. tool returns normalized output
7. session emits `InteractionResolved`
8. transcript updates the card state and appends a readable response summary

## Timeout And Cancellation

- Blocking requests use the same default timeout as today: 5 minutes
- On timeout:
  - card state becomes `TimedOut`
  - tool returns a normalized "user did not respond" result
  - session emits `InteractionResolved(outcome=TIMED_OUT, response=null, ...)`
- On session stop/shutdown:
  - the pending request is cancelled
  - card state becomes `Cancelled`

## State Model

```kotlin
data class InteractionCardData(
    val callId: String?,             // null for Summary
    val spec: InteractionSpec,
    val state: InteractionState,
    val responseSummary: String? = null
)

enum class InteractionState {
    Display,       // Summary — non-blocking, already resolved
    Pending,       // Blocking — waiting for user
    Resolved,      // User responded
    TimedOut,      // No response within timeout
    Cancelled      // Session stopped while pending
}
```

The one-pending-request rule applies only to `Pending` blocking cards.
Display-only cards never consume that slot.

## Capsule Mode Changes

Replace `WaitingForInput` and `WaitingForAction` with:

```kotlin
data class WaitingForInteraction(
    val callId: String,
    val spec: InteractionSpec,
    val canRenderInline: Boolean    // true for TextInput, ActionRequired
) : CapsuleMode
```

Delete: `WaitingForInput`, `WaitingForAction`.

`canRenderInline` is derived from spec type when entering this mode:
- `true` → render the interaction UI inline in the capsule
- `false` → render compact "respond in app" banner

## Component Changes

### Delete
- `AskUserType.kt`
- `AskUserEvents.kt`

### New
- `InteractionSpec.kt` — sealed hierarchy + `ChoiceOption`, `DetailRow`
- `InteractionResponse.kt` — sealed response hierarchy
- `InteractionEvents.kt` — `InteractionRequested`, `InteractionResolved`, domain marker
- `InteractionCardData.kt` — transcript card data + `InteractionState` enum
- `ChoiceCard.kt`, `ConfirmationCard.kt`, `SummaryCard.kt`, `TextInputCard.kt`, `ActionRequiredCard.kt` — Compose renderers

### Modified
- `Op.kt` — `UserResponse.response: String` → `InteractionResponse`
- `UserResponseChannel.kt` — `CompletableDeferred<String>` → `CompletableDeferred<InteractionResponse>`
- `AskUserTool.kt` — rewrite to build `InteractionSpec`, handle all kinds
- `AgentEventDispatcher.kt` — replace `emitAskUser` with `emitInteractionRequested/Resolved`
- `AgentEventDomains.kt` — replace `AskUserDomainEvent` with `InteractionDomainEvent`
- `ChatEventReducer.kt` — handle new events, insert and resolve interaction cards
- `ChatMessage.kt` — add `ContentBlock.Interaction`
- `CapsuleMode.kt` — replace `WaitingForInput/Action` with `WaitingForInteraction`
- `CapsuleStateHolder.kt` — derive `canRenderInline` from spec type
- `MessageBubble.kt` — route `ContentBlock.Interaction` to card composables
- `ContentBlockRecord` / `MessageConverter` / `AgentMessageBuffer` — add interaction persistence

## Non-Goals

- WebView or arbitrary HTML/JS rendering
- Turning the capsule into a full card browser
- Process-death restoration of an in-flight blocking request
- Multi-request queues in Step 1

## Why Not WebView

WebView adds a second rendering stack, JS bridge, lifecycle complexity (leak-prone), security surface (agent-generated HTML → XSS risk), and harder persistence/replay. None of that is needed to validate whether structured native cards improve agent-user collaboration. If native cards become the bottleneck for a proven use case, WebView can be a second host implementation behind the same `InteractionSpec` model.

## Why One Tool, Not Two

Both initial designs agreed on the typed schema. The disagreement was whether to extend `ask_user` or add `show_canvas`. Extending `ask_user` wins because:

1. **No split-brain.** One tool = one suspension channel, one Op, one event pair, one reducer path, one capsule mode. A second tool duplicates all of these.
2. **No migration ambiguity.** If `show_canvas` exists alongside `ask_user`, what happens to the old `QUESTION`/`ACTION` types? They overlap with `TextInput`/`ActionRequired`. The LLM and prompt tuning must learn "use `ask_user` for these two cases, `show_canvas` for these four." Extending `ask_user` means: always use `ask_user`, it handles everything.
3. **Pre-release product.** No backward compatibility concern. The old enum is deleted, the channel is retyped, done.
4. **`Summary` fits naturally.** `ask_user(kind=summary)` = "show the user this structured data." The tool returns immediately. The name `ask_user` is slightly misleading for display-only, but the tool description makes it clear. This is a minor naming aesthetic, not a design problem.

## Trade-offs

### Strengths
- One tool, one channel, one pending-interaction slot — minimal conceptual surface
- Typed spec/response eliminates string parsing
- Display-only cards (Summary) in scope from day one
- Capsule stays small — only inline-renderable specs expand there
- Clean replacement of old path, no dual-channel tax

### Costs
- Every new interaction type requires app code (Compose composable + spec variant)
- Less expressive than arbitrary HTML
- Process-death recovery for pending interactions is out of scope (same as today)
- `ask_user` name is slightly misleading for display-only `Summary` (acceptable)

## Open Questions

1. **Multi-select**: Should `SingleChoice` have a `multiSelect: Boolean` flag, or should that be a separate `MultiChoice` spec? Deferred — single select covers the initial use cases.

2. **Card update**: Can the agent update a displayed Summary card with new data mid-turn? Current design says no (display-only, fire-and-forget). If needed later, add an `InteractionUpdated` event.
