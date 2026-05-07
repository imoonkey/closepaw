# 7 Canvas Host - Claude Design

## Goal

Add a native intermediate UI layer so the agent can present structured choices, confirmations, and summaries when plain chat is inefficient, without jumping straight to WebView.

The design should fit the current Android Agent split:
- chat transcript in the main app
- Smart Capsule as the cross-context control surface
- `Op` and `AgentEvent` as the only state transport
- Compose-only rendering for the first release

## What The Existing Code Already Gives Us

1. There is already a blocking user-intervention path.
   `ask_user` suspends through `UserResponseChannel`, emits `AskUser`, and resumes when `Op.UserResponse` arrives.

2. That path is intentionally narrow.
   It only models `QUESTION` and `ACTION`, both capsule-oriented. It is good for "type an answer" or "finish a manual step", but not for rich selection.

3. The transcript model is not rich enough yet.
   `ChatMessage.Agent`, `ContentBlock`, `ContentBlockRecord`, and `MessageConverter` only know `Text` and `Action`.

4. Overlay and in-app UI have different jobs.
   The main app can host a dense interactive card list. The overlay capsule cannot safely become a tiny general-purpose form engine without turning into a second app.

5. Runtime history and persisted chat already have separate responsibilities.
   The LLM loop works through `ResponseItem`. User-facing replay works through session recording and `MessageRecord`. Any design that only updates one side is wrong.

## Design Position

Do not turn `ask_user` into a universal canvas protocol in the first step.

That would force the overlay capsule, the tool layer, and the protocol layer to all grow around one abstraction before we know which structured interactions really matter.

Instead:
- keep `ask_user` for simple blocking intervention
- add a transcript-first `CanvasSpec` model for structured cards
- add one dedicated tool for canvas cards that may optionally await a response
- keep complex selection in the main app chat
- let the capsule handle only small fallback prompts like "Open app to choose"

This is the more conservative design, but it is simpler to ship without contaminating the existing `ask_user` path.

## Proposed Model

### 1. Add Native Canvas Cards To The Transcript

Introduce a third agent content block:

```kotlin
sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
    data class Action(val data: ActionCardData) : ContentBlock
    data class Canvas(val data: CanvasCardData) : ContentBlock
}
```

`CanvasCardData` is schema-driven:

```kotlin
sealed interface CanvasSpec {
    data class ChoiceList(
        val title: String,
        val prompt: String,
        val options: List<ChoiceOption>
    ) : CanvasSpec

    data class Confirmation(
        val title: String,
        val prompt: String,
        val details: List<DetailRow> = emptyList(),
        val confirmLabel: String = "Confirm",
        val cancelLabel: String = "Cancel"
    ) : CanvasSpec

    data class Summary(
        val title: String,
        val rows: List<DetailRow>
    ) : CanvasSpec

    data class TextPrompt(
        val title: String,
        val prompt: String,
        val placeholder: String? = null,
        val submitLabel: String = "Send"
    ) : CanvasSpec
}
```

The card also stores lifecycle state:

```kotlin
data class CanvasCardData(
    val callId: String?,
    val spec: CanvasSpec,
    val state: CanvasState,
    val responseSummary: String? = null
)

enum class CanvasState {
    Display,
    Pending,
    Resolved,
    TimedOut,
    Cancelled
}
```

## 2. Add A Dedicated Tool: `show_canvas`

Keep `ask_user` unchanged for Step 1. Add a new tool:

```json
{
  "mode": "display",
  "spec": {
    "kind": "summary",
    "title": "Search results",
    "rows": [
      {"label": "Top result", "value": "United 8:20 AM"}
    ]
  }
}
```

or:

```json
{
  "mode": "request",
  "spec": {
    "kind": "choice_list",
    "title": "Choose a flight",
    "prompt": "I found 3 reasonable options.",
    "options": [
      {"id": "ua1", "label": "UA123 8:20 AM", "description": "$320 · nonstop"}
    ]
  }
}
```

Tool behavior:
- `mode=display`: publish a structured card and return immediately
- `mode=request`: publish a pending card, suspend, then resume on `Op.CanvasResponse`

This avoids bending `ask_user` into two jobs:
- physical or small blocking intervention
- structured transcript UI

## 3. Add Canvas-Specific Events

Use explicit protocol events instead of overloading `AskUser`:

```kotlin
data class CanvasShown(...)
data class CanvasUpdated(...)
data class CanvasResolved(...)
```

Why:
- chat/history care about canvas cards
- capsule cares only about whether user attention is needed
- keeping canvas separate from `AskUser` preserves a clean boundary

