# Agent Core Overview

> Design principles, architecture, and package structure for the Android Agent.
> Last updated: 2026-03-05 (commit: 0b5b379)

## Design Principles

| Principle | Description |
|-----------|-------------|
| **Task-Based Model** | Session > Task > Turn hierarchy. Multi-round interaction via `Idle` state between tasks. |
| **Mode-Selectable Runtime** | Main runtime can be `BASIC` (standalone) or `PRO` (planner + executor). |
| **Streaming Responses** | Native streaming with `LLMStreamEvent` for real-time UI updates. |
| **Thin Session Layer** | Session manages lifecycle only. Intelligence lives under `agent/`. |
| **Tools with Observation** | Tool execution captures post-action screen context for grounding. |
| **Context Hygiene** | Text-first history with fresh screen state injected each turn. Older screens compressed. |
| **Planning State Tools** | `write_todos` and `scratchpad` persist intent and facts across turns/agents. |
| **Cognition Layer** | Prompt/context/policy logic is isolated under `agent/cognition/`. |
| **Catalog-Driven Models** | `ModelCatalog` + `LLMClientFactory` resolve models at runtime from `llm_models.json`. |
| **Error Recovery** | `TurnErrorClassifier` distinguishes recoverable (DNS, rate limit) from fatal errors. |

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        Android Application                      │
├─────────────────────────────────────────────────────────────────┤
│  MainActivity ◄──────────────────────────────────────────────┐  │
│       │ runAgent(goal, apiKeys)                              │  │
│       ▼                                                      │  │
│  AgentService (AccessibilityService entry point)             │  │
│       │ creates                                              │  │
│       ▼                                                      │  │
│  AgentSession ────────► SessionServices (Dependencies)       │  │
│       │ starts              │                                │  │
│       ▼                     │ provides                       │  │
│  SessionAgentRunner         │                                │  │
│       │ creates Agent       │                                │  │
│       ▼                     │                                │  │
│  Agent (turn loop) ◄────────┘                                │  │
│       │ executes one turn via                                │  │
│       ▼                                                      │  │
│  AgentTurnRunner ──► TurnPlanningPhaseRunner (LLM call)      │  │
│                  └─► TurnExecutionPhaseRunner (Tool exec)     │  │
│                                                              │  │
│  Events (AgentEvent) ────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Package Structure

