# Android Agent Multi-Agent Extension Plan

> Design proposal for extending Android Agent infrastructure to support multi-agent capabilities.
> Based on analysis of OpenAI Codex CLI and Google Gemini CLI architectures.

## Current Architecture Review

### Existing Strong Foundations

Our Android Agent already has several components that facilitate multi-agent extension:

| Component | Location | Multi-Agent Readiness |
|-----------|----------|----------------------|
| **SessionServices** | `session/SessionServices.kt` | ✅ DI container can be cloned/scoped |
| **Agent** | `agent/Agent.kt` | ✅ Stateless ReAct loop, can have multiple instances |
| **AgentEvent** | `protocol/AgentEvent.kt` | ⚠️ Needs sub-agent event types |
| **Op** | `protocol/Op.kt` | ⚠️ Needs delegation operations |
| **ToolRouter** | `tool/ToolRouter.kt` | ✅ Already has approval routing |
| **ToolRegistry** | `tool/ToolRegistry.kt` | ✅ Can create isolated registries |
| **HistoryManager** | `history/HistoryManager.kt` | ⚠️ Needs per-agent isolation |

### Current Single-Agent Flow

```
Op.UserInput ──► AgentSession ──► Agent ──► [ReAct Loop] ──► AgentEvent
                      │              │
                      │              └── ToolRouter ──► Tool Execution
                      ▼
               SessionServices (shared)
```

---

## Proposed Multi-Agent Architecture

### Design Principles (Borrowed from References)

| Principle | Source | Application |
|-----------|--------|-------------|
| **Registry-based delegation** | Gemini | Type-safe agent discovery and invocation |
| **Parent-child session spawning** | Codex | Full agent instances with approval routing |
| **Activity streaming** | Gemini | Real-time sub-agent updates to UI |
| **Approval bubbling** | Codex | Security-sensitive ops route to parent |
| **Graceful termination** | Gemini | Recovery turns before hard stop |
| **Isolated tool registries** | Both | Each sub-agent gets scoped tools |

### Proposed Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        AgentSession                              │
│   ┌─────────────────────────┐                                   │
│   │     AgentRegistry       │ ◄── Built-in + user-defined       │
│   │  - getAgentDefinition() │                                   │
│   │  - getAllAgents()       │                                   │
│   └───────────┬─────────────┘                                   │
│               │                                                  │
│               ▼                                                  │
│   ┌─────────────────────────┐        ┌───────────────────────┐ │
│   │   Parent Agent          │        │   DelegateToAgentTool │ │
│   │   (ReAct Loop)          │◄──────►│   (Tool Invocation)   │ │
│   └───────────┬─────────────┘        └───────────┬───────────┘ │
│               │                                  │              │
│               │ Events                           │ Spawns       │
│               ▼                                  ▼              │
│   ┌─────────────────────────────────────────────────────────┐  │
│   │                   SubAgentRunner                         │  │
│   │   ┌─────────────────┐    ┌─────────────────┐            │  │
│   │   │  Sub Agent 1    │    │  Sub Agent 2    │            │  │
│   │   │  (own services) │    │  (own services) │            │  │
│   │   └────────┬────────┘    └────────┬────────┘            │  │
│   │            │                      │                      │  │
│   │            └──────────┬───────────┘                      │  │
│   │                       │                                  │  │
│   │                       ▼                                  │  │
│   │            ┌──────────────────────┐                      │  │
│   │            │  Approval Routing    │ ──► Parent Session   │  │
│   │            │  Event Forwarding    │ ──► Parent Events    │  │
│   │            └──────────────────────┘                      │  │
│   └─────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Component Design

### 1. AgentDefinition (New)

**File:** `agent/definition/AgentDefinition.kt`

