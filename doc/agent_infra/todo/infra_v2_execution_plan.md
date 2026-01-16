# Infrastructure V2 Execution Plan

**Status**: Implementation Guide  
**Author**: Engineering Team  
**Last Updated**: January 2026  
**Reference**: [infra_v2.md](./infra_v2.md)

---

## Overview

This document provides a detailed, step-by-step execution plan for migrating from infra_v1 (MobileAgent-v3 multi-agent) to infra_v2 (single ReAct agent). Each phase includes specific file changes, code references, and validation steps.

**Estimated Timeline**: 3-5 days for core implementation

---

## Phase 1: Create Agent Layer (Day 1)

### Goal
Create the new `agent/` package with `Agent` and `Turn` classes that implement a simple ReAct loop.

### 1.1 Create `agent/AgentConfig.kt`

**Location**: `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentConfig.kt`

**Reference**: Current `OrchestrationConfig` in `orchestration/AgentOrchestration.kt:100-115`

```kotlin
package com.moonkey.androidagent.agent

import com.moonkey.androidagent.protocol.SessionId

/**
 * Configuration for Agent execution.
 * 
 * Simplified from OrchestrationConfig - removes multi-agent specific fields.
 */
data class AgentConfig(
    /** The user's goal */
    val goal: String,
    
    /** Session ID for event emission */
    val sessionId: SessionId,
    
    /** Maximum number of turns before stopping */
    val maxTurns: Int = 50,
    
    /** Delay after action execution (for UI settling) */
    val uiSettleDelayMs: Long = 3000,
    
    /** Whether to enable debug logging */
    val debugMode: Boolean = false,
    
    /** System prompt template (null = use default) */
    val systemPrompt: String? = null
)
```

### 1.2 Create `agent/Turn.kt`

**Location**: `app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt`

**Reference**: 
- labmat's `turn.py:38-408` - Single LLM call encapsulation
- Current `MobileV3Orchestration.kt` executeTurn pattern

```kotlin
package com.moonkey.androidagent.agent

import com.moonkey.androidagent.data.llm.ChatMessage
import com.moonkey.androidagent.data.llm.LLMClient
import com.moonkey.androidagent.data.llm.Role
import com.moonkey.androidagent.domain.models.ScreenSnapshot
import com.moonkey.androidagent.infra.history.HistoryManager
import com.moonkey.androidagent.infra.history.ResponseItem
import com.moonkey.androidagent.infra.registry.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turn - Encapsulates a single ReAct iteration (LLM call + response parsing).
 * 
 * Reference: labmat's Turn class (turn.py)
 * 
 * A Turn handles:
 * 1. Building messages from history + current context
 * 2. Calling the LLM with tools
 * 3. Parsing the response (content and/or tool calls)
 */
class Turn(
    private val historyManager: HistoryManager,
    private val toolRegistry: ToolRegistry
) {
    /**
     * Execute one turn of the ReAct loop.
     * 
     * @param systemPrompt System prompt for the agent
     * @param userContext Current context (screen state, goal, etc.)
     * @return TurnResult with content and/or tool calls
     */
    suspend fun run(
        systemPrompt: String,
        userContext: String
    ): TurnResult {
        // 1. Build messages from history
        val messages = buildMessages(systemPrompt, userContext)
        
        // 2. Get tool definitions
        val tools = toolRegistry.getAllSchemas()
        
        // 3. Call LLM (TODO: Add tool calling support to LLMClient)
        val response = LLMClient.chat(messages)
        
        // 4. Parse response
        return parseResponse(response)
    }
    
    private fun buildMessages(systemPrompt: String, userContext: String): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        
        // System prompt
        messages.add(ChatMessage(Role.SYSTEM, systemPrompt))
        
        // History items converted to messages
        historyManager.forPrompt().forEach { item ->
            when (item) {
                is ResponseItem.Message -> {
                    val role = when (item.role) {
                        "user" -> Role.USER
                        "assistant" -> Role.ASSISTANT
                        else -> Role.SYSTEM
                    }
                    messages.add(ChatMessage(role, item.content))
                }
                is ResponseItem.FunctionCall -> {
                    // Include as assistant message with tool call
                    messages.add(ChatMessage(
                        Role.ASSISTANT,
                        "Tool call: ${item.name}(${item.arguments})"
                    ))
                }
                is ResponseItem.FunctionCallOutput -> {
                    // Include as function result
                    messages.add(ChatMessage(Role.USER, "Tool result: ${item.content}"))
                }
                else -> {} // Skip ghost snapshots
            }
        }
        
        // Current context as user message
        messages.add(ChatMessage(Role.USER, userContext))
        
        return messages
    }
    
    private fun parseResponse(response: String): TurnResult {
        // TODO: Parse for tool calls in JSON format
        // For now, return as content
        return TurnResult(
            content = response,
            toolCalls = emptyList(),
            isComplete = !response.contains("```tool") // Heuristic
        )
    }
}

