# Agent Infrastructure Design

**Status**: Draft  
**Author**: Engineering Team
**Date**: January 2026  
**Based on**: Analysis of Codex CLI (OpenAI), Gemini CLI (Google), and Mobile-Agent-v3

---

## 1. Executive Summary

This document proposes a production-grade **Agent Infrastructure** layer that is **orthogonal to agent orchestration strategy**. The goal is to create a stable foundation for lifecycle management, event handling, and tool execution—allowing the "research-heavy" agent logic (planning, reflection, multi-agent coordination) to evolve rapidly without destabilizing the underlying infrastructure.

### 1.1 Top-Down Architecture Comparison

Both Codex and Gemini are production-grade agent systems, but they have different architectural philosophies:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                            CODEX ARCHITECTURE (Rust)                            │
├─────────────────────────────────────────────────────────────────────────────────┤
│  Codex (Entry Point)                                                            │
│    ├── submit(Op) → tx_sub channel                                              │
│    └── next_event() ← rx_event channel                                          │
│                           │                                                     │
│                           ▼                                                     │
│  submission_loop(Session) ─── processes ops, calls handlers                     │
│                           │                                                     │
│                           ▼                                                     │
│  Session                                                                        │
│    ├── services: SessionServices (DI container)                                 │
│    ├── active_turn: ActiveTurn (tasks, cancellation)                            │
│    └── state: SessionState (context manager)                                    │
│                           │                                                     │
│                           ▼                                                     │
│  TurnContext ─── run_model_turn() ─── tool calls with approvals                 │
└─────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────┐
│                          GEMINI ARCHITECTURE (TypeScript)                       │
├─────────────────────────────────────────────────────────────────────────────────┤
│  Config (Service Locator - holds EVERYTHING)                                    │
│    ├── toolRegistry: ToolRegistry                                               │
│    ├── agentRegistry: AgentRegistry                                             │
│    ├── policyEngine: PolicyEngine                                               │
│    ├── messageBus: MessageBus                                                   │
│    └── ... many more services                                                   │
│                           │                                                     │
│                           ▼                                                     │
│  GeminiClient                                                                   │
│    └── processTurn() → yields ServerGeminiStreamEvent                           │
│                           │                                                     │
│                           ▼                                                     │
│  Turn                                                                           │
│    └── run() → yields events, manages tool calls                                │
│                           │                                                     │
│                           ▼                                                     │
│  CoreToolScheduler (State Machine: validating → scheduled → executing → done)   │
│    └── Uses PolicyEngine for approval decisions                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 Key Overlaps and Design Decisions

| Concern | Codex Approach | Gemini Approach | **Our Choice** | Rationale |
|---------|----------------|-----------------|----------------|-----------|
| **Communication** | Channel-based SQ/EQ | Callback/Event streaming | **SQ/EQ (Codex)** | Maps well to Kotlin Flow; cleaner for UI-Agent separation |
| **Service Location** | `SessionServices` container | `Config` mega-object | **SessionServices (Codex)** | More explicit DI; Config is too monolithic |
| **Tool State** | Implicit in execution | Explicit state machine | **State Machine (Gemini)** | Better debugging, clearer lifecycle |
| **Approvals** | `TurnState.pending_approvals` | `PolicyEngine` + callbacks | **PolicyEngine (Gemini)** | Cleaner separation of policy from state |
| **Conversation History** | `ContextManager` (history.rs) | History in `GeminiChat` | **HistoryManager (Codex-style)** | Explicit management with truncation |
| **Turn Scope** | `TurnContext` + `ActiveTurn` | `Turn` class | **TurnContext (Codex)** | Better separation of context vs execution |
| **Registries** | N/A (simpler model) | `ToolRegistry` + `AgentRegistry` | **Registries (Gemini)** | Better for extensibility and testing |

### 1.3 Important Clarification: Two Types of "Context"

**CRITICAL**: Gemini and Codex use "ContextManager" for DIFFERENT things!

| System | Component | What It Manages |
|--------|-----------|-----------------|
| **Codex** | `context_manager/history.rs` | **Conversation history** - truncation, normalization, token tracking |
| **Gemini** | `services/contextManager.ts` | **Memory files** (GEMINI.md) - discovery and loading of instructional context |
| **Gemini** | `GeminiChat.history` | **Conversation history** - stored directly in chat object |
| **Gemini** | `ChatCompressionService` | **History compression** - when context window fills |