## 4. Keep The Capsule Small

The capsule should not try to render a full option picker with arbitrary lists in overlay mode.

Overlay behavior:
- for `ask_user(question|action)`: current capsule flow remains
- for `show_canvas(mode=request)` while the user is outside the app:
  - capsule shows a compact pending state
  - message: "Agent needs your choice in the app"
  - affordance: open app / open viewer

Main-app behavior:
- chat renders the full card
- user answers directly in the card
- the card resolves in place

This is the key architectural choice. The main app is the canvas host. The capsule is the control strip, not the canvas itself.

## 5. Add A New User Op For Canvas Responses

Keep `Op.UserResponse` as the narrow path for `ask_user`.

Add:

```kotlin
sealed interface CanvasResponse {
    data class Text(val value: String) : CanvasResponse
    data class Choice(val optionId: String) : CanvasResponse
    data class Confirmation(val confirmed: Boolean) : CanvasResponse
}

data class CanvasResponseOp(
    val callId: String,
    val response: CanvasResponse
) : Op
```

This keeps each interaction channel simple:
- `ask_user` continues to deliver a string acknowledgement or answer
- `show_canvas` delivers typed card responses

## 6. Persistence Changes

Persist canvas cards through the same path as other agent content:
- add `Canvas` to `ContentBlockRecord`
- extend `MessageConverter`
- extend `AgentMessageBuffer`
- extend `SessionRecordingService`

Persist:
- `callId`
- `spec`
- `state`
- `responseSummary`

That is enough for replay, resume UI, and auditability.

## 7. Interaction Flow

### Display-only

```text
LLM
  -> show_canvas(mode=display, spec=Summary)
  -> CanvasShown
  -> Chat transcript adds Summary card
  -> tool returns success
```

### Blocking choice

```text
LLM
  -> show_canvas(mode=request, spec=ChoiceList)
  -> CanvasShown(callId, Pending)
  -> Chat transcript shows pending choice card
  -> user taps option in app
  -> Op.CanvasResponse(callId, Choice("ua1"))
  -> tool resumes
  -> CanvasResolved(responseSummary="Selected: UA123 8:20 AM")
  -> transcript updates in place
  -> tool output enters runtime history
```

### Manual prerequisite

```text
LLM
  -> ask_user(type=action, message="Log in and tap Done")
  -> existing capsule flow
  -> user confirms completion
  -> session continues
```

## Component Changes

### Tool Layer
- add `show_canvas`
- leave `ask_user` intact

### Protocol
- add `CanvasSpec`, `CanvasState`, `CanvasResponse`
- add `CanvasShown` and `CanvasResolved`
- add `Op.CanvasResponse`

### Chat/UI
- add `ContentBlock.Canvas`
- add `CanvasCard` composables for choice, confirmation, summary, and text prompt
- update `ChatEventReducer` to append and resolve canvas cards

### History
- add persisted canvas block records
- keep runtime `ResponseItem` tool call/output model unchanged

### Overlay
- add a compact capsule mode for "pending canvas response in app"
- do not add rich overlay card rendering in Step 1

## Why This Is Better Than WebView First

WebView adds:
- a second rendering stack
- JS bridge design
- focus and keyboard complexity in overlay contexts
- harder persistence and replay
- larger security surface

None of that is needed to validate the real product question: do native structured cards improve agent-user collaboration?

## Trade-offs

### Strengths

- minimal disruption to the existing `ask_user` path
- native Compose only
- supports both blocking and non-blocking structured cards from day one
- keeps rich selection where it belongs: in the app transcript
- avoids forcing overlay UI to become a tiny browser

### Weaknesses

- two user-interaction channels now exist (`ask_user` and `show_canvas`)
- canvas requests are best when the user can open the app
- future unification may still be needed if the schemas converge

## Phased Rollout

### Phase 1

Ship:
- transcript canvas cards
- `show_canvas(display|request)`
- app-hosted choice/confirmation/text prompt cards
- overlay fallback banner for pending app response
- existing `ask_user` untouched

### Phase 2

If usage shows the split is awkward, unify `ask_user` and `show_canvas` under one typed interaction model.

### Phase 3

Only if native cards become the bottleneck, evaluate a second host implementation such as WebView behind the same card protocol.

## Self-Review

This design chooses a narrower first step than a full unified interaction protocol. That is intentional.

The current architecture already has one stable blocking-intervention path and one stable transcript path. Extending both slightly is safer than replacing both with a bigger abstraction before we have evidence that the richer schema is worth it.
