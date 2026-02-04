package com.moonkey.androidagent.tool.impl

import com.moonkey.androidagent.session.ScratchpadState
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ToolObservation
import com.moonkey.androidagent.tool.ToolSpec
import com.moonkey.androidagent.tool.ValidationResult
import org.json.JSONArray
import org.json.JSONObject

class ScratchpadTool(
    private val state: ScratchpadState
) : ToolSpec {
    override val name: String = "scratchpad"

    override val description: String =
        """
        Store and retrieve key-value data for multi-step tasks.

        Use cases:
        - Store extracted info (e.g., contact list from one screen to use in another)
        - Remember values across navigation
        - Track intermediate results

        Actions:
        - write: Store key=value
        - read: Get value for key
        - delete: Remove key
        - list: Show all keys

        Limits:
        - Max keys: ${ScratchpadState.MAX_ENTRIES}
        - Max key length: ${ScratchpadState.MAX_KEY_LENGTH} chars
        - Max value length: ${ScratchpadState.MAX_VALUE_LENGTH} chars
        """.trimIndent()

    override val parameterSchema: JSONObject =
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("action", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray(listOf("write", "read", "delete", "list")))
                    put("description", "Action to perform")
                })
                put("key", JSONObject().apply {
                    put("type", "string")
                    put("description", "Key for write/read/delete")
                })
                put("value", JSONObject().apply {
                    put("type", "string")
                    put("description", "Value for write action")
                })
                put("agent_thought", JSONObject().apply {
                    put("type", "string")
                    put("description", "Brief reason for why this action is being performed")
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
                val key = params.optString("key", "").trim()
                val value = params.optString("value", "").trim()
                if (key.isEmpty()) errors.add("Missing required parameter: key")
                if (value.isEmpty()) errors.add("Missing required parameter: value")
                if (key.length > ScratchpadState.MAX_KEY_LENGTH) {
                    errors.add("key exceeds max length (${ScratchpadState.MAX_KEY_LENGTH})")
                }
                if (value.length > ScratchpadState.MAX_VALUE_LENGTH) {
                    errors.add("value exceeds max length (${ScratchpadState.MAX_VALUE_LENGTH})")
                }
                if (key.isNotEmpty()) {
                    val keys = state.list()
                    if (!keys.contains(key) && keys.size >= ScratchpadState.MAX_ENTRIES) {
                        errors.add("scratchpad is full (max ${ScratchpadState.MAX_ENTRIES} entries)")
                    }
                }
            }
            "read", "delete" -> {
                val key = params.optString("key", "").trim()
                if (key.isEmpty()) errors.add("Missing required parameter: key")
            }
            "list" -> Unit
            else -> errors.add("Unknown action: '$action'. Valid actions: write, read, delete, list")
        }

        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val action = params.getString("action")
        val key = params.optString("key", "").trim()
        val value = params.optString("value", "").trim()
        val agentThought = params.optString("agent_thought", "").trim()
        val description = buildDescription(action, key, agentThought)
        return ScratchpadInvocation(
            state = state,
            params = params,
            action = action,
            key = key,
            value = value,
            description = description
        )
    }

    private fun buildDescription(action: String, key: String, agentThought: String): String {
        val base = when (action) {
            "write" -> "Write scratchpad key '$key'"
            "read" -> "Read scratchpad key '$key'"
            "delete" -> "Delete scratchpad key '$key'"
            "list" -> "List scratchpad keys"
            else -> "Scratchpad action '$action'"
        }
        return if (agentThought.isNotEmpty()) "$base (reason: $agentThought)" else base
    }
}

private class ScratchpadInvocation(
    private val state: ScratchpadState,
    override val params: JSONObject,
    private val action: String,
    private val key: String,
    private val value: String,
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
                    state.write(key, value)
                    JSONObject().apply {
                        put("action", "write")
                        put("key", key)
                        put("value", value)
                    }.toString()
                }
                "read" -> {
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
                    val removed = state.delete(key)
                    JSONObject().apply {
                        put("action", "delete")
                        put("key", key)
                        put("removed", removed)
                    }.toString()
                }
                "list" -> {
                    val keys = state.list()
                    JSONObject().apply {
                        put("action", "list")
                        put("keys", JSONArray(keys))
                        put("count", keys.size)
                    }.toString()
                }
                else -> JSONObject().apply {
                    put("action", action)
                    put("error", "Unknown action")
                }.toString()
            }

            ToolExecutionResult.Success(
                output = output,
                observation = ToolObservation.TextOutput(output)
            )
        } catch (e: Exception) {
            ToolExecutionResult.Failure("Scratchpad action failed: ${e.message}", e)
        }
    }
}
