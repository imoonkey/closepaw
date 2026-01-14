package com.moonkey.androidagent.infra.registry

import android.util.Log

/**
 * AgentRegistry - Manages agent definitions (not instances).
 * 
 * This is an infrastructure component (stable) that stores agent DEFINITIONS.
 * Agent instances are created by AgentFactory during orchestration.
 * 
 * Pattern from Gemini CLI's AgentRegistry:
 * - Discovers and loads agent definitions from configuration
 * - Supports both local and remote agent definitions
 * - Provides metadata for tool descriptions and system prompts
 * 
 * For Mobile-Agent-v3:
 * - Registers Manager, Executor, Reflector as built-in LocalAgentDefinitions
 * - Orchestration creates instances from definitions as needed
 */
class AgentRegistry {
    
    companion object {
        private const val TAG = "AgentRegistry"
    }
    
    private val agents = mutableMapOf<String, AgentDefinition>()
    private var initialized = false
    
    /**
     * Initialize the registry with built-in agents.
     * 
     * This should be called once during session setup.
     */
    suspend fun initialize() {
        if (initialized) {
            Log.w(TAG, "AgentRegistry already initialized")
            return
        }
        
        loadBuiltInAgents()
        initialized = true
        Log.i(TAG, "AgentRegistry initialized with ${agents.size} agents")
    }
    
    /**
     * Load the built-in Mobile-Agent-v3 agents.
     */
    private fun loadBuiltInAgents() {
        // Manager Agent - High-level planning and goal decomposition
        register(AgentDefinition.Local(
            name = "manager",
            description = "High-level planning and goal decomposition. Breaks down user instructions into actionable steps.",
            capabilities = listOf(AgentCapability.PLANNING),
            modelConfig = AgentModelConfig(model = "inherit"),
            systemPrompt = ManagerPrompts.SYSTEM,
            tools = emptyList()  // Manager doesn't use tools directly
        ))
        
        // Executor Agent - Action selection and tool invocation
        register(AgentDefinition.Local(
            name = "executor",
            description = "Action selection and tool invocation. Determines the next atomic action to perform.",
            capabilities = listOf(AgentCapability.EXECUTION),
            modelConfig = AgentModelConfig(model = "inherit"),
            systemPrompt = ExecutorPrompts.SYSTEM,
            tools = listOf("click", "type", "scroll", "swipe", "back", "wait")
        ))
        
        // Reflector Agent - Outcome verification and error detection
        register(AgentDefinition.Local(
            name = "reflector",
            description = "Outcome verification and error detection. Validates if actions had the expected effect.",
            capabilities = listOf(AgentCapability.REFLECTION),
            modelConfig = AgentModelConfig(model = "inherit"),
            systemPrompt = ReflectorPrompts.SYSTEM,
            tools = emptyList()
        ))
        
        Log.d(TAG, "Loaded ${agents.size} built-in agents")
    }
    
    /**
     * Register an agent definition.
     * 
     * @param definition The agent definition to register
     */
    fun register(definition: AgentDefinition) {
        if (agents.containsKey(definition.name)) {
            Log.w(TAG, "Overwriting existing agent: ${definition.name}")
        }
        agents[definition.name] = definition
        Log.d(TAG, "Registered agent: ${definition.name}")
    }
    
    /**
     * Unregister an agent by name.
     * 
     * @param name The agent name
     * @return true if agent was removed, false if not found
     */
    fun unregister(name: String): Boolean {
        val removed = agents.remove(name) != null
        if (removed) {
            Log.d(TAG, "Unregistered agent: $name")
        }
        return removed
    }
    
    /**
     * Get an agent definition by name.
     * 
     * @param name The agent name
     * @return The agent definition or null if not found
     */
    fun getDefinition(name: String): AgentDefinition? = agents[name]
    
    /**
     * Get all registered agent definitions.
     */
    fun getAllDefinitions(): List<AgentDefinition> = agents.values.toList()
    
    /**
     * Get all registered agent names.
     */
    fun getNames(): Set<String> = agents.keys.toSet()
    
    /**
     * Check if an agent is registered.
     */
    fun contains(name: String): Boolean = agents.containsKey(name)
    
    /**
     * Get the number of registered agents.
     */
    fun size(): Int = agents.size
    
    /**
     * Generate a "phone book" context for system prompts.
     * 
     * This lists available agents for reference in multi-agent scenarios.
     */
    fun getDirectoryContext(): String {
        return buildString {
            appendLine("## Available Agents")
            agents.values.forEach { agent ->
                appendLine("- **${agent.name}**: ${agent.description}")
                if (agent.capabilities.isNotEmpty()) {
                    appendLine("  Capabilities: ${agent.capabilities.joinToString(", ")}")
                }
            }
        }
    }
    
    /**
     * Get agents by capability.
     */
    fun getAgentsByCapability(capability: AgentCapability): List<AgentDefinition> {
        return agents.values.filter { capability in it.capabilities }
    }
    
    /**
     * Get a summary of registered agents.
     */
    fun getSummary(): String {
        return buildString {
            appendLine("AgentRegistry (${agents.size} agents):")
            agents.values.forEach { agent ->
                appendLine("  - ${agent.name} [${agent::class.simpleName}]")
                appendLine("    ${agent.description}")
            }
        }
    }
}

