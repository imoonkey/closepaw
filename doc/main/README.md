# ClosePaw Documentation

> Entry point and navigation guide for the codebase.
> Last updated: 2026-05-04 (browser-cdp-runtime + browser-phase5/6 milestones)

## Quick Start

| Task | Command |
|------|---------|
| Build (default) | `./gradlew assembleDebug` |
| Build release (R8 + resource shrink, signed APK) | `./gradlew assembleRelease` |
| Test (JVM unit) | `./gradlew test` |
| Test (Compose UI on connected device/emulator) | `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=ai.closepaw.qa` |
| Lint | `./gradlew lint` |
| Full Check | `./gradlew clean assembleDebug lint test` |

Day-to-day development uses the debug APK. Release build is only for shipping, R8 keep-rule validation, and APK-size measurement — see `doc/dev/development.md` ("Debug vs Release APK") for the full comparison.

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
│   ├── memory.md      # Cross-session memory (MemoryStore, recall, auto-retain)
│   └── agent_skills.md # Agentskills.io system: catalog, activate_skill, App vs Agent skills
│
├── infra/             # Agent infrastructure
│   ├── session.md     # AgentSession, SessionServices, lifecycle
│   ├── tools.md       # Tool system, ToolRouter, ToolRegistry, PolicyEngine
│   ├── browser.md     # browser_script runtime, session ownership, policy
│   ├── platform.md    # AndroidPlatform, AccessibilityPlatform, action dispatch
│   ├── virtual_display.md # VirtualDisplayPlatform, ShizukuClient, hybrid surface
│   ├── perception.md  # Perceptor, ScreenSnapshot, prompt shaping, text semantics
│   └── llm.md         # LLM clients, ModelCatalog, retry infrastructure
│
├── protocol/          # Communication contracts
│   ├── overview.md    # Op, state machine, errors, utilities
│   ├── events.md      # AgentEvent domain hierarchy, key events
│   └── config.md      # SessionConfig, PlatformMode, AgentMode, LLM config
│
├── app/               # Application layer (non-agentic)
│   ├── history/       # Session history persistence, compression pipeline
│   │   ├── overview.md    # Architecture, recording flow, file structure
│   │   ├── persistence.md # SessionHistoryManager, SessionRecordingService, SessionStorage
│   │   ├── runtime.md     # HistoryManager, compression pipeline, token budgeting
│   │   └── models.md      # SessionRecord, MessageRecord, ScreenStateRecord
│   └── settings.md    # User settings, preferences, SessionConfig compilation
│
├── ui/                # User interface
│   ├── style.md       # Design system: colors, typography, shapes
│   ├── tech_design.md # Technical implementation: ViewModel, event reducers
│   ├── user_interaction.md # Pages, user flows, event→UI mapping
│   ├── overlay.md     # Edge Glow, Island, Visualizer, overlay infrastructure
│   ├── capsule/       # Smart Capsule
│   │   ├── architecture.md # Modes, rendering, state transitions, callbacks
│   │   ├── state_machine.md
│   │   └── user_flows.md
│   └── session/       # Session lifecycle state machine + user flows
│       ├── state_machine.md
│       └── user_flows.md
│
├── state_machines/    # Authoritative FSM reference (mermaid + invariants)
│   ├── README.md            # Index + quick FSM overview
│   ├── session_state.md     # AgentSession lifecycle (Created/Running/Idle/TakeoverPending/Paused/Shutdown)
│   ├── session_coordinator.md # SubmitResult queue + drain semantics
│   ├── agent_run_loop.md    # Agent.run TurnOutcome loop
│   ├── tool_call.md         # ToolCallState 7-state lifecycle
│   ├── llm_retry.md         # CloudStreamRetryPolicy + StreamRetryRunner
│   ├── local_model_loading.md # LFMLLMClient ModelLoadingState
│   ├── onboarding_wizard.md # WizardStep funnel + StepOutcome
│   ├── onboarding_apikey_step.md # ApiKeyStepState (manual + OAuth)
│   ├── onboarding_demo_step.md   # DemoStepState
│   ├── onboarding_permission_step.md # PermissionStepState
│   └── onboarding_step_states.md # Why 3 hierarchies are kept (KISS rationale)
│
├── error_handling.md  # Error patterns + silent-failure inventory
├── data_schemas.md    # Core schemas + redundancy findings
│
└── eval/              # Evaluation & benchmarking
    └── eval.md        # Eval runner architecture, AndroidWorld bridge
