# Phase 1: Sub-Agent Infrastructure Design

> **Goal**: Minimal infra to support delegation to specialized agents

**Codex Addition - Context Passing Fields**:
When parent delegates to executor, pass only:
- `query` (string) — the complete instruction
- `current_subgoal` (string, optional) — for context
- `important_notes` (short list, optional) — key observations
- Latest screen snapshot (a11y tree + screenshot)

Do NOT pass: full history, prior screenshots, or parent's todo list.

---

## 1. AgentDefinition (Minimal)

```kotlin
/**
 * Defines a sub-agent that can be invoked via delegate_task.
 * 
 * KISS: Just enough to spawn an isolated agent session.
 */
data class AgentDefinition(
    val name: String,
    val description: String,
    val systemPrompt: String,
    val toolNames: List<String>,  // Allowed tools
    val maxTurns: Int = 10,
    val timeoutMs: Long = 60_000
)
```

No complex InputConfig/OutputConfig. Natural language in, natural language out.

---

## 2. AgentRegistry (Minimal)

```kotlin
/**
 * Simple map of agent definitions.
 */
class AgentRegistry {
    private val agents = mutableMapOf<String, AgentDefinition>()
    
    fun register(definition: AgentDefinition) {
        agents[definition.name] = definition
    }
    
    fun get(name: String): AgentDefinition? = agents[name]
    
    fun getAll(): List<AgentDefinition> = agents.values.toList()
    
    fun getDirectoryPrompt(): String = agents.values.joinToString("\n") {
        "- ${it.name}: ${it.description}"
    }
    
    companion object {
        fun createDefault(toolRegistry: ToolRegistry): AgentRegistry {
            return AgentRegistry().apply {
                register(ExecutorAgent.definition)
                // Future: register(ScreenAnalyzerAgent.definition)
            }
        }
    }
}
```

---

## 3. SubAgentRunner (Core)

```kotlin
/**
 * Runs a sub-agent in isolation.
 * 
 * Key isolation guarantees:
 * - Fresh HistoryManager (no parent history)
 * - Filtered ToolRegistry (only allowed tools)
 * - Events bridged to parent
 */
class SubAgentRunner(
    private val definition: AgentDefinition,
    private val parentServices: SessionServices,
    private val eventBridge: (AgentEvent) -> Unit
) {
    suspend fun run(query: String): SubAgentResult = coroutineScope {
        // 1. Create isolated services
        val childTools = parentServices.toolRegistry
            .filterByNames(definition.toolNames)
            .exclude("delegate_task")  // Prevent recursion
        
        val childHistory = HistoryManager()  // Fresh, empty
        
        val childServices = parentServices.copy(
            toolRegistry = childTools,
            historyManager = childHistory
        )
        
        // 2. Create and run agent
        val agent = Agent(
            services = childServices,
            config = AgentConfig(
                systemPrompt = definition.systemPrompt,
                maxTurns = definition.maxTurns
            ),
            eventEmitter = { event -> bridgeEvent(event) }
        )
        
        // 3. Run with timeout
        val result = withTimeoutOrNull(definition.timeoutMs) {
            agent.run(goal = query)
        }
        
        // 4. Return result
        SubAgentResult(
            success = result?.reason == AgentStopReason.GOAL_ACHIEVED,
            message = result?.message ?: "Timeout after ${definition.timeoutMs}ms"
        )
    }
    
    private fun bridgeEvent(event: AgentEvent) {
        // Transform child events to parent-friendly format
        val bridged = SubAgentActivity(
            sessionId = parentServices.sessionId,
            agentName = definition.name,
            activity = event.toString()
        )
        eventBridge(bridged)
    }
}

data class SubAgentResult(
    val success: Boolean,
    val message: String
)
```

---

## 4. DelegateTaskTool

