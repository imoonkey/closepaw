package com.moonkey.androidagent.agent

import android.util.Log
import com.moonkey.androidagent.data.llm.ChatMessage
import com.moonkey.androidagent.data.llm.LLMClient
import com.moonkey.androidagent.data.llm.Role
import com.moonkey.androidagent.infra.history.HistoryManager
import com.moonkey.androidagent.infra.history.ResponseItem
import com.moonkey.androidagent.infra.registry.ToolRegistry
import org.json.JSONObject
import java.util.UUID

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
    companion object {
        private const val TAG = "Turn"
    }
    
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
        
        Log.d(TAG, "Running turn with ${messages.size} messages")
        
        // 2. Call LLM
        val response = LLMClient.chat(messages)
        
        Log.d(TAG, "LLM response: ${response.take(500)}...")
        
        // 3. Parse response for tool calls
        return parseResponse(response)
    }
    
    /**
     * Build messages list from system prompt, history, and current context.
     */
    private fun buildMessages(systemPrompt: String, userContext: String): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        
        // System prompt with tool instructions
        val toolInstructions = buildToolInstructions()
        val fullSystemPrompt = """
            $systemPrompt
            
            $toolInstructions
        """.trimIndent()
        
        messages.add(ChatMessage(Role.SYSTEM, fullSystemPrompt))
        
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
                        "I'll use the ${item.name} tool with arguments: ${item.arguments}"
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
    
    /**
     * Build tool instructions for the system prompt.
     * Uses JSON format that the model can understand and we can parse.
     */
    private fun buildToolInstructions(): String {
        return """
            ## Tool Usage
            
            When you need to perform an action, respond with a tool call in this EXACT format:
            ```tool
            {"name": "TOOL_NAME", "arguments": {"param": value}}
            ```
            
            ## Available Tools
            
            1. **click** - Click on a UI element
               Arguments: {"element_index": <integer>}
               Example: ```tool
               {"name": "click", "arguments": {"element_index": 5}}
               ```
            
            2. **type** - Type text into an editable field
               Arguments: {"element_index": <integer>, "text": "<string>"}
               Example: ```tool
               {"name": "type", "arguments": {"element_index": 3, "text": "Hello"}}
               ```
            
            3. **scroll** - Scroll the screen
               Arguments: {"direction": "up" | "down" | "left" | "right"}
               Example: ```tool
               {"name": "scroll", "arguments": {"direction": "down"}}
               ```
            
            4. **swipe** - Swipe from one point to another
               Arguments: {"start_x": <int>, "start_y": <int>, "end_x": <int>, "end_y": <int>, "duration_ms": <int>}
               Example: ```tool
               {"name": "swipe", "arguments": {"start_x": 200, "start_y": 800, "end_x": 200, "end_y": 200, "duration_ms": 300}}
               ```
            
            5. **back** - Press the system back button
               Arguments: {} (none required)
               Example: ```tool
               {"name": "back", "arguments": {}}
               ```
            
            6. **home** - Press the system home button
               Arguments: {} (none required)
               Example: ```tool
               {"name": "home", "arguments": {}}
               ```
            
            7. **wait** - Wait for UI to update
               Arguments: {"duration_ms": <integer>} (optional, default 1000)
               Example: ```tool
               {"name": "wait", "arguments": {"duration_ms": 2000}}
               ```
            
            ## Rules
            
            - Use ONLY ONE tool call per response
            - The element_index comes from the "index" field in the screen state JSON
            - Look for elements with "clickable": true for buttons/links
            - Look for elements with "editable": true for text input
            - After each action, wait for the result before deciding next step
            
            ## Completion
            
            When the goal is achieved, respond with "DONE:" followed by a summary.
            Do NOT include a tool call when the task is complete.
        """.trimIndent()
    }
    
    /**
     * Parse LLM response for tool calls and completion status.
     * 
     * Uses multiple patterns to handle edge cases:
     * 1. Standard ```tool ... ``` blocks
     * 2. Fallback JSON object detection for malformed responses
     */
    private fun parseResponse(response: String): TurnResult {
        val toolCalls = mutableListOf<ToolCallRequest>()
        val parseErrors = mutableListOf<String>()
        
        // Primary pattern: Extract tool calls from ```tool blocks
        val toolPattern = Regex("```tool\\s*\\n?([\\s\\S]*?)\\n?```", RegexOption.MULTILINE)
        val matches = toolPattern.findAll(response).toList()
        
        if (matches.isNotEmpty()) {
            matches.forEach { match ->
                val rawContent = match.groupValues[1]
                parseToolCallJson(rawContent, toolCalls, parseErrors)
            }
        } else {
            // Fallback: Try to find JSON with "name" field that looks like a tool call
            // This handles cases where LLM omits the ```tool wrapper
            val jsonPattern = Regex("""\{[^{}]*"name"\s*:\s*"[^"]+"\s*[^{}]*\}""")
            jsonPattern.findAll(response).forEach { match ->
                Log.d(TAG, "Fallback: Found potential tool JSON without wrapper: ${match.value}")
                parseToolCallJson(match.value, toolCalls, parseErrors)
            }
        }
        
        // Log parsing errors for debugging
        if (parseErrors.isNotEmpty()) {
            Log.w(TAG, "Tool call parsing errors:\n${parseErrors.joinToString("\n")}")
        }
        
        // Check for completion indicators
        val isComplete = toolCalls.isEmpty() && (
            response.contains("DONE:", ignoreCase = false) ||
            response.contains("goal achieved", ignoreCase = true) ||
            response.contains("task completed", ignoreCase = true) ||
            response.contains("successfully completed", ignoreCase = true)
        )
        
        Log.d(TAG, "Parse result: ${toolCalls.size} tool calls, isComplete=$isComplete")
        
        return TurnResult(
            content = response,
            toolCalls = toolCalls,
            isComplete = isComplete,
            parseErrors = parseErrors.takeIf { it.isNotEmpty() }
        )
    }
    
    /**
     * Parse a JSON string as a tool call and add to the list if valid.
     */
    private fun parseToolCallJson(
        rawContent: String,
        toolCalls: MutableList<ToolCallRequest>,
        parseErrors: MutableList<String>
    ) {
        val jsonStr = rawContent.trim()
        
        if (jsonStr.isEmpty()) {
            parseErrors.add("Empty tool call block")
            return
        }
        
        try {
            val json = JSONObject(jsonStr)
            
            // Validate required "name" field
            if (!json.has("name")) {
                parseErrors.add("Tool call missing 'name' field: $jsonStr")
                return
            }
            
            val name = json.getString("name")
            if (name.isBlank()) {
                parseErrors.add("Tool call has empty 'name': $jsonStr")
                return
            }
            
            // Validate tool exists in registry
            if (toolRegistry.get(name) == null) {
                parseErrors.add("Unknown tool '$name' - available tools: ${toolRegistry.getAll().map { it.name }}")
                // Still add it - let ToolRouter handle the error with proper state machine
            }
            
            val arguments = json.optJSONObject("arguments") ?: JSONObject()
            
            toolCalls.add(ToolCallRequest(
                id = UUID.randomUUID().toString(),
                name = name,
                arguments = arguments
            ))
            
            Log.d(TAG, "Parsed tool call: $name with args: $arguments")
            
        } catch (e: org.json.JSONException) {
            parseErrors.add("Invalid JSON in tool call: ${e.message}\nContent: $jsonStr")
        } catch (e: Exception) {
            parseErrors.add("Unexpected error parsing tool call: ${e.message}\nContent: $jsonStr")
        }
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
    val isComplete: Boolean,
    
    /** Any errors encountered while parsing tool calls (for debugging) */
    val parseErrors: List<String>? = null
)

/**
 * A tool call request from the LLM.
 */
data class ToolCallRequest(
    val id: String,
    val name: String,
    val arguments: JSONObject
)

