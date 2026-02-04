# Multi-Agent Infrastructure Design (Final)

> **Date**: 2026-02-03  
> **Status**: Consolidated from design_1/2/3.md, plan_1/3.md, and design reviews

## Executive Summary

This document consolidates three design proposals for extending Android Agent to support multi-agent capabilities. All designs converge on **sub-agent-as-tool** with **event/approval bridging**. The final design adopts **Design 2's comprehensive architecture** with **Design 1's phased implementation plan** and **risk management**.

| Component | Source |
|-----------|--------|
| Architecture (Registry, AgentDefinition) | Design 2 |
| Phased Plan + Risk Table | Design 1 / Plan 1 |
| Event Bridging Strategy | Design 1 + 3 |

---

## 1. Goals & Non-Goals

### Goals
- **Delegate sub-tasks** to specialized sub-agents
- **Preserve UX**: single approval stream, coherent event timeline
- **Scoped capability**: sub-agents run with tool subsets and explicit limits
- **Reusable core**: reuse existing `Agent` loop

### Non-Goals (Phase 1)
- Parallel multi-agent orchestration
- Cross-device orchestration
- Remote agent execution (A2A)
- User-defined agents at runtime

---

## 2. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        AgentSession                              │
│   ┌─────────────────────────┐                                   │
│   │     AgentRegistry       │ ◄── Built-in agent definitions    │
│   │  - getDefinition()      │                                   │
│   │  - getAllDefinitions()  │                                   │
│   └───────────┬─────────────┘                                   │
│               │                                                  │
│               ▼                                                  │
│   ┌─────────────────────────┐        ┌───────────────────────┐ │
│   │   Parent Agent          │        │   DelegateTaskTool    │ │
│   │   (ReAct Loop)          │◄──────►│   (Tool Invocation)   │ │
│   └───────────┬─────────────┘        └───────────┬───────────┘ │
│               │                                  │              │
│               │ Events                           │ Spawns       │
│               ▼                                  ▼              │
│   ┌─────────────────────────────────────────────────────────┐  │
│   │                   SubAgentRunner                         │  │
│   │   ┌─────────────────┐    ┌─────────────────┐            │  │
│   │   │  Child Agent    │    │  Event Bridge   │            │  │
│   │   │  (own services) │───►│  (→ Parent)     │            │  │
│   │   └────────┬────────┘    └─────────────────┘            │  │
│   │            │                                             │  │
│   │            ▼                                             │  │
│   │   ┌──────────────────────┐                               │  │
│   │   │  Approval Bridge     │ ──► Parent Session            │  │
│   │   └──────────────────────┘                               │  │
│   └─────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. Core Components

### 3.1 AgentDefinition

**File**: `agent/definition/AgentDefinition.kt`

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
        val promptBuilder: (AgentInputs) -> String,
        val toolNames: List<String>,
        val maxTurns: Int,
        val timeoutMs: Long
    ) : AgentDefinition
}

data class InputConfig(val inputs: Map<String, InputSpec>)

data class InputSpec(
    val description: String,
    val type: InputType,
    val required: Boolean
)

enum class InputType { STRING, INT, BOOLEAN, STRING_LIST }

data class OutputConfig(val outputName: String, val description: String)

typealias AgentInputs = Map<String, Any?>
```

### 3.2 AgentRegistry

**File**: `agent/registry/AgentRegistry.kt`

```kotlin
class AgentRegistry(private val parentToolRegistry: ToolRegistry) {
    private val agents = mutableMapOf<String, AgentDefinition>()
    
    fun registerAgent(definition: AgentDefinition) {
        agents[definition.name] = definition
    }
    
    fun getDefinition(name: String): AgentDefinition? = agents[name]
    fun getAllDefinitions(): List<AgentDefinition> = agents.values.toList()
    fun getDirectoryContext(): String { /* Generate markdown for system prompt */ }
    