/**
 * Result of a Turn execution.
 */
data class TurnResult(
    /** Text content from the LLM */
    val content: String?,
    
    /** Tool calls requested by the LLM */
    val toolCalls: List<ToolCallRequest>,
    
    /** Whether the agent considers the task complete */
    val isComplete: Boolean
)

/**
 * A tool call request from the LLM.
 */
data class ToolCallRequest(
    val id: String,
    val name: String,
    val arguments: JSONObject
)
```

### 1.3 Create `agent/Agent.kt`

**Location**: `app/src/main/kotlin/com/moonkey/androidagent/agent/Agent.kt`

**Reference**:
- labmat's `cycle.py:31-403` - AgentCycle execution
- Current `MobileV3Orchestration.kt:83-167` - Main loop structure

```kotlin
package com.moonkey.androidagent.agent

import android.util.Log
import com.moonkey.androidagent.data.perception.Perceptor
import com.moonkey.androidagent.domain.models.ScreenSnapshot
import com.moonkey.androidagent.infra.tools.SimpleToolRouterContext
import com.moonkey.androidagent.infra.tools.ToolCallResult
import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.TurnPhase
import com.moonkey.androidagent.session.SessionServices
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

/**
 * Agent - Single ReAct agent that executes goals.
 * 
 * Reference: labmat's AgentCycle (cycle.py)
 * 
 * Design principles:
 * - Simple ReAct loop: Perceive → Think → Act → Observe
 * - No multi-agent complexity (Manager/Executor/Reflector removed)
 * - Tool results include post-action screen observation
 * - Can spawn sub-agents (interface preserved for future)
 */
