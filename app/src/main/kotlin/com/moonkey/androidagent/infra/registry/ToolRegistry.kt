package com.moonkey.androidagent.infra.registry

import android.util.Log
import com.moonkey.androidagent.infra.tools.ToolSpec
import com.openai.core.JsonValue
import com.openai.models.responses.FunctionTool
import org.json.JSONArray
import org.json.JSONObject

/**
 * ToolRegistry - Manages tool discovery, registration, and lookup.
 * 
 * Provides:
 * - Tool registration and lookup by name
 * - Schema generation for LLM function calling
 * - Tool filtering based on configuration
 * 
 * Pattern from Gemini CLI's ToolRegistry.
 */
class ToolRegistry {
    
    companion object {
        private const val TAG = "ToolRegistry"
    }
    
    private val tools = mutableMapOf<String, ToolSpec>()
    
    /**
     * Register a tool.
     * 
     * @param tool The tool specification to register
     * @throws IllegalArgumentException if a tool with the same name already exists
     */
    fun register(tool: ToolSpec) {
        if (tools.containsKey(tool.name)) {
            Log.w(TAG, "Overwriting existing tool: ${tool.name}")
        }
        tools[tool.name] = tool
        Log.d(TAG, "Registered tool: ${tool.name}")
    }
    
    /**
     * Register multiple tools at once.
     */
    fun registerAll(vararg toolSpecs: ToolSpec) {
        toolSpecs.forEach { register(it) }
    }
    
    /**
     * Unregister a tool by name.
     * 
     * @param name The name of the tool to remove
     * @return true if the tool was removed, false if it didn't exist
     */
    fun unregister(name: String): Boolean {
        val removed = tools.remove(name) != null
        if (removed) {
            Log.d(TAG, "Unregistered tool: $name")
        }
        return removed
    }
    
    /**
     * Get a tool by name.
     * 
     * @param name The tool name
     * @return The tool specification or null if not found
     */
    fun get(name: String): ToolSpec? = tools[name]
    
    /**
     * Get all registered tool names.
     */
    fun getNames(): Set<String> = tools.keys.toSet()
    
    /**
     * Get all registered tools.
     */
    fun getAll(): List<ToolSpec> = tools.values.toList()
    
    /**
     * Check if a tool is registered.
     */
    fun contains(name: String): Boolean = tools.containsKey(name)
    
    /**
     * Get the count of registered tools.
     */
    fun size(): Int = tools.size
    
    /**
     * Clear all registered tools.
     */
    fun clear() {
        tools.clear()
        Log.d(TAG, "Cleared all tools")
    }
    
    /**
     * Generate OpenAI-compatible function schemas for all registered tools.
     * 
     * @param filter Optional filter to include only specific tools
     * @return JSON array of function schemas
     */
    fun generateFunctionSchemas(filter: ((ToolSpec) -> Boolean)? = null): JSONArray {
        val schemas = JSONArray()
        
        tools.values
            .filter { filter?.invoke(it) != false }
            .forEach { tool ->
                schemas.put(tool.toFunctionSchema())
            }
        
        return schemas
    }
    
    /**
     * Generate a tools parameter for OpenAI chat completion.
     * 
     * @param filter Optional filter to include only specific tools
     * @return List of tool objects for the API
     */
    fun generateToolsParam(filter: ((ToolSpec) -> Boolean)? = null): List<JSONObject> {
        return tools.values
            .filter { filter?.invoke(it) != false }
            .map { it.toFunctionSchema() }
    }
    
    /**
     * Get a human-readable summary of registered tools.
     */
    fun getSummary(): String {
        return buildString {
            appendLine("Registered Tools (${tools.size}):")
            tools.values.forEach { tool ->
                appendLine("  - ${tool.name}: ${tool.description}")
            }
        }
    }
    
    /**
     * Generate FunctionTool objects for the OpenAI Responses API.
     * 
     * Note: strict mode is disabled because it requires ALL properties to be
     * in the required array, which doesn't work well with optional parameters.
     * 
     * @param filter Optional filter to include only specific tools
     * @return List of FunctionTool objects ready for the Responses API
     */
    fun generateResponsesApiTools(filter: ((ToolSpec) -> Boolean)? = null): List<FunctionTool> {
        return tools.values
            .filter { filter?.invoke(it) != false }
            .map { tool ->
                FunctionTool.builder()
                    .name(tool.name)
                    .description(tool.description)
                    .parameters(jsonObjectToJsonValue(tool.parameterSchema))
                    // strict mode disabled - it requires ALL properties in required array,
                    // which doesn't work with optional parameters like duration_ms
                    .strict(false)
                    .build()
            }
    }
    
    /**
     * Convert org.json.JSONObject to OpenAI's JsonValue.
     */
    private fun jsonObjectToJsonValue(json: JSONObject): JsonValue {
        val map = mutableMapOf<String, Any?>()
        json.keys().forEach { key ->
            map[key] = convertJsonElement(json.get(key))
        }
        return JsonValue.from(map)
    }
    
    /**
     * Recursively convert JSON elements to native types for JsonValue.
     */
    private fun convertJsonElement(value: Any?): Any? {
        return when (value) {
            is JSONObject -> {
                val map = mutableMapOf<String, Any?>()
                value.keys().forEach { key ->
                    map[key] = convertJsonElement(value.get(key))
                }
                map
            }
            is JSONArray -> {
                val list = mutableListOf<Any?>()
                for (i in 0 until value.length()) {
                    list.add(convertJsonElement(value.get(i)))
                }
                list
            }
            else -> value
        }
    }
}