    companion object {
        fun createWithBuiltIns(toolRegistry: ToolRegistry): AgentRegistry {
            return AgentRegistry(toolRegistry).apply {
                registerAgent(ScreenAnalysisAgent.definition)
                registerAgent(AppExplorerAgent.definition)
            }
        }
    }
}
```

### 3.3 DelegateTaskTool

**File**: `tool/impl/DelegateTaskTool.kt`

```kotlin
class DelegateTaskTool(
    private val registry: AgentRegistry,
    private val sessionServices: SessionServices,
    private val parentEventDispatcher: AgentEventDispatcher
) : BaseTool(
    name = "delegate_task",
    description = "Delegate a sub-task to a specialized agent",
    arguments = /* derived from registry */
) {
    override suspend fun execute(args: JSONObject): ToolCallResult {
        val agentName = args.getString("agent_name")
        val definition = registry.getDefinition(agentName)
            ?: return ToolCallResult.Error("Unknown agent: $agentName")
        
        val runner = SubAgentRunner(
            definition = definition as AgentDefinition.Local,
            parentServices = sessionServices,
            parentEventDispatcher = parentEventDispatcher
        )
        return runner.run(args.toAgentInputs())
    }
}
```

### 3.4 SubAgentRunner

**File**: `agent/SubAgentRunner.kt`

```kotlin
class SubAgentRunner(
    private val definition: AgentDefinition.Local,
    private val parentServices: SessionServices,
    private val parentEventDispatcher: AgentEventDispatcher
) {
    suspend fun run(inputs: AgentInputs): ToolCallResult = coroutineScope {
        // 1. Create isolated services
        val subServices = parentServices.deriveForSubAgent(
            historyManager = HistoryManager(),
            toolRegistry = parentServices.toolRegistry
                .filterByNames(definition.toolNames)
                .excludeByName("delegate_task"), // Prevent recursion
            policyEngine = ParentRoutingPolicyEngine(parentServices.session)
        )
        
        // 2. Create sub-agent with event bridging
        val subAgent = Agent(
            services = subServices,
            promptBuilder = SubAgentPromptBuilder(definition, inputs),
            eventDispatcher = SubAgentEventBridge(
                parentDispatcher = parentEventDispatcher,
                agentName = definition.name
            )
        )
        
        // 3. Run with timeout
        val result = withTimeoutOrNull(definition.timeoutMs) {
            subAgent.run(goal = definition.buildGoal(inputs))
        }
        
        // 4. Convert to ToolCallResult
        when {
            result == null -> ToolCallResult.Success(
                observation = "Sub-agent timed out after ${definition.timeoutMs}ms"
            )
            result.reason == AgentStopReason.GOAL_ACHIEVED -> ToolCallResult.Success(
                observation = result.message ?: "Task completed"
            )
            else -> ToolCallResult.Success(
                observation = "Sub-agent stopped: ${result.reason}"
            )
        }
    }
}
```

---

## 4. Protocol Extensions

**File**: `protocol/AgentEvent.kt`

```kotlin
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

