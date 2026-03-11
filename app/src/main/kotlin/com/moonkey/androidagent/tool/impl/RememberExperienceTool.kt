package com.moonkey.androidagent.tool.impl

import com.moonkey.androidagent.memory.MemoryStore
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ToolSpec
import com.moonkey.androidagent.tool.ValidationResult
import com.moonkey.androidagent.tool.appendReason
import com.moonkey.androidagent.tool.textToolSuccess
import org.json.JSONArray
import org.json.JSONObject

class RememberExperienceTool(
    private val store: MemoryStore
) : ToolSpec {
    override val name: String = "remember_experience"

    override val description: String =
        """
        Save a reusable learning to long-term memory.

        Call this before complete_task when you learned something that would help
        in future tasks. You can call this alongside complete_task in the same turn.

        Prefix content with a kind tag:
        - [workflow] operation patterns, navigation sequences, useful shortcuts
        - [pitfall] traps, gotchas, things that don't work as expected
        - [verification] how to verify a result in this app

        What NOT to save:
        - Task-specific steps (use scratchpad instead)
        - Temporary data that won't help future tasks
        - Information already in Recalled Memory or App Skills

        Keep entries to 1-2 sentences. Generalize -- don't reference specific task details.
        """.trimIndent()

    override val parameterSchema: JSONObject =
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("agent_thought", JSONObject().apply {
                    put("type", "string")
                    put("description", "Brief reason for saving this experience")
                })
                put("category", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray(listOf("app", "user_pref", "device")))
                    put("description", "Where to store: app = app-specific quirk, user_pref = user preference, device = device fact")
                })
                put("content", JSONObject().apply {
                    put("type", "string")
                    put("description", "The learning to save (1-2 sentences, prefixed with [workflow], [pitfall], or [verification])")
                })
                put("package_name", JSONObject().apply {
                    put("type", "string")
                    put("description", "Target app package name (required when category = app)")
                })
            })
            put("required", JSONArray(listOf("category", "content")))
            put("additionalProperties", false)
        }

    override fun validate(params: JSONObject): ValidationResult {
        val category = params.optString("category", "")
        if (category !in listOf("app", "user_pref", "device")) {
            return ValidationResult.Invalid("category must be: app, user_pref, or device")
        }
        val content = params.optString("content", "").trim()
        if (content.isEmpty()) return ValidationResult.Invalid("content must not be empty")
        if (content.length > store.maxContentLength) {
            return ValidationResult.Invalid("content too long (max ${store.maxContentLength} chars)")
        }
        if (category == "app" && params.optString("package_name", "").trim().isEmpty()) {
            return ValidationResult.Invalid("package_name required when category = app")
        }
        return ValidationResult.Valid
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val agentThought = params.optString("agent_thought", "").trim()
        val category = params.getString("category")
        val content = params.getString("content").trim()
        return RememberExperienceInvocation(
            store = store,
            params = params,
            category = category,
            content = content,
            description = appendReason("Save experience ($category)", agentThought)
        )
    }
}

private class RememberExperienceInvocation(
    private val store: MemoryStore,
    override val params: JSONObject,
    private val category: String,
    private val content: String,
    private val description: String
) : ToolInvocation {
    override val toolName: String = "remember_experience"

    override fun getDescription(): String = description

    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        if (context.isCancelled()) return ToolExecutionResult.Cancelled()
        return try {
            when (category) {
                "app" -> store.appendAppMemory(params.getString("package_name").trim(), content)
                "user_pref" -> store.appendUserPref(content)
                "device" -> store.appendDeviceMemory(content)
            }
            textToolSuccess(output = "Saved to long-term memory ($category).")
        } catch (e: Exception) {
            ToolExecutionResult.Failure("Failed to save memory: ${e.message}", e)
        }
    }
}
