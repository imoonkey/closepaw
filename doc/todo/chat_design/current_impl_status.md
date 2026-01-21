# Multi-Round Chat Implementation Status

> **Date**: 2026-01-21
> **Status**: Core Implementation Complete (Backend) - Native Streaming
> **Next**: UI Integration

## Overview

Implemented the multi-round chat feature with **native OpenAI streaming** based on `final_design.md`. The agent now supports:
- **Task-based model**: `Session > Task > Turn` hierarchy (from Codex protocol)
- **Native streaming responses**: Uses OpenAI Java SDK's `ResponseStreamEvent` directly
- **Multi-round interaction**: Session stays alive between tasks via `Idle` state

## Files Changed

### Protocol Layer

| File | Change | Description |
|------|--------|-------------|
| `protocol/Op.kt` | MODIFIED | `Op.UserInput` as primary entry; `Op.Start` deprecated |
| `protocol/AgentEvent.kt` | MODIFIED | Added `TaskStarted`, `TaskCompleted`, `MessageDelta` events |
| `protocol/SessionState.kt` | MODIFIED | Added `Idle` state for between-task waiting |

### Session Layer

| File | Change | Description |
|------|--------|-------------|
| `session/AgentSession.kt` | MODIFIED | Task lifecycle via `handleUserInput()`; transitions to `Idle` after task completion |

### Agent Layer

| File | Change | Description |
|------|--------|-------------|
| `agent/AgentConfig.kt` | MODIFIED | Added `taskId` field |
| `agent/Agent.kt` | MODIFIED | Uses streaming turn; emits `MessageDelta` events |
| `agent/Turn.kt` | MODIFIED | Added `TurnStreamEvent`, `runStreaming()` method |

### LLM Layer

| File | Change | Description |
|------|--------|-------------|
| `llm/LLMClient.kt` | MODIFIED | Added `LLMStreamChunk`, `chatWithToolsStreaming()` method |

## New Types

### Native OpenAI Streaming (llm/LLMClient.kt)

**Removed**: Custom `LLMStreamChunk` sealed interface

**Now using**: OpenAI Java SDK's native `ResponseStreamEvent` type directly:
```kotlin
// Native streaming method - returns OpenAI SDK type
fun chatWithToolsStreaming(...): Flow<ResponseStreamEvent>

// Key event types from OpenAI SDK:
// - event.isOutputTextDelta() → text chunk via event.asOutputTextDelta().delta()
// - event.isOutputItemDone() → completed item (text or function call)
// - event.isCreated() → response created with ID
// - event.isCompleted() → stream finished
// - event.isFailed() → error occurred
```

### `TurnStreamEvent` (agent/Turn.kt)
```kotlin
// Thin wrapper that processes ResponseStreamEvent into agent-friendly events
sealed interface TurnStreamEvent {
    data class TextDelta(val text: String)
    data class ToolCallReceived(val toolCall: ToolCallRequest)
    data class Complete(val result: TurnResult)
    data class Error(val error: Throwable)
}
```

### New AgentEvents (protocol/AgentEvent.kt)
```kotlin
data class TaskStarted(sessionId, timestamp, taskId, input)
data class TaskCompleted(sessionId, timestamp, taskId, result)
data class MessageDelta(sessionId, timestamp, turnId, delta)
```

### New SessionState (protocol/SessionState.kt)
```kotlin
data object Idle : SessionState  // Session active, waiting for user input
```

## Data Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│ User Input                                                           │
│     │                                                                │
│     ▼                                                                │
│ Op.UserInput("Check my email")                                       │
│     │                                                                │
│     ▼                                                                │
│ AgentSession                                                         │
│     ├── State: Created → Running                                     │
│     ├── Emit: TaskStarted(taskId, input)                             │
│     └── Start Agent                                                  │
│            │                                                         │
│            ▼                                                         │
│        Agent.run()                                                   │
│            │                                                         │
│            ├── Loop: executeTurn()                                   │
│            │       │                                                 │
│            │       ├── Perceive (capture screen)                     │
│            │       │                                                 │
│            │       ├── Think (LLM streaming)                         │
│            │       │       │                                         │
│            │       │       ├── Emit: MessageDelta(turnId, "I'll...")│
│            │       │       ├── Emit: MessageDelta(turnId, " open...")│
│            │       │       └── ... (streaming text)                  │
│            │       │                                                 │
│            │       ├── Act (execute tool calls)                      │
│            │       │       └── Emit: ActionExecuted(...)             │
│            │       │                                                 │
│            │       └── Observe (capture screen after action)         │
│            │                                                         │
│            └── Task Complete                                         │
│                    │                                                 │
│                    ▼                                                 │
│            Emit: TaskCompleted(taskId, result)                       │
│            State: Running → Idle                                     │
│                                                                      │
│ ─────────────── Ready for next Op.UserInput ─────────────────────── │
└─────────────────────────────────────────────────────────────────────┘
```

## Implementation Notes

### Streaming Implementation
- **Approach**: Native OpenAI SDK streaming via `client.responses().createStreaming()`
- **Event Processing**: Uses `ResponseStreamEvent` with `isOutputTextDelta()`, `isOutputItemDone()`, etc.
- **Benefits**: Real-time streaming, proper tool call events, SDK-managed connection handling
- **Flow Type**: Uses `callbackFlow` for proper coroutine integration with blocking SDK stream

### Concurrency
- Only one Task at a time
- `AgentSession` rejects `UserInput` if a Task is already running
- Future: Could queue inputs or interrupt current task

### Backward Compatibility
- `Op.Start(goal)` still works, maps to `Op.UserInput(goal)`
- Existing code using `Op.Start` will continue to function

## Remaining Work

### UI Integration (Phase 5)
- [ ] Update `AgentScreen` to handle `MessageDelta` events
- [ ] Implement chat bubble UI with streaming text
- [ ] Add input box for multi-round interaction
- [ ] Handle `TaskStarted` / `TaskCompleted` for UI state

### Testing
- [ ] Unit tests for streaming turn
- [ ] Integration tests for multi-round task flow
- [ ] Manual testing of streaming UI

## Design Documents
- `doc/todo/chat_design/final_design.md` - Full design specification
- `doc/todo/chat_design/claude.md` - Initial proposal (reference)
- `doc/todo/chat_design/codex.md` - Alternative proposal (reference)
- `doc/todo/chat_design/gemini.md` - Alternative proposal (reference)
