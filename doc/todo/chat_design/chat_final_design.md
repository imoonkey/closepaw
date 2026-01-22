# Multi-Round Chat & Streaming UI - Final Design (v2)

> **Status**: Backend Implementation Complete (Phase 1-4 DONE) - Ready for UI (Phase 5)
> **Type**: Feature (MVP)
> **Reference**: `.reference/codex/codex-rs/docs/protocol_v1.md`

## 1. Executive Summary

This design transforms the Android Agent into a **multi-round conversational agent** centered around the concept of **Tasks**.

Key changes from v1:
1.  **Task-Based Model**: Adopts the Codex `Session > Task > Turn` hierarchy. A user input starts a **Task**, which consists of multiple **Turns** (ReAct loop) until completion.
2.  **Unified Streaming Turn**: No separate "Chat Mode". All turns are streaming. The `Turn` class is updated to support streaming text and tool calls natively.
3.  **Tool-Enabled Chat**: The chat interface is not just a Q&A bot; it is the full agent interface. Tool calls occur within the chat flow.

## 2. Core Concepts

### 2.1 The Task Model

We adopt the definition from Codex Protocol v1:

*   **Session**: Long-lived configuration and state (History, Services).
*   **Task**: Work executed in response to a `Op.UserInput`.
    *   A Session runs one Task at a time.
    *   A Task consists of multiple **Turns**.
    *   A Task ends when the agent determines it is done (via `complete_task` tool or text-only response) or is interrupted.
*   **Turn**: One cycle of `Perceive -> Think (LLM) -> Act (Tool) -> Observe`.
    *   **Streaming**: The "Think" phase streams text and tool calls from the LLM.

### 2.2 Interaction Flow

```mermaid
sequenceDiagram
    participant User
    participant Session
    participant Agent (Task)
    participant LLM

    User->>Session: Op.UserInput("Check my email")
    Session->>Session: Create Task ID
    Session->>User: Event.TaskStarted
    
    loop ReAct Cycle (Turns)
        Session->>Agent: Execute Turn
        Agent->>LLM: Stream Chat
        
        loop Streaming Response
            LLM->>Agent: Chunk (Text Delta)
            Agent->>Session: Event.MessageDelta
            Session->>User: Update UI (Streaming)
        end
        
        LLM->>Agent: Tool Call (e.g., Open Gmail)
        Agent->>Session: Event.ActionProposed
        
        Agent->>Agent: Execute Tool
        Agent->>Session: Event.ActionExecuted
        Agent->>Agent: Observe Screen
    end
    
    Agent->>Session: Event.TaskCompleted
    Session->>User: Ready for next input
```

## 3. Protocol Updates

### 3.1 Operations (`Op.kt`) - DONE

*   **`Op.UserInput(text: String)`**: The primary way to interact. Starts a new Task.
*   **`Op.Start(goal: String)`**: Deprecated but maintained for backward compatibility. Maps to `Op.UserInput(goal)`.
*   **`Op.Interrupt`**: Stops the current Task but keeps Session alive.

### 3.2 Events (`AgentEvent.kt`) - DONE

*   **`TaskStarted(taskId, input)`**: Emitted when a new task begins.
*   **`TaskCompleted(taskId, result)`**: Emitted when a task ends.
*   **`MessageDelta(turnId, delta)`**: Emitted for each streaming text chunk.

### 3.3 Session State (`SessionState.kt`) - DONE

*   Added **`Idle`** state: Session active but waiting for user input (no task running).

## 4. Architecture Updates

### 4.1 LLM Client Streaming (`llm/LLMClient.kt`)

Use OpenAI Java SDK's **native streaming** via the Responses API.

**Key Types from OpenAI SDK**:
- `ResponseStreamEvent` - Base event type from streaming Responses API
- Event accessors: `isOutputTextDelta()`, `isOutputItemDone()`, `isCreated()`, `isCompleted()`, `isFailed()`

**Stream Events**:
| Event Type | Description |
|------------|-------------|
| `response.created` | Response initiated (includes response ID) |
| `response.output_text.delta` | Text chunk via `asOutputTextDelta().delta()` |
| `response.output_item.done` | Output item completed (text or tool call) |
| `response.completed` | Stream finished successfully |
| `response.failed` | Error occurred |

```kotlin
// Native streaming method using OpenAI SDK (wrapped in callbackFlow)
fun chatWithToolsStreaming(
    systemPrompt: String,
    inputItems: List<ResponseInputItem>,
    tools: List<FunctionTool>,
    model: ChatModel = ChatModel.GPT_4O
): Flow<ResponseStreamEvent>

// Manual accumulation of text and tool calls
val textAccumulator = StringBuilder()
val toolCalls = mutableListOf<LLMToolCall>()

llmClient.chatWithToolsStreaming(...).collect { event ->
    if (event.isOutputTextDelta()) {
        textAccumulator.append(event.asOutputTextDelta().delta())
    }
    if (event.isOutputItemDone() && event.asOutputItemDone().item().isFunctionCall()) {
        // Extract and store tool call
    }
}
```

**Implementation Strategy**: Use OpenAI Java SDK's native `createStreaming()` method, wrapped in Kotlin `callbackFlow` for coroutine compatibility. Manual accumulation of text/tool calls (no `ResponseAccumulator`).

### 4.2 Unified `Turn` with Streaming (`agent/Turn.kt`)

Add a streaming method alongside the existing `run()`.

