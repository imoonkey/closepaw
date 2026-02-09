# Android Agent Documentation

> Entry point and navigation guide for the codebase.
> Last updated: 2026-02-08 (commit: a475ef9aacefa7da5ac84bfb0a09a48ce29776d9)

## Quick Start

| Task | Command |
|------|---------|
| Build | `./gradlew assembleDebug` |
| Test | `./gradlew test` |
| Lint | `./gradlew lint` |
| Full Check | `./gradlew clean assembleDebug lint test` |

---

## Documentation Map

```
doc/main/
│
├── README.md          ← You are here (navigation guide)
│
├── agent/             # Core agent intelligence
│   ├── overview.md    # Design principles, architecture, package structure
│   ├── loop.md        # ReAct loop, Turn, streaming execution
│   ├── turn_prompt_anatomy.md # Per-turn OpenAI prompt/input/tools breakdown
│   ├── multiagent.md  # Sub-agent system, delegation, registry
│   └── planning.md    # TodoState, ScratchpadState, context hygiene
│
├── infra/             # Agent infrastructure
│   ├── session.md     # AgentSession, SessionServices, lifecycle
│   ├── tools.md       # Tool system, ToolRouter, ToolRegistry
│   ├── platform.md    # AndroidPlatform, Perceptor, perception
│   └── llm.md         # LLM clients, backends, API configuration
│
├── protocol/          # Communication contracts
│   └── protocol.md    # Op/Event, state machine, errors, config
│
├── app/               # Application layer (non-agentic)
│   ├── history.md     # Session history persistence
│   └── settings.md    # User settings, preferences persistence
│
└── ui/                # User interface
    ├── style.md       # Design system, colors, typography
    ├── tech_design.md # Technical implementation, components
    ├── user_interaction.md # Pages, user flows
    └── overlay.md     # Smart Capsule, Edge Glow, Visualizer
```

---

## Code Structure

```
app/src/main/kotlin/com/moonkey/androidagent/
│
├── app/                          # Application entry points
│   ├── MainActivity.kt           # UI entry point
│   └── AgentService.kt           # AccessibilityService entry point
│
├── agent/                        # Core agent logic
│   ├── Agent.kt                  # Top-level turn loop controller
│   ├── AgentTurnRunner.kt        # Single turn execution
│   ├── AgentRuntimeTypes.kt      # AgentStopReason, TurnOutcome, TurnRunnerState
│   ├── AgentExecutionConfig.kt   # Agent runtime configuration
│   ├── AgentEventDispatcher.kt   # AgentEvent emission helpers
│   ├── ActionDescriptionFormatter.kt # Tool action descriptions
│   ├── Turn.kt                   # LLM call wrapper (OpenAI Responses API)
│   ├── definition/               # Planner/Executor/Standalone definitions
│   ├── cognition/                # Prompt/context/policy helpers
│   └── subagent/
│       └── SubAgentRunner.kt     # AgentDefinition/Registry + runner + executor preset
│
├── session/                      # Session management
│   ├── AgentSession.kt           # Lifecycle manager
│   ├── SessionAgentRunner.kt     # Mode-based main-agent runner + delegate tool wiring
│   ├── SessionServices.kt        # Dependency injection
│   ├── AgentSessionState.kt      # Shared state container
│   ├── TodoState.kt              # Planning state
│   └── ScratchpadState.kt        # Key-value memory state
│
├── tool/                         # Tool system
│   ├── ToolSpec.kt               # Tool interface
│   ├── ToolRegistry.kt           # Discovery/registration
│   ├── ToolRouter.kt             # Execution state machine
│   ├── PolicyEngine.kt           # Approval logic
│   ├── action/                   # Executor layer (mobile_action)
│   │   ├── ClickExecutor.kt      # Click fallback chain
│   │   ├── LongPressExecutor.kt  # Long press fallback chain
│   │   ├── TypeExecutor.kt       # Type with focus management
│   │   ├── SwipeExecutor.kt      # Swipe direction/distance
│   │   ├── TargetResolver.kt     # Target → coordinates
│   │   ├── UiChangeDetector.kt   # Snapshot fingerprinting
│   │   └── ObservationBuilder.kt # Post-action observation
│   ├── handlers/
│   │   └── UIActionInvocation.kt # For SystemButton/Wait tools
│   └── impl/                     # Tool implementations
│       ├── MobileActionTool.kt
│       ├── MobileActionInvocation.kt
│       ├── OpenAppTool.kt
│       ├── SystemButtonTool.kt
│       ├── WaitTool.kt
│       ├── CompleteTaskTool.kt
│       ├── WriteTodosTool.kt
│       ├── ScratchpadTool.kt
│       └── DelegateTaskTool.kt
│
├── trace/                        # Structured trace events and artifacts
│   ├── AgentTrace.kt             # Runtime-to-trace bridge
│   ├── TraceRecorder.kt          # Recorder interface
│   └── TraceRecorderFactory.kt   # Recorder creation
│
├── protocol/                     # Communication contracts
│   ├── Op.kt                     # Operations (UI → Agent)
│   ├── AgentEvent.kt             # Events (Agent → UI)
│   ├── SessionState.kt           # State machine
│   └── AgentError.kt             # Error types
│
├── platform/                     # Android platform (atomic operations)
│   ├── AndroidPlatform.kt        # Interface
│   ├── AccessibilityPlatform.kt  # Implementation
│   ├── AccessibilityNodeFinder.kt # Node search helpers
│   ├── UIAction.kt               # Atomic action types
│   └── ActionResult.kt           # Result types
│
├── perception/                   # Screen perception
│   ├── Perceptor.kt              # A11y tree → ScreenSnapshot
│   └── ScreenSummary.kt          # Compact observation summary
│
├── llm/                          # LLM integration
│   ├── LLMClient.kt              # Unified interface
│   ├── OpenAILLMClient.kt        # OpenAI client
│   └── LFMLLMClient.kt           # Local LFM client
│
├── history/                      # Session history
│   ├── SessionHistoryManager.kt  # High-level API
│   ├── SessionRecordingService.kt # Real-time recording
│   └── storage/
│       └── SessionStorage.kt     # File I/O
│
├── ui/                           # UI layer
│   ├── theme/                    # Design system
│   ├── chat/                     # Chat components
│   ├── navigation/               # Navigation drawer
│   ├── overlay/                  # Overlays (capsule, glow)
│   ├── settings/                 # Settings sheet
│   └── session/                  # Session history UI
│
└── model/                        # Domain models
    └── Models.kt                 # ScreenSnapshot, etc.
```

