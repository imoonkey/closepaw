# Agent Infrastructure Design

**Status**: Implemented (Phase 1-6 Complete)  
**Author**: Engineering Team  
**Last Updated**: January 2026

---

## 1. Overview

This document describes the **Agent Infrastructure** layer - a stable foundation for lifecycle management, event handling, and tool execution. The infrastructure is designed to be **orthogonal to agent orchestration strategy**, allowing the research-heavy agent logic to evolve without destabilizing the underlying system.

### Design Philosophy

1. **Separation of Stable vs Evolving**: Infrastructure is stable; orchestration evolves
2. **Single Responsibility**: Each component has one clear job
3. **Dependency Inversion**: Higher layers depend on abstractions from lower layers
4. **Explicit State Machines**: No hidden state transitions
5. **Testable at Every Level**: Interfaces enable mocking

### Related Documents

- **[Reference Analysis](./reference_analysis.md)**: Detailed comparison of Codex CLI and Gemini CLI architectures
- **[Migration Status](./migration_status.md)**: Implementation phases and current status

---

## 2. Layered Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                         UI Layer                            │
│    (OverlayManager, MainActivity, StatusDisplay)            │
└───────────────────────────┬─────────────────────────────────┘
                            │ Events ↑↓ Operations
┌───────────────────────────┴─────────────────────────────────┐
│                    Protocol Layer                           │
│    (Op, AgentEvent, SessionId, SessionState)                │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────────────────┴─────────────────────────────────┐
│                     Session Layer                           │
│    (AgentSession - lifecycle, state, event dispatch)        │
└───────────┬──────────────────────────────────┬──────────────┘
            │                                  │
┌───────────┴──────────────┐    ┌──────────────┴──────────────┐
│   Orchestration Layer    │    │    Infrastructure Layer     │
│   (MobileAgentV3 etc.    │    │   (ToolRouter, PolicyEngine,│
│    - EVOLVING)           │    │    HistoryManager - STABLE) │
└──────────────────────────┘    └─────────────────────────────┘
            │                                  │
            └──────────────┬───────────────────┘
                           │
┌──────────────────────────┴──────────────────────────────────┐
│                    Platform Layer                           │
│    (AndroidPlatform - abstracts device operations)          │
└─────────────────────────────────────────────────────────────┘
```

### Key Principle: Separation of Concerns

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

### Layer Dependencies

- **UI Layer** → Protocol (submits Ops, receives Events)
- **Session Layer** → Protocol, Infrastructure, Platform
- **Orchestration Layer** → Protocol, Infrastructure, Platform
- **Infrastructure Layer** → Protocol, Platform (for tool execution)
- **Platform Layer** → None (leaf layer)

---

## 3. Protocol Layer

The Protocol layer defines the typed communication contract between UI and the agent system.

### Operations (UI → Session)

Operations represent user intents. The UI submits operations; the session processes them asynchronously.

**Implementation**: [`protocol/Op.kt`](../../app/src/main/kotlin/com/moonkey/androidagent/protocol/Op.kt)

| Operation | Description | Valid States |
|-----------|-------------|--------------|
| `Start(goal, config)` | Begin agent execution | Created |
| `Pause` | Cooperative pause | Running |
| `Resume` | Resume from pause | Paused |
| `Interrupt` | Abort current turn | Running |
| `Shutdown` | End session | Any |
| `UserInput(text)` | Additional user input | Running |
| `Approve(actionId, decision)` | Respond to approval request | Running |

### Events (Session → UI)

Events represent state changes, progress, and results. The UI observes events via Kotlin Flow.

**Implementation**: [`protocol/AgentEvent.kt`](../../app/src/main/kotlin/com/moonkey/androidagent/protocol/AgentEvent.kt)

| Event Category | Events |
|---------------|--------|
| **Session Lifecycle** | SessionStarted, SessionCompleted, SessionError, SessionPaused, SessionResumed |
| **Turn Events** | TurnStarted, TurnCompleted, TurnPhaseChanged |
| **Agent Thinking** | AgentThinking |
| **Actions** | ActionProposed, ActionExecuted, ActionSkipped |
| **Perception** | ScreenCaptured |
| **Approval** | ApprovalRequired, ApprovalResolved |
| **Status** | StatusUpdate (convenience for simple UIs) |

### Session State Machine

**Implementation**: [`protocol/SessionState.kt`](../../app/src/main/kotlin/com/moonkey/androidagent/protocol/SessionState.kt)

```
Created ──Start──► Running ──Pause──► Paused
   │                  │                  │
   │                  │ ◄──Resume────────┘
   │                  │
   │                  ├──Complete──► Completed
   │                  │
   │                  ├──Error──► Error
   │                  │
   └──────────────────┴──Shutdown──► Shutdown