**Our naming**:
- `HistoryManager` - Conversation history (Codex's ContextManager)
- `MemoryDiscovery` - Instructional files (Gemini's ContextManager) - *if needed*

### 1.4 Context and Agent Orchestration Relationship

**Key insight**: Agent design IS context engineering.

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                        CONTEXT LAYERS IN AN AGENT SYSTEM                        │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  Layer 1: BASE INSTRUCTIONS (Static)                                            │
│    └── System prompt, tool definitions                                          │
│                                                                                 │
│  Layer 2: ENVIRONMENT CONTEXT (Per-Session)                                     │
│    └── CWD, date, device info, app context                                      │
│                                                                                 │
│  Layer 3: MEMORY/INSTRUCTIONS (Semi-Static)                                     │
│    └── GEMINI.md files, user preferences                                        │
│                                                                                 │
│  Layer 4: CONVERSATION HISTORY (Dynamic)                                        │
│    └── Previous turns, tool outputs (grows over time)                           │
│                                                                                 │
│  Layer 5: CURRENT TURN CONTEXT (Ephemeral)                                      │
│    └── Current screen state, pending actions                                    │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

**Single-Agent vs Multi-Agent Context**:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│  SINGLE-AGENT (Codex)                                                           │
│                                                                                 │
│  One Agent ←────────── One HistoryManager ←────────── All conversation history  │
│                                                                                 │
│  Context management = Managing ONE long conversation                            │
│  Auto-compact when context window fills                                         │
└─────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────┐
│  MULTI-AGENT (Gemini sub-agents, Mobile-Agent-v3)                               │
│                                                                                 │
│  Main Agent ←─── Main History                                                   │
│       │                                                                         │
│       ├── SubAgent A (fresh context, own system prompt, limited tools)          │
│       │        └── Returns result via tool output                               │
│       │                                                                         │
│       └── SubAgent B (different context, different prompt, different tools)     │
│                └── Returns result via tool output                               │
│                                                                                 │
│  Each agent has DIFFERENT context needs!                                        │
│  - Manager: Planning context, goal, high-level history                          │
│  - Executor: Current screen, available actions, recent actions                  │
│  - Reflector: Before/after screens, expected vs actual outcomes                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

**Gemini's Sub-Agent Pattern**:
- Each `LocalAgentExecutor` creates **fresh chat context** (`createChatObject`)
- Has its own **system prompt** (built via `buildSystemPrompt()`)
- Has its own **tool set** (not all tools available to all agents)
- Runs in **isolated loop** until completion
- Returns result to parent via **tool output**

**Implication for Mobile-Agent-v3**:
Our Manager, Executor, and Reflector don't need to share conversation history - they can each have their own context optimized for their task.

### 1.6 Our Unified Architecture

We combine the best of both:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          OUR ARCHITECTURE (Kotlin)                              │
├─────────────────────────────────────────────────────────────────────────────────┤
│  AgentSession (Entry Point) ──────────────────────────────────────────────────  │
│    ├── submit(Op) → opChannel (Kotlin Channel)                                  │
│    └── events: Flow<AgentEvent>                                                 │
│                           │                                                     │
│  SessionServices (DI Container from Codex)                                      │
│    ├── toolRegistry: ToolRegistry (from Gemini)                                 │
│    ├── agentRegistry: AgentRegistry (from Gemini)                               │
│    ├── policyEngine: PolicyEngine (from Gemini)                                 │
│    ├── historyManager: HistoryManager (from Codex)                              │
│    ├── llmClient: LLMClient                                                     │
│    └── platform: AndroidPlatform                                                │
│                           │                                                     │
│  Session Loop (processes ops)                                                   │
│    └── dispatches to: TurnContext (from Codex)                                  │
│                           │                                                     │
│  Orchestration (pluggable: MobileV3, Single-agent, etc.)                        │
│    └── Uses: ToolRouter with State Machine (from Gemini)                        │
│               validating → scheduled → awaiting_approval → executing → done     │
└─────────────────────────────────────────────────────────────────────────────────┘
```

**Key Design Principles**:
1. **Single source of truth for each concern** - No overlapping components
2. **Clear ownership boundaries** - Each component has one job
3. **Explicit state machines** - No hidden state transitions
4. **Testable at every level** - Interfaces for mocking

### 1.5 Architecture Review: Design Principles

Reviewing our design against common infrastructure architecture principles:

| Principle | How We Address It | Potential Gaps |
|-----------|-------------------|----------------|
| **Single Responsibility** | ✅ Each component has one job (ToolRouter executes, PolicyEngine decides, HistoryManager tracks) | Watch for Agent class doing too much |
| **Open/Closed** | ✅ Registries allow adding tools/agents without modifying core | Need interface for new orchestration strategies |
| **Dependency Inversion** | ✅ SessionServices as DI container; depend on interfaces | `AndroidPlatform` interface enables testing |
| **Interface Segregation** | ⚠️ `Agent` interface might be too broad | Consider splitting invoke vs lifecycle |
| **Separation of Concerns** | ✅ Protocol, Session, Orchestration, Infrastructure layers | History vs Memory distinction now clear |

**Additional Infrastructure Patterns**:

| Pattern | Status | Notes |
|---------|--------|-------|
| **Explicit State Machines** | ✅ Tool call states, Session states | Prevents race conditions |
| **Cooperative Cancellation** | ✅ CancellationSignal propagation | Critical for Pause/Stop |
| **Event-Driven Communication** | ✅ Op/Event protocol | Loose coupling UI↔Agent |
| **Fail-Fast Validation** | ✅ Tool validation before execution | Prevents wasted LLM calls |
| **Bulkhead Isolation** | ⚠️ Not yet implemented | Consider isolating LLM calls from tool execution |
| **Circuit Breaker** | ⚠️ Retry with backoff, but no circuit breaker | Add for LLM API failures |
| **Idempotency** | ⚠️ Not explicitly addressed | UI actions may not be idempotent |

**What We Learned We Were Missing**:

1. **Naming Confusion**: "ContextManager" means different things in Codex vs Gemini
   - Fix: Rename to `HistoryManager` (conversation) vs `MemoryDiscovery` (files)

2. **Multi-Agent Context Isolation**: Each agent can have different context needs
   - Fix: Don't assume shared history; design for per-agent context

3. **Compression Strategy**: Both systems have history compression when context window fills
   - Need: Add compression for Mobile-Agent-v3 (screen states can be large)

---

## 2. Problems with Current Implementation

Our Mobile-Agent-v3 port has several engineering gaps:

1. **Race Conditions in Lifecycle**:
   - `AtomicBoolean` for pause/resume is insufficient
   - `job?.cancel()` doesn't wait for cleanup
   - Overlay button callbacks can race with coroutine state

2. **Tight Coupling**:
   - `AgentOrchestrator` directly depends on `AccessibilityService`
   - Status updates are callback-based, not event-driven
   - No clean separation between infra and agent logic

3. **No Proper Error Recovery**:
   - LLM errors crash the loop
   - No retry with backoff
   - No graceful degradation

4. **Testing Impossibility**:
   - Can't test orchestration without Android device
   - No mocking boundaries for agents or tools

---

## 3. Proposed Architecture

### 3.1 Layered Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         UI Layer                                │
│    (OverlayManager, MainActivity, StatusDisplay)                │
└───────────────────────────┬─────────────────────────────────────┘
                            │ Events ↑↓ Operations
┌───────────────────────────┴─────────────────────────────────────┐
│                    Protocol Layer                               │
│    (Op sealed class, Event sealed class, SessionId)             │
└───────────────────────────┬─────────────────────────────────────┘
                            │
┌───────────────────────────┴─────────────────────────────────────┐
│                     Session Layer                               │
│    (AgentSession - lifecycle, state, event dispatch)            │
└───────────┬──────────────────────────────────────┬──────────────┘
            │                                      │
┌───────────┴──────────────┐      ┌────────────────┴──────────────┐
│   Orchestration Layer    │      │      Infrastructure Layer     │
│   (Agents, Planning,     │      │   (ToolRouter, LLMClient,     │
│    Reflection - VARIES)  │      │    Perceptor, ActionDispatcher│
│                          │      │    - STABLE)                  │
└──────────────────────────┘      └───────────────────────────────┘
```

### 3.2 Key Principle: Separation of Concerns

```
┌─────────────────────────────────────────────────────────────────┐
│                 STABLE (Change Rarely)                          │
│  • Protocol definitions (Op, Event)                             │
│  • Session lifecycle management                                 │
│  • Tool infrastructure (ToolSpec, ToolRouter)                   │
│  • LLM client abstraction                                       │
│  • Cancellation / error handling primitives                     │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│              EVOLVING (Change Frequently)                       │
│  • Agent implementations (Manager, Executor, Reflector)         │
│  • Orchestration strategies (single-agent, multi-agent)         │
│  • Prompts and context construction                             │
│  • Tool implementations (specific Android actions)              │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. Core Components Design

### 4.1 Protocol: Operations and Events

Following Codex's SQ/EQ pattern, we define a typed protocol:

```kotlin
// ============ Operations (UI → Session) ============

sealed interface Op {
    /** Start the agent with a goal */
    data class Start(val goal: String, val config: SessionConfig) : Op
    
    /** Pause execution (cooperative) */
    object Pause : Op
    
    /** Resume from pause */
    object Resume : Op
    
    /** Abort current turn, continue session */
    object Interrupt : Op
    
    /** Shutdown session completely */
    object Shutdown : Op
    
    /** User provides additional input mid-execution */
    data class UserInput(val text: String) : Op
    
    /** Approve a pending action */
    data class Approve(val actionId: String, val decision: ApprovalDecision) : Op
}

enum class ApprovalDecision { APPROVED, DENIED, ABORT }

// ============ Events (Session → UI) ============

sealed interface AgentEvent {
    val sessionId: SessionId
    val timestamp: Long
    
    // Lifecycle events
    data class SessionStarted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val goal: String
    ) : AgentEvent
    
    data class SessionCompleted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val result: String?
    ) : AgentEvent
    
    data class SessionError(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val error: AgentError
    ) : AgentEvent
    
    // Turn events
    data class TurnStarted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val turnId: String,
        val phase: TurnPhase
    ) : AgentEvent
    
    data class TurnCompleted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val turnId: String
    ) : AgentEvent
    
    // Agent thinking events
    data class AgentThinking(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val agent: String,  // "manager", "executor", "reflector"
        val thought: String
    ) : AgentEvent
    
    // Action events
    data class ActionProposed(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val actionId: String,
        val action: ProposedAction
    ) : AgentEvent
    
    data class ActionExecuted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val actionId: String,
        val result: ActionResult
    ) : AgentEvent
    
    // Screen perception events
    data class ScreenCaptured(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val elementCount: Int,
        val packageName: String?
    ) : AgentEvent
    
    // Status update (for simple UI)
    data class StatusUpdate(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val status: String,
        val emoji: String? = null
    ) : AgentEvent
    
    // Approval request
    data class ApprovalRequired(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val actionId: String,
        val description: String,
        val details: ApprovalDetails
    ) : AgentEvent
    
    // Pause/Resume acknowledgment
    data class Paused(
        override val sessionId: SessionId,
        override val timestamp: Long
    ) : AgentEvent
    
    data class Resumed(
        override val sessionId: SessionId,
        override val timestamp: Long
    ) : AgentEvent
}

enum class TurnPhase { PERCEPTION, REFLECTION, PLANNING, EXECUTION }

@JvmInline
value class SessionId(val value: String) {
    companion object {
        fun generate(): SessionId = SessionId(UUID.randomUUID().toString())
    }
}
```

### 4.2 Session: Lifecycle Management

```kotlin
/**
 * AgentSession manages the lifecycle of an agent execution.
 * It receives Operations and emits Events.
 * 
 * Key properties:
 * - Thread-safe operation submission
 * - Cooperative cancellation
 * - Clean shutdown with resource cleanup
 */
class AgentSession private constructor(
    val sessionId: SessionId,
    private val config: SessionConfig,
    private val orchestrationFactory: OrchestrationFactory,
    private val eventChannel: Channel<AgentEvent>,
    private val scope: CoroutineScope
) {
    // ===== State Machine =====
    private val _state = MutableStateFlow<SessionState>(SessionState.Created)
    val state: StateFlow<SessionState> = _state.asStateFlow()
    
    // Events as a Flow for observers
    val events: Flow<AgentEvent> = eventChannel.receiveAsFlow()
    
    // Cancellation support
    private val cancellationSignal = CompletableDeferred<CancellationReason>()
    
    // Current orchestration (can be swapped for different strategies)
    private var orchestration: AgentOrchestration? = null
    
    companion object {
        fun create(
            config: SessionConfig,
            orchestrationFactory: OrchestrationFactory,
            scope: CoroutineScope
        ): AgentSession {
            return AgentSession(
                sessionId = SessionId.generate(),
                config = config,
                orchestrationFactory = orchestrationFactory,
                eventChannel = Channel(Channel.BUFFERED),
                scope = scope
            )
        }
    }
    
    /**
     * Submit an operation. Thread-safe.
     */
    suspend fun submit(op: Op) {
        when (op) {
            is Op.Start -> handleStart(op)
            is Op.Pause -> handlePause()
            is Op.Resume -> handleResume()
            is Op.Interrupt -> handleInterrupt()
            is Op.Shutdown -> handleShutdown()
            is Op.UserInput -> handleUserInput(op)
            is Op.Approve -> handleApproval(op)
        }
    }
    
    private suspend fun handleStart(op: Op.Start) {
        if (_state.value != SessionState.Created) {
            emit(AgentEvent.SessionError(sessionId, now(), AgentError.InvalidState("Already started")))
            return
        }
        
        _state.value = SessionState.Running
        emit(AgentEvent.SessionStarted(sessionId, now(), op.goal))
        
        // Create and run orchestration
        orchestration = orchestrationFactory.create(
            goal = op.goal,
            config = config,
            eventEmitter = { emit(it) },
            cancellationSignal = cancellationSignal
        )
        
        scope.launch {
            try {
                orchestration?.run()
                _state.value = SessionState.Completed
                emit(AgentEvent.SessionCompleted(sessionId, now(), "Task finished"))
            } catch (e: CancellationException) {
                _state.value = SessionState.Cancelled
                emit(AgentEvent.SessionCompleted(sessionId, now(), "Cancelled"))
            } catch (e: Exception) {
                _state.value = SessionState.Error(e)
                emit(AgentEvent.SessionError(sessionId, now(), AgentError.from(e)))
            } finally {
                cleanup()
            }
        }
    }
    
    private suspend fun handlePause() {
        if (_state.value != SessionState.Running) return
        
        _state.value = SessionState.Paused
        orchestration?.pause()
        emit(AgentEvent.Paused(sessionId, now()))
    }
    
    private suspend fun handleResume() {
        if (_state.value != SessionState.Paused) return
        
        _state.value = SessionState.Running
        orchestration?.resume()
        emit(AgentEvent.Resumed(sessionId, now()))
    }
    
    private suspend fun handleInterrupt() {
        orchestration?.interrupt()
    }
    
    private suspend fun handleShutdown() {
        cancellationSignal.complete(CancellationReason.UserRequested)
        orchestration?.stop()
        _state.value = SessionState.Shutdown
        eventChannel.close()
    }
    
    private suspend fun cleanup() {
        orchestration = null
    }
    
    private suspend fun emit(event: AgentEvent) {
        eventChannel.send(event)
    }
    
    private fun now() = System.currentTimeMillis()
}