```kotlin
sealed interface AgentDefinition {
    val name: String
    val description: String
    val inputConfig: InputConfig
    val outputConfig: OutputConfig?
    
    data class Local(
        override val name: String,
        override val description: String,
        override val inputConfig: InputConfig,
        override val outputConfig: OutputConfig?,
        val promptBuilder: ((AgentInputs) -> String),  // System prompt generator
        val toolNames: List<String>,                    // Tools available to sub-agent
        val maxTurns: Int,
        val timeoutMs: Long
    ) : AgentDefinition
}

data class InputConfig(
    val inputs: Map<String, InputSpec>
)

data class InputSpec(
    val description: String,
    val type: InputType,
    val required: Boolean
)

enum class InputType { STRING, INT, BOOLEAN, STRING_LIST }

data class OutputConfig(
    val outputName: String,
    val description: String
)

typealias AgentInputs = Map<String, Any?>
```

---

### 2. AgentRegistry (New)

**File:** `agent/registry/AgentRegistry.kt`

```kotlin
class AgentRegistry(
    private val parentToolRegistry: ToolRegistry
) {
    private val agents = mutableMapOf<String, AgentDefinition>()
    
    fun registerAgent(definition: AgentDefinition) {
        agents[definition.name] = definition
    }
    
    fun getDefinition(name: String): AgentDefinition? = agents[name]
    
    fun getAllDefinitions(): List<AgentDefinition> = agents.values.toList()
    
    fun getDirectoryContext(): String {
        // Generate markdown listing for system prompt
    }
    
    companion object {
        fun createWithBuiltIns(toolRegistry: ToolRegistry): AgentRegistry {
            return AgentRegistry(toolRegistry).apply {
                // Register built-in specialized agents
                registerAgent(ScreenAnalysisAgent.definition)
                registerAgent(AppExplorerAgent.definition)
                registerAgent(TextExtractionAgent.definition)
            }
        }
    }
}
```

---

### 3. DelegateToAgentTool (New)

**File:** `tool/impl/DelegateToAgentTool.kt`

```kotlin
class DelegateToAgentTool(
    private val registry: AgentRegistry,
    private val sessionServices: SessionServices,
    private val parentSession: AgentSession,
    private val parentEventDispatcher: AgentEventDispatcher
) : MultiActionTool(
    name = "delegate_task",
    description = registry.getDirectoryContext(),
    actions = registry.getAllDefinitions().map { def ->
        ActionSpec(
            name = def.name,
            description = def.description,
            parameters = buildParametersFromInputConfig(def.inputConfig)
        )
    }
) {
    override suspend fun execute(
        action: String,
        args: JSONObject,
        snapshot: ScreenSnapshot
    ): ToolCallResult {
        val definition = registry.getDefinition(action)
            ?: return ToolCallResult.Error("Unknown agent: $action")
        
        return when (definition) {
            is AgentDefinition.Local -> executeLocalAgent(definition, args, snapshot)
        }
    }
    
    private suspend fun executeLocalAgent(
        definition: AgentDefinition.Local,
        args: JSONObject,
        snapshot: ScreenSnapshot
    ): ToolCallResult {
        val runner = SubAgentRunner(
            definition = definition,
            parentSession = parentSession,
            parentServices = sessionServices,
            parentEventDispatcher = parentEventDispatcher
        )
        
        return runner.run(args.toAgentInputs())
    }
}
```

---

### 4. SubAgentRunner (New)

**File:** `agent/SubAgentRunner.kt`