/**
 * AgentDefinition - Specification for an agent without implementing it.
 * 
 * Defines what an agent CAN do, not how it does it.
 * Actual agent instances are created by AgentFactory using these definitions.
 */
sealed class AgentDefinition {
    /** Unique name of the agent */
    abstract val name: String
    
    /** Human-readable description */
    abstract val description: String
    
    /** Capabilities this agent has */
    abstract val capabilities: List<AgentCapability>
    
    /**
     * Local agent that runs within our process.
     * Uses the LLM directly for reasoning.
     */
    data class Local(
        override val name: String,
        override val description: String,
        override val capabilities: List<AgentCapability>,
        val modelConfig: AgentModelConfig,
        val systemPrompt: String,
        val tools: List<String>  // Tool names this agent can use
    ) : AgentDefinition()
    
    /**
     * Remote agent accessed via network.
     * Delegates to an external service (A2A, MCP, etc.)
     * 
     * Note: Not yet implemented - for future extensibility.
     */
    data class Remote(
        override val name: String,
        override val description: String,
        override val capabilities: List<AgentCapability>,
        val endpoint: String,
        val protocol: RemoteProtocol = RemoteProtocol.A2A,
        val authConfig: AuthConfig? = null
    ) : AgentDefinition()
}

/**
 * AgentModelConfig - Model configuration for an agent.
 */
data class AgentModelConfig(
    /** Model to use. "inherit" means use session's model. */
    val model: String = "inherit",
    
    /** Temperature for sampling (null = model default) */
    val temperature: Float? = null,
    
    /** Max tokens for response (null = model default) */
    val maxTokens: Int? = null,
    
    /** Thinking budget for reasoning models (null = not applicable) */
    val thinkingBudget: Int? = null
)

/**
 * AgentCapability - What an agent can do.
 */
enum class AgentCapability {
    /** Can create high-level plans */
    PLANNING,
    
    /** Can select and execute actions */
    EXECUTION,
    
    /** Can verify outcomes and detect errors */
    REFLECTION,
    
    /** Can manage long-term memory */
    MEMORY,
    
    /** Can search for information */
    SEARCH,
    
    /** Can coordinate other agents */
    COORDINATION
}

/**
 * RemoteProtocol - Protocol for remote agent communication.
 */
enum class RemoteProtocol {
    /** Agent-to-Agent protocol */
    A2A,
    
    /** Model Context Protocol */
    MCP,
    
    /** HTTP REST API */
    REST
}

/**
 * AuthConfig - Authentication configuration for remote agents.
 */
data class AuthConfig(
    val type: AuthType,
    val credentials: String  // Could be API key, token, etc.
)

enum class AuthType {
    NONE,
    API_KEY,
    BEARER_TOKEN,
    OAUTH2
}

// ===== Built-in Agent Prompts =====

/**
 * System prompts for the Manager agent.
 */
object ManagerPrompts {
    val SYSTEM = """
        You are a Manager Agent for an Android phone.
        Your goal is to break down user instructions into a high-level plan.
        
        Output strictly in JSON format:
        {
            "thought": "Your reasoning here",
            "plan": "1. step one\n2. step two...",
            "completed_subgoal": "What has been done so far (e.g. 'Opened Settings')"
        }
        
        Guidelines:
        1. If the task is finished, set plan to "Finished".
        2. If starting, list all steps.
        3. Use the Screen Context to verify if steps are completed.
    """.trimIndent()
}

/**
 * System prompts for the Executor agent.
 */
object ExecutorPrompts {
    val SYSTEM = """
        You are an Executor Agent for Android.
        Your goal is to perform the NEXT ATOMIC ACTION to fulfill the Current Subgoal.
        
        Available Actions:
        - {"action": "click", "element_id": 12, "reason": "..."}
        - {"action": "type", "element_id": 12, "text": "hello", "reason": "..."}
        - {"action": "scroll", "direction": "up/down/left/right", "reason": "..."}
        - {"action": "system", "button": "back/home/enter", "reason": "..."}
        - {"action": "answer", "text": "final answer", "reason": "..."}  (If task requests an answer)
        - {"action": "done", "reason": "..."} (If subgoal is visibly finished)
        
        Rules:
        1. Use 'element_id' from Screen Context.
        2. To scroll down (to see bottom), direction="down".
        3. Output strictly valid JSON.
    """.trimIndent()
}

/**
 * System prompts for the Reflector agent.
 */
object ReflectorPrompts {
    val SYSTEM = """
        You are a Reflector Agent.
        Your job is to compare the Before and After screen states to verify if the Last Action was successful.
        
        Output strictly in JSON:
        {
            "outcome": "A", // A = Success (State changed as expected)
            "reason": "..."
        }
        or
        {
            "outcome": "B", // B = Failed (Wrong page/state, need backtrack)
            "reason": "..."
        }
        or
        {
            "outcome": "C", // C = Failed (No change detected)
            "reason": "..."
        }
    """.trimIndent()
}