sealed interface SessionState {
    object Created : SessionState
    object Running : SessionState
    object Paused : SessionState
    object Completed : SessionState
    object Cancelled : SessionState
    object Shutdown : SessionState
    data class Error(val exception: Exception) : SessionState
}

sealed interface CancellationReason {
    object UserRequested : CancellationReason
    object Timeout : CancellationReason
    data class Error(val message: String) : CancellationReason
}
```

### 4.3 Orchestration Interface

This is where the "research" part lives. The interface is stable, but implementations can vary:

```kotlin
/**
 * AgentOrchestration defines how agents are coordinated.
 * 
 * Implementations can include:
 * - SingleAgentOrchestration: Simple loop (current)
 * - MultiAgentOrchestration: Mobile-Agent-v3 style
 * - TreeOfThoughtsOrchestration: Future exploration
 */
interface AgentOrchestration {
    /** Run the main agent loop. Should be cancellation-aware. */
    suspend fun run()
    
    /** Cooperative pause - completes current action, then waits */
    suspend fun pause()
    
    /** Resume from pause */
    suspend fun resume()
    
    /** Interrupt current turn, prepare for next */
    suspend fun interrupt()
    
    /** Stop completely and cleanup */
    suspend fun stop()
}

/**
 * Factory to create orchestrations.
 * Allows swapping strategies without changing Session.
 */
fun interface OrchestrationFactory {
    fun create(
        goal: String,
        config: SessionConfig,
        eventEmitter: suspend (AgentEvent) -> Unit,
        cancellationSignal: Deferred<CancellationReason>
    ): AgentOrchestration
}
```

### 4.4 Tool Infrastructure

Inspired by both Codex's `ToolOrchestrator` and Gemini's `DeclarativeTool`.

#### 4.4.1 Tool Call State Machine

We adopt Gemini's explicit state machine for tool calls, implemented in `ToolRouter`:

```
  ┌─────────────┐
  │  VALIDATING │ ←─── Tool call received from LLM
  └──────┬──────┘
         │ validation pass
         ▼
  ┌────────────────────────────────────────┐
  │  PolicyEngine.check() → ALLOW/DENY/ASK │
  └────────────────────────────────────────┘
         │           │           │
         │ ALLOW     │ DENY      │ ASK_USER
         ▼           ▼           ▼
  ┌───────────┐  ┌───────┐  ┌─────────────────────┐
  │ SCHEDULED │  │ ERROR │  │  AWAITING_APPROVAL  │
  └─────┬─────┘  └───────┘  └──────────┬──────────┘
        │                              │
        │                     ┌────────┼────────┐
        │                     ▼ approve│ deny   ▼ abort
        │              ┌───────────┐  ┌───────────────┐
        └─────────────►│ EXECUTING │  │   CANCELLED   │
                       └─────┬─────┘  └───────────────┘
                             │
                      ┌──────┴──────┐
                      ▼             ▼
                  [SUCCESS]     [ERROR]
```

**Implementation**: See `ToolRouter` and `ToolCallState` classes below.

#### 4.4.2 Tool Specification and Registry

```kotlin
/**
 * Tool specification - describes a tool without implementing it.
 * Stable interface that won't change often.
 */
interface ToolSpec<TParams, TResult> {
    val name: String
    val description: String
    val parameterSchema: JsonSchema
    
    /** Validate parameters before execution */
    fun validate(params: TParams): ValidationResult
    
    /** Create an executable invocation */
    fun build(params: TParams): ToolInvocation<TResult>
}

/**
 * A validated, ready-to-execute tool call.
 */
interface ToolInvocation<TResult> {
    /** Human-readable description of what this will do */
    fun getDescription(): String
    
    /** Does this need user approval? */
    suspend fun requiresApproval(): ApprovalRequirement
    
    /** Execute the tool */
    suspend fun execute(signal: CancellationSignal): TResult
}

sealed interface ApprovalRequirement {
    object None : ApprovalRequirement
    data class Required(val reason: String, val riskLevel: RiskLevel) : ApprovalRequirement
    data class Forbidden(val reason: String) : ApprovalRequirement
}

enum class RiskLevel { LOW, MEDIUM, HIGH }

/**
 * ToolRouter manages tool registration and execution.
 * Handles:
 * - Tool lookup by name
 * - Approval flow
 * - Retry semantics
 * - Error handling
 */
/**
 * ToolRegistry manages tool discovery, registration, and lifecycle.
 * Part of infrastructure - stable interface.
 * 
 * Pattern from Gemini CLI:
 * - Discovers tools from multiple sources (core, MCP servers, extensions)
 * - Provides schema generation for LLM function calling
 * - Filters tools based on configuration (allowedTools, excludeTools)
 */
class ToolRegistry(
    private val config: SessionConfig,
    private val policyEngine: PolicyEngine
) {
    private val tools = mutableMapOf<String, ToolSpec<*, *>>()
    
    fun registerTool(tool: ToolSpec<*, *>) {
        tools[tool.name] = tool
    }
    
    fun unregisterTool(name: String) {
        tools.remove(name)
    }
    
    fun getTool(name: String): ToolSpec<*, *>? = tools[name]
    
    fun getAllTools(): List<ToolSpec<*, *>> = tools.values.toList()
    
    fun getAllToolNames(): List<String> = tools.keys.toList()
    
    /** Generate OpenAI-compatible function schemas for all registered tools */
    fun generateFunctionSchemas(): List<JsonObject> {
        return tools.values
            .filter { config.isToolAllowed(it.name) }
            .map { it.toFunctionSchema() }
    }
}

/**
 * ToolRouter executes tool calls with state machine and policy-based approval.
 * 
 * Implements the tool call state machine (from Gemini):
 * VALIDATING → SCHEDULED → AWAITING_APPROVAL → EXECUTING → SUCCESS/ERROR/CANCELLED
 */
class ToolRouter(
    private val registry: ToolRegistry,
    private val policyEngine: PolicyEngine
) {
    // Currently executing tool calls (for tracking and cancellation)
    private val activeToolCalls = mutableMapOf<String, ToolCallState>()
    
    /**
     * Execute a tool call with state machine.
     * 
     * @param approvalHandler Called when user approval is needed; 
     *                       should suspend until user responds via Op.Approve
     */
    suspend fun execute(
        callId: String,
        name: String,
        params: JsonObject,
        signal: CancellationSignal,
        eventEmitter: suspend (AgentEvent) -> Unit,
        approvalHandler: suspend (ToolCallState.AwaitingApproval) -> ApprovalDecision
    ): ToolCallResult {
        
        // === STATE: VALIDATING ===
        val tool = registry.getTool(name) 
            ?: return ToolCallResult.Error(callId, UnknownToolException(name))
        
        val validation = tool.validate(params)
        if (validation is ValidationResult.Invalid) {
            return ToolCallResult.Error(callId, ValidationException(validation.errors))
        }
        
        val invocation = tool.build(params)
        
        // === PolicyEngine decides approval requirement ===
        val policyDecision = policyEngine.check(ToolCall(name, params))
        
        when (policyDecision) {
            PolicyDecision.DENY -> {
                return ToolCallResult.Error(callId, PolicyDeniedException())
            }
            
            PolicyDecision.ASK_USER -> {
                // === STATE: AWAITING_APPROVAL ===
                val awaitingState = ToolCallState.AwaitingApproval(
                    callId = callId,
                    tool = tool,
                    invocation = invocation,
                    description = invocation.getDescription()
                )
                activeToolCalls[callId] = awaitingState
                
                eventEmitter(AgentEvent.ApprovalRequired(
                    sessionId = SessionId(""), // Filled by caller
                    timestamp = System.currentTimeMillis(),
                    actionId = callId,
                    description = invocation.getDescription(),
                    details = ApprovalDetails(toolName = name, args = params)
                ))
                
                // Wait for user decision (via Op.Approve → approvalHandler)
                val decision = approvalHandler(awaitingState)
                
                if (decision != ApprovalDecision.APPROVED) {
                    activeToolCalls.remove(callId)
                    return ToolCallResult.Cancelled(callId, "User denied")
                }
            }
            
            PolicyDecision.ALLOW -> {
                // === STATE: SCHEDULED (skip approval) ===
            }
        }
        
        // === STATE: EXECUTING ===
        activeToolCalls[callId] = ToolCallState.Executing(callId, tool, invocation)
        
        return try {
            if (signal.isCancelled) {
                ToolCallResult.Cancelled(callId, "Cancelled before execution")
            } else {
                val result = invocation.execute(signal)
                // === STATE: SUCCESS ===
                ToolCallResult.Success(callId, result)
            }
        } catch (e: Exception) {
            // === STATE: ERROR ===
            ToolCallResult.Error(callId, e)
        } finally {
            activeToolCalls.remove(callId)
        }
    }
    
    /** Cancel a pending or executing tool call */
    fun cancel(callId: String) {
        activeToolCalls.remove(callId)
        // CancellationSignal will propagate to execution
    }
}