```kotlin
class SubAgentRunner(
    private val definition: AgentDefinition.Local,
    private val parentSession: AgentSession,
    private val parentServices: SessionServices,
    private val parentEventDispatcher: AgentEventDispatcher
) {
    suspend fun run(inputs: AgentInputs): ToolCallResult = coroutineScope {
        // 1. Create isolated services for sub-agent
        val subServices = createIsolatedServices(definition)
        
        // 2. Create sub-agent with filtered tool registry
        val subAgent = Agent(
            services = subServices,
            promptBuilder = SubAgentPromptBuilder(definition, inputs),
            eventDispatcher = SubAgentEventDispatcher(
                parentDispatcher = parentEventDispatcher,
                agentName = definition.name
            )
        )
        
        // 3. Run with timeout and cancellation
        val result = withTimeoutOrNull(definition.timeoutMs) {
            subAgent.run(goal = definition.buildGoal(inputs))
        }
        
        // 4. Format result
        when {
            result == null -> ToolCallResult.Success(
                observation = "Sub-agent ${definition.name} timed out",
                isComplete = false
            )
            result.reason == AgentStopReason.GOAL_ACHIEVED -> ToolCallResult.Success(
                observation = result.message ?: "Task completed by ${definition.name}",
                isComplete = false
            )
            else -> ToolCallResult.Success(
                observation = "Sub-agent ${definition.name} stopped: ${result.reason}",
                isComplete = false
            )
        }
    }
    
    private fun createIsolatedServices(definition: AgentDefinition.Local): SessionServices {
        // Clone parent services with:
        // - New HistoryManager (isolated conversation)
        // - Filtered ToolRegistry (only allowed tools)
        // - Shared Platform (same device access)
        // - Shared LLM client (same API key)
        return parentServices.copy(
            historyManager = HistoryManager(),
            toolRegistry = parentServices.toolRegistry.filterByNames(definition.toolNames),
            // PolicyEngine routes approvals to parent session
            policyEngine = ParentRoutingPolicyEngine(parentSession)
        )
    }
}
```

---

### 5. Protocol Extensions

**File:** `protocol/AgentEvent.kt` (modifications)

```kotlin
sealed interface AgentEvent {
    // ... existing events ...
    
    // New sub-agent events
    data class SubAgentStarted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val agentName: String,
        val parentTurnId: String,
        val inputs: Map<String, Any?>
    ) : AgentEvent
    
    data class SubAgentActivity(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val agentName: String,
        val activityType: SubAgentActivityType,
        val message: String
    ) : AgentEvent
    
    data class SubAgentCompleted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val agentName: String,
        val result: String,
        val terminateReason: SubAgentTerminateReason
    ) : AgentEvent
}

enum class SubAgentActivityType {
    THINKING,
    TOOL_CALL_START,
    TOOL_CALL_END,
    ERROR
}

enum class SubAgentTerminateReason {
    GOAL_ACHIEVED,
    TIMEOUT,
    MAX_TURNS,
    ERROR,
    ABORTED
}
```

---

### 6. Approval Routing

**File:** `agent/policy/ParentRoutingPolicyEngine.kt`

```kotlin
class ParentRoutingPolicyEngine(
    private val parentSession: AgentSession
) : PolicyEngine {
    
    override suspend fun checkPolicy(
        toolName: String,
        args: JSONObject,
        riskLevel: RiskLevel
    ): PolicyDecision {
        // Route approval requests to parent session
        // Parent session will emit ApprovalRequired event
        // User decision flows back through the hierarchy
        
        return suspendCancellableCoroutine { cont ->
            parentSession.requestSubAgentApproval(
                toolName = toolName,
                args = args,
                riskLevel = riskLevel,
                onDecision = { decision ->
                    cont.resume(decision)
                }
            )
        }
    }
}
```

---

## Built-In Agents (Examples)

### ScreenAnalysisAgent

```kotlin
object ScreenAnalysisAgent {
    val definition = AgentDefinition.Local(
        name = "screen_analyzer",
        description = "Analyzes the current screen and provides structured information about visible UI elements, their purposes, and possible actions.",
        inputConfig = InputConfig(
            inputs = mapOf(
                "question" to InputSpec(
                    description = "What you want to know about the current screen",
                    type = InputType.STRING,
                    required = true
                )
            )
        ),
        outputConfig = OutputConfig(
            outputName = "analysis",
            description = "Structured analysis of the screen"
        ),
        promptBuilder = { inputs ->
            """
            You are a screen analysis specialist for Android devices.
            Your task is to analyze the current screen and answer the question:
            ${inputs["question"]}
            
            Focus on:
            - What app is currently open
            - What UI elements are visible
            - What actions are possible
            - Any relevant text content
            
            Provide a structured, concise analysis.
            """.trimIndent()
        },
        toolNames = listOf("mobile_action"),  // Limited to observation
        maxTurns = 3,
        timeoutMs = 30_000
    )
}
```