---

## Reading Order

### New to the codebase?

1. [agent/overview.md](agent/overview.md) - Architecture and design principles
2. [agent/loop.md](agent/loop.md) - How the agent executes
3. [agent/turn_prompt_anatomy.md](agent/turn_prompt_anatomy.md) - What is sent to OpenAI each turn
4. [protocol/protocol.md](protocol/protocol.md) - How UI and agent communicate

### Working on specific areas?

| Area | Start With |
|------|------------|
| Agent behavior | [agent/loop.md](agent/loop.md), [agent/turn_prompt_anatomy.md](agent/turn_prompt_anatomy.md), [agent/planning.md](agent/planning.md) |
| Multi-agent | [agent/multiagent.md](agent/multiagent.md) |
| Adding tools | [infra/tools.md](infra/tools.md) |
| Session lifecycle | [infra/session.md](infra/session.md) |
| Screen perception | [infra/platform.md](infra/platform.md) |
| LLM integration | [infra/llm.md](infra/llm.md) |
| UI changes | [ui/tech_design.md](ui/tech_design.md) |
| Design system | [ui/style.md](ui/style.md) |
| Overlays | [ui/overlay.md](ui/overlay.md) |
| History persistence | [app/history.md](app/history.md) |
| Settings | [app/settings.md](app/settings.md) |

---

## Key Concepts

| Concept | Description | Doc |
|---------|-------------|-----|
| **ReAct Loop** | Perceive → Think → Act → Observe | [loop.md](agent/loop.md) |
| **Task** | Work from user input to completion | [protocol.md](protocol/protocol.md) |
| **Turn** | One LLM call + tool execution | [loop.md](agent/loop.md) |
| **Sub-Agent** | Delegated executor for atomic actions | [multiagent.md](agent/multiagent.md) |
| **Op** | User intent (UI → Agent) | [protocol.md](protocol/protocol.md) |
| **AgentEvent** | State notification (Agent → UI) | [protocol.md](protocol/protocol.md) |
| **Context Hygiene** | Token-efficient history management | [planning.md](agent/planning.md) |

---

## Development Workflow

→ See: [doc/dev/development.md](../dev/development.md)
