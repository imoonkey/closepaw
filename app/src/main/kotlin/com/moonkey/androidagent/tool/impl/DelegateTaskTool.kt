package com.moonkey.androidagent.tool.impl

import com.moonkey.androidagent.agent.AgentEventDispatcher
import com.moonkey.androidagent.agent.definition.AgentRoleDef
import com.moonkey.androidagent.agent.subagent.SubAgentRequest
import com.moonkey.androidagent.agent.subagent.SubAgentRunner
import com.moonkey.androidagent.protocol.*
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ToolSpec
import com.moonkey.androidagent.tool.ValidationResult
import com.moonkey.androidagent.tool.appendReason
import com.moonkey.androidagent.tool.textToolSuccess
import org.json.JSONArray
import org.json.JSONObject

internal class DelegateTaskTool(
    private val delegatableRoles: List<AgentRoleDef>,
    private val runnerFactory: (AgentRoleDef) -> SubAgentRunner,
    private val eventDispatcher: AgentEventDispatcher
) : ToolSpec {

    private val rolesByName = delegatableRoles.associateBy { it.name }

    override val name: String = "delegate_task"

    override val description: String =
        """
        Delegate ONE atomic UI action to a sub-agent.

        Available agents:
        ${delegatableRoles.joinToString("\n") { "- ${it.name}: ${it.description}" }}

        Query must be a single atomic intent: "Tap the Send button", "Scroll down", "Extract sender and subject", "Type 'hello' into search". NOT multi-step ("Open app, navigate to settings, change theme").
        """.trimIndent()

    override val parameterSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("agent_thought", JSONObject().apply {
                put("type", "string")
                put("description", "Brief reason for this delegation")
            })
            put("agent_name", JSONObject().apply {
                put("type", "string")
                put("description", "Name of sub-agent to run")
            })
            put("query", JSONObject().apply {
                put("type", "string")
                put("description", "Complete instruction for the sub-agent")
            })
            put("current_subgoal", JSONObject().apply {
                put("type", "string")
                put("description", "Optional current subgoal context")
            })
            put("important_notes", JSONObject().apply {
                put("type", "array")
                put("description", "Optional short notes to preserve context")
                put("items", JSONObject().apply { put("type", "string") })
            })
        })
        put("required", JSONArray(listOf("agent_name", "query")))
        put("additionalProperties", false)
    }

    override fun validate(params: JSONObject): ValidationResult {
        val errors = mutableListOf<String>()

        val agentName = params.optString("agent_name", "").trim()
        if (agentName.isEmpty()) {
            errors.add("Missing required parameter: agent_name")
        } else if (agentName !in rolesByName) {
            errors.add("Unknown agent: $agentName")
        }

        val query = params.optString("query", "").trim()
        if (query.isEmpty()) {
            errors.add("Missing required parameter: query")
        }

        if (params.has("important_notes") && params.optJSONArray("important_notes") == null) {
            errors.add("important_notes must be an array of strings")
        }

        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val agentName = params.getString("agent_name")
        val query = params.getString("query").trim()
        val currentSubgoal = params.optString("current_subgoal", "").trim().ifEmpty { null }
        val importantNotes = parseStringArray(params.optJSONArray("important_notes"))
        val agentThought = params.optString("agent_thought", "").trim()
        val roleDef = rolesByName[agentName]
            ?: throw IllegalArgumentException("Unknown agent: $agentName")
        val description = buildDescription(agentName, query, agentThought)

        return DelegateTaskInvocation(
            params = params,
            roleDef = roleDef,
            request = SubAgentRequest(
                query = query,
                currentSubgoal = currentSubgoal,
                importantNotes = importantNotes
            ),
            description = description,
            runnerFactory = runnerFactory,
            eventDispatcher = eventDispatcher
        )
    }

    private fun buildDescription(agentName: String, query: String, thought: String): String {
        val queryPreview = query.take(80)
        val base = "Delegate to $agentName: $queryPreview"
        return appendReason(base, thought)
    }

    private fun parseStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()

        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index, "").trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    }
}

private class DelegateTaskInvocation(
    override val params: JSONObject,
    private val roleDef: AgentRoleDef,
    private val request: SubAgentRequest,
    private val description: String,
    private val runnerFactory: (AgentRoleDef) -> SubAgentRunner,
    private val eventDispatcher: AgentEventDispatcher
) : ToolInvocation {

    override val toolName: String = "delegate_task"

    override fun getDescription(): String = description

    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        if (context.isCancelled()) {
            return ToolExecutionResult.Cancelled("Cancelled before execution")
        }

        val requestWithCallId = request.copy(delegationCallId = context.callId)

        eventDispatcher.subAgentStarted(
            agentName = roleDef.name,
            query = requestWithCallId.query
        )

        val result = runnerFactory(roleDef).run(requestWithCallId)

        eventDispatcher.subAgentCompleted(
            agentName = roleDef.name,
            success = result.success,
            message = result.message
        )

        val output = if (result.success) {
            result.message
        } else {
            "Sub-agent failed: ${result.message}"
        }

        return textToolSuccess(
                output = output,
                data = mapOf(
                        "agent" to roleDef.name,
                        "success" to result.success,
                        "message" to result.message
                )
        )
    }
}
