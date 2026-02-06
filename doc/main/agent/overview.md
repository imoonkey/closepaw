# Agent Core Overview

> Design principles, architecture, and package structure for the Android Agent.
> Last updated: 2026-02-06

## Design Principles

| Principle | Description |
|-----------|-------------|
| **Task-Based Model** | Session > Task > Turn hierarchy. Multi-round interaction via `Idle` state. |
| **Planner-Executor Pattern** | Main planner agent delegates atomic UI actions to executor sub-agents. |
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
│   ├── AgentConfig.kt            # Runtime configuration
│   ├── AgentEventDispatcher.kt   # Event emission helpers
│   ├── ActionDescriptionFormatter.kt # Tool action descriptions
│   ├── Turn.kt                   # LLM call wrapper (Responses API)
│   ├── cognition/
│   │   ├── prompt/               # Planner/Executor templates + PromptUtils
│   │   ├── context/              # NavigationState + screen signatures
│   │   └── policy/               # TurnToolPolicy, loop detection, step budget
│   └── subagent/
│       └── SubAgentRunner.kt     # AgentDefinition/Registry + isolated runner
│
├── session/                      # Session management
│   ├── AgentSession.kt           # Lifecycle manager
│   ├── SessionAgentRunner.kt     # Starts planner and wires delegation tool
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
- [Multi-Agent](multiagent.md) - Sub-agent system, delegation
- [Planning State](planning.md) - Todos, scratchpad, context hygiene
- [Session Infrastructure](../infra/session.md) - AgentSession lifecycle
- [Protocol](../protocol/protocol.md) - Op/Event communication