/** Tool call states */
sealed class ToolCallState {
    abstract val callId: String
    
    data class Validating(override val callId: String) : ToolCallState()
    data class Scheduled(override val callId: String, val tool: ToolSpec<*, *>) : ToolCallState()
    data class AwaitingApproval(
        override val callId: String,
        val tool: ToolSpec<*, *>,
        val invocation: ToolInvocation<*>,
        val description: String
    ) : ToolCallState()
    data class Executing(
        override val callId: String,
        val tool: ToolSpec<*, *>,
        val invocation: ToolInvocation<*>
    ) : ToolCallState()
}

/** Tool call results */
sealed class ToolCallResult {
    abstract val callId: String
    
    data class Success(override val callId: String, val result: Any?) : ToolCallResult()
    data class Error(override val callId: String, val exception: Exception) : ToolCallResult()
    data class Cancelled(override val callId: String, val reason: String) : ToolCallResult()
}

/** Policy engine for approval decisions */
class PolicyEngine(private var approvalMode: ApprovalMode) {
    
    fun check(toolCall: ToolCall): PolicyDecision {
        return when (approvalMode) {
            ApprovalMode.ALWAYS_ASK -> PolicyDecision.ASK_USER
            ApprovalMode.AUTO_APPROVE -> PolicyDecision.ALLOW
            ApprovalMode.SMART -> evaluateRisk(toolCall)
        }
    }
    
    private fun evaluateRisk(toolCall: ToolCall): PolicyDecision {
        // For Mobile-Agent-v3, most UI actions are reversible
        return when (toolCall.name) {
            "click", "scroll", "swipe" -> PolicyDecision.ALLOW
            "type", "back" -> PolicyDecision.ALLOW  // Or ASK_USER for sensitive apps
            else -> PolicyDecision.ASK_USER
        }
    }
    
    fun setApprovalMode(mode: ApprovalMode) {
        approvalMode = mode
    }
}

enum class ApprovalMode { 
    ALWAYS_ASK,      // Always ask user (safest)
    AUTO_APPROVE,    // Never ask (fastest, for trusted environments)
    SMART            // Risk-based (default)
}

enum class PolicyDecision { ALLOW, DENY, ASK_USER }
```

### 4.5 Agent Registry (Infrastructure Component)

**Important Clarification**: There is a distinction between:
1. **AgentRegistry (Infrastructure)** - A registry of agent **definitions/specifications**, part of stable infrastructure
2. **Agent instances (Orchestration)** - Actual agent objects created and used during orchestration

The `AgentRegistry` is a **general infrastructure component**, not specific to any orchestration strategy. It:
- Discovers and loads agent definitions from configuration
- Supports both local and remote agents (like MCP servers)
- Provides metadata for tool descriptions and system prompts
- Is part of the session configuration (like ToolRegistry)

```kotlin
/**
 * AgentDefinition describes an agent without implementing it.
 * Stable specification that defines what an agent CAN do.
 * 
 * Pattern from Gemini CLI:
 * - Local agents: Run within the same process
 * - Remote agents: Call external services (A2A protocol)
 */
sealed class AgentDefinition {
    abstract val name: String
    abstract val description: String
    abstract val capabilities: List<AgentCapability>
    
    /** Local agent that runs within our process */
    data class Local(
        override val name: String,
        override val description: String,
        override val capabilities: List<AgentCapability>,
        val modelConfig: AgentModelConfig,
        val systemPrompt: String,
        val tools: List<String>  // Tool names this agent can use
    ) : AgentDefinition()
    
    /** Remote agent accessed via network (A2A, MCP, etc.) */
    data class Remote(
        override val name: String,
        override val description: String,
        override val capabilities: List<AgentCapability>,
        val endpoint: String,
        val authConfig: AuthConfig?
    ) : AgentDefinition()
}

data class AgentModelConfig(
    val model: String,           // "inherit" = use session model
    val temperature: Float? = null,
    val thinkingBudget: Int? = null
)

enum class AgentCapability {
    PLANNING,      // Can create high-level plans
    EXECUTION,     // Can select and execute actions
    REFLECTION,    // Can verify outcomes
    MEMORY,        // Can manage long-term memory
    SEARCH,        // Can search for information
}

/**
 * AgentRegistry manages agent definitions (not instances).
 * Part of infrastructure - stable interface.
 * 
 * For Mobile-Agent-v3:
 * - Register Manager, Executor, Reflector as LocalAgentDefinitions
 * - Orchestration creates instances from definitions as needed
 */
class AgentRegistry(private val config: SessionConfig) {
    private val agents = mutableMapOf<String, AgentDefinition>()
    
    suspend fun initialize() {
        // Discover from configuration
        loadBuiltInAgents()
        loadUserAgents()
        loadProjectAgents()
    }
    
    private fun loadBuiltInAgents() {
        // Register Mobile-Agent-v3 built-in agents
        registerAgent(AgentDefinition.Local(
            name = "manager",
            description = "High-level planning and goal decomposition",
            capabilities = listOf(AgentCapability.PLANNING),
            modelConfig = AgentModelConfig(model = "inherit"),
            systemPrompt = ManagerPrompts.SYSTEM,
            tools = emptyList()  // Manager doesn't use tools directly
        ))
        
        registerAgent(AgentDefinition.Local(
            name = "executor",
            description = "Action selection and tool invocation",
            capabilities = listOf(AgentCapability.EXECUTION),
            modelConfig = AgentModelConfig(model = "inherit"),
            systemPrompt = ExecutorPrompts.SYSTEM,
            tools = listOf("click", "type", "scroll", "swipe", "back")
        ))
        
        registerAgent(AgentDefinition.Local(
            name = "reflector",
            description = "Outcome verification and error detection",
            capabilities = listOf(AgentCapability.REFLECTION),
            modelConfig = AgentModelConfig(model = "inherit"),
            systemPrompt = ReflectorPrompts.SYSTEM,
            tools = emptyList()
        ))
    }
    
    fun registerAgent(definition: AgentDefinition) {
        agents[definition.name] = definition
    }
    
    fun getDefinition(name: String): AgentDefinition? = agents[name]
    
    fun getAllDefinitions(): List<AgentDefinition> = agents.values.toList()
    
    /** Generate a "phone book" for system prompts listing available agents */
    fun getDirectoryContext(): String {
        return buildString {
            appendLine("## Available Agents")
            agents.values.forEach { agent ->
                appendLine("- **${agent.name}**: ${agent.description}")
            }
        }
    }
}

/**
 * Agent instance - created by orchestration from a definition.
 * This is what actually does the work.
 */
interface Agent {
    val definition: AgentDefinition
    
    /** Invoke the agent with context and get a response */
    suspend fun invoke(context: AgentContext): AgentResponse
}

/**
 * Factory to create agent instances from definitions.
 * Orchestration uses this to get actual working agents.
 */
class AgentFactory(
    private val llmClient: LLMClient,
    private val toolRouter: ToolRouter
) {
    fun create(definition: AgentDefinition): Agent {
        return when (definition) {
            is AgentDefinition.Local -> LocalAgent(definition, llmClient, toolRouter)
            is AgentDefinition.Remote -> RemoteAgent(definition)
        }
    }
}
```

**Where AgentRegistry Lives in Architecture**:

```
┌─────────────────────────────────────────────────────────────┐
│                  Infrastructure Layer (STABLE)              │
│  ┌─────────────┐  ┌─────────────┐  ┌───────────────────┐   │
│  │ToolRegistry │  │AgentRegistry│  │HistoryManager     │   │
│  │  (tools)    │  │(definitions)│  │ (history/memory)  │   │
│  └─────────────┘  └─────────────┘  └───────────────────┘   │
│                         │                                   │
│                    definitions                              │
│                         ▼                                   │
└─────────────────────────────────────────────────────────────┘
                          │
          ┌───────────────┴───────────────┐
          ▼                               ▼
┌─────────────────────┐        ┌─────────────────────┐
│ Orchestration Layer │        │ Other Orchestration │
│ (MobileV3Orch)      │        │ (SingleAgentOrch)   │
│                     │        │                     │
│ Creates instances:  │        │ Creates instances:  │
│ - ManagerAgent      │        │ - MainAgent         │
│ - ExecutorAgent     │        │                     │
│ - ReflectorAgent    │        │                     │
└─────────────────────┘        └─────────────────────┘
```

### 4.6 History Manager (Conversation History)

Inspired by Codex's `ContextManager` (in `context_manager/history.rs`), this component manages:
- Conversation history with truncation
- Token budget tracking
- History normalization (ensuring call/output pairs match)
- Turn rollback capability

**Note**: This is NOT about memory files (GEMINI.md). For that, see Memory Discovery in future considerations.

```kotlin
/**
 * HistoryManager handles conversation history.
 * Stable infrastructure component.
 * 
 * Key features from Codex:
 * - Truncation policies for tool outputs
 * - History normalization (orphan outputs, missing outputs)
 * - Token usage tracking
 * - Turn rollback for error recovery
 */
