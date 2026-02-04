package com.moonkey.androidagent.tool

import org.json.JSONObject

/**
 * ToolSpec - Specification for a tool that can be invoked by the agent.
 * 
 * Tools are declarative specifications that describe:
 * - What the tool does (name, description)
 * - What parameters it accepts (schema)
 * - How to validate inputs
 * - How to create an executable invocation
 * 
 * Pattern inspired by Gemini CLI's DeclarativeTool.
 */
interface ToolSpec {
    /** Unique name of the tool (used in LLM function calling) */
    val name: String
    
    /** Human-readable description for the LLM */
    val description: String
    
    /** JSON Schema for the tool's parameters */
    val parameterSchema: JSONObject
    
    /**
     * Validate the parameters before creating an invocation.
     * 
     * @param params The parameters to validate
     * @return ValidationResult indicating success or failure with details
     */
    fun validate(params: JSONObject): ValidationResult
    
    /**
     * Create an executable invocation from validated parameters.
     * 
     * @param params The validated parameters
     * @return A ToolInvocation ready to execute
     */
    fun createInvocation(params: JSONObject): ToolInvocation
    
    /**
     * Generate an OpenAI-compatible function schema for this tool.
     */
    fun toFunctionSchema(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", parameterSchema)
            })
        }
    }
}

/**
 * ValidationResult - Result of validating tool parameters.
 */
sealed interface ValidationResult {
    /** Parameters are valid */
    data object Valid : ValidationResult
    
    /** Parameters are invalid */
    data class Invalid(
        val errors: List<String>
    ) : ValidationResult {
        constructor(error: String) : this(listOf(error))
    }
    
    fun isValid(): Boolean = this is Valid
}

/**
 * ToolInvocation - A validated, ready-to-execute tool call.
 * 
 * This represents a tool call that has passed validation and is ready
 * to be executed. It captures all the information needed to:
 * - Describe what will happen (for approval UI)
 * - Execute the action
 * - Handle cancellation
 */
interface ToolInvocation {
    /** The tool this invocation is for */
    val toolName: String
    
    /** The parameters for this invocation */
    val params: JSONObject
    
    /**
     * Get a human-readable description of what this invocation will do.
     * Used for approval dialogs and logging.
     */
    fun getDescription(): String
    
    /**
     * Execute the tool invocation.
     * 
     * @param context Execution context with platform access
     * @return The result of execution
     */
    suspend fun execute(context: ToolExecutionContext): ToolExecutionResult
}

/**
 * ToolExecutionContext - Context provided to tool invocations during execution.
 */
interface ToolExecutionContext {
    /**
     * Call id assigned by ToolRouter, useful for cross-component correlation.
     * Nullable for tests or custom execution contexts.
     */
    val callId: String? get() = null

    /** Access to platform operations */
    val platform: com.moonkey.androidagent.platform.AndroidPlatform
    
    /** Current screen snapshot (if available) */
    val currentSnapshot: com.moonkey.androidagent.model.ScreenSnapshot?
    
    /** Check if execution should be cancelled */
    fun isCancelled(): Boolean
}

/**
 * ToolExecutionResult - Result of executing a tool.
 */
sealed interface ToolExecutionResult {
    /** Execution succeeded */
    data class Success(
        val output: String,
        val data: Any? = null,
        val observation: ToolObservation? = null
    ) : ToolExecutionResult
    
    /** Execution failed */
    data class Failure(
        val error: String,
        val exception: Throwable? = null
    ) : ToolExecutionResult
    
    /** Execution was cancelled */
    data class Cancelled(
        val reason: String = "Cancelled"
    ) : ToolExecutionResult
    
    fun isSuccess(): Boolean = this is Success
}

/**
 * ToolObservation - Post-action observation captured after tool execution.
 * 
 * V2 Addition: Tools now capture the screen state after execution,
 * so the agent can see what changed as a result of the action.
 * 
 * The snapshot is included for use by subsequent tool executions to avoid
 * using stale element indices.
 */
sealed interface ToolObservation {
    /** Screen state after action (for UI tools) */
    data class ScreenState(
        val accessibilityTree: String,
        val elementCount: Int,
        val summary: String = "",
        /** The actual snapshot object for subsequent tool executions */
        val snapshot: com.moonkey.androidagent.model.ScreenSnapshot? = null
    ) : ToolObservation
    
    /** Text output for non-UI tools */
    data class TextOutput(val content: String) : ToolObservation
}
