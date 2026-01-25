package com.moonkey.androidagent.llm.local

import android.util.Log
import org.json.JSONException
import org.json.JSONObject

/**
 * LFMOutputParser - Parses output from LFM2.5 models with tool call support.
 * 
 * Handles LFM2.5's native output format:
 * - Tool calls wrapped in `<|tool_call_start|>` and `<|tool_call_end|>` tokens
 * - Thinking/reasoning content for thinking models
 * - JSON parsing with error recovery
 * 
 * ## LFM2.5 Tool Call Format
 * 
 * Based on https://docs.liquid.ai/lfm/key-concepts/tool-use:
 * 
 * Pythonic format:
 * ```
 * <|tool_call_start|>[function_name(arg1="value1", arg2=123)]<|tool_call_end|>
 * ```
 * 
 * JSON format (when instructed):
 * ```
 * <|tool_call_start|>{"name":"function_name","arguments":{"arg1":"value1"}}<|tool_call_end|>
 * ```
 * 
 * ## Thinking Model Support
 * 
 * For LFM2.5-Thinking models, output may contain reasoning before the tool call:
 * ```
 * Let me analyze this step by step.
 * First, I need to click on the button at index 5.
 * <|tool_call_start|>{"name":"click","arguments":{"element_index":5}}<|tool_call_end|>
 * ```
 */
object LFMOutputParser {
    
    private const val TAG = "LFMOutputParser"
    
    // LFM2.5 special tokens
    private const val TOOL_CALL_START = "<|tool_call_start|>"
    private const val TOOL_CALL_END = "<|tool_call_end|>"
    private const val IM_END = "<|im_end|>"
    
    /**
     * Result of parsing LFM model output.
     */
    sealed interface ParseResult {
        /**
         * Successfully parsed a message (final answer).
         */
        data class Message(val content: String) : ParseResult
        
        /**
         * Successfully parsed a tool call.
         */
        data class ToolCall(
            val name: String,
            val arguments: String
        ) : ParseResult
        
        /**
         * Output is incomplete (waiting for more tokens).
         */
        data object Incomplete : ParseResult
        
        /**
         * Output is invalid and could not be recovered.
         */
        data class Invalid(val raw: String, val reason: String) : ParseResult
    }
    
    /**
     * Result of parsing output that may contain thinking content.
     */
    data class LFMParseResult(
        /** The thinking/reasoning content before the final output (may be null) */
        val thinkingContent: String?,
        /** The parsed result (message, tool call, incomplete, or invalid) */
        val result: ParseResult
    )
    
    /**
     * Parse LFM model output.
     * 
     * @param buffer The accumulated output buffer
     * @param isThinkingModel Whether this is a thinking model that may produce reasoning
     * @return LFMParseResult with thinking content and parsed result
     */
    fun parse(buffer: String, isThinkingModel: Boolean = false): LFMParseResult {
        // Check for tool call with special tokens
        if (buffer.contains(TOOL_CALL_START)) {
            return parseWithToolCallTokens(buffer, isThinkingModel)
        }
        
        // Check for JSON output (fallback for older prompts)
        if (buffer.contains("{")) {
            return parseJsonOutput(buffer, isThinkingModel)
        }
        
        // Plain text response
        val cleanContent = buffer
            .replace(IM_END, "")
            .trim()
        
        return if (cleanContent.isNotEmpty()) {
            LFMParseResult(
                thinkingContent = if (isThinkingModel) null else null,
                result = ParseResult.Message(cleanContent)
            )
        } else {
            LFMParseResult(null, ParseResult.Incomplete)
        }
    }
    
    /**
     * Parse output with LFM tool call tokens.
     */
    private fun parseWithToolCallTokens(buffer: String, isThinkingModel: Boolean): LFMParseResult {
        val startIdx = buffer.indexOf(TOOL_CALL_START)
        val endIdx = buffer.indexOf(TOOL_CALL_END)
        
        // Extract thinking content (everything before tool call)
        val thinkingContent = if (isThinkingModel && startIdx > 0) {
            buffer.substring(0, startIdx).trim().takeIf { it.isNotBlank() }
        } else null
        
        // Check if tool call is complete
        if (endIdx <= startIdx) {
            return LFMParseResult(thinkingContent, ParseResult.Incomplete)
        }
        
        // Extract tool call content
        val toolCallContent = buffer.substring(
            startIdx + TOOL_CALL_START.length,
            endIdx
        ).trim()
        
        // Parse the tool call
        val toolCall = parseToolCall(toolCallContent)
        
        return if (toolCall != null) {
            LFMParseResult(thinkingContent, toolCall)
        } else {
            LFMParseResult(
                thinkingContent,
                ParseResult.Invalid(toolCallContent, "Failed to parse tool call")
            )
        }
    }
    