class HistoryManager(
    private val config: SessionConfig
) {
    private val items = mutableListOf<ResponseItem>()
    private var tokenInfo: TokenUsageInfo? = null
    
    /** Record items from a turn */
    fun recordItems(newItems: List<ResponseItem>, policy: TruncationPolicy) {
        newItems.forEach { item ->
            val processed = processItem(item, policy)
            items.add(processed)
        }
    }
    
    /** Get history prepared for sending to model */
    fun forPrompt(): List<ResponseItem> {
        val normalized = normalizeHistory(items.toList())
        return normalized.filter { it !is ResponseItem.GhostSnapshot }
    }
    
    /** Estimate token count for context window management */
    fun estimateTokenCount(): Long {
        // Simplified - real implementation would use tokenizer
        return items.sumOf { it.approximateTokens() }
    }
    
    /** Drop last N user turns (for rollback/retry) */
    fun dropLastNUserTurns(n: Int) {
        if (n <= 0) return
        
        val userTurnPositions = items.mapIndexedNotNull { index, item ->
            if (item is ResponseItem.Message && item.role == "user") index else null
        }
        
        if (userTurnPositions.isEmpty()) return
        
        val cutIndex = if (n >= userTurnPositions.size) {
            userTurnPositions.first()
        } else {
            userTurnPositions[userTurnPositions.size - n]
        }
        
        items.subList(cutIndex, items.size).clear()
    }
    
    /** Remove first item (oldest) for context window management */
    fun removeFirstItem() {
        if (items.isNotEmpty()) {
            val removed = items.removeAt(0)
            // Also remove corresponding output/call if needed
            removeCorrespondingFor(removed)
        }
    }
    
    /** Truncate tool output based on policy */
    private fun processItem(item: ResponseItem, policy: TruncationPolicy): ResponseItem {
        return when (item) {
            is ResponseItem.FunctionCallOutput -> {
                val truncated = truncateText(item.content, policy)
                item.copy(content = truncated)
            }
            else -> item
        }
    }
    
    /** Ensure every call has output and vice versa */
    private fun normalizeHistory(items: List<ResponseItem>): List<ResponseItem> {
        val result = items.toMutableList()
        // 1. Add placeholder outputs for calls without outputs
        ensureCallOutputsPresent(result)
        // 2. Remove outputs without corresponding calls
        removeOrphanOutputs(result)
        return result
    }
}

sealed class ResponseItem {
    data class Message(val role: String, val content: String) : ResponseItem()
    data class FunctionCall(val id: String, val name: String, val args: JsonObject) : ResponseItem()
    data class FunctionCallOutput(val callId: String, val content: String, val success: Boolean) : ResponseItem()
    object GhostSnapshot : ResponseItem()  // Placeholder for removed items
}

enum class TruncationPolicy(val maxTokens: Int) {
    NONE(Int.MAX_VALUE),
    CONSERVATIVE(8000),
    AGGRESSIVE(2000)
}
```

### 4.7 Session Services Container

Following Codex's `SessionServices` pattern, aggregate all session-scoped services:

```kotlin
/**
 * Container for all session-scoped services.
 * Dependency injection for orchestration and agents.
 * 
 * Pattern from Codex: Single object holding all services needed for a session.
 */
/**
 * SessionServices - Dependency Injection Container
 * 
 * Each service has ONE clear responsibility:
 * - toolRegistry: Discovery and schema generation for tools
 * - toolRouter: Execution of tools with state machine (includes approval flow)
 * - agentRegistry: Discovery of agent definitions
 * - agentFactory: Creates agent instances from definitions
 * - historyManager: Conversation history with truncation/normalization
 * - policyEngine: Decides ALLOW/DENY/ASK_USER for tool calls
 * - llmClient: LLM API communication
 * - platform: Android-specific operations
 * - config: Session configuration
 */
data class SessionServices(
    val toolRegistry: ToolRegistry,
    val toolRouter: ToolRouter,
    val agentRegistry: AgentRegistry,
    val agentFactory: AgentFactory,
    val historyManager: HistoryManager,
    val policyEngine: PolicyEngine,
    val llmClient: LLMClient,
    val platform: AndroidPlatform,
    val config: SessionConfig
) {
    companion object {
        suspend fun create(config: SessionConfig, platform: AndroidPlatform): SessionServices {
            // Create services in dependency order
            val llmClient = createLLMClient(config)
            val policyEngine = PolicyEngine(config.approvalMode)
            
            val toolRegistry = ToolRegistry(config).apply {
                // Register built-in tools
                registerTool(ClickTool())
                registerTool(TypeTool())
                registerTool(ScrollTool())
                registerTool(SwipeTool())
                registerTool(BackTool())
            }
            
            // ToolRouter uses PolicyEngine for approval decisions
            val toolRouter = ToolRouter(toolRegistry, policyEngine)
            
            val agentRegistry = AgentRegistry(config).apply { initialize() }
            val agentFactory = AgentFactory(llmClient, toolRouter)
            val historyManager = HistoryManager(config)
            
            return SessionServices(
                toolRegistry = toolRegistry,
                toolRouter = toolRouter,
                agentRegistry = agentRegistry,
                agentFactory = agentFactory,
                historyManager = historyManager,
                policyEngine = policyEngine,
                llmClient = llmClient,
                platform = platform,
                config = config
            )
        }
    }
}
```

### 4.8 Android Platform Bridge

Isolate Android-specific code behind interfaces:

```kotlin
/**
 * Platform abstraction for Android-specific operations.
 * Allows testing orchestration logic without Android.
 */
interface AndroidPlatform {
    /** Get the current screen state */
    suspend fun captureScreen(): ScreenSnapshot
    
    /** Perform a UI action */
    suspend fun performAction(action: UIAction): ActionResult
    
    /** Check if we have required permissions */
    fun hasRequiredPermissions(): Boolean
}

/**
 * Real implementation using AccessibilityService
 */
class AccessibilityPlatform(
    private val service: AccessibilityService
) : AndroidPlatform {
    private val perceptor = Perceptor()
    private val dispatcher = ActionDispatcher(service)
    
    override suspend fun captureScreen(): ScreenSnapshot {
        return withContext(Dispatchers.Main) {
            val root = service.rootInActiveWindow
            perceptor.snapshot(root)
        }
    }
    
    override suspend fun performAction(action: UIAction): ActionResult {
        return dispatcher.perform(action)
    }
    
    override fun hasRequiredPermissions(): Boolean {
        return service.serviceInfo != null
    }
}

/**
 * Mock implementation for testing
 */
class MockPlatform(
    private val screenSequence: List<ScreenSnapshot>,
    private val actionResults: Map<UIAction, ActionResult> = emptyMap()
) : AndroidPlatform {
    private var screenIndex = 0
    
    override suspend fun captureScreen(): ScreenSnapshot {
        return screenSequence.getOrElse(screenIndex++) { screenSequence.last() }
    }
    
    override suspend fun performAction(action: UIAction): ActionResult {
        return actionResults[action] ?: ActionResult.Success
    }
    
    override fun hasRequiredPermissions() = true
}
```

---

## 5. Mobile-Agent-v3 Orchestration Implementation

With the stable infrastructure in place, here's how we implement the v3 multi-agent orchestration.

**Note on AgentRegistry vs Agent Instances**:
- `AgentRegistry` (infrastructure) stores agent **definitions**
- `AgentFactory` creates **instances** from definitions
- Orchestration holds agent **instances** that do the actual work

```kotlin
/**
 * Mobile-Agent-v3 style multi-agent orchestration.
 * 
 * Agents:
 * - Manager: High-level planning
 * - Executor: Action selection
 * - Reflector: Outcome verification
 * - Notetaker: Memory management (optional)
 * 
 * This orchestration creates agent instances from definitions
 * provided by the infrastructure's AgentRegistry.
 */
