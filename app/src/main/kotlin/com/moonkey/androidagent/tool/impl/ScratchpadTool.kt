package com.moonkey.androidagent.tool.impl

import com.moonkey.androidagent.session.ScratchpadState
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ToolSpec
import com.moonkey.androidagent.tool.ValidationResult
import com.moonkey.androidagent.tool.appendReason
import com.moonkey.androidagent.tool.textToolSuccess
import org.json.JSONArray
import org.json.JSONObject

class ScratchpadTool(
    private val state: ScratchpadState
) : ToolSpec {
    override val name: String = "scratchpad"

    override val description: String =
        """
        Store key-value data for multi-step tasks and cross-app handoffs.

        Scratchpad is always shown in context every turn (values truncated if long).
        Use read only when you need the full value for a truncated key.

        Good usage:
        - Write facts before navigating away from the current screen
        - Store actual extracted content (not vague references)
        - Use short semantic keys (email_1_subject, total_price)
        - Capture ALL relevant data from the current screen in a single write call

        Actions:
        - write: Store one or more key-value pairs. content is a JSON object string.
          Example: {"email_subject": "Meeting at 3pm", "sender": "alice@example.com"}
          If you have multiple fields to store, include them all in a single write call, to minimize tool call turns for the same result.
        - read: Get value for key
        - delete: Remove key

        Limits:
        - Max keys: ${ScratchpadState.MAX_ENTRIES}
        - Max value length: ${ScratchpadState.MAX_VALUE_LENGTH} chars per value
        """.trimIndent()

    override val parameterSchema: JSONObject =
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("agent_thought", JSONObject().apply {
                    put("type", "string")
                    put("description", "Brief reason for why this action is being performed")
                })
                put("action", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray(listOf("write", "read", "delete")))
                    put("description", "Action to perform")
                })
                put("content", JSONObject().apply {
                    put("type", "string")
                    put(
                        "description",
                        "JSON object string for write. Example: {\"key1\": \"value1\", \"key2\": \"value2\"}"
                    )
                })
                put("key", JSONObject().apply {
                    put("type", "string")
                    put("description", "Key for read/delete")
                })
            })
            put("required", JSONArray(listOf("action")))
            put("additionalProperties", false)
        }

    override fun validate(params: JSONObject): ValidationResult {
        val action = params.optString("action", "")
        if (action.isEmpty()) {
            return ValidationResult.Invalid("Missing required parameter: action")
        }

        val errors = mutableListOf<String>()
        when (action) {
            "write" -> {
                val content = params.optString("content", "").trim()
                if (content.isEmpty()) {
                    errors.add("Missing required parameter: content")
                } else {
                    // Try parsing JSON to validate early
                    try {
                        val json = JSONObject(content)
                        val existingKeys = state.list().toSet()
                        var newKeyCount = 0
                        for (key in json.keys()) {
                            if (key.length > ScratchpadState.MAX_KEY_LENGTH) {
                                errors.add("key '$key' exceeds max length (${ScratchpadState.MAX_KEY_LENGTH})")
                            }
                            val valStr = json.get(key).toString()
                            if (valStr.length > ScratchpadState.MAX_VALUE_LENGTH) {
                                errors.add("value for '$key' exceeds max length (${ScratchpadState.MAX_VALUE_LENGTH})")
                            }
                            if (!existingKeys.contains(key)) {
                                newKeyCount++
                            }
                        }
                        val totalAfter = existingKeys.size + newKeyCount
                        if (totalAfter > ScratchpadState.MAX_ENTRIES) {
                            errors.add("would exceed max entries (${ScratchpadState.MAX_ENTRIES}): " +
                                "current=${existingKeys.size}, adding=$newKeyCount")
                        }
                    } catch (e: Exception) {
                        errors.add("content is not valid JSON: ${e.message}")
                    }
                }
            }
            "read", "delete" -> {
                val key = params.optString("key", "").trim()
                if (key.isEmpty()) errors.add("Missing required parameter: key")
            }
            else -> errors.add("Unknown action: '$action'. Valid actions: write, read, delete")
        }

        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val action = params.getString("action")
        val agentThought = params.optString("agent_thought", "").trim()
        val description = buildDescription(action, params, agentThought)
        return ScratchpadInvocation(
            state = state,
            params = params,
            action = action,
            description = description
        )
    }

    private fun buildDescription(action: String, params: JSONObject, agentThought: String): String {
        val base = when (action) {
            "write" -> {
                val content = params.optString("content", "")
                val json = try { JSONObject(content) } catch (_: Exception) { null }
                val keyCount = json?.length() ?: 0
                if (keyCount <= 1) {
                    val key = try { json?.keys()?.next() } catch (_: Exception) { null } ?: "?"
                    "Write scratchpad key '$key'"
                } else {
                    "Write scratchpad: $keyCount keys"
                }
            }
            "read" -> "Read scratchpad key '${params.optString("key", "")}'"
            "delete" -> "Delete scratchpad key '${params.optString("key", "")}'"
            else -> "Scratchpad action '$action'"
        }
        return appendReason(base, agentThought)
    }
}

private class ScratchpadInvocation(
    private val state: ScratchpadState,
    override val params: JSONObject,
    private val action: String,
    private val description: String
) : ToolInvocation {
    override val toolName: String = "scratchpad"

    override fun getDescription(): String = description

    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        if (context.isCancelled()) {
            return ToolExecutionResult.Cancelled("Cancelled before execution")
        }

        return try {
            val output = when (action) {
                "write" -> {
                    val content = params.optString("content", "")
                    val json = JSONObject(content)
                    val storedKeys = mutableListOf<String>()
                    for (key in json.keys()) {
                        state.write(key, json.get(key))
                        storedKeys.add(key)
                    }
                    "Stored ${storedKeys.size} keys: ${storedKeys.joinToString(", ")}."
                }
                "read" -> {
                    val key = params.optString("key", "").trim()
                    val readValue = state.read(key)
                    JSONObject().apply {
                        put("action", "read")
                        put("key", key)
                        if (readValue == null) {
                            put("value", JSONObject.NULL)
                        } else {
                            put("value", readValue)
                        }
                    }.toString()
                }
                "delete" -> {
                    val key = params.optString("key", "").trim()
                    val removed = state.delete(key)
                    JSONObject().apply {
                        put("action", "delete")
                        put("key", key)
                        put("removed", removed)
                    }.toString()
                }
                else -> JSONObject().apply {
                    put("action", action)
                    put("error", "Unknown action")
                }.toString()
            }

            textToolSuccess(output = output)
        } catch (e: Exception) {
            ToolExecutionResult.Failure("Scratchpad action failed: ${e.message}", e)
        }
    }
}