```

---

## Code Structure

```
app/src/main/kotlin/ai/closepaw/
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
│   ├── ActionTarget.kt           # Shared UI-element target decoder (text/bounds/point/index)
│   ├── ActionDescriptionFormatter.kt # Tool action descriptions
│   ├── Turn.kt                   # LLM call wrapper (OpenAI Responses API)
│   ├── definition/               # AgentRoleDef: Planner/Executor/Standalone
│   ├── cognition/                # PromptBuilder, TurnObservation, policies, NavigationState
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
│       ├── ShellTool.kt
│       ├── BrowserScriptTool.kt              # browser_script: validation, capability gate, bounded output, trace
│       ├── BrowserScriptTypes.kt             # gate/invoker/sink interfaces, outcome taxonomy, runner JSON serializer
│       └── DefaultBrowserScriptCapabilityGate.kt  # production gate: experimental flag → Shizuku → preflight
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
│   ├── AgentEventDomains.kt      # Domain marker interfaces
│   ├── SessionState.kt           # State machine (5 states: Created/Running/Idle/Paused/Shutdown)
│   ├── SessionConfig.kt          # Session configuration
│   ├── ApprovalTypes.kt          # Approval decision types
│   ├── TaskOutcome.kt            # Task-level outcome (5 values)
│   ├── SessionEndReason.kt       # Session-level shutdown reason (3 values)
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
│   ├── capsule/                  # SmartCapsuleSurface, CapsuleControlBar, CapsuleInputBar, CapsuleBinding
│   ├── common/                   # Shared composables
│   ├── navigation/               # Navigation drawer
│   ├── overlay/                  # System overlays (capsule, glow, island, visualizer)
│   ├── settings/                 # Settings full-screen pages
│   ├── session/                  # Session history UI
│   └── viewer/                   # VirtualDisplayViewerActivity
│
├── model/                        # Domain models
│   └── Models.kt                 # ScreenSnapshot, etc.
│
├── browser/                      # Browser automation (CDP over Shizuku or wireless ADB)
│   ├── cdp/                      # Chrome DevTools Protocol client
│   │   ├── CdpTransport.kt       # WebSocket transport abstraction
│   │   ├── ChromeCdpCommand.kt   # CDP message types, parse/build (result is JsonElement)
│   │   ├── ChromeCdpTarget.kt    # Page target filtering (real vs internal)
│   │   ├── ChromeCdpEventBuffer.kt # Thread-safe event ring buffer
│   │   ├── ChromeCdpClient.kt    # Core client: routing, attach, target-switch atomicity, recovery
│   │   ├── RelayAuthToken.kt     # Per-session 32-byte hex token; X-ClosePaw-Token gate; slowloris deadline
│   │   ├── shizuku/              # Shizuku transport (USER_SERVICE)
│   │   │   ├── ShizukuChromeDevtoolsBridge.kt  # Cascade: USER_SERVICE → WIRELESS_ADB_SELF_PAIR
│   │   │   ├── ChromeDevtoolsUserService.kt    # Shell-UID socket proxy (token-gated)
│   │   │   ├── ShizukuUserServiceProvider.kt   # Single-flight binder, pair-once
│   │   │   └── ...               # HTTP bootstrap, transport, diagnostics, error classification
│   │   └── wireless/             # Wireless-ADB self-pair transport (no PC, no root)
│   │       ├── WirelessAdbSelfPairTransport.kt # Token-gated relay; same start() contract
│   │       ├── AdbPairingClient.kt             # TLS-PSK SPAKE2-25519 pairing handshake
│   │       ├── Spake25519.kt                   # In-house SPAKE2-25519 over net.i2p.crypto:eddsa (CC0)
│   │       ├── AdbWireProtocolClient.kt        # Post-mTLS CNXN/AUTH/OPEN ADB wire protocol
│   │       ├── AdbCryptoKeyStore.kt            # RSA-2048 key persistence + adb_keys ceiling
│   │       └── ...               # AndroidPubkey, providers, /proc/net listener discovery
│   └── script/                   # Hidden-WebView JavaScript automation host
│       ├── BrowserSessionManager.kt # Session owner: lease, lazy resources, reconnect, cleanup,
│       │                          # session-scoped storeArtifact byte counter (atomic CAS)
│       ├── BrowserScriptPrelude.kt # JS prelude (only `globalThis.cdp` exposed) + script wrapper
│       ├── BrowserScriptBridge.kt  # Pure-Kotlin bridge: parse send → ChromeCdpClient → resolve/reject; cancel/timeout
│       ├── BrowserScriptJsInterface.kt # @JavascriptInterface surface (`send`/`done`/`storeArtifact`)
│       └── BrowserScriptRunner.kt  # Hardened hidden WebView lifecycle, timeout, navigation block, cancel-guarded callbacks
│
└── util/                         # Shared utilities
```

---

## Reading Order

### New to the codebase?

1. [agent/overview.md](agent/overview.md) — Architecture and design principles
2. [agent/loop.md](agent/loop.md) — How the agent executes (ReAct loop)
3. [agent/turn_prompt_anatomy.md](agent/turn_prompt_anatomy.md) — What is sent to the LLM each turn
4. [protocol/overview.md](protocol/overview.md) — How UI and agent communicate

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
| History persistence | [history/overview.md](app/history/overview.md) |
| Settings | [settings.md](app/settings.md) |
| Evaluation | [eval.md](eval/eval.md) |

---

## Key Concepts

| Concept | Description | Doc |
|---------|-------------|-----|
| **ReAct Loop** | Perceive → Think → Act → Observe | [loop.md](agent/loop.md) |
| **Task** | Work unit from user input to completion | [protocol/overview.md](protocol/overview.md) |
| **Turn** | One LLM call + tool execution cycle | [loop.md](agent/loop.md) |
| **AgentDef** | Agent definition (Planner/Executor/Standalone) | [multiagent.md](agent/multiagent.md) |
| **Op** | User intent (UI → Agent) | [protocol/overview.md](protocol/overview.md) |
| **AgentEvent** | State notification (Agent → UI) | [protocol/events.md](protocol/events.md) |
| **SessionConfig** | Compiled settings snapshot for a session | [settings.md](app/settings.md) |
| **Hot Idle** | Session stays alive between tasks for follow-up | [session/](ui/session/state_machine.md) |
| **CapsuleMode** | Smart Capsule state (Running, Takeover, etc.) | [overlay.md](ui/overlay.md) |
| **Context Hygiene** | Token-efficient history management (compression pipeline) | [planning.md](agent/planning.md), [history/runtime.md](app/history/runtime.md) |
| **Cross-Session Memory** | Persistent app-specific learnings recalled per turn | [memory.md](agent/memory.md) |
| **Agent Skills** | agentskills.io-compatible task/capability skills with catalog + on-demand activation | [agent_skills.md](agent/agent_skills.md) |

---

## Development Workflow

→ See: [doc/dev/development.md](../dev/development.md)