```

### Error Hierarchy

**Implementation**: [`protocol/AgentError.kt`](../../app/src/main/kotlin/com/moonkey/androidagent/protocol/AgentError.kt)

Each error type indicates what went wrong, whether it's recoverable, and context for debugging.

---

## 4. Session Layer

The Session layer manages agent lifecycle, coordinating between orchestration and UI.

### AgentSession

**Implementation**: [`session/AgentSession.kt`](../../app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt)

Key responsibilities:
- Accept operations via `submit(Op)`
- Emit events via `events: Flow<AgentEvent>`
- Maintain session state via `state: StateFlow<SessionState>`
- Coordinate orchestration lifecycle

Factory methods:
- `create()` - Legacy mode (backward compatibility)
- `createWithServices()` - Full Phase 6 mode with SessionServices
- `createWithFactory()` - Custom orchestration injection for testing

### SessionServices (DI Container)

**Implementation**: [`session/SessionServices.kt`](../../app/src/main/kotlin/com/moonkey/androidagent/session/SessionServices.kt)

Aggregates all session-scoped services with single responsibility:

| Service | Responsibility |
|---------|---------------|
| `toolRegistry` | Discovery and schema generation for tools |
| `toolRouter` | Execution of tools with state machine |
| `agentRegistry` | Discovery of agent definitions |
| `historyManager` | Conversation history management |
| `policyEngine` | Decides ALLOW/DENY/ASK_USER for tool calls |
| `platform` | Android-specific operations |
| `config` | Session configuration |

---

## 5. Infrastructure Layer

The Infrastructure layer provides stable services for tool execution, history management, and policy enforcement.

### 5.1 Tool System

#### ToolSpec (Interface)

**Implementation**: [`infra/tools/ToolSpec.kt`](../../app/src/main/kotlin/com/moonkey/androidagent/infra/tools/ToolSpec.kt)

Tools are declarative specifications that describe:
- What the tool does (name, description)
- What parameters it accepts (JSON schema)
- How to validate inputs
- How to create an executable invocation

#### ToolRouter (Execution Engine)

**Implementation**: [`infra/tools/ToolRouter.kt`](../../app/src/main/kotlin/com/moonkey/androidagent/infra/tools/ToolRouter.kt)

Executes tool calls through an explicit state machine:

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

#### ToolCallState

**Implementation**: [`infra/tools/ToolCallState.kt`](../../app/src/main/kotlin/com/moonkey/androidagent/infra/tools/ToolCallState.kt)

States: Validating, Scheduled, AwaitingApproval, Executing, Success, Error, Cancelled

#### ToolRegistry

**Implementation**: [`infra/registry/ToolRegistry.kt`](../../app/src/main/kotlin/com/moonkey/androidagent/infra/registry/ToolRegistry.kt)

Manages tool registration, lookup, and schema generation for LLM function calling.

#### Built-in Tools

**Implementation**: [`tools/impl/`](../../app/src/main/kotlin/com/moonkey/androidagent/tools/impl/)

| Tool | File | Description |
|------|------|-------------|
| Click | `ClickTool.kt` | Click on element by ID |
| Type | `TypeTool.kt` | Input text into element |
| Scroll | `ScrollTool.kt` | Scroll in direction |
| Swipe | `SwipeTool.kt` | Swipe gesture |
| Back | `BackTool.kt` | System back button |
| Wait | `WaitTool.kt` | Wait for UI to settle |

Base class: [`tools/base/BaseTool.kt`](../../app/src/main/kotlin/com/moonkey/androidagent/tools/base/BaseTool.kt)

### 5.2 Policy Engine

**Implementation**: [`infra/policy/PolicyEngine.kt`](../../app/src/main/kotlin/com/moonkey/androidagent/infra/policy/PolicyEngine.kt)

Decides whether tool calls should be allowed, denied, or require user approval.

| Mode | Behavior |
|------|----------|
| `ALWAYS_ASK` | Always request user approval |
| `AUTO_APPROVE` | Never ask, execute immediately |
| `SMART` | Risk-based: LOW=allow, MEDIUM=allow, HIGH=ask |

### 5.3 History Manager

**Implementation**: [`infra/history/HistoryManager.kt`](../../app/src/main/kotlin/com/moonkey/androidagent/infra/history/HistoryManager.kt)

Manages conversation history with:
- Truncation policies for tool outputs (to manage context window)
- History normalization (ensures call/output pairs match)
- Turn rollback for error recovery
- Token budget estimation
- Compression when approaching limits

**Note**: This manages CONVERSATION history, not memory files.

### 5.4 Agent Registry

**Implementation**: [`infra/registry/AgentRegistry.kt`](../../app/src/main/kotlin/com/moonkey/androidagent/infra/registry/AgentRegistry.kt)

Stores agent DEFINITIONS (not instances). Built-in agents:

| Agent | Capability | Description |
|-------|------------|-------------|
| Manager | PLANNING | High-level planning and goal decomposition |
| Executor | EXECUTION | Action selection and tool invocation |
| Reflector | REFLECTION | Outcome verification and error detection |

---

## 6. Platform Layer

The Platform layer abstracts Android-specific operations, enabling testability.

### AndroidPlatform Interface

**Implementation**: [`platform/AndroidPlatform.kt`](../../app/src/main/kotlin/com/moonkey/androidagent/platform/AndroidPlatform.kt)

| Method | Description |
|--------|-------------|
| `captureScreen()` | Capture current screen state as ScreenSnapshot |
| `performAction(action, snapshot)` | Execute a UIAction on the device |
| `hasRequiredPermissions()` | Check if permissions are available |
| `getCurrentPackageName()` | Get foreground app package name |
| `getDisplayInfo()` | Get screen dimensions |

### Implementations

| Class | Purpose |
|-------|---------|
| [`AccessibilityPlatform.kt`](../../app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityPlatform.kt) | Real implementation using AccessibilityService |
| [`MockPlatform.kt`](../../app/src/main/kotlin/com/moonkey/androidagent/platform/mock/MockPlatform.kt) | Test implementation with predefined responses |

### UIAction and ActionResult

**Implementations**: 
- [`platform/UIAction.kt`](../../app/src/main/kotlin/com/moonkey/androidagent/platform/UIAction.kt)
- [`platform/ActionResult.kt`](../../app/src/main/kotlin/com/moonkey/androidagent/platform/ActionResult.kt)

---

## 7. Orchestration Layer

The Orchestration layer defines HOW agents work together. This is the "evolving" part of the system.

### AgentOrchestration Interface

**Implementation**: [`orchestration/AgentOrchestration.kt`](../../app/src/main/kotlin/com/moonkey/androidagent/orchestration/AgentOrchestration.kt)

| Method | Description |
|--------|-------------|
| `run()` | Main loop - runs until goal achieved or cancelled |
| `pause()` | Cooperative pause - finish current action, then wait |
| `resume()` | Resume from pause |
| `interrupt()` | Abort current turn, prepare for next |
| `stop()` | Stop completely and cleanup |

### MobileV3Orchestration

**Implementation**: [`orchestration/v3/MobileV3Orchestration.kt`](../../app/src/main/kotlin/com/moonkey/androidagent/orchestration/v3/MobileV3Orchestration.kt)

Multi-agent orchestration following Mobile-Agent-v3 pattern:

```
Loop:
  1. PERCEPTION  → Capture screen state
  2. REFLECTION  → Verify previous action (if history exists)
  3. PLANNING    → Get/update plan from Manager (if needed)
  4. EXECUTION   → Select and execute action via Executor
  5. SETTLING    → Wait for UI to stabilize
