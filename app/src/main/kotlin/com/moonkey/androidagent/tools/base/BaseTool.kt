package com.moonkey.androidagent.tools.base

import com.moonkey.androidagent.infra.tools.ToolExecutionContext
import com.moonkey.androidagent.infra.tools.ToolExecutionResult
import com.moonkey.androidagent.infra.tools.ToolInvocation
import com.moonkey.androidagent.infra.tools.ToolSpec
import com.moonkey.androidagent.infra.tools.ValidationResult
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.UIAction
import org.json.JSONObject

/**
 * BaseTool - Abstract base class for Mobile-Agent tools.
 * 
 * Provides common functionality for tools that execute UIActions.
 */
abstract class BaseTool : ToolSpec {
    
    /**
     * Create a UIAction from the validated parameters.
     */
    protected abstract fun createUIAction(params: JSONObject): UIAction?
    
    /**
     * Get a description of what this action will do.
     */
    protected abstract fun getActionDescription(params: JSONObject): String
    
    override fun createInvocation(params: JSONObject): ToolInvocation {
        return BaseToolInvocation(
            toolName = name,
            params = params,
            description = getActionDescription(params),
            uiAction = createUIAction(params)
        )
    }
    
    /**
     * Helper to validate required integer parameter.
     */
    protected fun validateRequiredInt(params: JSONObject, key: String, errors: MutableList<String>): Int? {
        if (!params.has(key)) {
            errors.add("Missing required parameter: $key")
            return null
        }
        return try {
            params.getInt(key)
        } catch (e: Exception) {
            errors.add("Parameter '$key' must be an integer")
            null
        }
    }
    
    /**
     * Helper to validate required string parameter.
     */
    protected fun validateRequiredString(params: JSONObject, key: String, errors: MutableList<String>): String? {
        if (!params.has(key)) {
            errors.add("Missing required parameter: $key")
            return null
        }
        return try {
            params.getString(key)
        } catch (e: Exception) {
            errors.add("Parameter '$key' must be a string")
            null
        }
    }
    
    /**
     * Helper to validate optional string parameter.
     */
    protected fun validateOptionalString(params: JSONObject, key: String): String? {
        return if (params.has(key)) {
            try {
                params.getString(key)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }
    
    /**
     * Helper to validate enum parameter.
     */
    protected inline fun <reified T : Enum<T>> validateEnum(
        params: JSONObject,
        key: String,
        errors: MutableList<String>,
        default: T? = null
    ): T? {
        val value = validateOptionalString(params, key) ?: return default
        return try {
            enumValueOf<T>(value.uppercase())
        } catch (e: Exception) {
            val validValues = enumValues<T>().joinToString(", ") { it.name.lowercase() }
            errors.add("Parameter '$key' must be one of: $validValues")
            null
        }
    }
    
    /**
     * Create a simple parameter schema for a tool with no parameters.
     */
    protected fun emptySchema(): JSONObject {
        return JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject())
            put("required", org.json.JSONArray())
        }
    }
    
    /**
     * Create a parameter schema with specified properties.
     */
    protected fun createSchema(
        properties: Map<String, Pair<String, String>>,  // name -> (type, description)
        required: List<String> = emptyList()
    ): JSONObject {
        return JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                properties.forEach { (name, typeAndDesc) ->
                    put(name, JSONObject().apply {
                        put("type", typeAndDesc.first)
                        put("description", typeAndDesc.second)
                    })
                }
            })
            put("required", org.json.JSONArray(required))
        }
    }
}

/**
 * Base invocation that executes a UIAction.
 */
class BaseToolInvocation(
    override val toolName: String,
    override val params: JSONObject,
    private val description: String,
    private val uiAction: UIAction?
) : ToolInvocation {
    
    override fun getDescription(): String = description
    
    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        if (uiAction == null) {
            return ToolExecutionResult.Failure("Failed to create UI action")
        }
        
        if (context.isCancelled()) {
            return ToolExecutionResult.Cancelled("Cancelled before execution")
        }
        
        val result = context.platform.performAction(uiAction, context.currentSnapshot)
        
        return when (result) {
            is ActionResult.Success -> ToolExecutionResult.Success(result.message)
            is ActionResult.Failure -> ToolExecutionResult.Failure(result.reason, result.exception)
            is ActionResult.ElementNotFound -> ToolExecutionResult.Failure(
                "Element not found: index ${result.elementIndex}"
            )
            is ActionResult.Cancelled -> ToolExecutionResult.Cancelled(result.reason)
        }
    }
}

