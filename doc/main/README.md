# Android Agent Documentation

> Entry point and navigation guide for the codebase.
> Last updated: 2026-03-11 (commit: 2a35d90)

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
│   ├── multiagent.md  # Multi-agent system, Planner-Executor, delegation
│   ├── planning.md    # TodoState, ScratchpadState, context hygiene
│   └── memory.md      # Cross-session memory (MemoryStore, recall, auto-retain)
│
├── infra/             # Agent infrastructure
│   ├── session.md     # AgentSession, SessionServices, lifecycle
│   ├── tools.md       # Tool system, ToolRouter, ToolRegistry, PolicyEngine
│   ├── platform.md    # AndroidPlatform, action dispatch, capture wiring, VirtualDisplay
│   ├── perception.md  # Perceptor, ScreenSnapshot, prompt shaping, text semantics
│   └── llm.md         # LLM clients, ModelCatalog, retry infrastructure
│
├── protocol/          # Communication contracts
│   └── protocol.md    # Op/AgentEvent, state machine, errors, SessionConfig
│
├── app/               # Application layer (non-agentic)
│   ├── history.md     # Session history persistence, compression pipeline, runtime token management
│   └── settings.md    # User settings, preferences, SessionConfig compilation
│
└── ui/                # User interface
    ├── style.md       # Design system: colors, typography, shapes
    ├── tech_design.md # Technical implementation: ViewModel, event reducers
    ├── user_interaction.md # Pages, user flows, event→UI mapping
    ├── overlay.md     # Smart Capsule, Edge Glow, Island, Visualizer
    ├── capsule/       # Capsule state machine + user flows
    │   ├── state_machine.md
    │   └── user_flows.md
    └── session/       # Session lifecycle state machine + user flows
        ├── state_machine.md
        └── user_flows.md