class MobileV3Orchestration(
    private val goal: String,
    private val services: SessionServices,  // All services via DI container
    private val eventEmitter: suspend (AgentEvent) -> Unit,
    private val cancellationSignal: Deferred<CancellationReason>
) : AgentOrchestration {
    
    // Agent INSTANCES created from definitions (not the registry itself)
    private val manager: Agent = services.agentFactory.create(
        services.agentRegistry.getDefinition("manager")!!
    )
    private val executor: Agent = services.agentFactory.create(
        services.agentRegistry.getDefinition("executor")!!
    )
    private val reflector: Agent = services.agentFactory.create(
        services.agentRegistry.getDefinition("reflector")!!
    )
    
    // Convenience accessors
    private val platform get() = services.platform
    private val toolRouter get() = services.toolRouter
    private val historyManager get() = services.historyManager
    
    // Cooperative pause via StateFlow
    private val pauseState = MutableStateFlow(false)
    private val interruptSignal = CompletableDeferred<Unit>()
    
    // Session state (InfoPool equivalent)
    private val sessionState = SessionExecutionState(
        instruction = goal,
        plan = "",
        currentSubgoal = "",
        actionHistory = mutableListOf(),
        outcomes = mutableListOf(),
        memory = ""
    )
    
    override suspend fun run() = coroutineScope {
        var previousSnapshot: ScreenSnapshot? = null
        
        emitStatus("🚀 Starting agent for: $goal")
        
        while (isActive && !cancellationSignal.isCompleted) {
            // Cooperative pause check
            pauseState.first { !it }  // Suspends while paused
            
            // Check for interrupt
            if (interruptSignal.isCompleted) {
                interruptSignal.await()
                // Reset for next turn
                continue
            }
            
            // === TURN START ===
            val turnId = UUID.randomUUID().toString()
            
            // 1. Perception
            emitTurn(turnId, TurnPhase.PERCEPTION)
            emitStatus("👀 Scanning screen...")
            val currentSnapshot = platform.captureScreen()
            emitScreenCaptured(currentSnapshot)
            
            // 2. Reflection (if we have history)
            if (previousSnapshot != null && sessionState.actionHistory.isNotEmpty()) {
                emitTurn(turnId, TurnPhase.REFLECTION)
                emitStatus("🤔 Verifying last action...")
                
                val lastAction = sessionState.actionHistory.last()
                val context = ReflectorContext(
                    before = previousSnapshot,
                    after = currentSnapshot,
                    action = lastAction,
                    state = sessionState
                )
                val response = reflector.invoke(context)
                val outcome = response.asOutcome()
                sessionState.outcomes.add(outcome)
                emitThought("reflector", "Outcome: ${outcome.status}")
            }
            
            // 3. Planning
            if (shouldReplan(sessionState)) {
                emitTurn(turnId, TurnPhase.PLANNING)
                emitStatus("🧠 Planning...")
                
                val context = ManagerContext(
                    state = sessionState,
                    currentScreen = currentSnapshot
                )
                val response = manager.invoke(context)
                val planResult = response.asPlanResult()
                sessionState.apply {
                    plan = planResult.plan
                    currentSubgoal = planResult.currentSubgoal
                }
                emitThought("manager", "Plan: ${planResult.plan}")
                
                if (planResult.isFinished) {
                    emitStatus("✅ Task completed!")
                    break
                }
            }
            
            // 4. Execution
            emitTurn(turnId, TurnPhase.EXECUTION)
            emitStatus("💡 Executing...")
            
            val context = ExecutorContext(
                state = sessionState,
                currentScreen = currentSnapshot
            )
            val response = executor.invoke(context)
            val action = response.asAction()
            emitThought("executor", "Action: $action")
            
            when (action) {
                is AgentAction.FinishAction -> {
                    // Subgoal finished, next loop will replan
                }
                is AgentAction.InvalidAction -> {
                    sessionState.errorDescriptions.add(action.reason ?: "Invalid action")
                }
                else -> {
                    // Execute via tool router
                    val result = executeAction(action, currentSnapshot, turnId)
                    emitActionResult(action, result)
                }
            }
            
            // 5. Update state
            sessionState.actionHistory.add(action)
            previousSnapshot = currentSnapshot
            
            // === TURN END ===
            emitTurnCompleted(turnId)
            
            // Wait for UI to settle
            delay(config.actionDelayMs)
        }
    }
    
    override suspend fun pause() {
        pauseState.value = true
    }
    
    override suspend fun resume() {
        pauseState.value = false
    }
    
    override suspend fun interrupt() {
        interruptSignal.complete(Unit)
    }
    
    override suspend fun stop() {
        // Cancellation happens via cancellationSignal
    }
    
    private fun shouldReplan(state: SessionExecutionState): Boolean {
        return state.plan.isEmpty() ||
               state.errorFlagPlan ||
               state.actionHistory.lastOrNull() is AgentAction.FinishAction
    }
    
    // ... helper emit methods ...
}
```

### 5.2 How Approvals Work (Unified Design)

Our design uses **PolicyEngine** (from Gemini) for approval decisions, integrated with the **Tool State Machine**:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                        APPROVAL FLOW (Our Unified Design)                       │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  1. Tool call received from LLM                                                 │
│        │                                                                        │
│        ▼                                                                        │
│  ┌─────────────┐                                                                │
│  │  VALIDATING │ ← Validate parameters                                          │
│  └──────┬──────┘                                                                │
│         │                                                                       │
│         ▼                                                                       │
│  ┌──────────────────────────────────────────────────────────────────────┐       │
│  │  PolicyEngine.check(toolCall) → ALLOW / DENY / ASK_USER             │       │
│  └──────────────────────────────────────────────────────────────────────┘       │
│         │                    │                    │                             │
│         ▼ ALLOW              ▼ DENY               ▼ ASK_USER                    │
│  ┌─────────────┐      ┌─────────────┐     ┌───────────────────┐                 │
│  │  SCHEDULED  │      │   ERROR     │     │ AWAITING_APPROVAL │                 │
│  └──────┬──────┘      └─────────────┘     └─────────┬─────────┘                 │
│         │                                           │                           │
│         │                        ┌──────────────────┼──────────────────┐        │
│         │                        │ UI shows approval dialog            │        │
│         │                        │ User submits Op.Approve(decision)   │        │
│         │                        └──────────────────┼──────────────────┘        │
│         │                                           │                           │
│         │                   ┌────────────┬──────────┴──────────┐                │
│         │                   ▼ APPROVED   ▼ DENIED              ▼ ABORT          │
│         │            ┌─────────────┐  ┌─────────────┐   ┌─────────────┐         │
│         └───────────►│  EXECUTING  │  │  CANCELLED  │   │  CANCELLED  │         │
│                      └──────┬──────┘  └─────────────┘   └─────────────┘         │
│                             │                                                   │
│                     ┌───────┴───────┐                                           │
│                     ▼ success       ▼ failure                                   │
│              ┌─────────────┐  ┌─────────────┐                                   │
│              │   SUCCESS   │  │    ERROR    │                                   │
│              └─────────────┘  └─────────────┘                                   │
└─────────────────────────────────────────────────────────────────────────────────┘
```

**Key Design Decision**: We use `PolicyEngine` for policy decisions rather than `TurnState.pendingApprovals` (Codex approach).

**Why**:
- **Codex's `TurnState.pendingApprovals`**: Stores pending approvals in a map with `CompletableDeferred`. Mixes policy logic with state management.
- **Gemini's `PolicyEngine`**: Separates policy decisions (allow/deny/ask) from execution state. Cleaner and more testable.

Our approach:
1. `PolicyEngine` decides whether approval is needed (based on `ApprovalMode`)
2. Tool state machine tracks execution state (including `AWAITING_APPROVAL`)
3. `Op.Approve` operation resolves the pending state via the session's op handler
4. No separate `TurnState` class needed - state lives in the tool call itself

---

## 6. Error Handling Strategy

```kotlin
/**
 * Centralized error handling with categorization and recovery.
 */
sealed class AgentError {
    abstract val message: String
    abstract val isRecoverable: Boolean
    
    // LLM errors
    data class LLMError(
        override val message: String,
        val statusCode: Int?,
        val retryAfterMs: Long?
    ) : AgentError() {
        override val isRecoverable = statusCode in listOf(429, 503, 504)
    }
    
    // Platform errors
    data class PlatformError(
        override val message: String,
        val cause: Throwable?
    ) : AgentError() {
        override val isRecoverable = false
    }
    
    // Validation errors
    data class ValidationError(
        override val message: String,
        val field: String
    ) : AgentError() {
        override val isRecoverable = true  // LLM can try again
    }
    
    // State errors
    data class InvalidState(
        override val message: String
    ) : AgentError() {
        override val isRecoverable = false
    }
    
    companion object {
        fun from(e: Exception): AgentError = when (e) {
            is HttpException -> LLMError(e.message ?: "HTTP error", e.code(), null)
            is SocketTimeoutException -> LLMError("Timeout", null, 5000)
            is AccessibilityException -> PlatformError(e.message ?: "Accessibility error", e)
            else -> PlatformError(e.message ?: "Unknown error", e)
        }
    }
}

/**
 * Retry wrapper with exponential backoff.
 */
suspend fun <T> withRetry(
    maxAttempts: Int = 3,
    initialDelayMs: Long = 1000,
    maxDelayMs: Long = 30000,
    shouldRetry: (AgentError) -> Boolean = { it.isRecoverable },
    block: suspend () -> T
): Result<T> {
    var currentDelay = initialDelayMs
    var lastError: AgentError? = null
    
    repeat(maxAttempts) { attempt ->
        try {
            return Result.success(block())
        } catch (e: Exception) {
            lastError = AgentError.from(e)
            
            if (!shouldRetry(lastError!!) || attempt == maxAttempts - 1) {
                return Result.failure(e)
            }
            
            delay(currentDelay)
            currentDelay = (currentDelay * 2).coerceAtMost(maxDelayMs)
        }
    }
    
    return Result.failure(Exception(lastError?.message ?: "Max retries exceeded"))
}
```

---

## 7. Package Structure

