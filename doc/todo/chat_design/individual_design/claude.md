# Multi-Round Chat Design for Android Agent

> Design document for implementing conversational chat mode with streaming text output.

## Overview

Transform the current single-goal execution model into a multi-round conversational chat that:
1. Supports continuous user-agent dialogue
2. Streams text output to UI in real-time
3. Maintains conversation history across turns
4. Preserves the existing ReAct loop for tool execution

## Current State Analysis

### Current Architecture

```
User Goal → Agent.run() → [Turn → Turn → Turn...] → Completion
             ↑                                        ↓
            Op.Start(goal)                    AgentEvent.SessionCompleted
```

**Limitations:**
- Single goal per session
- No way to send follow-up messages
- Text output appears only after full LLM response
- Session ends when goal is achieved or failed

### Desired Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        ChatSession                                   │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   User Message #1 → [ReAct Cycle] → Streaming Response #1           │
│                           ↓                                          │
│   User Message #2 → [ReAct Cycle] → Streaming Response #2           │
│                           ↓                                          │
│   User Message #3 → [ReAct Cycle] → Streaming Response #3           │
│                          ...                                         │
└─────────────────────────────────────────────────────────────────────┘
```

## Design Principles

1. **Minimal Change to Core Loop** - The existing ReAct loop (`Agent` + `Turn`) works well; wrap it, don't rewrite it
2. **Streaming-First** - All text output should stream to UI immediately
3. **History Reuse** - Leverage existing `HistoryManager` for multi-turn context
4. **Event Compatibility** - Extend existing event system, don't replace it
5. **Reference: labmat** - Follow labmat's simplified patterns for streaming and session management

## Architecture

### Component Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              UI Layer                                    │
│  ┌─────────────┐  ┌──────────────────────────────────────────────────┐ │
│  │ Chat Input  │  │              Message List                        │ │
│  │    Box      │  │  ┌─────────────────────────────────────────────┐ │ │
│  └──────┬──────┘  │  │ UserMessage: "Open Settings"                │ │ │
│         │         │  ├─────────────────────────────────────────────┤ │ │
│         │         │  │ AssistantMessage: "Opening Settings app..." │ │ │
│         ▼         │  │ [streaming text appearing...]               │ │ │
│    Op.SendMessage │  ├─────────────────────────────────────────────┤ │ │
│         │         │  │ ToolExecution: click(Settings)              │ │ │
│         │         │  ├─────────────────────────────────────────────┤ │ │
│         │         │  │ AssistantMessage: "Done! Settings is open." │ │ │
│         │         │  └─────────────────────────────────────────────┘ │ │
│         │         └──────────────────────────────────────────────────┘ │
└─────────┼───────────────────────────────────────────────────────────────┘
          │                              ▲
          │  AgentEvent.TextDelta        │  AgentEvent.MessageComplete
          │  AgentEvent.ToolExecuted     │  AgentEvent.ThinkingStarted
          ▼                              │
┌─────────────────────────────────────────────────────────────────────────┐
│                           ChatSession                                    │
│  ┌───────────────────┐    ┌───────────────────────────────────────────┐│
│  │  ConversationState │    │              ChatCycle                    ││
│  │  - messages[]      │◄───│  - executeTurn()                          ││
│  │  - pendingTools    │    │  - processStream()                        ││
│  │  - turnCount       │    │  - executeTools()                         ││
│  └───────────────────┘    └───────────────────────────────────────────┘│
│                                      │                                  │
│                                      ▼                                  │
│  ┌───────────────────────────────────────────────────────────────────┐ │
│  │                    StreamingTurn                                   │ │
│  │  - runStreaming() → Flow<StreamEvent>                              │ │
│  │  - Wraps existing Turn class                                       │ │
│  └───────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

### New Components

#### 1. StreamEvent - Streaming Event Types

```kotlin
/**
 * Events emitted during streaming response generation.
 * 
 * Reference: labmat's ContentMessage, ThoughtMessage, ToolCallRequestMessage
 */
sealed interface StreamEvent {
    val sessionId: SessionId
    val timestamp: Long
    
    /** Text content delta (incremental) */
    data class TextDelta(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val delta: String,           // The new text chunk
        val messageId: String        // ID of the message being built
    ) : StreamEvent
    
    /** Thinking/reasoning started (for models with extended thinking) */
    data class ThinkingStarted(
        override val sessionId: SessionId,
        override val timestamp: Long
    ) : StreamEvent
    
    /** Thinking content delta */
    data class ThinkingDelta(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val delta: String
    ) : StreamEvent
    
    /** Thinking completed */
    data class ThinkingComplete(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val fullThought: String
    ) : StreamEvent
    