### AppExplorerAgent

```kotlin
object AppExplorerAgent {
    val definition = AgentDefinition.Local(
        name = "app_explorer",
        description = "Explores an app to find specific features or screens. Can navigate through menus and screens.",
        inputConfig = InputConfig(
            inputs = mapOf(
                "target" to InputSpec(
                    description = "What feature or screen to find",
                    type = InputType.STRING,
                    required = true
                ),
                "app_package" to InputSpec(
                    description = "Package name of the app to explore",
                    type = InputType.STRING,
                    required = false
                )
            )
        ),
        promptBuilder = { inputs -> /* ... */ },
        toolNames = listOf("mobile_action", "app_control"),
        maxTurns = 10,
        timeoutMs = 60_000
    )
}
```

---

## Implementation Phases

### Phase 1: Core Infrastructure (Week 1-2)

1. **AgentDefinition** and **InputConfig** types
2. **AgentRegistry** with registration and lookup
3. **SubAgentRunner** with isolated services
4. Basic **DelegateToAgentTool** implementation
5. Sub-agent events in protocol

### Phase 2: Approval Routing (Week 3)

1. **ParentRoutingPolicyEngine**
2. Modify **AgentSession** for sub-agent approval requests
3. UI updates to show nested approval dialogs
4. Test approval flow end-to-end

### Phase 3: Built-In Agents (Week 4)

1. **ScreenAnalysisAgent**
2. **AppExplorerAgent**
3. **TextExtractionAgent**
4. Integration testing with real tasks

### Phase 4: Activity Streaming (Week 5)

1. **SubAgentEventDispatcher** with filtering
2. UI updates to show sub-agent progress
3. Session history integration for sub-agent actions
4. Graceful recovery mechanism

---

## Key Differences from References

| Aspect | Codex | Gemini | Android Agent Proposal |
|--------|-------|--------|------------------------|
| Sub-agent type | Full Codex instance | LocalExecutor loop | Simplified Agent instance |
| Event forwarding | All non-approval | Streaming thoughts | Configurable filter |
| Approval routing | Async via channels | Message bus | Coroutine flow |
| Configuration | AGENTS.md files | TOML files | Kotlin DSL + annotations |
| Remote agents | N/A | A2A protocol | Out of scope (v1) |

---

## Compatibility Considerations

### Backward Compatibility

- Existing single-agent sessions work unchanged
- New events are additive (no breaking changes)
- Tool registration API remains stable

### Forward Compatibility

- AgentDefinition sealed interface allows future remote agents
- InputConfig extensible to new types
- Event protocol versioned for future changes

---

## Testing Strategy

### Unit Tests

- AgentRegistry registration/lookup
- InputConfig to JSONSchema conversion
- SubAgentRunner isolation verification
- ParentRoutingPolicyEngine approval flow

### Integration Tests

- End-to-end delegation with mock LLM
- Approval routing across parent-child
- Timeout and cancellation handling
- Event flow to UI

### Manual Tests

- Real device delegation scenarios
- Complex multi-step tasks with sub-agents
- UI feedback during sub-agent execution

---

## Open Questions

1. **Concurrent sub-agents?** Should we allow parallel sub-agent execution?
2. **Nested delegation?** Can sub-agents delegate to other sub-agents?
3. **Shared state?** Should sub-agents share observation history with parent?
4. **User-defined agents?** Should we support runtime agent definition?

---

## References

- [Codex Summary](./codex_summary_claude.md)
- [Gemini CLI Summary](./gemini_summary_claude.md)
- [Android Agent Infrastructure](../../main/agent_infra.md)
- [Android Agent Protocol](../../main/agent_protocol.md)