```
com.moonkey.androidagent/
│
├── protocol/                    # PHASE 1 - Pure data types, no dependencies
│   ├── Op.kt                   # Operation sealed interface (UI → Session)
│   ├── AgentEvent.kt           # Event sealed interface (Session → UI)
│   ├── SessionId.kt            # Value class for session ID
│   ├── SessionState.kt         # Lifecycle state machine
│   ├── AgentError.kt           # Error type hierarchy
│   └── ApprovalTypes.kt        # ApprovalDecision, ApprovalDetails, RiskLevel
│
├── session/                     # PHASE 2 - Session lifecycle management
│   ├── AgentSession.kt         # Main session class with Op/Event protocol
│   ├── SessionConfig.kt        # Configuration (model, approval mode, etc.)
│   └── SessionServices.kt      # PHASE 5 - DI container for all services
│
├── platform/                    # PHASE 3 - Android abstraction
│   ├── AndroidPlatform.kt      # Interface for platform operations
│   ├── AccessibilityPlatform.kt # Real implementation wrapping existing code
│   ├── UIAction.kt             # Action data types (click, type, scroll)
│   ├── ActionResult.kt         # Result data types (success, error)
│   └── mock/
│       └── MockPlatform.kt     # Test implementation
│
├── infra/                       # PHASE 4-5 - Infrastructure components
│   ├── tools/                  # PHASE 4 - Tool infrastructure
│   │   ├── ToolSpec.kt         # Tool specification interface
│   │   ├── ToolInvocation.kt   # Executable invocation
│   │   ├── ToolCallState.kt    # State machine (validating→executing→done)
│   │   ├── ToolCallResult.kt   # Result types (success, error, cancelled)
│   │   └── ToolRouter.kt       # Tool execution with state machine
│   ├── registry/               # PHASE 4-5 - Registration and discovery
│   │   ├── ToolRegistry.kt     # Tool registration & schema generation
│   │   └── AgentRegistry.kt    # Agent definitions registry (PHASE 5)
│   ├── policy/                 # PHASE 4 - Policy and approval
│   │   └── PolicyEngine.kt     # Policy decisions (allow/deny/ask)
│   └── history/                # PHASE 5 - Conversation history
│       └── HistoryManager.kt   # History, truncation, normalization
│
├── tools/                       # PHASE 4 - Tool implementations
│   ├── impl/
│   │   ├── ClickTool.kt
│   │   ├── TypeTool.kt
│   │   ├── ScrollTool.kt
│   │   ├── SwipeTool.kt
│   │   └── BackTool.kt
│   └── base/
│       └── BaseTool.kt         # Common tool implementation helpers
│
├── orchestration/               # PHASE 6 - Agent coordination strategies
│   ├── AgentOrchestration.kt   # Interface for orchestration strategies
│   ├── OrchestrationFactory.kt # Factory interface
│   ├── v3/                     # Mobile-Agent-v3 multi-agent style
│   │   ├── MobileV3Orchestration.kt
│   │   └── SessionExecutionState.kt
│   └── legacy/                 # Adapter for existing orchestrator
│       └── LegacyOrchestrationAdapter.kt
│
├── agents/                      # PHASE 6 - Agent implementations
│   ├── Agent.kt                # Agent interface
│   ├── AgentFactory.kt         # Creates instances from definitions
│   ├── AgentDefinition.kt      # Agent specification data class
│   └── impl/
│       ├── LocalAgent.kt       # Local LLM-based agent
│       └── RemoteAgent.kt      # Future: remote agents via A2A
│
├── domain/                      # EXISTING - Domain models (kept for compatibility)
│   ├── agents/                 # Existing agent implementations
│   │   ├── Agent.kt
│   │   ├── Executor.kt
│   │   ├── Manager.kt
│   │   └── Reflector.kt
│   ├── models/
│   │   └── Models.kt           # AgentAction, ScreenSnapshot, etc.
│   └── state/
│       └── InfoPool.kt         # Existing state management
│
├── data/                        # EXISTING - Data layer
│   ├── llm/
│   │   ├── LLMClient.kt
│   │   └── ChatMessage.kt
│   └── perception/
│       └── Perceptor.kt
│
├── service/                     # EXISTING - Android service integration
│   ├── AgentService.kt         # AccessibilityService entry point
│   ├── AgentOrchestrator.kt    # LEGACY - Existing orchestrator
│   ├── ActionDispatcher.kt     # LEGACY - Action execution
│   └── OverlayManager.kt       # Floating UI
│
├── ui/
│   └── MainActivity.kt
│
└── legacy/                      # PHASE 7 - Deprecated code (for removal)
    └── (code moved here before deletion)
```

**Notes**:
- `PHASE N` comments indicate when each package is introduced
- `EXISTING` packages contain current code that will be wrapped/migrated
- `LEGACY` marks code that will be replaced by new implementations
- The structure supports incremental migration with clear boundaries

---

## 8. Testing Strategy

The layered architecture enables testing at each level:

### Unit Tests (No Android)
```kotlin
class MobileV3OrchestrationTest {
    @Test
    fun `should replan when plan is empty`() = runTest {
        val mockPlatform = MockPlatform(screenSequence = listOf(
            ScreenSnapshot(elements = listOf(/* ... */))
        ))
        val mockAgents = MockAgentRegistry(
            manager = MockManager(returnsPlan = "Step 1: Click X"),
            executor = MockExecutor(returnsAction = AgentAction.Click(1)),
            reflector = MockReflector(returnsSuccess = true)
        )
        
        val orchestration = MobileV3Orchestration(
            goal = "Test goal",
            platform = mockPlatform,
            agents = mockAgents,
            // ...
        )
        
        orchestration.run()
        
        verify(mockAgents.manager).think(any(), any())
    }
}
```

### Integration Tests (Android, Mocked LLM)
```kotlin
@RunWith(AndroidJUnit4::class)
class AgentSessionTest {
    @Test
    fun `pause and resume work correctly`() = runTest {
        val session = AgentSession.create(
            config = testConfig,
            orchestrationFactory = MockOrchestrationFactory(),
            scope = this
        )
        
        session.submit(Op.Start("Test goal", config))
        advanceUntilIdle()
        assertEquals(SessionState.Running, session.state.value)
        
        session.submit(Op.Pause)
        advanceUntilIdle()
        assertEquals(SessionState.Paused, session.state.value)
        
        session.submit(Op.Resume)
        advanceUntilIdle()
        assertEquals(SessionState.Running, session.state.value)
    }
}
```

---

## 9. Migration Path

**Design Principles for Migration**:
1. **Each phase is independently deployable** - The app works on device after each phase
2. **No forward dependencies** - Each phase only uses code from previous phases
3. **Backward compatible** - Existing functionality is preserved until explicitly replaced
4. **Testable checkpoints** - Each phase has clear validation criteria

### Phase 1: Protocol Layer (Days 1-2)
**Goal**: Define the communication protocol as pure data types with no dependencies.

**Tasks**:
1. Create `protocol/` package with:
   - `Op.kt` - Operations sealed interface
   - `AgentEvent.kt` - Events sealed interface
   - `SessionId.kt` - Value class for session identification
   - `SessionState.kt` - State machine states
   - `AgentError.kt` - Error type hierarchy
   - `ApprovalTypes.kt` - Approval-related types (ApprovalDecision, ApprovalDetails)

**Validation**:
- ✅ Project compiles
- ✅ Unit tests pass for data classes (serialization, equality)
- ✅ No runtime changes - existing app unchanged

**Deliverables**:
```
protocol/
├── Op.kt                 # Sealed interface for UI → Session operations
├── AgentEvent.kt         # Sealed interface for Session → UI events
├── SessionId.kt          # @JvmInline value class
├── SessionState.kt       # Sealed interface for lifecycle states
├── AgentError.kt         # Sealed class hierarchy for errors
└── ApprovalTypes.kt      # ApprovalDecision, ApprovalDetails, RiskLevel
```

---

### Phase 2: Session Bridge (Days 3-4)
**Goal**: Introduce AgentSession as a facade over existing AgentOrchestrator, using the new protocol.

**Tasks**:
1. Create `session/AgentSession.kt` that:
   - Accepts `Op` operations via `submit()` method
   - Emits `AgentEvent` via `Flow<AgentEvent>`
   - **Internally delegates to existing `AgentOrchestrator`** (bridge pattern)
   - Maintains `SessionState` state machine

2. Update `AgentService.kt` to:
   - Create and hold `AgentSession` instead of direct `AgentOrchestrator`
   - Forward overlay button presses as `Op.Pause`, `Op.Resume`, `Op.Shutdown`

3. Update `OverlayManager.kt` to:
   - Observe `AgentSession.events` Flow for status updates
   - Convert current callback-based status to event-driven

**Validation**:
- ✅ App runs on device with identical behavior
- ✅ Overlay buttons still work (pause/resume/stop)
- ✅ Status updates appear in overlay
- ✅ `adb logcat` shows Op/Event flow

**Key Code - Bridge Pattern**:
```kotlin
// AgentSession internally uses existing orchestrator
class AgentSession(...) {
    private val legacyOrchestrator: AgentOrchestrator  // Bridge to existing code
    
    suspend fun submit(op: Op) {
        when (op) {
            is Op.Pause -> legacyOrchestrator.pause()
            is Op.Resume -> legacyOrchestrator.resume()
            // etc.
        }
    }
}
```

---

### Phase 3: Platform Abstraction (Days 5-7)
**Goal**: Abstract Android-specific code behind interfaces for testability.

**Tasks**:
1. Create `platform/AndroidPlatform.kt` interface with:
   - `captureScreen(): ScreenSnapshot`
   - `performAction(action: UIAction): ActionResult`
   - `hasRequiredPermissions(): Boolean`

2. Create `platform/AccessibilityPlatform.kt`:
   - Wraps existing `Perceptor` and `ActionDispatcher`
   - Implements `AndroidPlatform` interface

3. Create `platform/mock/MockPlatform.kt` for testing

4. Update `AgentOrchestrator` to use `AndroidPlatform` interface

**Validation**:
- ✅ App runs on device with identical behavior
- ✅ Can write unit test for orchestrator logic with MockPlatform
- ✅ No behavioral changes to end user

**Deliverables**:
```
platform/
├── AndroidPlatform.kt         # Interface
├── AccessibilityPlatform.kt   # Real implementation
├── UIAction.kt                # Action data types
├── ActionResult.kt            # Result data types
└── mock/
    └── MockPlatform.kt        # Test implementation
```

