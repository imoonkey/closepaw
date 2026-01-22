# Multi-Round Chat with Streaming MVP Design

## 1. Overview
This design transforms the current single-goal ReAct agent into a conversational agent that can:
1.  Engage in multi-round dialogue with the user.
2.  Stream LLM text responses to the UI in real-time.
3.  Execute tools as part of the conversation (interleaved with text).
4.  Ask clarifying questions to the user.

This implementation targets a "Simple MVP" level, inspired by the `labmat` reference, while adopting robust event-driven patterns from `gemini-cli` and `codex` where appropriate (without over-engineering).

## 2. Architecture Changes

### 2.1. Agent Loop Updates (`Agent.kt`)
The current `run()` loop executes turns until a goal is achieved. We need to modify this to a continuous message loop:

*   **Current**: `while (shouldContinue) { executeTurn() }`
*   **New**: A state machine that waits for user input when idle.

New states:
*   `IDLE`: Waiting for user input.
*   `THINKING`: Calling LLM (streaming).
*   `EXECUTING`: Running tools.
*   `AWAITING_USER_INPUT`: Explicitly requested input (optional for MVP, `IDLE` might suffice).

### 2.2. Protocol Updates (`protocol/`)

**`Op.kt` (User Intents)**
Add operations for chat interaction:
```kotlin
sealed class Op {
    // ... existing ops ...
    data class UserInput(val text: String) : Op() // Send message to agent
}
```

**`AgentEvent.kt` (Agent Outputs)**
Add events for streaming chat:
```kotlin
sealed class AgentEvent {
    // ... existing events ...
    
    // New: Streaming Text
    data class MessageDelta(
        val sessionId: SessionId,
        val content: String,
        val turnId: String
    ) : AgentEvent()

    // New: User message acknowledgment (echo)
    data class UserMessage(
        val sessionId: SessionId,
        val content: String,
        val timestamp: Long
    ) : AgentEvent()
}
```

### 2.3. Turn Execution (`Turn.kt`)
Modify `Turn.run` (or add `Turn.runStream`) to support streaming.

*   **Input**: `History` + `UserContext`
*   **Output**: `Flow<TurnEvent>` instead of a single `TurnResult`.
*   `TurnEvent` types:
    *   `TextDelta(text)`
    *   `ToolCall(toolCall)`
    *   `Finished`

The `LLMClient` needs to expose a streaming API (OpenAI `stream=true`).

## 3. Data Flow

### 3.1. User Sends Message
1.  **UI**: User types "Check my email" -> `AgentService.submit(Op.UserInput("Check my email"))`.
2.  **AgentSession**: Forwards `Op.UserInput` to `Agent`.
3.  **Agent**:
    *   Adds user message to `HistoryManager`.
    *   Emits `AgentEvent.UserMessage` (so UI displays it).
    *   Triggers `executeTurn()`.

### 3.2. Agent "Thinking" (Streaming)
1.  **Agent**: Calls `Turn.runStream()`.
2.  **Turn**: Calls `LLMClient.streamChat()`.
3.  **LLMClient**: Yields chunks from OpenAI API.
4.  **Agent**:
    *   Receives text chunk -> Emits `AgentEvent.MessageDelta`.
    *   Receives tool call -> Accumulates tool call.
5.  **UI**: Appends `MessageDelta` to the last assistant message bubble.

### 3.3. Tool Execution (Interleaved)
1.  **Agent**: Detects tool call from LLM stream completion.
2.  **Agent**: Executes tool (same as current `ToolRouter`).
3.  **Agent**:
    *   Adds tool result to `HistoryManager`.
    *   Triggers *another* `executeTurn()` (recursion/loop) to let LLM see the result and respond.

## 4. Component Design

### 4.1. `LLMClient`
*   Add `streamChatWithTools(...)`: Returns `Flow<ChatStreamResponse>`.
*   Handling `ChatStreamResponse`:
    *   `content`: String delta.
    *   `tool_calls`: Delta or accumulated tool calls.

### 4.2. `HistoryManager`
*   Need to persist the conversation flow: `User` -> `Assistant` -> `Tool` -> `ToolResult` -> `Assistant` -> ...
*   Ensure `MessageDelta`s are aggregated into a single `ResponseItem.Message` when the turn completes.

### 4.3. UI (`OverlayManager` -> `ChatWindow`)
*   The current `OverlayManager` is too small for chat.
*   **MVP**: Expand the overlay into a bottom-sheet or a larger floating window when chat is active.
*   **Components**:
    *   `MessageList`: RecyclerView/LazyColumn of bubbles.
    *   `InputArea`: TextField + Send button.
    *   `StatusHeader`: Existing status dots/text.

## 5. Implementation Plan

### Phase 1: LLM Streaming
1.  Update `LLMClient` to support streaming with `com.openai.models.ChatModel`.
2.  Create `Turn.runStream` to handle the stream and emit deltas.

### Phase 2: Agent Loop Refactor
1.  Refactor `Agent.run()` to be an event loop that reacts to `Op.UserInput`.
2.  Implement the "Auto-continue" logic: if a tool was executed, immediately start next turn (Agent-driven). If text was output, wait for user (User-driven).

### Phase 3: UI Implementation
1.  Create `ChatOverlay` (or extend `OverlayManager`).
2.  Connect `AgentEvent.MessageDelta` to UI updates.

## 6. Comparison with References

*   **Labmat**: We borrow the simple "Route" concept where the UI sends a JSON request and the backend streams events back. Our `Op.UserInput` is the request, `AgentEvent.MessageDelta` is the stream.
*   **Gemini-CLI/Codex**: They use complex message processors. We simplify this by keeping the `Agent` as the single coordinator, avoiding separate "Executor" classes for now. We stick to the ReAct loop but make the "Think" phase streamable.

## 7. Future Considerations
*   **Interrupts**: Allow user to send `Op.Stop` or new `Op.UserInput` while agent is streaming/acting.
*   **Multimodal Input**: Allow user to send images/screenshots (already supported by protocol, just need UI).