class Agent(
    private val config: AgentConfig,
    private val services: SessionServices,
    private val eventEmitter: suspend (AgentEvent) -> Unit,
    private val cancellationSignal: CompletableDeferred<AgentStopReason>
) {
    companion object {
        private const val TAG = "Agent"
    }
    
    // State
    private var turnCount = 0
    private val pauseState = MutableStateFlow(false)
    private var stopRequested = false
    
    /**
     * Run the agent until goal achieved, max turns, or stopped.
     */
    suspend fun run(): AgentStopReason {
        Log.i(TAG, "Starting agent for goal: ${config.goal}")
        emitStatus("🚀 Starting agent...")
        
        // Add initial user message to history
        services.historyManager.addItem(
            com.moonkey.androidagent.infra.history.ResponseItem.Message(
                role = "user",
                content = "Goal: ${config.goal}"
            )
        )
        
        while (shouldContinue()) {
            // Check pause
            if (pauseState.value) {
                pauseState.first { !it }
            }
            
            // Check max turns
            if (turnCount >= config.maxTurns) {
                Log.w(TAG, "Max turns (${config.maxTurns}) reached")
                return AgentStopReason.MaxTurnsReached
            }
            
            // Execute one turn
            val result = executeTurn()
            
            when (result) {
                is TurnOutcome.Continue -> {
                    delay(config.uiSettleDelayMs)
                }
                is TurnOutcome.Complete -> {
                    emitStatus("✅ Goal achieved!")
                    return AgentStopReason.GoalAchieved
                }
                is TurnOutcome.Error -> {
                    if (!result.recoverable) {
                        emitStatus("❌ Error: ${result.message}")
                        return AgentStopReason.Error(result.message)
                    }
                    // Recoverable error - continue
                    delay(config.uiSettleDelayMs)
                }
                TurnOutcome.Cancelled -> {
                    return AgentStopReason.UserRequested
                }
            }
        }
        
        return if (stopRequested) AgentStopReason.UserRequested else AgentStopReason.GoalAchieved
    }
    
    /**
     * Execute a single turn of the ReAct loop.
     */
    private suspend fun executeTurn(): TurnOutcome {
        turnCount++
        val turnId = "turn-$turnCount"
        Log.d(TAG, "=== TURN $turnCount START ===")
        
        try {
            // 1. PERCEPTION: Capture screen
            emitTurnPhase(turnId, TurnPhase.PERCEPTION)
            emitStatus("👀 Scanning screen...")
            
            val snapshot = services.platform.captureScreen()
            emitScreenCaptured(snapshot)
            
            // Check cancellation
            if (cancellationSignal.isCompleted || stopRequested) {
                return TurnOutcome.Cancelled
            }
            
            // 2. THINK: Call LLM
            emitTurnPhase(turnId, TurnPhase.EXECUTION) // Using EXECUTION for think+act
            emitStatus("🧠 Thinking...")
            
            val turn = Turn(services.historyManager, services.toolRegistry)
            val systemPrompt = buildSystemPrompt()
            val userContext = buildUserContext(snapshot)
            
            val turnResult = turn.run(systemPrompt, userContext)
            
            // Record assistant response in history
            if (turnResult.content != null) {
                services.historyManager.addItem(
                    com.moonkey.androidagent.infra.history.ResponseItem.Message(
                        role = "assistant",
                        content = turnResult.content
                    )
                )
            }
            
            // 3. ACT: Execute tool calls
            if (turnResult.toolCalls.isNotEmpty()) {
                emitStatus("💡 Executing actions...")
                
                for (toolCall in turnResult.toolCalls) {
                    // Record tool call in history
                    services.historyManager.addItem(
                        com.moonkey.androidagent.infra.history.ResponseItem.FunctionCall(
                            id = toolCall.id,
                            name = toolCall.name,
                            arguments = toolCall.arguments
                        )
                    )
                    
                    // Execute via ToolRouter
                    val context = SimpleToolRouterContext(
                        platform = services.platform,
                        currentSnapshot = snapshot
                    )
                    
                    val result = services.toolRouter.execute(
                        toolName = toolCall.name,
                        params = toolCall.arguments,
                        context = context,
                        onApprovalRequired = { details ->
                            eventEmitter(AgentEvent.ApprovalRequired(
                                sessionId = config.sessionId,
                                timestamp = System.currentTimeMillis(),
                                actionId = toolCall.id,
                                toolName = toolCall.name,
                                args = details.args,
                                description = details.description,
                                riskLevel = details.riskLevel
                            ))
                        }
                    )
                    
                    // 4. OBSERVE: Capture post-action screen
                    val observation = captureObservation()
                    
                    // Record tool result with observation in history
                    services.historyManager.addItem(
                        com.moonkey.androidagent.infra.history.ResponseItem.FunctionCallOutput(
                            callId = toolCall.id,
                            content = formatToolResult(result, observation),
                            success = result is ToolCallResult.Success
                        )
                    )
                    
                    emitStatus("✓ ${toolCall.name} executed")
                }
            }
            
            // Check if complete
            if (turnResult.isComplete) {
                return TurnOutcome.Complete(turnResult.content ?: "Goal achieved")
            }
            
            return TurnOutcome.Continue
            
        } catch (e: Exception) {
            Log.e(TAG, "Turn execution failed", e)
            return TurnOutcome.Error(e.message ?: "Unknown error", recoverable = true)
        }
    }
    
    /**
     * Capture post-action observation (screen state).
     */
    private suspend fun captureObservation(): Observation {
        delay(500) // Brief delay for UI to settle
        val snapshot = services.platform.captureScreen()
        val accessibilityTree = Perceptor.toPromptJson(snapshot)
        return Observation.ScreenState(accessibilityTree)
    }
    
    /**
     * Format tool result with observation for history.
     */
    private fun formatToolResult(result: ToolCallResult, observation: Observation): String {
        val resultText = when (result) {
            is ToolCallResult.Success -> "Success: ${result.output}"
            is ToolCallResult.Error -> "Error: ${result.message}"
            is ToolCallResult.Cancelled -> "Cancelled: ${result.reason}"
        }
        
        val observationText = when (observation) {
            is Observation.ScreenState -> "Screen after action:\n${observation.accessibilityTree}"
            is Observation.TextOutput -> observation.content
        }
        
        return "$resultText\n\n$observationText"
    }
    
    private fun buildSystemPrompt(): String {
        return config.systemPrompt ?: DEFAULT_SYSTEM_PROMPT
    }
    
    private fun buildUserContext(snapshot: ScreenSnapshot): String {
        val screenJson = Perceptor.toPromptJson(snapshot)
        return """
            Current screen state:
            ```json
            $screenJson
            ```
            
            Available tools: ${services.toolRegistry.getNames().joinToString(", ")}
            
            What action should I take next to achieve the goal?
        """.trimIndent()
    }
    
    private fun shouldContinue(): Boolean {
        return !stopRequested && !cancellationSignal.isCompleted
    }
    
    // === Lifecycle Methods ===
    
    suspend fun pause() {
        pauseState.value = true
        emitStatus("⏸️ Paused")
    }
    
    suspend fun resume() {
        pauseState.value = false
        emitStatus("▶️ Resuming...")
    }
    
    fun stop() {
        stopRequested = true
        pauseState.value = false
    }
    
    // === Event Emission ===
    
    private suspend fun emitStatus(status: String) {
        eventEmitter(AgentEvent.StatusUpdate(
            sessionId = config.sessionId,
            timestamp = System.currentTimeMillis(),
            status = status
        ))
    }
    
    private suspend fun emitTurnPhase(turnId: String, phase: TurnPhase) {
        eventEmitter(AgentEvent.TurnStarted(
            sessionId = config.sessionId,
            timestamp = System.currentTimeMillis(),
            turnId = turnId,
            turnNumber = turnCount,
            phase = phase
        ))
    }
    
    private suspend fun emitScreenCaptured(snapshot: ScreenSnapshot) {
        eventEmitter(AgentEvent.ScreenCaptured(
            sessionId = config.sessionId,
            timestamp = System.currentTimeMillis(),
            elementCount = snapshot.elements.size,
            packageName = services.platform.getCurrentPackageName(),
            activityName = null
        ))
    }
    
    companion object {
        private val DEFAULT_SYSTEM_PROMPT = """
            You are an Android automation agent. You can interact with the device using tools.
            
            Your task is to achieve the user's goal by:
            1. Observing the current screen state
            2. Deciding what action to take
            3. Executing the action using available tools
            4. Observing the result and continuing until done
            
            When the goal is achieved, respond with "DONE: [reason]" without any tool calls.
            
            Available tools will click, type, scroll, etc. on UI elements identified by their index.
        """.trimIndent()
    }
}

