package com.moonkey.androidagent.tool.impl

import com.moonkey.androidagent.protocol.Todo
import com.moonkey.androidagent.protocol.TodoStatus
import com.moonkey.androidagent.session.TodoState
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ToolSpec
import com.moonkey.androidagent.tool.ValidationResult
import com.moonkey.androidagent.tool.textToolSuccess
import org.json.JSONArray
import org.json.JSONObject

class WriteTodosTool(
    private val state: TodoState
) : ToolSpec {
    override val name: String = "write_todos"

    override val description: String =
        """
        Update the task plan. Pass the FULL list (replaces previous).
        Each item has description + status (pending, in_progress, completed, cancelled).
        At most one item can be in_progress at a time.
        Update todos when new requirements appear during execution.
        Do not use for tasks that need only 1-2 actions.
        """.trimIndent()

    override val parameterSchema: JSONObject =
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("todos", JSONObject().apply {
                    put("type", "array")
                    put("description", "Full list of todo items")
                    put("items", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("description", JSONObject().apply {
                                put("type", "string")
                                put("description", "Todo description")
                            })
                            put("status", JSONObject().apply {
                                put("type", "string")
                                put("enum", JSONArray(listOf("pending", "in_progress", "completed", "cancelled")))
                                put("description", "Todo status")
                            })
                        })
                        put("required", JSONArray(listOf("description", "status")))
                        put("additionalProperties", false)
                    })
                })
                put("agent_thought", JSONObject().apply {
                    put("type", "string")
                    put("description", "Brief reason for this update. When changing the plan, explain what changed and why.")
                })
            })
            put("required", JSONArray(listOf("todos")))
            put("additionalProperties", false)
        }

    override fun validate(params: JSONObject): ValidationResult {
        val errors = mutableListOf<String>()
        val todosArray = params.optJSONArray("todos")
        if (todosArray == null) {
            errors.add("Missing required parameter: todos")
        } else {
            val todos = parseTodos(todosArray, errors)
            if (todos.isNotEmpty()) {
                val inProgressCount = todos.count { it.status == TodoStatus.IN_PROGRESS }
                if (inProgressCount > 1) {
                    errors.add("Only one task can be IN_PROGRESS at a time")
                }
            }
        }

        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val todos = parseTodos(params.getJSONArray("todos"), mutableListOf())
        val agentThought = params.optString("agent_thought", "").trim()
        val description = if (agentThought.isNotEmpty()) {
            "Update todos (${todos.size} items) (reason: $agentThought)"
        } else {
            "Update todos (${todos.size} items)"
        }

        return WriteTodosInvocation(
            state = state,
            params = params,
            todos = todos,
            description = description
        )
    }

    private fun parseTodos(array: JSONArray, errors: MutableList<String>): List<Todo> {
        val todos = mutableListOf<Todo>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
            if (item == null) {
                errors.add("Todo at index $index must be an object")
                continue
            }
            val description = item.optString("description", "").trim()
            if (description.isEmpty()) {
                errors.add("Todo at index $index is missing description")
            }
            val statusRaw = item.optString("status", "").trim()
            val status = parseStatus(statusRaw)
            if (status == null) {
                errors.add(
                    "Todo at index $index has invalid status '$statusRaw' (use pending, in_progress, completed, cancelled)"
                )
            }
            if (description.isNotEmpty() && status != null) {
                todos.add(Todo(description = description, status = status))
            }
        }
        return todos
    }

    private fun parseStatus(raw: String): TodoStatus? {
        if (raw.isBlank()) return null
        return try {
            TodoStatus.valueOf(raw.trim().uppercase())
        } catch (_: Exception) {
            null
        }
    }
}

private class WriteTodosInvocation(
    private val state: TodoState,
    override val params: JSONObject,
    private val todos: List<Todo>,
    private val description: String
) : ToolInvocation {
    override val toolName: String = "write_todos"

    override fun getDescription(): String = description

    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        if (context.isCancelled()) {
            return ToolExecutionResult.Cancelled("Cancelled before execution")
        }

        return try {
            state.update(todos)
            val output = buildOutput(todos)
            textToolSuccess(output = output, data = todos)
        } catch (e: Exception) {
            ToolExecutionResult.Failure("Failed to update todos: ${e.message}", e)
        }
    }

    private fun buildOutput(todos: List<Todo>): String = "Plan updated (${todos.size} items)."
}