```

State management: [`orchestration/v3/SessionExecutionState.kt`](../../app/src/main/kotlin/com/moonkey/androidagent/orchestration/v3/SessionExecutionState.kt)

### OrchestrationFactory

**Implementation**: [`orchestration/OrchestrationFactory.kt`](../../app/src/main/kotlin/com/moonkey/androidagent/orchestration/OrchestrationFactory.kt)

| Factory | Description |
|---------|-------------|
| `MobileV3OrchestrationFactory` | Creates MobileV3Orchestration |

---

## 8. Package Structure

```
com.moonkey.androidagent/
│
├── protocol/                    # Communication contract (stable)
│   ├── Op.kt                   # Operations + SessionConfig + ApprovalMode
│   ├── AgentEvent.kt           # Events + TurnPhase + CompletionReason
│   ├── SessionId.kt            # Value class for session ID
│   ├── SessionState.kt         # Lifecycle states + CancellationReason
│   ├── AgentError.kt           # Error type hierarchy
│   └── ApprovalTypes.kt        # ApprovalDecision, ApprovalDetails, RiskLevel
│
├── session/                     # Session lifecycle management (stable)
│   ├── AgentSession.kt         # Main session class
│   └── SessionServices.kt      # DI container
│
├── platform/                    # Android abstraction (stable)
│   ├── AndroidPlatform.kt      # Interface
│   ├── AccessibilityPlatform.kt
│   ├── UIAction.kt
│   ├── ActionResult.kt
│   └── mock/MockPlatform.kt
│
├── infra/                       # Infrastructure components (stable)
│   ├── tools/                  # Tool execution system
│   │   ├── ToolSpec.kt
│   │   ├── ToolCallState.kt
│   │   ├── ToolCallResult.kt
│   │   └── ToolRouter.kt
│   ├── registry/               # Registration & discovery
│   │   ├── ToolRegistry.kt
│   │   └── AgentRegistry.kt
│   ├── policy/
│   │   └── PolicyEngine.kt
│   └── history/
│       └── HistoryManager.kt
│
├── tools/                       # Tool implementations (stable interface, evolving impls)
│   ├── base/BaseTool.kt
│   └── impl/                   # ClickTool, TypeTool, etc.
│
├── orchestration/               # Agent coordination (evolving)
│   ├── AgentOrchestration.kt
│   ├── OrchestrationFactory.kt
│   └── v3/
│       ├── MobileV3Orchestration.kt
│       └── SessionExecutionState.kt
│
├── domain/                      # Existing domain models (kept for compatibility)
│   ├── agents/                 # Existing agent implementations
│   ├── models/                 # AgentAction, ScreenSnapshot, etc.
│   └── state/InfoPool.kt
│
├── data/                        # Data layer (existing)
│   ├── llm/LLMClient.kt
│   └── perception/Perceptor.kt
│
└── service/                     # Android service integration
    └── OverlayManager.kt       # Floating overlay UI