/**
 * Observation - Post-action state captured after tool execution.
 */
sealed class Observation {
    data class ScreenState(val accessibilityTree: String) : Observation()
    data class TextOutput(val content: String) : Observation()
}

/**
 * Outcome of a single turn.
 */
sealed class TurnOutcome {
    object Continue : TurnOutcome()
    data class Complete(val message: String) : TurnOutcome()
    data class Error(val message: String, val recoverable: Boolean) : TurnOutcome()
    object Cancelled : TurnOutcome()
}

/**
 * Reason why the agent stopped.
 */
sealed class AgentStopReason {
    object GoalAchieved : AgentStopReason()
    object UserRequested : AgentStopReason()
    object MaxTurnsReached : AgentStopReason()
    data class Error(val message: String) : AgentStopReason()
}
```

### 1.4 Create `agent/AgentSource.kt` (Future Multi-Agent Interface)

**Location**: `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentSource.kt`

**Reference**: Codex's `SessionSource` in `protocol/src/items.rs`

```kotlin
package com.moonkey.androidagent.agent

/**
 * AgentSource - Identifies whether an agent is primary or delegated.
 * 
 * Reference: Codex's SessionSource::SubAgent pattern
 * 
 * This is a placeholder for future multi-agent support where:
 * - Primary agents are started by user input
 * - SubAgents are spawned by other agents for delegation
 */
