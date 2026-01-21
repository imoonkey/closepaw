# Multi-Round Chat + Streaming UI (MVP)

## Goal
Upgrade the current single-round interaction into a **multi-round chat** that **streams assistant text to the UI** in real time, while reusing existing session + history infrastructure.

This document is an **independent design** (no other local designs consulted). It borrows the minimal streaming contract from `labmat` and the newline-gated streaming idea from `codex` to avoid UI churn.

## Non-Goals (for MVP)
- No tool-calling chat mode changes (tools remain in agent mode only).
- No file attachments, RAG, or long-term memory.
- No cross-device sync.
- No markdown rendering edge-case perfection (plain text streaming is enough).

## Current State (Baseline)
- `Op.UserInput` exists but is **no-op** in `AgentSession`.
- UI is a **status log** in `MainActivity` + `AgentScreen`; no chat timeline.
- `HistoryManager` already stores message history (`ResponseItem.Message`) and is used by `Turn`.
- `LLMClient` uses non-streaming Responses API.

## MVP Overview
Add a **Chat Mode** to the existing session pipeline:

```
UI (Compose ChatScreen)
   └─ submits Op.UserInput("...") to AgentSession
       └─ ChatTurn starts in AgentSession
           ├─ append user message to HistoryManager
           ├─ stream assistant response from LLMClient
           ├─ emit ChatDelta events to UI
           └─ append final assistant message to HistoryManager
```

### Why this shape
- Uses existing `AgentSession` + `HistoryManager` rather than inventing a parallel stack.
- Streaming contract mirrors `labmat` (`delta`, `done`, `error`).
- UI updates are throttled + newline-gated (inspired by `codex`) to avoid flicker.

## Architecture Details

### 1) Data Model (UI)
Add a simple chat model for Compose:

```
data class UiChatMessage(
  val id: String,
  val role: ChatRole,        // USER | ASSISTANT | SYSTEM
  val text: String,
  val status: ChatStatus,    // STREAMING | COMPLETE | ERROR
  val createdAt: Long
)
```

Maintain `List<UiChatMessage>` in a `ChatViewModel` (or in `MainActivity` state for MVP). Append user messages immediately; update assistant message as deltas arrive.

### 2) Protocol Extensions (Events)
Extend `AgentEvent` with chat events (minimal set):

```
data class ChatMessageAdded(
  val messageId: String,
  val role: String,
  val text: String
)

data class ChatDelta(
  val messageId: String,
  val delta: String
)

data class ChatMessageCompleted(
  val messageId: String,
  val fullText: String
)

data class ChatError(
  val messageId: String,
  val error: String
)
```

Notes:
- Keep these events immutable and fire them through the existing `AgentSession.events` flow.
- Tie them to the current `sessionId` (reuse `AgentEvent` fields).

### 3) Streaming Contract (LLM → Session)
Adopt the `labmat` style stream items:

```
sealed class ChatStreamItem {
  data class Delta(val text: String)
  data class Done
  data class Error(val message: String)
}
```

`LLMClient.streamChat(...)` should emit `ChatStreamItem` to a callback or flow. The session converts these into `AgentEvent.ChatDelta` / `ChatMessageCompleted` / `ChatError`.

### 4) Chat Turn Execution (Session)
Implement `AgentSession.handleUserInput(op)` to:

1) **Append user message** to history.
2) **Emit ChatMessageAdded** for user.
3) **Create assistant message placeholder** + emit ChatMessageAdded (assistant, empty text, status=STREAMING).
4) **Stream response**:
   - Accumulate in `StringBuilder`.
   - Emit `ChatDelta` as deltas arrive (throttled).
5) **Finalize**:
   - Append final assistant message to history.
   - Emit `ChatMessageCompleted`.
6) **Error**:
   - Emit `ChatError`, keep partial text for user visibility.

#### Concurrency rule (MVP)
Allow only **one in-flight assistant response** at a time:
- Disable send in UI while streaming, or
- If user sends another message, cancel previous stream and start new.

### 5) History Integration (Multi-round Context)
Use the existing `HistoryManager`:
- User message → `ResponseItem.Message(role="user", content=...)`
- Assistant final message → `ResponseItem.Message(role="assistant", content=...)`

This keeps `Turn.buildInputItems()` compatible (it already consumes `ResponseItem.Message`).

### 6) UI Rendering + Throttle (Streaming)
Follow `labmat`’s 50 ms cadence to reduce UI churn:
- Collect deltas into a buffer.
- Only emit UI updates every ~50 ms (or on newline).

In Compose:
- Use a `MutableSharedFlow<ChatDelta>` with `buffer` and `sample(50.milliseconds)` or a custom timer in the ViewModel.
- Update the **last assistant message** in-place (not append).

### 7) Newline-Gated Commit (Optional MVP+)
Borrow `codex`’s idea: only render **completed logical lines** during streaming.
- If `delta` doesn’t contain `\n`, keep it in a pending buffer.
- On newline, append the completed line(s) to the UI string and clear pending.

This avoids "partial line flicker" during streaming, and becomes more important once markdown rendering is added.

## LLM Streaming (Implementation Notes)
Add a streaming method to `LLMClient`:

```
suspend fun streamChat(
  systemPrompt: String,
  inputItems: List<ResponseInputItem>,
  model: ChatModel,
  onItem: suspend (ChatStreamItem) -> Unit
)
```

Implementation expectations:
- Use OpenAI Responses streaming API (or equivalent in `openai-java`).
- Parse stream deltas to `ChatStreamItem.Delta`.
- Emit `Done` when complete.
- Catch exceptions and emit `Error`.

## UI Wire-up (Compose)
Add a new `ChatScreen` alongside `AgentScreen` (MVP can be a toggle):
- Chat list (lazy column).
- Input box with send button.
- "Streaming" indicator on last assistant message.
- Auto-scroll to latest when new items arrive.

Connect to `AgentService` using a new `chatFlow` or by filtering `AgentEvent` for chat events.

## Error Handling
- Stream errors emit `ChatError` with partial text preserved.
- If stream fails before any delta, surface a toast or inline error bubble.
- Retry is manual (user re-sends).

## Telemetry (Local Debug)
Log:
- First-token latency.
- Total tokens streamed.
- Error counts.

Avoid persistent logging of user text in release builds.

## Test Plan (MVP)
1) **Unit**: `ChatStreamCollector` newline gating (if implemented).
2) **Unit**: `AgentSession.handleUserInput` adds history + emits events in order.
3) **UI**: send "hello" → see streaming assistant response.
4) **Multi-round**: send two messages; second sees context from first.
5) **Error**: simulate network failure → error bubble shows.

## Incremental Steps
1) Add chat events + UI model.
2) Implement `LLMClient.streamChat`.
3) Wire `Op.UserInput` to chat streaming in `AgentSession`.
4) Add `ChatScreen` + hook to event flow.
5) Add throttle + optional newline gating.

## Key Borrowed Ideas
- **LabMat**: simple `delta/done/error` stream contract + throttled UI updates.
- **Codex**: newline-gated streaming to avoid partial-line flicker.
- **Gemini-cli**: session/transport stability patterns (keep stream ownership inside the session).