├── eval/              # Evaluation & benchmarking
│   └── eval.md        # Eval runner architecture, AndroidWorld bridge
```

---

## Code Structure

```
app/src/main/kotlin/com/moonkey/androidagent/
│
├── app/                          # Application entry points
│   ├── MainActivity.kt           # UI entry point
│   ├── MainActivityContent.kt    # Compose content composition
│   ├── AgentService.kt           # AccessibilityService entry point
│   ├── AgentServiceEventHandler.kt # AgentEvent → overlay/UI dispatch
│   ├── ServiceOverlayController.kt # Mode-aware overlay branching
│   ├── AppSettingsStore.kt        # SharedPreferences persistence
│   └── ...                       # Intent helpers, model validation
│
├── agent/                        # Core agent logic
│   ├── Agent.kt                  # Top-level turn loop controller
│   ├── AgentTurnRunner.kt        # Single turn execution (planning + execution phases)
│   ├── AgentRuntimeTypes.kt      # AgentStopReason, TurnOutcome, TurnRunnerState
│   ├── AgentExecutionConfig.kt   # Agent runtime configuration
│   ├── AgentEventDispatcher.kt   # AgentEvent emission helpers
│   ├── ActionSignature.kt        # Stable action signatures for loop detection/blocking
│   ├── ActionDescriptionFormatter.kt # Tool action descriptions
│   ├── Turn.kt                   # LLM call wrapper (OpenAI Responses API)
│   ├── definition/               # AgentDef: Planner/Executor/Standalone
│   ├── cognition/                # PromptBuilder, policies, NavigationState
│   └── subagent/
│       └── SubAgentRunner.kt     # AgentDefRegistry + runner + executor preset
│
├── session/                      # Session management
│   ├── AgentSession.kt           # Lifecycle manager (bootstrap → run → Hot Idle → teardown)
│   ├── SessionCoordinator.kt     # Event-driven input queue, cold-idle auto-reload support
│   ├── SessionAgentRunner.kt     # Mode-based agent runner + delegate tool wiring
│   ├── SessionServices.kt        # Dependency injection container
│   ├── SessionCheckpointCoordinator.kt # Checkpoint persistence for process-death recovery
│   ├── AgentSessionState.kt      # Shared state container
│   ├── TodoState.kt              # Planning state (with mutation listener)
│   └── ScratchpadState.kt        # Key-value memory state (with mutation listener)
│
├── tool/                         # Tool system
│   ├── ToolSpec.kt               # Tool interface
│   ├── ToolRegistry.kt           # Discovery/registration
│   ├── ToolRouter.kt             # Execution state machine
│   ├── PolicyEngine.kt           # Approval logic
│   ├── action/                   # Executor layer (mobile_action)
│   │   ├── PointActionExecutorCore.kt # Shared click/long-press fallback chain
│   │   ├── ClickExecutor.kt      # Click thin wrapper (channel mapping)
│   │   ├── LongPressExecutor.kt  # Long press thin wrapper (channel mapping)
│   │   ├── TypeExecutor.kt       # Type with focus management
│   │   ├── ScrollExecutor.kt     # Content-direction scroll cascade
│   │   ├── SwipeExecutor.kt      # Precision coordinate gestures
│   │   ├── TargetResolver.kt     # Target → coordinates
│   │   ├── UiChangeDetector.kt   # Snapshot fingerprinting (diagnostics utility)
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
│       ├── DelegateTaskTool.kt
│       ├── RememberExperienceTool.kt
│       └── ShellTool.kt
│
├── memory/                          # Cross-session persistent memory
│   ├── MemoryStore.kt               # File I/O, entry caps, thread safety
│   └── MemoryRecaller.kt            # Elastic-budget recall per turn
│
├── trace/                        # Structured trace events and artifacts
│   ├── AgentTrace.kt             # Runtime-to-trace bridge
│   ├── TraceRecorder.kt          # Recorder interface
│   └── TraceRecorderFactory.kt   # Recorder creation
│
├── protocol/                     # Communication contracts (26 files)
│   ├── Op.kt                     # Operations (UI → Agent, 8 ops)
│   ├── AgentEvent.kt             # Events (Agent → UI, base sealed interface)
│   ├── AgentEventDomains.kt      # 12 domain marker interfaces
│   ├── SessionState.kt           # State machine (5 states: Created/Running/Idle/Paused/Shutdown)
│   ├── SessionConfig.kt          # Session configuration (12 fields)
│   ├── AgentError.kt             # Error types (11 variants)
│   ├── ApprovalTypes.kt          # Approval decision types
│   ├── CompletionReason.kt       # Task completion reasons
│   ├── TurnPhase.kt              # PLANNING / EXECUTION phase
│   └── ...                       # Domain event files, models, utilities
│
├── platform/                     # Android platform (atomic operations)
│   ├── AndroidPlatform.kt        # Interface (start/stop/capture/perform)
│   ├── AccessibilityPlatform.kt  # Accessibility API implementation
│   ├── AccessibilityNodeFinder.kt # Node search helpers
│   ├── NodeActionPerformer.kt    # Shared node actions (both platforms)
│   ├── UIAction.kt               # Sealed interface (9 action types)
│   ├── ActionResult.kt           # Success/Failure/Cancelled
│   ├── PlatformFactory.kt        # Platform selection by mode
│   ├── AppManager.kt             # App launch/package resolution
│   ├── BitmapUtils.kt            # Screenshot processing
│   └── virtualdisplay/           # Virtual display (Shizuku-based)
│       ├── VirtualDisplayPlatform.kt
│       ├── VirtualDisplaySurfaceController.kt
│       ├── VirtualDisplayCaptureCoordinator.kt
│       ├── VirtualDisplayInputInjector.kt
│       ├── VirtualDisplayAppController.kt
│       ├── ShizukuClient.kt      # Shizuku connection management
│       └── ...                   # Config, transports, shell, viewer touch
│
├── perception/                   # Screen perception
│   ├── PerceptionConfig.kt       # Session-level modality selection
│   ├── Perceptor.kt              # A11y tree → ScreenSnapshot (multi-root support)
│   ├── PerceptorFilterConfig.kt  # Element filtering config (maxElements, thresholds)
│   ├── PerceptorDiagnostics.kt   # Capture diagnostics counters
│   └── ScreenSummary.kt          # Compact observation summary
│
├── llm/                          # LLM integration
│   ├── LLMClient.kt              # Abstract base (stream events, result types)
│   ├── LLMClientFactory.kt       # Catalog-driven creation with caching
│   ├── ModelCatalog.kt            # ModelEntry, LLMProvider, ApiType
│   ├── OpenAIResponseClient.kt   # OpenAI Responses API client
│   ├── ChatCompletionClient.kt   # Chat Completions API client
│   ├── ChatCompletionInterop.kt  # Responses ↔ Chat Completions type bridge
│   ├── LFMLLMClient.kt           # Local LFM client (Leap SDK)
│   ├── CloudLlmRetry.kt          # Non-streaming retry
│   ├── CloudStreamRetryRunner.kt # Streaming retry
│   ├── OpenAIErrorClassifier.kt  # Exception → retryable classification
│   └── ...                       # Logger, local config, interop helpers
│
├── history/                      # Session history
│   ├── HistoryManager.kt         # Runtime token budget (100K tokens)
│   ├── SessionHistoryManager.kt  # High-level persistence API
│   ├── SessionRecordingService.kt # Real-time recording (500ms debounce)
│   ├── AgentMessageBuffer.kt     # Turn message accumulation
│   ├── SessionRecordMessageMerger.kt # Record merging
│   ├── model/                    # Data models
│   │   ├── SessionRecord.kt
│   │   ├── MessageRecord.kt
│   │   ├── SessionInfo.kt
│   │   └── ...
│   └── storage/
│       └── SessionStorage.kt     # File I/O
│
├── ui/                           # UI layer
│   ├── theme/                    # Design system (Color, Shape, Theme, Type)
│   ├── chat/                     # ChatScreen, ChatViewModel, ChatEventReducer
│   ├── capsule/                  # SmartCapsuleCompose, SmartCapsuleSurface
│   ├── common/                   # Shared composables
│   ├── navigation/               # Navigation drawer
│   ├── overlay/                  # System overlays (capsule, glow, island, visualizer)
│   ├── settings/                 # Settings bottom sheet
│   ├── session/                  # Session history UI
│   └── viewer/                   # VirtualDisplayViewerActivity
│
├── model/                        # Domain models
│   └── Models.kt                 # ScreenSnapshot, etc.
│
└── util/                         # Shared utilities
```

---

## Reading Order

### New to the codebase?

1. [agent/overview.md](agent/overview.md) — Architecture and design principles
2. [agent/loop.md](agent/loop.md) — How the agent executes (ReAct loop)
3. [agent/turn_prompt_anatomy.md](agent/turn_prompt_anatomy.md) — What is sent to the LLM each turn
4. [protocol/protocol.md](protocol/protocol.md) — How UI and agent communicate

### Working on specific areas?

| Area | Start With |
|------|------------|
| Agent behavior | [loop.md](agent/loop.md), [turn_prompt_anatomy.md](agent/turn_prompt_anatomy.md), [planning.md](agent/planning.md) |
| Memory system | [memory.md](agent/memory.md) |
| Multi-agent | [multiagent.md](agent/multiagent.md) |
| Adding tools | [tools.md](infra/tools.md) |
| Session lifecycle | [session.md](infra/session.md), [session/](ui/session/state_machine.md) |
| Screen perception | [perception.md](infra/perception.md), [platform.md](infra/platform.md) |
| LLM integration | [llm.md](infra/llm.md) |
| UI changes | [tech_design.md](ui/tech_design.md) |
| Design system | [style.md](ui/style.md) |
| Overlays | [overlay.md](ui/overlay.md) |
| History persistence | [history.md](app/history.md) |
| Settings | [settings.md](app/settings.md) |
| Evaluation | [eval.md](eval/eval.md) |

---

## Key Concepts

| Concept | Description | Doc |
|---------|-------------|-----|
| **ReAct Loop** | Perceive → Think → Act → Observe | [loop.md](agent/loop.md) |
| **Task** | Work unit from user input to completion | [protocol.md](protocol/protocol.md) |
| **Turn** | One LLM call + tool execution cycle | [loop.md](agent/loop.md) |
| **AgentDef** | Agent definition (Planner/Executor/Standalone) | [multiagent.md](agent/multiagent.md) |
| **Op** | User intent (UI → Agent) | [protocol.md](protocol/protocol.md) |
| **AgentEvent** | State notification (Agent → UI) | [protocol.md](protocol/protocol.md) |
| **SessionConfig** | Compiled settings snapshot for a session | [settings.md](app/settings.md) |
| **Hot Idle** | Session stays alive between tasks for follow-up | [session/](ui/session/state_machine.md) |
| **CapsuleMode** | Smart Capsule state (Running, Takeover, etc.) | [overlay.md](ui/overlay.md) |
| **Context Hygiene** | Token-efficient history management (compression pipeline) | [planning.md](agent/planning.md), [history.md](app/history.md) |
| **Cross-Session Memory** | Persistent app-specific learnings recalled per turn | [memory.md](agent/memory.md) |

---

## Development Workflow

→ See: [doc/dev/development.md](../dev/development.md)