enum class AgentSource {
    /** Agent started directly by user */
    Primary,
    
    /** Agent spawned by another agent for delegation */
    SubAgent
}
```

### Validation for Phase 1
- [ ] All new files compile without errors
- [ ] Run `./gradlew compileDebugKotlin` passes
- [ ] No changes to existing files yet

---

## Phase 2: Integrate Agent with AgentSession (Day 2)

### Goal
Update `AgentSession` to use the new `Agent` class instead of `AgentOrchestration`.

### 2.1 Update `session/AgentSession.kt`

**Current code to modify**: Lines 142-256 in `AgentSession.kt`

**Changes**:
1. Replace `AgentOrchestration` with `Agent`
2. Remove `OrchestrationFactory` dependency
3. Update `startOrchestration()` to `startAgent()`

```kotlin
// BEFORE (current):
private var orchestration: AgentOrchestration? = null
private var orchestrationJob: Job? = null

// AFTER:
private var agent: Agent? = null
private var agentJob: Job? = null
```

**Key method changes**:

```kotlin
// Replace startOrchestration() with:
private fun startAgent(goal: String) {
    val signal = CompletableDeferred<AgentStopReason>()
    
    val agentConfig = AgentConfig(
        goal = goal,
        sessionId = sessionId,
        maxTurns = config.maxTurns,
        uiSettleDelayMs = config.actionDelayMs,
        debugMode = config.debugMode
    )
    
    val newAgent = Agent(
        config = agentConfig,
        services = services,
        eventEmitter = { event -> emit(event) },
        cancellationSignal = signal
    )
    agent = newAgent
    
    agentJob = scope.launch {
        try {
            val result = newAgent.run()
            handleAgentComplete(result)
        } catch (e: CancellationException) {
            handleAgentComplete(AgentStopReason.UserRequested)
        } catch (e: Exception) {
            handleAgentComplete(AgentStopReason.Error(e.message ?: "Unknown error"))
        }
    }
}
```

### 2.2 Update `session/SessionServices.kt`

**Changes**:
1. Remove `AgentRegistry` (no longer needed without multi-agent)
2. Keep all other services

```kotlin
// BEFORE:
data class SessionServices(
    val toolRegistry: ToolRegistry,
    val toolRouter: ToolRouter,
    val agentRegistry: AgentRegistry,  // REMOVE THIS
    val historyManager: HistoryManager,
    val policyEngine: PolicyEngine,
    val platform: AndroidPlatform,
    val config: SessionConfig
)

// AFTER:
data class SessionServices(
    val toolRegistry: ToolRegistry,
    val toolRouter: ToolRouter,
    val historyManager: HistoryManager,
    val policyEngine: PolicyEngine,
    val platform: AndroidPlatform,
    val config: SessionConfig
)
```

### 2.3 Update Imports

In `AgentSession.kt`, update imports:
```kotlin
// REMOVE:
import com.moonkey.androidagent.orchestration.AgentOrchestration
import com.moonkey.androidagent.orchestration.CancellationReason
import com.moonkey.androidagent.orchestration.CancellationSignal
import com.moonkey.androidagent.orchestration.MobileV3OrchestrationFactory
import com.moonkey.androidagent.orchestration.OrchestrationConfig
import com.moonkey.androidagent.orchestration.OrchestrationFactory