enum class SubAgentActivityType { THINKING, TOOL_CALL_START, TOOL_CALL_END, ERROR }
enum class SubAgentTerminateReason { GOAL_ACHIEVED, TIMEOUT, MAX_TURNS, ERROR, ABORTED }
```

---

## 5. Event & Approval Bridging

### 5.1 Event Bridge

```kotlin
class SubAgentEventBridge(
    private val parentDispatcher: AgentEventDispatcher,
    private val agentName: String
) : AgentEventDispatcher {
    override fun emit(event: AgentEvent) {
        when (event) {
            is MessageDelta -> parentDispatcher.emit(
                SubAgentActivity(agentName = agentName, message = event.content, ...)
            )
            is StatusUpdate -> parentDispatcher.emit(
                SubAgentActivity(agentName = agentName, message = "↳ ${event.status}", ...)
            )
            is TaskStarted, is TaskCompleted -> { /* Suppress to avoid UI confusion */ }
            else -> { /* Forward as-is or wrap */ }
        }
    }
}
```

### 5.2 Approval Bridge

```kotlin
class ParentRoutingPolicyEngine(
    private val parentSession: AgentSession
) : PolicyEngine {
    override suspend fun checkPolicy(
        toolName: String,
        args: JSONObject,
        riskLevel: RiskLevel
    ): PolicyDecision = suspendCancellableCoroutine { cont ->
        parentSession.requestSubAgentApproval(
            toolName = toolName,
            args = args,
            riskLevel = riskLevel,
            onDecision = { decision -> cont.resume(decision) }
        )
    }
}
```

---

## 6. Built-In Agents

### ScreenAnalysisAgent

```kotlin
object ScreenAnalysisAgent {
    val definition = AgentDefinition.Local(
        name = "screen_analyzer",
        description = "Analyzes current screen UI elements and provides structured information",
        inputConfig = InputConfig(mapOf(
            "question" to InputSpec("What to analyze", InputType.STRING, required = true)
        )),
        promptBuilder = { inputs -> """
            You are a screen analysis specialist. Analyze the current screen and answer:
            ${inputs["question"]}
            Focus on: app identity, visible UI elements, possible actions, relevant text.
        """.trimIndent() },
        toolNames = listOf("mobile_action"),
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
        description = "Explores an app to find specific features or screens",
        inputConfig = InputConfig(mapOf(
            "target" to InputSpec("Feature or screen to find", InputType.STRING, required = true),
            "app_package" to InputSpec("App package name", InputType.STRING, required = false)
        )),
        promptBuilder = { inputs -> /* Navigation-focused prompt */ },
        toolNames = listOf("mobile_action", "app_control"),
        maxTurns = 10,
        timeoutMs = 60_000
    )
}
```

---

## 7. Implementation Plan

### Phase 1: Foundations (Low Risk)
1. Add protocol events: `SubAgentStarted/Activity/Completed`
2. Add `subAgentId` to approval payload (optional field)
3. Create `AgentDefinition` and `InputConfig` types

### Phase 2: Sub-Agent Runtime (Medium Risk)
4. Implement `SessionServices.deriveForSubAgent()`
5. Create `SubAgentRunner` with coroutine scope + cancellation
6. Implement `SubAgentEventBridge`

### Phase 3: Tool Integration (Medium Risk)
7. Create `AgentRegistry` with built-in agents
8. Implement `DelegateTaskTool`
9. Register tool, ensure sub-agent allowlists exclude it

### Phase 4: Approval Routing (Medium Risk)
10. Implement `ParentRoutingPolicyEngine`
11. Update `AgentSession` for sub-agent approval flow
12. Test end-to-end approval routing

### Phase 5: Testing & Polish
13. Unit tests: registry, bridges, tool invocation
14. Integration test: parent → child delegation flow
15. Manual test: real device multi-step task

---

## 8. Risk Mitigation

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Approval deadlock | M | H | Timeout + explicit routing keys |
| Event spam | M | M | Filter + prefix only key events |
| Infinite recursion | L | H | Exclude `delegate_task` from child registry |
| Shared resource contention | M | M | Child scope isolation |

---

## 9. Guardrails

- **Tool allowlist**: Each sub-agent gets restricted tool set
- **Max depth**: `maxDelegationDepth = 1` (prevent nested delegation initially)
- **Timeouts**: Per-agent time limits (30s-60s default)
- **Turn limits**: Per-agent max turns (3-10 default)
- **Approval policy**: All child tool calls route through parent

---

## 10. Future Extensions (Out of Scope)

- **Parallel sub-agents**: `SubAgentTaskGroup` with bounded concurrency
- **Nested delegation**: Allow depth > 1 with explicit policy
- **Remote agents**: A2A protocol integration
- **User-defined agents**: Runtime agent definition via config files

---

## References

- [design_1.md](./design_1.md) - MVP-focused with explicit bridges
- [design_2.md](./design_2.md) - Comprehensive registry architecture
- [design_3.md](./design_3.md) - Minimal hybrid approach
- [Codex Summary](./codex_summary_claude.md)
- [Gemini CLI Summary](./gemini_summary_claude.md)
