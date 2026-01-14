# Reference Analysis: Codex CLI and Gemini CLI

**Purpose**: This document captures the architectural analysis of two production-grade agent systems that informed our Agent Infrastructure design.

---

## 1. Top-Down Architecture Comparison

### Codex Architecture (Rust)

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
```

### Gemini Architecture (TypeScript)

```
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

---

## 2. Design Decisions Matrix

| Concern | Codex Approach | Gemini Approach | **Our Choice** | Rationale |
|---------|----------------|-----------------|----------------|-----------|
| **Communication** | Channel-based SQ/EQ | Callback/Event streaming | **SQ/EQ (Codex)** | Maps well to Kotlin Flow; cleaner for UI-Agent separation |
| **Service Location** | `SessionServices` container | `Config` mega-object | **SessionServices (Codex)** | More explicit DI; Config is too monolithic |
| **Tool State** | Implicit in execution | Explicit state machine | **State Machine (Gemini)** | Better debugging, clearer lifecycle |
| **Approvals** | `TurnState.pending_approvals` | `PolicyEngine` + callbacks | **PolicyEngine (Gemini)** | Cleaner separation of policy from state |
| **Conversation History** | `ContextManager` (history.rs) | History in `GeminiChat` | **HistoryManager (Codex-style)** | Explicit management with truncation |
| **Turn Scope** | `TurnContext` + `ActiveTurn` | `Turn` class | **TurnContext (Codex)** | Better separation of context vs execution |
| **Registries** | N/A (simpler model) | `ToolRegistry` + `AgentRegistry` | **Registries (Gemini)** | Better for extensibility and testing |

---

## 3. Important Naming Clarification

**CRITICAL**: Gemini and Codex use "ContextManager" for DIFFERENT things!

| System | Component | What It Manages |
|--------|-----------|-----------------|
| **Codex** | `context_manager/history.rs` | **Conversation history** - truncation, normalization, token tracking |
| **Gemini** | `services/contextManager.ts` | **Memory files** (GEMINI.md) - discovery and loading of instructional context |
| **Gemini** | `GeminiChat.history` | **Conversation history** - stored directly in chat object |
| **Gemini** | `ChatCompressionService` | **History compression** - when context window fills |

**Our naming convention**:
- `HistoryManager` - Conversation history (Codex's ContextManager)
- `MemoryDiscovery` - Instructional files (Gemini's ContextManager) - *if needed later*

---

## 4. Context Layers in Agent Systems

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

### Single-Agent vs Multi-Agent Context

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

---

## 5. Key Files Referenced

### Codex CLI (Rust)

| File | Purpose |
|------|---------|
| `codex-rs/core/src/state/session.rs` | Session state management |
| `codex-rs/core/src/state/turn.rs` | Turn state and active turn tracking |
| `codex-rs/core/src/state/service.rs` | SessionServices container |
| `codex-rs/core/src/context_manager/history.rs` | History management with truncation |
| `codex-rs/core/src/agent/control.rs` | Agent control with cancellation |

### Gemini CLI (TypeScript)

| File | Purpose |
|------|---------|
| `packages/core/src/core/coreToolScheduler.ts` | Tool call state machine |
| `packages/core/src/scheduler/types.ts` | Tool call state types |
| `packages/core/src/config/config.ts` | Config as service locator (1800+ lines!) |
| `packages/core/src/agents/registry.ts` | AgentRegistry for agent definitions |
| `packages/core/src/tools/tool-registry.ts` | ToolRegistry for tool management |
| `packages/core/src/confirmation-bus/message-bus.ts` | Pub/sub MessageBus |

---

## 6. Deferred Patterns

These patterns exist in the reference implementations but were **not included** in our initial design:

### 6.1 MessageBus (Gemini) - Deferred

Gemini uses a pub/sub MessageBus for decoupled inter-component communication.

**Why deferred**: Our `Flow<AgentEvent>` pattern provides sufficient decoupling. MessageBus adds complexity for multi-subscriber scenarios we don't yet have.

**Consider adding if we need**:
- Multiple independent UI components reacting to same events
- Plugin/extension systems with loose coupling
- Analytics that shouldn't be coupled to main event flow

### 6.2 Hook System (Gemini) - Deferred

Gemini has a `HookSystem` for pre/post tool execution hooks.

**Why deferred**: We don't have extension/plugin requirements yet.

**Consider adding if we need**:
- Pre-tool validation beyond PolicyEngine
- Post-tool logging/analytics
- Third-party tool integrations

### 6.3 MCP Integration - Deferred

Model Context Protocol for external tool servers.

**Why deferred**: Mobile-Agent-v3 tools are all local (click, type, scroll).

**Consider adding if we need**:
- External AI services
- Desktop integration
- Cloud tool execution

---

## 7. External Links

- [Codex CLI Source](https://github.com/openai/codex)
- [Gemini CLI Source](https://github.com/google-gemini/gemini-cli)
- [Mobile-Agent-v3 Paper](https://arxiv.org/abs/...)
- [Clean Architecture - Uncle Bob](https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html)