// ADD:
import com.moonkey.androidagent.agent.Agent
import com.moonkey.androidagent.agent.AgentConfig
import com.moonkey.androidagent.agent.AgentStopReason
```

### Validation for Phase 2
- [ ] `AgentSession` compiles with new `Agent` integration
- [ ] App can be built and installed
- [ ] Basic goal execution works (even if LLM tool calling isn't perfect yet)

---

## Phase 3: Update Tool Interface for Observation (Day 2-3)

### Goal
Modify tool execution to return post-action screen state (Observation).

### 3.1 Update `infra/tools/ToolSpec.kt`

Add new types for observation-aware results:

```kotlin
/**
 * Extended tool result that includes post-action observation.
 */
data class ToolResultWithObservation(
    val success: Boolean,
    val message: String?,
    val observation: ToolObservation?
)

/**
 * Observation captured after tool execution.
 */
sealed interface ToolObservation {
    /** Screen state after action */
    data class ScreenState(
        val accessibilityTree: String,
        val elementCount: Int
    ) : ToolObservation
    
    /** Text output for non-UI tools */
    data class TextOutput(val content: String) : ToolObservation
}
```

### 3.2 Update `tools/base/BaseTool.kt`

Modify `BaseToolInvocation.execute()` to capture observation:

```kotlin
override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
    if (uiAction == null) {
        return ToolExecutionResult.Failure("Failed to create UI action")
    }
    
    if (context.isCancelled()) {
        return ToolExecutionResult.Cancelled("Cancelled before execution")
    }
    
    // Execute the action
    val result = context.platform.performAction(uiAction, context.currentSnapshot)
    
    // Capture observation AFTER action (new!)
    val observation = capturePostActionObservation(context)
    
    return when (result) {
        is ActionResult.Success -> ToolExecutionResult.Success(
            output = result.message,
            data = observation  // Include observation in data field
        )
        is ActionResult.Failure -> ToolExecutionResult.Failure(result.reason, result.exception)
        is ActionResult.ElementNotFound -> ToolExecutionResult.Failure(
            "Element not found: index ${result.elementIndex}"
        )
        is ActionResult.Cancelled -> ToolExecutionResult.Cancelled(result.reason)
    }
}

private suspend fun capturePostActionObservation(context: ToolExecutionContext): ToolObservation? {
    return try {
        // Brief delay for UI to settle
        kotlinx.coroutines.delay(300)
        
        val snapshot = context.platform.captureScreen()
        val tree = com.moonkey.androidagent.data.perception.Perceptor.toPromptJson(snapshot)
        
        ToolObservation.ScreenState(
            accessibilityTree = tree,
            elementCount = snapshot.elements.size
        )
    } catch (e: Exception) {
        null // Return null if capture fails
    }
}
```

### 3.3 Update Tool Implementations (if needed)

Most tools inherit from `BaseTool` and will automatically get observation support.
Custom tools may need individual updates.

### Validation for Phase 3
- [ ] Tool execution returns observation in result
- [ ] Observation appears in history manager
- [ ] LLM receives post-action screen state

---

## Phase 4: Clean Up Deprecated Code (Day 3-4)

### Goal
Remove all MobileAgent-v3 specific code that's no longer needed.

### 4.1 Files to DELETE

| File | Reason |
|------|--------|
| `orchestration/AgentOrchestration.kt` | Replaced by Agent |
| `orchestration/OrchestrationFactory.kt` | No longer needed |
| `orchestration/v3/MobileV3Orchestration.kt` | Replaced by Agent |
| `orchestration/v3/SessionExecutionState.kt` | State now in Agent |
| `domain/agents/Agent.kt` | Old interface, conflicts with new Agent |
| `domain/agents/Manager.kt` | Multi-agent specific |
| `domain/agents/Executor.kt` | Multi-agent specific |
| `domain/agents/Reflector.kt` | Multi-agent specific |
| `domain/state/InfoPool.kt` | Multi-agent state sharing |
| `infra/registry/AgentRegistry.kt` | No longer needed |
| `platform/mock/MockPlatform.kt` | Unused |

### 4.2 Delete Commands

```bash
# Delete orchestration package
rm -rf app/src/main/kotlin/com/moonkey/androidagent/orchestration/