```
com.moonkey.androidagent/
├── app/                                # Application entry points
│   ├── MainActivity.kt                 # UI entry, session management
│   ├── MainActivityContent.kt          # Root composable
│   ├── MainActivityIntentPayload.kt    # Intent parameter model
│   ├── MainActivityIntentApplier.kt    # Intent → settings mapping
│   ├── MainActivityModelValidation.kt  # API key validation
│   ├── MainActivityUiHelpers.kt        # UI helper extensions
│   ├── AgentService.kt                 # AccessibilityService entry point
│   ├── AgentServiceEventHandler.kt     # Event → UI/recording dispatch
│   ├── AgentServiceReceiverHelpers.kt  # Debug broadcast receiver
│   ├── AgentServiceViewerBridge.kt     # Service ↔ VD viewer bridge
│   ├── ServiceOverlayController.kt     # Overlay window visibility
│   ├── OverlayLocationPolicy.kt        # User location → visibility rules
│   ├── AppSettingsState.kt             # Mutable settings state holder
│   └── AppSettingsStore.kt             # SharedPreferences persistence
│
├── agent/                              # Core agent logic
│   ├── Agent.kt                        # Top-level task/turn loop controller
│   ├── AgentTurnRunner.kt              # Per-turn pipeline orchestrator
│   ├── TurnPlanningPhaseRunner.kt      # LLM call + tool arbitration
│   ├── TurnExecutionPhaseRunner.kt     # Tool execution + observation
│   ├── TurnErrorClassifier.kt          # Error classification (recoverable/fatal)
│   ├── AgentModelResolver.kt           # Catalog-driven model resolution
│   ├── AgentRuntimeTypes.kt            # Stop reasons, turn outcomes, turn state
│   ├── AgentExecutionConfig.kt         # Runtime config + AgentExecutionRole
│   ├── AgentEventDispatcher.kt         # Event emission helpers
│   ├── ActionSignature.kt             # Stable action signature for loop detection/blocking
│   ├── ActionDescriptionFormatter.kt   # Human-readable tool descriptions
│   ├── Turn.kt                         # LLM call wrapper (streaming + sync)
│   ├── definition/                     # Agent role definitions
│   │   ├── AgentDef.kt                 # Abstract base (id, role, prompt, tools)
│   │   ├── AgentDefRegistry.kt         # mainFor(mode) + executor() factory
│   │   ├── PlannerAgentDef.kt          # Planner: delegation workflow
│   │   ├── ExecutorAgentDef.kt         # Executor: atomic UI actions
│   │   └── StandaloneAgentDef.kt       # Standalone: direct end-to-end
│   ├── cognition/
│   │   ├── prompt/
│   │   │   └── PromptBuilder.kt        # History → Memory → Observation assembly
│   │   ├── context/
│   │   │   └── NavigationState.kt      # Screen signatures + loop detection data
│   │   └── policy/
│   │       ├── TurnToolPolicy.kt       # Tool arbitration + completion decision
│   │       ├── LoopDetectionPolicy.kt  # Repeated screen/action warnings
│   │       └── ExecutorStepPolicy.kt   # Sub-agent step budget guard
│   └── subagent/
│       └── SubAgentRunner.kt           # AgentDefinition, AgentRegistry, IsolatedSubAgentRunner
│
├── session/                            # Session management
│   ├── AgentSession.kt                 # Lifecycle manager (Op → state transitions)
│   ├── SessionAgentRunner.kt           # Agent lifecycle orchestration
│   ├── SessionServices.kt             # Dependency injection container
│   ├── SessionLlmBootstrapper.kt       # LLM client + catalog creation
│   ├── SessionToolingBootstrapper.kt   # Tools + policy + state creation
│   ├── SessionHistoryBootstrapper.kt   # History + recording creation
│   ├── SessionServicesSummaryFormatter.kt # Debug summary
│   ├── AgentSessionState.kt            # Shared state (todos + scratchpad)
│   ├── TodoState.kt                    # Thread-safe todo list
│   ├── ScratchpadState.kt              # Thread-safe key-value store
│   └── UserResponseChannel.kt          # ask_user suspension bridge
│
├── tool/                               # Tool system
│   └── (→ See: infra/tools.md)
│
├── protocol/                           # Communication contracts
│   └── (→ See: protocol/protocol.md)
│
├── platform/                           # Android platform abstraction
│   └── (→ See: infra/platform.md)
│
├── perception/                         # Screen perception
│   └── (→ See: infra/platform.md)
│
├── llm/                                # LLM integration
│   └── (→ See: infra/llm.md)
│
├── trace/                              # Debug trace events + artifacts
│   └── (→ See: agent/turn_prompt_anatomy.md)
│
├── history/                            # Session history persistence
│   └── (→ See: app/history.md)
│
├── model/                              # Domain models
│   └── Models.kt                       # ScreenSnapshot, PerceptionElement, Bounds, Point
│
├── ui/                                 # UI layer
│   └── (→ See: ui/ docs)
│
└── util/
    └── StatusUtils.kt                  # Status type classification
```

---

## Related Docs

- [Loop Execution](loop.md) - ReAct loop, Turn, streaming
- [Multi-Agent](multiagent.md) - Sub-agent system and delegation
- [Planning State](planning.md) - Todos, scratchpad, context hygiene
- [Turn Prompt Anatomy](turn_prompt_anatomy.md) - Prompt structure and trace
- [Session Infrastructure](../infra/session.md) - AgentSession lifecycle
- [Protocol](../protocol/protocol.md) - Op/Event communication