```kotlin
// TurnStreamEvent - thin wrapper over OpenAI's ResponseStreamEvent
sealed interface TurnStreamEvent {
    data class TextDelta(val text: String) : TurnStreamEvent
    data class ToolCallReceived(val toolCall: ToolCallRequest) : TurnStreamEvent
    data class Complete(val result: TurnResult) : TurnStreamEvent
    data class Error(val error: Throwable) : TurnStreamEvent
}

// New method
fun runStreaming(...): Flow<TurnStreamEvent>
```

**Implementation**:
1. Call `llmClient.chatWithToolsStreaming()` to get `Flow<ResponseStreamEvent>`.
2. Process events:
   - `isOutputTextDelta()` → accumulate text, emit `TurnStreamEvent.TextDelta`
   - `isOutputItemDone()` with function call → accumulate tool call, emit `TurnStreamEvent.ToolCallReceived`
3. Manually accumulate text and tool calls in local variables.
4. When stream ends, emit `TurnStreamEvent.Complete` with `TurnResult`.

### 4.3 Agent Loop Update (`agent/Agent.kt`)

Update `executeTurn()` to use streaming and emit `MessageDelta` events.

```kotlin
// In executeTurn():
turn.runStreaming(systemPrompt, userContext, model).collect { event ->
    when (event) {
        is TurnStreamEvent.TextDelta -> {
            eventEmitter(AgentEvent.MessageDelta(
                sessionId = config.sessionId,
                timestamp = now(),
                turnId = turnId,
                delta = event.text
            ))
        }
        is TurnStreamEvent.ToolCallReceived -> {
            // Store for later execution
        }
        is TurnStreamEvent.Complete -> {
            // Process final result, execute tools
        }
        is TurnStreamEvent.Error -> {
            // Handle error
        }
    }
}
```

## 5. Implementation Plan (Detailed)

### Phase 1: LLM Streaming Infrastructure - DONE

**File**: `app/src/main/kotlin/com/moonkey/androidagent/llm/LLMClient.kt`

| Step | Description | Status |
|------|-------------|--------|
| 1.1 | Remove custom `LLMStreamChunk` sealed interface | ✅ DONE |
| 1.2 | Update `chatWithToolsStreaming()` to use native SDK streaming via `callbackFlow` | ✅ DONE |
| 1.3 | Proper retry logic with exponential backoff | ✅ DONE |

**Deliverable**: `LLMClient.chatWithToolsStreaming()` returns `Flow<ResponseStreamEvent>` (native OpenAI type).

### Phase 2: Turn Streaming - DONE

**File**: `app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt`

| Step | Description | Status |
|------|-------------|--------|
| 2.1 | Add `TurnStreamEvent` sealed interface | ✅ DONE |
| 2.2 | Add `runStreaming()` method | ✅ DONE |
| 2.3 | Implement manual delta accumulation for text and tool calls | ✅ DONE |

**Deliverable**: `Turn.runStreaming()` returns `Flow<TurnStreamEvent>`.

### Phase 3: Agent Integration - DONE

**File**: `app/src/main/kotlin/com/moonkey/androidagent/agent/Agent.kt`

| Step | Description | Status |
|------|-------------|--------|
| 3.1 | Update `executeTurn()` to use `turn.runStreaming()` | ✅ DONE |
| 3.2 | Emit `MessageDelta` events during streaming | ✅ DONE |
| 3.3 | Execute tools after stream completes (from `TurnResult`) | ✅ DONE |

**Deliverable**: Agent emits `MessageDelta` events during LLM response.

### Phase 4: Protocol & Session - DONE

| Item | Status |
|------|--------|
| `Op.kt` - `UserInput` as primary entry, `Start` deprecated with `ReplaceWith` | ✅ DONE |
| `AgentEvent.kt` - `TaskStarted`, `TaskCompleted`, `MessageDelta` | ✅ DONE |
| `SessionState.kt` - `Idle` state, `TaskCompleted` in diagram | ✅ DONE |
| `AgentSession.kt` - Task lifecycle via `handleUserInput()` | ✅ DONE |

### Phase 5: UI Integration (Future)

| Step | Description | Status |
|------|-------------|--------|
| 5.1 | Update `AgentScreen` to handle `MessageDelta` | PENDING |
| 5.2 | Implement chat bubble UI with streaming text | PENDING |
| 5.3 | Add input box for multi-round interaction | PENDING |

## 6. Open Questions & Risks

*   **~~OpenAI SDK Streaming~~**: ✅ Confirmed - `openai-java` SDK v4.14.0 supports native streaming via `createStreaming()`.
*   **Tool Call Streaming**: Tool calls arrive via `outputItemDone()` event after arguments are streamed.
*   **History Growth**: With multi-round tasks, history grows. `HistoryManager`'s truncation logic is critical.
*   **Concurrency**: Only one Task at a time. `AgentSession` rejects `UserInput` if busy.

## 7. File Change Summary

| File | Change Type | Description |
|------|-------------|-------------|
| `llm/LLMClient.kt` | ✅ DONE | Native streaming via `callbackFlow`, returns `Flow<ResponseStreamEvent>` |
| `agent/Turn.kt` | ✅ DONE | `runStreaming()` processes events, emits `TurnStreamEvent` |
| `agent/Agent.kt` | ✅ DONE | Uses streaming turn, emits `MessageDelta` events |
| `agent/AgentConfig.kt` | ✅ DONE | Added `taskId` field |
| `protocol/Op.kt` | ✅ DONE | `UserInput` as primary, `Start` deprecated with `ReplaceWith` |
| `protocol/AgentEvent.kt` | ✅ DONE | `TaskStarted`, `TaskCompleted`, `MessageDelta` |
| `protocol/SessionState.kt` | ✅ DONE | `Idle` state, consistent `TaskCompleted` terminology |
| `session/AgentSession.kt` | ✅ DONE | Task lifecycle via `handleUserInput()` |