```kotlin
/**
 * Tool for parent agent to delegate work to sub-agents.
 * 
 * Usage in prompt:
 * delegate_task(agent_name="executor", query="tap the login button")
 */
class DelegateTaskTool(
    private val registry: AgentRegistry,
    private val services: SessionServices,
    private val eventEmitter: (AgentEvent) -> Unit
) : BaseTool(
    name = "delegate_task",
    description = """
        Delegate a task to a specialized sub-agent.
        
        Available agents:
        ${registry.getDirectoryPrompt()}
        
        The query should be a complete, self-contained instruction.
        The sub-agent has no memory of previous delegations.
    """.trimIndent(),
    arguments = listOf(
        Argument("agent_name", "string", required = true,
            description = "Name of the agent to delegate to"),
        Argument("query", "string", required = true,
            description = "Complete instruction for the sub-agent")
    )
) {
    override suspend fun execute(args: JSONObject): ToolCallResult {
        val agentName = args.getString("agent_name")
        val query = args.getString("query")
        
        val definition = registry.get(agentName)
            ?: return ToolCallResult.Error("Unknown agent: $agentName")
        
        // Emit start event
        eventEmitter(SubAgentStarted(
            sessionId = services.sessionId,
            agentName = agentName,
            query = query
        ))
        
        val runner = SubAgentRunner(definition, services, eventEmitter)
        val result = runner.run(query)
        
        // Emit completion event
        eventEmitter(SubAgentCompleted(
            sessionId = services.sessionId,
            agentName = agentName,
            success = result.success,
            message = result.message
        ))
        
        return if (result.success) {
            ToolCallResult.Success(observation = result.message)
        } else {
            ToolCallResult.Success(observation = "Sub-agent failed: ${result.message}")
        }
    }
}
```

---

## 5. ExecutorAgent (Built-in)

```kotlin
/**
 * Executor: Grounds semantic intent to UI actions.
 * 
 * Designed for mobile-use: receives complete instruction,
 * interacts with current screen, returns result.
 */
object ExecutorAgent {
    val definition = AgentDefinition(
        name = "executor",
        description = "Execute UI actions on the current screen",
        systemPrompt = """
            You are an Executor agent. Your job is to accomplish the given query
            by interacting with the Android device.
            
            Rules:
            1. Read the query carefully - it contains your complete objective
            2. Look at the CURRENT screen and find the right element
            3. Execute the action (tap, type, scroll, etc.)
            4. Observe the result
            5. Use complete_task when done or if stuck
            
            Do NOT:
            - Ask for clarification (you won't get a response)
            - Make assumptions about previous screens
            - Take more than 10 actions
        """.trimIndent(),
        toolNames = listOf("mobile_action", "app_control", "complete_task"),
        maxTurns = 10,
        timeoutMs = 60_000
    )
}
```

---

## 6. Protocol Events

```kotlin
// Minimal sub-agent events
data class SubAgentStarted(
    override val sessionId: SessionId,
    override val timestamp: Long = System.currentTimeMillis(),
    val agentName: String,
    val query: String
) : AgentEvent

data class SubAgentActivity(
    override val sessionId: SessionId,
    override val timestamp: Long = System.currentTimeMillis(),
    val agentName: String,
    val activity: String
) : AgentEvent

data class SubAgentCompleted(
    override val sessionId: SessionId,
    override val timestamp: Long = System.currentTimeMillis(),
    val agentName: String,
    val success: Boolean,
    val message: String
) : AgentEvent
```

---

## 7. Integration Points

### ToolRegistry Setup
```kotlin
// When creating parent agent
val registry = AgentRegistry.createDefault(toolRegistry)
val delegateTool = DelegateTaskTool(registry, services, eventEmitter)
toolRegistry.register(delegateTool)
```

### System Prompt Addition
```kotlin
// Add to parent agent's system prompt
"""
## Available Sub-Agents
${registry.getDirectoryPrompt()}

Use delegate_task to have a sub-agent execute UI actions.
Provide complete, self-contained instructions.
"""
```

---

## Implementation Order

1. Add `AgentDefinition` data class
2. Add `AgentRegistry`
3. Add sub-agent events
4. Implement `SubAgentRunner`
5. Implement `DelegateTaskTool`
6. Add `ExecutorAgent.definition`
7. Wire up in `SessionServices`
8. Test end-to-end delegation

**Estimated effort**: 2-3 days

---

## What's NOT Included (KISS)

- ❌ Approval bridging (Phase 3)
- ❌ Nested delegation (out of scope)
- ❌ InputConfig/OutputConfig schemas
- ❌ Parallel sub-agents
- ❌ Agent-to-Agent protocol