# Delete domain/agents (old multi-agent)
rm -rf app/src/main/kotlin/com/moonkey/androidagent/domain/agents/

# Delete InfoPool
rm app/src/main/kotlin/com/moonkey/androidagent/domain/state/InfoPool.kt

# Delete AgentRegistry
rm app/src/main/kotlin/com/moonkey/androidagent/infra/registry/AgentRegistry.kt

# Delete MockPlatform
rm -rf app/src/main/kotlin/com/moonkey/androidagent/platform/mock/
```

### 4.3 Update Remaining References

After deletion, fix any compile errors by removing imports and references:

1. **`SessionServices.kt`**: Remove `AgentRegistry` import and field
2. **`AgentSession.kt`**: Already updated in Phase 2
3. **`Perceptor.kt`**: May have references to old models - update if needed

### Validation for Phase 4
- [ ] All deleted files are gone
- [ ] Project compiles cleanly
- [ ] No dangling imports

---

## Phase 5: Update LLMClient for Tool Calling (Day 4)

### Goal
Enable proper tool calling in `LLMClient` to support the ReAct loop.

### 5.1 Update `data/llm/LLMClient.kt`

**Current code**: `LLMClient.kt:89-168`

**Add tool calling support**:

```kotlin
/**
 * Chat with tool calling support.
 */
suspend fun chatWithTools(
    messages: List<ChatMessage>,
    tools: List<JSONObject>
): ChatResponse = withContext(Dispatchers.IO) {
    if (!isInitialized || client == null) {
        throw IllegalStateException("LLMClient not initialized")
    }
    
    // Build request with tools
    val builder = ChatCompletionCreateParams.builder()
        .model(ChatModel.GPT_4O)
    
    messages.forEach { msg ->
        when (msg.role) {
            Role.SYSTEM -> builder.addSystemMessage(msg.content)
            Role.USER -> builder.addUserMessage(msg.content)
            Role.ASSISTANT -> builder.addAssistantMessage(msg.content)
        }
    }
    
    // Add tools (convert JSONObject to OpenAI format)
    if (tools.isNotEmpty()) {
        // TODO: Convert tools to OpenAI function format
        // This requires proper OpenAI SDK integration
    }
    
    val response = client!!.chat().completions().create(builder.build())
    
    // Parse response including tool calls
    val choice = response.choices().firstOrNull()
        ?: throw RuntimeException("No choices in response")
    
    ChatResponse(
        content = choice.message().content().orElse(null),
        toolCalls = parseToolCalls(choice)  // TODO: Implement
    )
}