    /**
     * Parse a tool call from content between tool call tokens.
     * 
     * Handles both JSON format and Pythonic format.
     */
    fun parseToolCall(content: String): ParseResult.ToolCall? {
        val trimmed = content.trim()
        
        // Try JSON format first: {"name":"...", "arguments":{...}}
        if (trimmed.startsWith("{")) {
            return parseJsonToolCall(trimmed)
        }
        
        // Try Pythonic format: [function_name(arg1="value1")]
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return parsePythonicToolCall(trimmed)
        }
        
        // Try bare function call: function_name(arg1="value1")
        if (trimmed.contains("(") && trimmed.contains(")")) {
            return parsePythonicToolCall("[$trimmed]")
        }
        
        Log.w(TAG, "Could not parse tool call: $trimmed")
        return null
    }
    
    /**
     * Parse JSON format tool call.
     */
    private fun parseJsonToolCall(content: String): ParseResult.ToolCall? {
        return try {
            val json = JSONObject(content)
            val name = json.getString("name")
            val arguments = when {
                json.has("arguments") -> {
                    val args = json.get("arguments")
                    when (args) {
                        is JSONObject -> args.toString()
                        is String -> args
                        else -> args.toString()
                    }
                }
                else -> "{}"
            }
            ParseResult.ToolCall(name, arguments)
        } catch (e: JSONException) {
            Log.w(TAG, "Failed to parse JSON tool call: ${e.message}")
            // Try repair
            val repaired = tryRepairJson(content)
            if (repaired != null) {
                parseJsonToolCall(repaired)
            } else {
                null
            }
        }
    }
    
    /**
     * Parse Pythonic format tool call: [function_name(arg1="value1", arg2=123)]
     */
    private fun parsePythonicToolCall(content: String): ParseResult.ToolCall? {
        try {
            // Remove brackets: function_name(arg1="value1", arg2=123)
            val inner = content.trim().removePrefix("[").removeSuffix("]").trim()
            
            // Find function name and arguments
            val parenStart = inner.indexOf('(')
            val parenEnd = inner.lastIndexOf(')')
            
            if (parenStart == -1 || parenEnd <= parenStart) {
                return null
            }
            
            val functionName = inner.substring(0, parenStart).trim()
            val argsString = inner.substring(parenStart + 1, parenEnd).trim()
            
            // Parse arguments into JSON
            val argsJson = parsePythonicArguments(argsString)
            
            return ParseResult.ToolCall(functionName, argsJson.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Pythonic tool call: ${e.message}")
            return null
        }
    }
    
    /**
     * Parse Pythonic arguments to JSON.
     * Example: arg1="value1", arg2=123, arg3=true
     */
    private fun parsePythonicArguments(argsString: String): JSONObject {
        val json = JSONObject()
        
        if (argsString.isBlank()) return json
        
        // Simple regex-based parsing for key=value pairs
        val pattern = Regex("""(\w+)\s*=\s*("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|\d+(?:\.\d+)?|true|false|null|\{[^}]*\}|\[[^\]]*\])""")
        
        for (match in pattern.findAll(argsString)) {
            val key = match.groupValues[1]
            var value = match.groupValues[2]
            
            // Parse the value
            when {
                value.startsWith("\"") || value.startsWith("'") -> {
                    // String value - remove quotes
                    json.put(key, value.substring(1, value.length - 1))
                }
                value == "true" -> json.put(key, true)
                value == "false" -> json.put(key, false)
                value == "null" -> json.put(key, JSONObject.NULL)
                value.contains(".") -> json.put(key, value.toDoubleOrNull() ?: value)
                value.startsWith("{") -> json.put(key, JSONObject(value))
                value.startsWith("[") -> json.put(key, org.json.JSONArray(value))
                else -> json.put(key, value.toIntOrNull() ?: value)
            }
        }
        
        return json
    }
    
    /**
     * Parse JSON output (fallback for outputs without tool call tokens).
     */
    private fun parseJsonOutput(buffer: String, isThinkingModel: Boolean): LFMParseResult {
        // Find the outermost JSON object using brace matching
        val jsonBounds = findOutermostJson(buffer)
        
        if (jsonBounds == null) {
            return LFMParseResult(null, ParseResult.Incomplete)
        }
        
        val (jsonStart, jsonEnd) = jsonBounds
        
        // Extract thinking content
        val thinkingContent = if (isThinkingModel && jsonStart > 0) {
            buffer.substring(0, jsonStart).trim().takeIf { it.isNotBlank() }
        } else null
        
        // Extract and parse JSON
        val jsonCandidate = buffer.substring(jsonStart, jsonEnd + 1)
        val result = parseJsonCandidate(jsonCandidate, buffer)
        
        return LFMParseResult(thinkingContent, result)
    }
    
    /**
     * Find the outermost JSON object using brace matching.
     * Returns (startIndex, endIndex) or null if not found.
     */
    private fun findOutermostJson(buffer: String): Pair<Int, Int>? {
        var depth = 0
        var jsonStart = -1
        var jsonEnd = -1
        
        for (i in buffer.indices) {
            when (buffer[i]) {
                '{' -> {
                    if (depth == 0) jsonStart = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && jsonStart != -1) {
                        jsonEnd = i
                        break
                    }
                }
            }
        }
        
        return if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
            Pair(jsonStart, jsonEnd)
        } else {
            null
        }
    }
    
    /**
     * Parse a JSON candidate string.
     */
    private fun parseJsonCandidate(candidate: String, originalBuffer: String): ParseResult {
        return try {
            val json = JSONObject(candidate)
            parseJsonObject(json)
        } catch (e: JSONException) {
            Log.d(TAG, "Initial parse failed, attempting repair: ${e.message}")
            val repaired = tryRepairJson(candidate)
            if (repaired != null) {
                try {
                    val json = JSONObject(repaired)
                    parseJsonObject(json)
                } catch (e2: JSONException) {
                    ParseResult.Invalid(candidate, "Could not parse or repair JSON")
                }
            } else {
                ParseResult.Invalid(candidate, "Could not parse or repair JSON")
            }
        }
    }
    
    /**
     * Parse a valid JSONObject into a ParseResult.
     */
    private fun parseJsonObject(json: JSONObject): ParseResult {
        val type = json.optString("type", "")
        
        return when (type) {
            "message" -> {
                val content = json.optString("content", "")
                if (content.isNotEmpty()) {
                    ParseResult.Message(content)
                } else {
                    ParseResult.Invalid(json.toString(), "Message has empty content")
                }
            }
            
            "tool_call" -> {
                val name = json.optString("name", "")
                if (name.isEmpty()) {
                    return ParseResult.Invalid(json.toString(), "Tool call missing name")
                }
                
                val arguments = when {
                    json.has("arguments") -> {
                        val args = json.opt("arguments")
                        when (args) {
                            is JSONObject -> args.toString()
                            is String -> args
                            null -> "{}"
                            else -> args.toString()
                        }
                    }
                    else -> "{}"
                }
                
                ParseResult.ToolCall(name = name, arguments = arguments)
            }
            
            // No type field - check if it's a tool call by name field
            "" -> {
                if (json.has("name") && (json.has("arguments") || json.has("parameters"))) {
                    val name = json.getString("name")
                    val arguments = json.optJSONObject("arguments")?.toString()
                        ?: json.optJSONObject("parameters")?.toString()
                        ?: "{}"
                    ParseResult.ToolCall(name = name, arguments = arguments)
                } else if (json.has("content")) {
                    ParseResult.Message(json.getString("content"))
                } else {
                    ParseResult.Invalid(json.toString(), "Unknown JSON structure")
                }
            }
            
            else -> ParseResult.Invalid(json.toString(), "Unknown type: $type")
        }
    }
    
    /**
     * Try to repair common JSON issues.
     */
    private fun tryRepairJson(raw: String): String? {
        var repaired = raw
        
        // Repair 1: Trailing commas
        repaired = repaired.replace(Regex(",\\s*}"), "}")
        repaired = repaired.replace(Regex(",\\s*]"), "]")
        
        // Repair 2: Single quotes → double quotes (careful with apostrophes)
        repaired = repaired.replace(Regex("'([^']*)'\\s*:")) { match ->
            "\"${match.groupValues[1]}\":"
        }
        repaired = repaired.replace(Regex(":\\s*'([^']*)'")) { match ->
            ":\"${match.groupValues[1]}\""
        }
        
        // Repair 3: Unquoted keys
        repaired = repaired.replace(Regex("([{,])\\s*(\\w+)\\s*:")) { match ->
            "${match.groupValues[1]}\"${match.groupValues[2]}\":"
        }
        
        // Repair 4: Missing closing brace
        val openBraces = repaired.count { it == '{' }
        val closeBraces = repaired.count { it == '}' }
        if (openBraces > closeBraces) {
            repaired += "}".repeat(openBraces - closeBraces)
        }
        
        // Repair 5: Newlines in string values
        repaired = repaired.replace(Regex("\"([^\"]*)\\n([^\"]*)\"")) { match ->
            "\"${match.groupValues[1]}\\n${match.groupValues[2]}\""
        }
        
        // Validate repair worked
        return try {
            JSONObject(repaired)
            repaired
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Check if a buffer might contain valid output eventually.
     */
    fun mightContainOutput(buffer: String): Boolean {
        return buffer.contains(TOOL_CALL_START) ||
               buffer.contains("{") ||
               buffer.contains("tool_call") ||
               buffer.contains("message")
    }
    
    /**
     * Check if the buffer appears to have complete output.
     */
    fun appearsComplete(buffer: String): Boolean {
        // Check for tool call completion
        if (buffer.contains(TOOL_CALL_START) && buffer.contains(TOOL_CALL_END)) {
            return true
        }
        
        // Check for JSON completion
        val hasOpenBrace = buffer.contains('{')
        val hasCloseBrace = buffer.contains('}')
        
        if (hasOpenBrace && hasCloseBrace) {
            val bounds = findOutermostJson(buffer)
            return bounds != null
        }
        
        // Check for end of message token
        if (buffer.contains(IM_END)) {
            return true
        }
        
        return false
    }
}

// Alias for backward compatibility
typealias LocalOutputParser = LFMOutputParser