    /** A complete message (final, not delta) */
    data class MessageComplete(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val messageId: String,
        val content: String,
        val role: MessageRole      // USER, ASSISTANT, TOOL
    ) : StreamEvent
    
    /** Tool call requested by LLM */
    data class ToolCallRequest(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val callId: String,
        val toolName: String,
        val arguments: JSONObject,
        val needsApproval: Boolean
    ) : StreamEvent
    
    /** Tool execution result */
    data class ToolCallResult(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val callId: String,
        val success: Boolean,
        val output: String
    ) : StreamEvent
    
    /** Turn completed (one ReAct cycle done) */
    data class TurnComplete(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val turnNumber: Int,
        val hasMoreWork: Boolean    // false = waiting for user input
    ) : StreamEvent
    
    /** Error during streaming */
    data class StreamError(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val error: String,
        val recoverable: Boolean
    ) : StreamEvent
}

enum class MessageRole {
    USER, ASSISTANT, TOOL, SYSTEM
}
```

#### 2. Extended Op - Chat Operations

```kotlin
// Add to existing Op sealed interface:

sealed interface Op {
    // ... existing operations ...
    
    /**
     * Send a message in chat mode.
     * 
     * Unlike Op.Start which begins a goal-oriented session,
     * SendMessage continues the conversation.
     */
    data class SendMessage(
        val content: String,
        val attachments: List<Attachment> = emptyList()
    ) : Op
    
    /**
     * Clear conversation history and start fresh.
     */
    data object ClearHistory : Op
}

/**
 * Attachment types for user messages.
 * Future: support images, files, etc.
 */
sealed interface Attachment {
    data class Screenshot(val bitmap: Bitmap) : Attachment
    // Future: data class File(val uri: Uri) : Attachment
}
```

#### 3. ChatSession - Multi-Round Session Manager

```kotlin
/**
 * ChatSession - Manages multi-round conversational interaction.
 * 
 * Reference: labmat's ChatSession (session.py)
 * 
 * Key differences from Agent:
 * - Agent: Single goal, runs until complete
 * - ChatSession: Multiple messages, continuous conversation
 */