data class ChatResponse(
    val content: String?,
    val toolCalls: List<ToolCallRequest>
)
```

### 5.2 Alternative: Use JSON-based Tool Format

If direct tool calling is complex, use a JSON format that the model understands:

```kotlin
// In system prompt:
"""
When you need to use a tool, respond with:
```tool
{"name": "click", "arguments": {"element_index": 5}}
```

Available tools and their parameters:
- click(element_index: int) - Click on element
- type(element_index: int, text: string) - Type text
- scroll(direction: string) - Scroll up/down/left/right
- back() - Press back button
- wait(ms: int) - Wait for milliseconds
"""
```

Then parse the response in `Turn.parseResponse()`:

```kotlin
private fun parseResponse(response: String): TurnResult {
    val toolCalls = mutableListOf<ToolCallRequest>()
    
    // Extract tool calls from ```tool blocks
    val toolPattern = Regex("```tool\\s*\\n([\\s\\S]*?)\\n```")
    toolPattern.findAll(response).forEach { match ->
        try {
            val json = JSONObject(match.groupValues[1])
            toolCalls.add(ToolCallRequest(
                id = java.util.UUID.randomUUID().toString().take(8),
                name = json.getString("name"),
                arguments = json.getJSONObject("arguments")
            ))
        } catch (e: Exception) {
            Log.w("Turn", "Failed to parse tool call: ${e.message}")
        }
    }
    
    val isComplete = toolCalls.isEmpty() && 
        (response.contains("DONE:") || response.contains("goal achieved", ignoreCase = true))
    
    return TurnResult(
        content = response,
        toolCalls = toolCalls,
        isComplete = isComplete
    )
}
```

### Validation for Phase 5
- [ ] LLM returns tool calls in parseable format
- [ ] Tools execute correctly based on LLM decisions
- [ ] ReAct loop completes simple tasks

---

## Phase 6: Testing and Validation (Day 4-5)

### 6.1 Test Scenarios

| Test | Description | Expected |
|------|-------------|----------|
| Simple Click | "Click on Settings" | Agent finds and clicks Settings |
| Type Text | "Type hello in search box" | Agent types text |
| Multi-Step | "Open Settings and turn on WiFi" | Agent completes multi-step task |
| Error Recovery | Invalid element index | Agent handles gracefully |
| Max Turns | Run until limit | Agent stops at max turns |
| User Pause/Resume | Pause mid-task | Agent pauses and resumes correctly |

### 6.2 Debug Logging

Ensure comprehensive logging is enabled in Agent:

```kotlin
// Add to Agent.executeTurn():
Log.d(TAG, "Turn $turnCount: Screen has ${snapshot.elements.size} elements")
Log.d(TAG, "Turn $turnCount: LLM response: ${turnResult.content?.take(200)}...")
Log.d(TAG, "Turn $turnCount: Tool calls: ${turnResult.toolCalls.map { it.name }}")
```

### 6.3 Manual Testing Checklist

- [ ] Install app on device
- [ ] Enable accessibility service
- [ ] Start agent with simple goal
- [ ] Verify screen capture works
- [ ] Verify LLM is called
- [ ] Verify tool execution
- [ ] Verify observation in history
- [ ] Test pause/resume
- [ ] Test stop

---

## Summary: File Changes

### New Files (Create)
```
agent/
├── Agent.kt           # Main agent class
├── AgentConfig.kt     # Configuration
├── AgentSource.kt     # Primary/SubAgent enum
└── Turn.kt            # Single LLM turn
```

### Modified Files
```
session/AgentSession.kt      # Use Agent instead of Orchestration
session/SessionServices.kt   # Remove AgentRegistry
tools/base/BaseTool.kt       # Add observation capture
data/llm/LLMClient.kt        # Add tool calling support
```

### Deleted Files
```
orchestration/               # Entire package
domain/agents/               # Old agent interfaces
domain/state/InfoPool.kt     # Multi-agent state
infra/registry/AgentRegistry.kt
platform/mock/MockPlatform.kt
```

---

## Appendix: Reference Code Mapping

| V2 Component | labmat Reference | Codex Reference |
|--------------|------------------|-----------------|
| `Agent.kt` | `cycle.py:AgentCycle` | `codex.rs:Codex` |
| `Turn.kt` | `turn.py:Turn` | `turn.rs:TurnContext` |
| `AgentConfig` | N/A (uses dict) | `protocol.rs:SessionConfiguration` |
| `HistoryManager` | `history.py:HistoryManager` | `history.rs:ContextManager` |
| `ToolRouter` | N/A (inline in cycle) | N/A (inline) |
| `AgentStopReason` | N/A (exceptions) | `protocol.rs:TurnAbortReason` |

