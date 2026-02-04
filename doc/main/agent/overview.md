# Agent Core Overview

> Design principles, architecture, and package structure for the Android Agent.
> Last updated: 2026-02-05

## Design Principles

| Principle | Description |
|-----------|-------------|
| **Task-Based Model** | Session > Task > Turn hierarchy. Multi-round interaction via `Idle` state. |
| **Planner-Executor Pattern** | Main agent plans and delegates atomic UI actions to executor sub-agents. |
| **Streaming Responses** | Native OpenAI streaming with `MessageDelta` events for real-time UI updates. |
| **Thin Session Layer** | Session manages lifecycle only. All intelligence lives in the Agent. |
| **Tools with Observation** | Every tool execution captures post-action screen state. |
| **Context Hygiene** | Text-only history (no screenshots/a11y trees). Latest screen injected per turn. |
| **Planning State Tools** | `write_todos` and `scratchpad` for stateful planning and cross-agent data handoff. |
| **Cognition Layer** | Prompt/context/policy/trace logic is isolated under `agent/cognition/` for faster iteration. |

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        Android Application                       │
├─────────────────────────────────────────────────────────────────┤
│  MainActivity ◄─────────────────────────────────────────────┐   │
│       │ runAgent(goal, apiKey)                              │   │
│       ▼                                                     │   │
│  AgentService (AccessibilityService entry point)            │   │
│       │ creates                                             │   │
│       ▼                                                     │   │
│  AgentSession ────────► SessionServices (Dependencies)      │   │
│       │ starts              │                               │   │
│       ▼                     │ provides                      │   │
│  AgentRuntime (ReAct Loop) ◄┘                               │   │
│       │ executes tools via                                  │   │
│       ▼                                                     │   │
│  ToolRouter ────────► AndroidPlatform (Accessibility)       │   │
│                                                             │   │
│  Events (AgentEvent) ───────────────────────────────────────┘   │
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
│   ├── AgentRuntime.kt           # ReAct loop executor
│   ├── AgentTurnRunner.kt        # Single turn execution
│   ├── AgentConfig.kt            # Agent configuration
│   ├── AgentEventDispatcher.kt   # AgentEvent emission helpers
│   ├── AgentObservation.kt       # Observation types + conversions
│   ├── AgentPromptBuilder.kt     # System prompt + context builder
│   ├── ActionDescriptionFormatter.kt # Tool action descriptions
│   ├── Turn.kt                   # LLM call wrapper (OpenAI Responses API)
│   ├── TurnInputBuilder.kt       # ResponseInputItem assembly
│   ├── cognition/                # Cognition layer (Lab)
│   │   ├── prompt/               # Prompt templates + assembler
│   │   ├── profile/              # Cognition profiles + registry
│   │   ├── context/              # Context packaging policy
│   │   ├── policy/               # Turn arbitration/completion policy
│   │   ├── trace/                # Redaction + input trace serializers
│   │   └── metrics/              # Run metrics model
│   └── subagent/                 # Sub-agent delegation
│       ├── AgentDefinition.kt    # Sub-agent definition
│       ├── AgentRegistry.kt      # Sub-agent discovery
│       ├── SubAgentRunner.kt     # Sub-agent execution with isolation
│       └── ExecutorAgent.kt      # UI grounding executor agent
│
├── session/                      # Session management
│   ├── AgentSession.kt           # Lifecycle manager
│   ├── SessionAgentRunner.kt     # Agent lifecycle runner
│   ├── SessionServices.kt        # Dependency injection
│   ├── AgentSessionState.kt      # Shared state container
│   ├── TodoState.kt              # Todo list state (planning)
│   └── ScratchpadState.kt        # Key-value memory state
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