class ChatSession(
    val sessionId: SessionId,
    private val services: SessionServices,
    private val config: ChatConfig
) {
    // Conversation state
    private val messages = mutableListOf<ChatMessage>()
    private var turnCount = 0
    private var isProcessing = AtomicBoolean(false)
    
    // Event emission
    private val _events = MutableSharedFlow<StreamEvent>(
        replay = 0,
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    val events: SharedFlow<StreamEvent> = _events.asSharedFlow()
    
    /**
     * Send a user message and stream the response.
     * 
     * Returns a Flow that emits StreamEvents as the response is generated.
     */
    fun sendMessage(content: String): Flow<StreamEvent> = flow {
        if (!isProcessing.compareAndSet(false, true)) {
            emit(StreamEvent.StreamError(
                sessionId = sessionId,
                timestamp = now(),
                error = "Already processing a message",
                recoverable = true
            ))
            return@flow
        }
        
        try {
            // 1. Add user message to history
            val userMessage = ChatMessage.User(
                id = generateMessageId(),
                content = content,
                timestamp = now()
            )
            messages.add(userMessage)
            services.historyManager.addItem(
                ResponseItem.Message(role = "user", content = content)
            )
            
            emit(StreamEvent.MessageComplete(
                sessionId = sessionId,
                timestamp = now(),
                messageId = userMessage.id,
                content = content,
                role = MessageRole.USER
            ))
            
            // 2. Execute ReAct cycle with streaming
            emitAll(executeCycle())
            
        } finally {
            isProcessing.set(false)
        }
    }
    
    /**
     * Execute one or more ReAct turns until:
     * - LLM produces a final response (no tool calls)
     * - Max turns reached
     * - Error occurs
     */
    private fun executeCycle(): Flow<StreamEvent> = flow {
        while (turnCount < config.maxTurnsPerMessage) {
            turnCount++
            
            // 2.1 Capture screen
            val snapshot = services.platform.captureScreen()
            
            // 2.2 Execute streaming turn
            val turnResult = StreamingTurn(
                services = services,
                sessionId = sessionId
            )
            
            var hasToolCalls = false
            
            turnResult.run(snapshot).collect { event ->
                emit(event)
                
                if (event is StreamEvent.ToolCallRequest) {
                    hasToolCalls = true
                }
            }
            
            // 2.3 If no tool calls, LLM is done responding
            if (!hasToolCalls) {
                emit(StreamEvent.TurnComplete(
                    sessionId = sessionId,
                    timestamp = now(),
                    turnNumber = turnCount,
                    hasMoreWork = false
                ))
                break
            }
            
            // 2.4 Execute tools and continue
            emit(StreamEvent.TurnComplete(
                sessionId = sessionId,
                timestamp = now(),
                turnNumber = turnCount,
                hasMoreWork = true
            ))
        }
    }
}
```

#### 4. StreamingTurn - LLM Call with Streaming

```kotlin
/**
 * StreamingTurn - Executes a single LLM call with streaming output.
 * 
 * Reference: labmat's Turn (turn.py) with streaming support
 * 
 * This wraps the LLM call to emit text deltas as they arrive,
 * rather than waiting for the complete response.
 */
class StreamingTurn(
    private val services: SessionServices,
    private val sessionId: SessionId
) {
    companion object {
        private const val TAG = "StreamingTurn"
    }
    
    /**
     * Execute the turn, streaming events as they occur.
     */
    fun run(snapshot: ScreenSnapshot): Flow<StreamEvent> = flow {
        val messageId = generateMessageId()
        val screenJson = Perceptor.toPromptJson(snapshot)
        
        // Build input for LLM
        val inputItems = buildInputItems(screenJson)
        val tools = services.toolRegistry.generateResponsesApiTools()
        val systemPrompt = buildSystemPrompt()
        
        try {
            // Stream response from LLM
            services.llmClient.chatWithToolsStreaming(
                systemPrompt = systemPrompt,
                inputItems = inputItems,
                tools = tools,
                model = services.config.model
            ).collect { chunk ->
                when (chunk) {
                    is LLMStreamChunk.TextDelta -> {
                        emit(StreamEvent.TextDelta(
                            sessionId = sessionId,
                            timestamp = now(),
                            delta = chunk.text,
                            messageId = messageId
                        ))
                    }
                    
                    is LLMStreamChunk.ThinkingDelta -> {
                        emit(StreamEvent.ThinkingDelta(
                            sessionId = sessionId,
                            timestamp = now(),
                            delta = chunk.text
                        ))
                    }
                    
                    is LLMStreamChunk.ToolCall -> {
                        val needsApproval = services.policyEngine.evaluate(
                            chunk.name, chunk.arguments
                        ) == PolicyDecision.ASK_USER
                        
                        emit(StreamEvent.ToolCallRequest(
                            sessionId = sessionId,
                            timestamp = now(),
                            callId = chunk.callId,
                            toolName = chunk.name,
                            arguments = chunk.arguments,
                            needsApproval = needsApproval
                        ))
                        
                        // Execute tool (or wait for approval)
                        val result = executeToolCall(chunk)
                        
                        emit(StreamEvent.ToolCallResult(
                            sessionId = sessionId,
                            timestamp = now(),
                            callId = chunk.callId,
                            success = result.success,
                            output = result.output
                        ))
                    }
                    
                    is LLMStreamChunk.Complete -> {
                        // Record complete message in history
                        if (chunk.fullText.isNotBlank()) {
                            services.historyManager.addItem(
                                ResponseItem.Message(
                                    role = "assistant",
                                    content = chunk.fullText
                                )
                            )
                        }
                        
                        emit(StreamEvent.MessageComplete(
                            sessionId = sessionId,
                            timestamp = now(),
                            messageId = messageId,
                            content = chunk.fullText,
                            role = MessageRole.ASSISTANT
                        ))
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Streaming turn failed", e)
            emit(StreamEvent.StreamError(
                sessionId = sessionId,
                timestamp = now(),
                error = e.message ?: "Unknown error",
                recoverable = e !is java.net.UnknownHostException
            ))
        }
    }
}
```

#### 5. LLMClient Extension - Streaming Support

```kotlin
/**
 * Extend LLMClient to support streaming responses.
 */
class LLMClient(apiKey: String) {
    // ... existing code ...
    
    /**
     * Stream chunks from the LLM as they arrive.
     * 
     * NOTE: OpenAI Responses API doesn't support native streaming yet.
     * Options for MVP:
     * 1. Use Chat Completions API with streaming
     * 2. Simulate streaming by chunking the response
     * 3. Wait for Responses API streaming support
     * 
     * For MVP, we'll use option 2 (simulate) with the existing
     * non-streaming call, then migrate when native streaming is available.
     */
    fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel = ChatModel.GPT_4O
    ): Flow<LLMStreamChunk> = flow {
        // MVP: Use existing non-streaming call, then emit as chunks
        // This provides the architecture without requiring API changes
        
        val result = chatWithTools(systemPrompt, inputItems, tools, model)
        
        // Emit text in chunks to simulate streaming
        // In production, replace with actual SSE streaming
        result.textContent?.let { text ->
            val words = text.split(" ")
            for (i in words.indices) {
                val chunk = if (i == 0) words[i] else " ${words[i]}"
                emit(LLMStreamChunk.TextDelta(chunk))
                delay(20) // Simulate streaming delay
            }
        }
        
        // Emit tool calls
        result.toolCalls.forEach { call ->
            emit(LLMStreamChunk.ToolCall(
                callId = call.callId,
                name = call.name,
                arguments = JSONObject(call.arguments)
            ))
        }
        
        emit(LLMStreamChunk.Complete(
            fullText = result.textContent ?: "",
            toolCalls = result.toolCalls
        ))
    }
}

sealed interface LLMStreamChunk {
    data class TextDelta(val text: String) : LLMStreamChunk
    data class ThinkingDelta(val text: String) : LLMStreamChunk
    data class ToolCall(
        val callId: String,
        val name: String,
        val arguments: JSONObject
    ) : LLMStreamChunk
    data class Complete(
        val fullText: String,
        val toolCalls: List<LLMToolCall>
    ) : LLMStreamChunk
}
```

### UI Integration

#### Message Display Component

```kotlin
/**
 * UI state for chat messages.
 */
sealed interface ChatMessageUI {
    val id: String
    val timestamp: Long
    
    data class UserMessage(
        override val id: String,
        override val timestamp: Long,
        val content: String
    ) : ChatMessageUI
    
    data class AssistantMessage(
        override val id: String,
        override val timestamp: Long,
        val content: String,           // Full content so far
        val isStreaming: Boolean,      // true while receiving deltas
        val thinking: String? = null   // Extended thinking content
    ) : ChatMessageUI
    
    data class ToolExecution(
        override val id: String,
        override val timestamp: Long,
        val toolName: String,
        val status: ToolStatus,        // PENDING, EXECUTING, SUCCESS, ERROR
        val result: String? = null
    ) : ChatMessageUI
    
    data class ErrorMessage(
        override val id: String,
        override val timestamp: Long,
        val error: String
    ) : ChatMessageUI
}

enum class ToolStatus {
    PENDING_APPROVAL,
    EXECUTING,
    SUCCESS,
    ERROR,
    CANCELLED
}
```

#### ViewModel Integration

```kotlin
class ChatViewModel(
    private val sessionFactory: () -> ChatSession
) : ViewModel() {
    
    private val _messages = MutableStateFlow<List<ChatMessageUI>>(emptyList())
    val messages: StateFlow<List<ChatMessageUI>> = _messages.asStateFlow()
    
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()
    
    private var session: ChatSession? = null
    private var currentAssistantMessageId: String? = null
    
    fun sendMessage(text: String) {
        val chatSession = session ?: sessionFactory().also { session = it }
        
        viewModelScope.launch {
            chatSession.sendMessage(text)
                .catch { e -> handleError(e) }
                .collect { event -> handleStreamEvent(event) }
        }
    }
    
    private fun handleStreamEvent(event: StreamEvent) {
        when (event) {
            is StreamEvent.TextDelta -> {
                // Update the current assistant message with new text
                updateAssistantMessage(event.messageId) { msg ->
                    msg.copy(content = msg.content + event.delta)
                }
            }
            
            is StreamEvent.MessageComplete -> {
                when (event.role) {
                    MessageRole.USER -> {
                        addMessage(ChatMessageUI.UserMessage(
                            id = event.messageId,
                            timestamp = event.timestamp,
                            content = event.content
                        ))
                    }
                    MessageRole.ASSISTANT -> {
                        // Finalize streaming message
                        updateAssistantMessage(event.messageId) { msg ->
                            msg.copy(isStreaming = false)
                        }
                        currentAssistantMessageId = null
                    }
                    else -> { /* Handle other roles */ }
                }
            }
            
            is StreamEvent.ToolCallRequest -> {
                addMessage(ChatMessageUI.ToolExecution(
                    id = event.callId,
                    timestamp = event.timestamp,
                    toolName = event.toolName,
                    status = if (event.needsApproval) 
                        ToolStatus.PENDING_APPROVAL 
                        else ToolStatus.EXECUTING
                ))
            }
            
            is StreamEvent.ToolCallResult -> {
                updateToolExecution(event.callId) { tool ->
                    tool.copy(
                        status = if (event.success) ToolStatus.SUCCESS else ToolStatus.ERROR,
                        result = event.output
                    )
                }
            }
            
            is StreamEvent.StreamError -> {
                addMessage(ChatMessageUI.ErrorMessage(
                    id = generateId(),
                    timestamp = event.timestamp,
                    error = event.error
                ))
            }
            
            // ... handle other events
        }
    }
    
    private fun updateAssistantMessage(
        messageId: String,
        transform: (ChatMessageUI.AssistantMessage) -> ChatMessageUI.AssistantMessage
    ) {
        // If no current message, create one
        if (currentAssistantMessageId == null) {
            currentAssistantMessageId = messageId
            addMessage(ChatMessageUI.AssistantMessage(
                id = messageId,
                timestamp = System.currentTimeMillis(),
                content = "",
                isStreaming = true
            ))
        }
        
        _messages.update { list ->
            list.map { msg ->
                if (msg.id == messageId && msg is ChatMessageUI.AssistantMessage) {
                    transform(msg)
                } else msg
            }
        }
    }
}
```

## Migration Strategy

### Phase 1: Streaming Infrastructure (MVP)
1. Add `StreamEvent` types to protocol
2. Add `LLMStreamChunk` and simulated streaming to `LLMClient`
3. Create `StreamingTurn` wrapper
4. Add `Op.SendMessage` operation

**Estimated changes:** ~4 new files, ~300 lines

### Phase 2: ChatSession Core
1. Implement `ChatSession` class
2. Integrate with existing `SessionServices`
3. Wire up event emission

**Estimated changes:** ~2 new files, ~400 lines

### Phase 3: UI Integration
1. Create chat UI state types
2. Implement `ChatViewModel`
3. Update overlay UI to show chat interface

**Estimated changes:** ~3 files modified, ~500 lines

### Phase 4: Backward Compatibility
1. Keep existing `Agent` class for goal-mode
2. Add configuration flag to choose mode
3. Migrate `AgentService` to support both modes

**Estimated changes:** ~2 files modified, ~200 lines

## Configuration

```kotlin
data class ChatConfig(
    /** Maximum turns per user message before requiring new input */
    val maxTurnsPerMessage: Int = 10,
    
    /** Delay between tool executions */
    val toolDelayMs: Long = 500,
    
    /** Enable extended thinking display */
    val showThinking: Boolean = true,
    
    /** Auto-scroll to latest message */
    val autoScroll: Boolean = true,
    
    /** Inherit from SessionConfig */
    val sessionConfig: SessionConfig = SessionConfig()
)
```

## Open Questions

### Q1: Keep Goal Mode?
**Decision:** Yes, keep both modes.
- Goal mode: `Op.Start(goal)` → runs until complete
- Chat mode: `Op.SendMessage(text)` → continuous conversation

The agent should support both use cases.

### Q2: True Streaming vs Simulated?
**Decision:** Start with simulated, migrate to true streaming.
- MVP: Simulate streaming by chunking complete response
- Future: Use OpenAI streaming APIs when Responses API supports it
- Architecture supports both approaches transparently

### Q3: History Truncation Strategy?
**Decision:** Use existing HistoryManager with sliding window.
- Keep recent messages in full
- Compress older tool outputs
- Use labmat's approach of structured message tracking

### Q4: Tool Approval in Chat Mode?
**Decision:** Emit `ToolCallRequest` with `needsApproval` flag.
- If `needsApproval=true`, wait for user decision
- Use existing `Op.Approve` operation
- Policy engine determines which tools need approval

## Success Criteria

1. **Streaming works:** Text appears word-by-word as LLM generates
2. **Multi-turn works:** Can send multiple messages in one session
3. **Tools work:** Tool calls execute and results appear in chat
4. **History persists:** Previous messages provide context to LLM
5. **Backward compatible:** Goal mode still works

## File Changes Summary

### New Files
| File | Purpose |
|------|---------|
| `chat/ChatSession.kt` | Multi-round session manager |
| `chat/StreamingTurn.kt` | Streaming LLM turn execution |
| `chat/ChatConfig.kt` | Chat configuration |
| `protocol/StreamEvent.kt` | Streaming event types |
| `llm/LLMStreamChunk.kt` | LLM streaming types |

### Modified Files
| File | Changes |
|------|---------|
| `protocol/Op.kt` | Add `SendMessage`, `ClearHistory` |
| `llm/LLMClient.kt` | Add streaming method |
| `history/HistoryManager.kt` | Support chat message types |
| `ui/overlay/OverlayManager.kt` | Chat UI integration |

## References

- **labmat:** `.reference/labmat/python/src/labmat_py/agent/` - Simplified chat architecture
- **gemini-cli:** `.reference/gemini-cli/packages/core/src/core/` - Industry streaming patterns
- **codex:** `.reference/codex/codex-rs/protocol/src/protocol.rs` - Delta event model