---

### Phase 4: Tool Infrastructure (Week 2)
**Goal**: Create tool registry and execution infrastructure, migrate ActionDispatcher.

**Tasks**:
1. Create `infra/tools/` package with:
   - `ToolSpec.kt` - Tool specification interface
   - `ToolInvocation.kt` - Executable invocation
   - `ToolCallState.kt` - State machine (validating → executing → done)
   - `ToolCallResult.kt` - Result types

2. Create `infra/registry/ToolRegistry.kt`:
   - Tool registration and lookup
   - Schema generation for LLM function calling

3. Create `infra/policy/PolicyEngine.kt`:
   - Simple implementation (ALLOW all for now)

4. Create `infra/tools/ToolRouter.kt`:
   - Executes tools through state machine
   - Uses PolicyEngine for approval decisions

5. Implement tools in `tools/impl/`:
   - `ClickTool.kt`, `TypeTool.kt`, `ScrollTool.kt`, `SwipeTool.kt`, `BackTool.kt`
   - Each wraps corresponding ActionDispatcher functionality

6. Update `AccessibilityPlatform` to use tools internally

**Validation**:
- ✅ App runs on device with identical behavior
- ✅ Tool calls flow through state machine (visible in logs)
- ✅ Can unit test tool validation logic

---

### Phase 5: Infrastructure Services (Week 2-3)
**Goal**: Complete infrastructure layer with remaining services.

**Tasks**:
1. Create `infra/history/HistoryManager.kt`:
   - Conversation history tracking
   - Truncation policies
   - Turn rollback capability

2. Create `infra/registry/AgentRegistry.kt`:
   - Agent definition storage
   - Built-in Mobile-Agent-v3 agent definitions

3. Create `session/SessionServices.kt`:
   - DI container aggregating all services
   - Factory method for proper initialization order

4. Update `AgentSession` to use `SessionServices`

**Validation**:
- ✅ App runs on device
- ✅ History is tracked (visible in debug logs)
- ✅ Services properly initialized in correct order

---

### Phase 6: New Orchestration (Week 3)
**Goal**: Implement proper MobileV3Orchestration using new infrastructure.

**Tasks**:
1. Create `orchestration/AgentOrchestration.kt` interface

2. Create `orchestration/OrchestrationFactory.kt` interface

3. Create `orchestration/v3/MobileV3Orchestration.kt`:
   - Implements `AgentOrchestration`
   - Uses `SessionServices` for all dependencies
   - Proper cooperative pause/resume/interrupt

4. Create `orchestration/legacy/LegacyOrchestrationAdapter.kt`:
   - Wraps old `AgentOrchestrator` as `AgentOrchestration`
   - Allows fallback via config flag

5. Update `AgentSession` to use `OrchestrationFactory`

6. Add config flag to choose orchestration:
   - `SessionConfig.useNewOrchestration: Boolean`

**Validation**:
- ✅ App works with BOTH orchestrations (toggle via config)
- ✅ New orchestration has cleaner logs
- ✅ Pause/resume/interrupt work correctly
- ✅ A/B testing possible

---

### Phase 7: Polish & Cleanup (Week 4)
**Goal**: Production-ready with comprehensive error handling.

**Tasks**:
1. Add retry with exponential backoff for LLM calls
2. Implement proper CancellationSignal propagation
3. Add telemetry/structured logging
4. Write comprehensive tests:
   - Unit tests for each component
   - Integration tests with MockPlatform
   - Instrumented tests on device
5. Remove legacy code when new orchestration is stable
6. Documentation cleanup

**Validation**:
- ✅ Graceful error handling (no crashes on LLM timeout)
- ✅ Clean shutdown (no leaked resources)
- ✅ Test coverage > 70%
- ✅ No legacy code paths in use

---

### Migration Summary

| Phase | Duration | Risk | Rollback |
|-------|----------|------|----------|
| 1. Protocol | 2 days | None | N/A (additive) |
| 2. Session Bridge | 2 days | Low | Remove AgentSession, revert AgentService |
| 3. Platform | 3 days | Low | Inline interface calls |
| 4. Tools | 5 days | Medium | Use ActionDispatcher directly |
| 5. Services | 3 days | Low | Remove unused services |
| 6. Orchestration | 5 days | Medium | Config flag to use legacy |
| 7. Polish | 5 days | Low | N/A |

**Total**: ~4 weeks with buffer for testing and iteration

---

## 10. Open Questions

1. **Event persistence**: Should we persist events to disk for session recovery?
2. **Multi-session**: Do we ever need multiple concurrent sessions?
3. **MCP integration**: Should we support Model Context Protocol for tool extensibility?
4. **Streaming**: Should LLM responses stream, or batch?
5. **MessageBus pattern**: Gemini uses a pub/sub MessageBus for decoupled communication. Do we need this level of decoupling, or is the simpler event channel sufficient?
6. **Hook system**: Gemini has a HookSystem for pre/post tool execution hooks. Should we support custom hooks for extensibility?
7. **Token budget management**: How aggressively should we truncate context? What's the right policy for mobile screens vs coding context?

---

## 11. Future Considerations (Not in Initial Design)

These patterns are available in the reference implementations but **not included in our initial design** to keep complexity manageable. Consider adding them later if needed.

### 11.1 MessageBus (Gemini) - Deferred

Gemini uses a pub/sub MessageBus for decoupled inter-component communication.

**Why deferred**: Our `Flow<AgentEvent>` pattern provides sufficient decoupling for now. MessageBus adds complexity for multi-subscriber scenarios we don't yet have. Consider adding if we need:
- Multiple independent UI components reacting to same events
- Plugin/extension systems with loose coupling
- Analytics that shouldn't be coupled to main event flow

### 11.2 Hook System (Gemini) - Deferred

Gemini has a `HookSystem` for pre/post tool execution hooks.

**Why deferred**: We don't have extension/plugin requirements yet. Consider adding if we need:
- Pre-tool validation beyond PolicyEngine
- Post-tool logging/analytics
- Third-party tool integrations

### 11.3 MCP Integration - Deferred

Model Context Protocol for external tool servers.

**Why deferred**: Mobile-Agent-v3 tools are all local (click, type, scroll). Consider adding if we need:
- External AI services
- Desktop integration
- Cloud tool execution

---

## 12. Summary of Key Design Decisions

### Infrastructure vs Orchestration Separation

| Component | Layer | Stability | Purpose |
|-----------|-------|-----------|---------|
| `Op` / `AgentEvent` | Protocol | **Stable** | Communication contract |
| `AgentSession` | Session | **Stable** | Lifecycle management |
| `ToolRegistry` | Infrastructure | **Stable** | Tool discovery & schemas |
| `AgentRegistry` | Infrastructure | **Stable** | Agent definition registry |
| `HistoryManager` | Infrastructure | **Stable** | Conversation history management |
| `SessionServices` | Infrastructure | **Stable** | DI container |
| `PolicyEngine` | Infrastructure | **Stable** | Approval policy decisions |
| `MobileV3Orchestration` | Orchestration | **Evolving** | Multi-agent strategy |
| Agent implementations | Orchestration | **Evolving** | Manager/Executor/Reflector |
| Prompts | Orchestration | **Evolving** | LLM instructions |

### Key Takeaways

1. **Unified design from best of both**: SQ/EQ pattern (Codex) + Tool state machine (Gemini) + PolicyEngine (Gemini) + SessionServices (Codex).

2. **AgentRegistry is infrastructure, not orchestration**. It stores agent *definitions*; orchestration creates *instances* via `AgentFactory`.

3. **Tool call state machine in ToolRouter** provides clear lifecycle: `VALIDATING → SCHEDULED/AWAITING_APPROVAL → EXECUTING → SUCCESS/ERROR/CANCELLED`.

4. **PolicyEngine decides approval**, ToolRouter manages state. No separate ApprovalManager or TurnState.pendingApprovals needed.

5. **SessionServices container** is the single DI entry point - no `Config` mega-object.

6. **HistoryManager handles conversation history** (truncation, normalization, rollback) - renamed to avoid confusion with Gemini's ContextManager (memory files).

7. **Op/Event protocol** enables clean UI-Agent separation via Kotlin Flows.

---

## 13. References

- [Codex CLI Source](https://github.com/openai/codex)
- [Gemini CLI Source](https://github.com/google-gemini/gemini-cli)
- [Mobile-Agent-v3 Paper](https://arxiv.org/abs/...)
- [Clean Architecture - Uncle Bob](https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html)

### Key Files Referenced

**Codex CLI (Rust)**:
- `codex-rs/core/src/state/session.rs` - Session state management
- `codex-rs/core/src/state/turn.rs` - Turn state and active turn tracking
- `codex-rs/core/src/state/service.rs` - SessionServices container
- `codex-rs/core/src/context_manager/history.rs` - History management with truncation
- `codex-rs/core/src/agent/control.rs` - Agent control with cancellation

**Gemini CLI (TypeScript)**:
- `packages/core/src/core/coreToolScheduler.ts` - Tool call state machine
- `packages/core/src/scheduler/types.ts` - Tool call state types
- `packages/core/src/config/config.ts` - Config as service locator (1800+ lines!)
- `packages/core/src/agents/registry.ts` - AgentRegistry for agent definitions
- `packages/core/src/tools/tool-registry.ts` - ToolRegistry for tool management
- `packages/core/src/confirmation-bus/message-bus.ts` - Pub/sub MessageBus

