# Agent Core Overview

> Design principles, architecture, and package structure for the Android Agent.
> Last updated: 2026-02-09 (commit: e2e2f8cde08b4b5fb225d1f09a616b6630db1695)

## Design Principles

| Principle | Description |
|-----------|-------------|
| **Task-Based Model** | Session > Task > Turn hierarchy. Multi-round interaction via `Idle` state. |
| **Mode-Selectable Runtime** | Main runtime can be `BASIC` (standalone) or `PRO` (planner + executor). |
| **Streaming Responses** | Native OpenAI streaming with `MessageDelta` events for real-time UI updates. |
| **Thin Session Layer** | Session manages lifecycle only. Intelligence lives under `agent/`. |
| **Tools with Observation** | Tool execution captures post-action context for grounding. |
| **Context Hygiene** | Text-first history with fresh screen state injected each turn. |
| **Planning State Tools** | `write_todos` and `scratchpad` persist intent and facts across turns/agents. |
| **Cognition Layer** | Prompt/context/policy logic is isolated under `agent/cognition/`. |

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        Android Application                     │
├─────────────────────────────────────────────────────────────────┤
│  MainActivity ◄─────────────────────────────────────────────┐  │
│       │ runAgent(goal, apiKey)                              │  │
│       ▼                                                     │  │
│  AgentService (AccessibilityService entry point)            │  │
│       │ creates                                             │  │
│       ▼                                                     │  │
│  AgentSession ────────► SessionServices (Dependencies)      │  │
│       │ starts              │                               │  │
│       ▼                     │ provides                      │  │
│  Agent (turn loop) ◄────────┘                               │  │
│       │ executes one turn via                               │  │
│       ▼                                                     │  │
│  AgentTurnRunner ────► ToolRouter ────► AndroidPlatform    │  │
│                                                             │  │
│  Events (AgentEvent) ───────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Package Structure

```
com.moonkey.androidagent/
├── app/                          # Application entry points
│   ├── MainActivity.kt           # UI entry point
│   └── AgentService.kt           # AccessibilityService entry point
│
├── agent/                        # Core agent logic
│   ├── Agent.kt                  # Top-level task/turn loop controller
│   ├── AgentTurnRunner.kt        # Per-turn pipeline executor
│   ├── AgentRuntimeTypes.kt      # Stop reasons + turn outcomes + turn state
│   ├── AgentExecutionConfig.kt   # Runtime configuration
│   ├── AgentEventDispatcher.kt   # Event emission helpers
│   ├── ActionDescriptionFormatter.kt # Tool action descriptions
│   ├── Turn.kt                   # LLM call wrapper (Responses API)
│   ├── definition/               # AgentDef, Planner/Executor/Standalone defs
│   ├── cognition/
│   │   ├── prompt/               # PromptBuilder
│   │   ├── context/              # NavigationState + screen signatures
│   │   └── policy/               # TurnToolPolicy, loop detection, step budget
│   └── subagent/
│       └── SubAgentRunner.kt     # AgentDefinition/Registry + isolated runner
│
├── session/                      # Session management
│   ├── AgentSession.kt           # Lifecycle manager
│   ├── SessionAgentRunner.kt     # Starts selected main agent and wires delegation
│   ├── SessionServices.kt        # Dependency injection
│   ├── AgentSessionState.kt      # Shared state container
│   ├── TodoState.kt              # Todo list state
│   └── ScratchpadState.kt        # Key-value state
│
├── tool/                         # Tool system
│   └── (see infra/tools.md)
│
├── protocol/                     # Communication contracts
│   └── (see protocol/protocol.md)
│
├── platform/                     # Android platform abstraction
│   └── (see infra/platform.md)
│
├── perception/                   # Screen perception
│   └── (see infra/platform.md)
│
├── llm/                          # LLM integration
│   └── (see infra/llm.md)
│
├── trace/                        # Trace events + persisted artifacts
│   └── (see agent/turn_prompt_anatomy.md)
│
├── history/                      # Session history
│   └── (see app/history.md)
│
├── model/                        # Domain models
│   └── Models.kt                 # ScreenSnapshot, PerceptionElement
│
├── ui/                           # UI layer
│   └── (see ui/ docs)
│
└── util/
    └── StatusUtils.kt
```

---

## Related Docs

- [Loop Execution](loop.md) - ReAct loop, Turn, streaming
- [Multi-Agent](multiagent.md) - Sub-agent system and delegation
- [Planning State](planning.md) - Todos, scratchpad, context hygiene
- [Session Infrastructure](../infra/session.md) - AgentSession lifecycle
- [Protocol](../protocol/protocol.md) - Op/Event communication
