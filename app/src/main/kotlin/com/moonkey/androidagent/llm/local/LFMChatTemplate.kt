package com.moonkey.androidagent.llm.local

import android.util.Log
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.FunctionTool
import org.json.JSONArray
import org.json.JSONObject

/**
 * LFMChatTemplate - Builds prompts using LFM2.5's native chat template format.
 * 
 * Based on https://docs.liquid.ai/lfm/key-concepts/chat-template
 * 
 * ## Chat Template Format
 * 
 * LFM2.5 uses a ChatML-like format:
 * ```
 * <|startoftext|><|im_start|>system
 * {system_content}<|im_end|>
 * <|im_start|>user
 * {user_content}<|im_end|>
 * <|im_start|>assistant
 * ```
 * 
 * ## Roles
 * 
 * - system: Defines who the assistant is and how it should respond
 * - user: Messages from the user
 * - assistant: Responses from the model
 * - tool: Results from tool/function execution
 * 
 * ## Tool Calling (https://docs.liquid.ai/lfm/key-concepts/tool-use)
 * 
 * Tools are defined as JSON in the system prompt:
 * ```
 * List of tools: [{...tool definitions...}]
 * ```
 * 
 * Model outputs tool calls wrapped in special tokens:
 * ```
 * <|tool_call_start|>[function_name(arg1="value1")]<|tool_call_end|>
 * ```
 * 
 * Or as JSON when instructed:
 * ```
 * <|tool_call_start|>{"name":"function_name","arguments":{...}}<|tool_call_end|>
 * ```
 */
object LFMChatTemplate {
    
    private const val TAG = "LFMChatTemplate"
    
    // LFM2.5 special tokens
    private const val START_OF_TEXT = "<|startoftext|>"
    private const val IM_START = "<|im_start|>"
    private const val IM_END = "<|im_end|>"
    
    /**
     * Build complete LFM2.5 chat template prompt for inference.
     * 
     * @param systemPrompt Base system prompt (agent instructions)
     * @param inputItems Conversation history (OpenAI SDK type)
     * @param tools Available tools (OpenAI SDK type)
     * @return Complete prompt string in LFM2.5 chat template format
     */
    fun buildPrompt(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>
    ): String {
        val sb = StringBuilder()
        
        // Start of conversation
        sb.append(START_OF_TEXT)
        
        // System message with tools
        val systemContent = buildSystemContent(systemPrompt, tools)
        sb.append(formatMessage("system", systemContent))
        
        // Conversation history
        for (item in inputItems) {
            val formattedItem = formatInputItem(item)
            if (formattedItem != null) {
                sb.append(formattedItem)
            }
        }
        
        // Prompt for assistant response
        sb.append("$IM_START assistant\n")
        
        return sb.toString()
    }
    
    /**
     * Build system content with tool definitions.
     */
    private fun buildSystemContent(systemPrompt: String, tools: List<FunctionTool>): String {
        val sb = StringBuilder()
        sb.append(systemPrompt)
        
        if (tools.isNotEmpty()) {
            sb.append("\n\n")
            sb.append("## Available Tools\n\n")
            sb.append("List of tools: ")
            sb.append(formatToolsAsJson(tools))
            sb.append("\n\n")
            sb.append("## Tool Response Format\n\n")
            sb.append("When you need to use a tool, output ONLY the tool call as JSON:\n")
            sb.append("<|tool_call_start|>{\"name\":\"TOOL_NAME\",\"arguments\":{...}}<|tool_call_end|>\n\n")
            sb.append("When you have the final answer (no tool needed), respond normally with your answer.")
        }
        
        return sb.toString()
    }
    
    /**
     * Format tools as JSON array matching LFM2.5 expected format.
     */
    private fun formatToolsAsJson(tools: List<FunctionTool>): String {
        val jsonArray = JSONArray()
        
        for (tool in tools) {
            val toolJson = JSONObject()
            toolJson.put("name", tool.name())
            
            // Get description
            val description = tool.description().orElse("No description")
            toolJson.put("description", description)
            
            // Get parameters - extract the actual JSON schema
            try {
                val params = tool.parameters()
                // FunctionParameters wraps JsonValue - try to get the underlying structure
                val paramsStr = params.toString()
                
                // Try to parse as JSON to extract the actual schema
                if (paramsStr.startsWith("{")) {
                    val paramsJson = JSONObject(paramsStr)
                    toolJson.put("parameters", paramsJson)
                } else {
                    // Fallback: create minimal schema
                    toolJson.put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                    })
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse parameters for ${tool.name()}: ${e.message}")
                toolJson.put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                })
            }
            
            jsonArray.put(toolJson)
        }
        
        return jsonArray.toString()
    }
    
    /**
     * Format a message with role and content.
     */
    private fun formatMessage(role: String, content: String): String {
        return "$IM_START$role\n$content$IM_END\n"
    }
    
    /**
     * Format a single input item to LFM chat format.
     */
    private fun formatInputItem(item: ResponseInputItem): String? {
        return when {
            // EasyInputMessage - simple user/assistant messages
            item.isEasyInputMessage() -> {
                val msg = item.asEasyInputMessage()
                val role = when (msg.role()) {
                    com.openai.models.responses.EasyInputMessage.Role.USER -> "user"
                    com.openai.models.responses.EasyInputMessage.Role.ASSISTANT -> "assistant"
                    else -> "system"
                }
                
                // EasyInputMessage content is a union type - try to extract text
                val content = try {
                    val contentObj = msg.content()
                    // The content object may have different representations
                    // Use toString() as a fallback for all cases
                    contentObj.toString()
                } catch (e: Exception) {
                    msg.content().toString()
                }
                
                formatMessage(role, content)
            }
            
            // Function call from assistant - format as tool call
            item.isFunctionCall() -> {
                val fc = item.asFunctionCall()
                val toolCallJson = JSONObject().apply {
                    put("name", fc.name())
                    put("arguments", JSONObject(fc.arguments()))
                }
                
                val content = "<|tool_call_start|>${toolCallJson}<|tool_call_end|>"
                formatMessage("assistant", content)
            }
            
            // Function call output (tool result)
            item.isFunctionCallOutput() -> {
                val fco = item.asFunctionCallOutput()
                // Output is a wrapper type - convert to string
                formatMessage("tool", fco.output().toString())
            }
            
            // Message with structured content
            item.isMessage() -> {
                val msg = item.asMessage()
                // Convert role enum to string by checking the enum name
                val roleStr = msg.role().toString().lowercase()
                val role = when {
                    roleStr.contains("user") -> "user"
                    roleStr.contains("assistant") -> "assistant"
                    roleStr.contains("system") -> "system"
                    else -> "user"
                }
                
                // Extract text content from the message
                val textContent = try {
                    msg.content().mapNotNull { content ->
                        when {
                            content.isInputText() -> content.asInputText().text()
                            else -> null
                        }
                    }.joinToString(" ")
                } catch (e: Exception) {
                    // Fallback to toString if mapNotNull fails
                    msg.content().toString()
                }
                
                if (textContent.isNotEmpty()) formatMessage(role, textContent) else null
            }
            
            // ItemReference - skip for prompt building
            item.isItemReference() -> null
            
            else -> null
        }
    }
}

// Alias for backward compatibility
typealias ToolCallProtocol = LFMChatTemplate