```

---

## 9. Key Design Decisions

### Infrastructure vs Orchestration Separation

| Component | Layer | Stability | Purpose |
|-----------|-------|-----------|---------|
| `Op` / `AgentEvent` | Protocol | **Stable** | Communication contract |
| `AgentSession` | Session | **Stable** | Lifecycle management |
| `ToolRegistry` | Infrastructure | **Stable** | Tool discovery & schemas |
| `AgentRegistry` | Infrastructure | **Stable** | Agent definition registry |
| `HistoryManager` | Infrastructure | **Stable** | Conversation history |
| `PolicyEngine` | Infrastructure | **Stable** | Approval policy decisions |
| `MobileV3Orchestration` | Orchestration | **Evolving** | Multi-agent strategy |
| Agent implementations | Orchestration | **Evolving** | Manager/Executor/Reflector |
| Prompts | Orchestration | **Evolving** | LLM instructions |

### Key Takeaways

1. **Op/Event protocol** enables clean UI-Agent separation via Kotlin Flows

2. **SessionServices container** is the single DI entry point

3. **Tool call state machine in ToolRouter** provides clear lifecycle

4. **PolicyEngine decides approval**, ToolRouter manages execution state

5. **AgentRegistry stores definitions**, orchestration creates instances

6. **HistoryManager handles conversation history** (renamed from ContextManager to avoid confusion)

---

## 10. Open Questions

1. **Event persistence**: Should we persist events to disk for session recovery?
2. **Multi-session**: Do we ever need multiple concurrent sessions?
3. **MCP integration**: Should we support Model Context Protocol for tool extensibility?
4. **Streaming**: Should LLM responses stream, or batch?
5. **Token budget management**: How aggressively should we truncate context?

---

## 11. References

- **Internal**: [Reference Analysis](./reference_analysis.md), [Migration Status](./migration_status.md)
- **External**: [Codex CLI](https://github.com/openai/codex), [Gemini CLI](https://github.com/google-gemini/gemini-cli)
