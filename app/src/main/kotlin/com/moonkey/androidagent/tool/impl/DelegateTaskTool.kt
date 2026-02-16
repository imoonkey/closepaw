package com.moonkey.androidagent.tool.impl

import com.moonkey.androidagent.agent.subagent.AgentDefinition
import com.moonkey.androidagent.agent.subagent.AgentRegistry
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

class DelegateTaskTool(
    private val sessionId: SessionId,
    private val registry: AgentRegistry,
    private val runnerFactory: (AgentDefinition) -> SubAgentRunner,
    private val eventEmitter: suspend (AgentEvent) -> Unit
) : ToolSpec {

    override val name: String = "delegate_task"

    override val description: String =
        """
        Delegate ONE atomic UI action to a sub-agent.

        Available agents:
        ${registry.getDirectoryPrompt()}

        ## Query Format (ATOMIC intents):
        - TAP: "Tap on the 'Send' button", "Tap the first email in the list"
        - SCROLL: "Scroll down to reveal more items", "Scroll up"
        - EXTRACT: "Extract sender, subject from current email view"
        - TYPE: "Type 'hello' into the search field"
        - BACK: "Press back to return to previous screen"

        BAD: "Open app, navigate to settings, change theme" (too many steps!)
        GOOD: "Tap on the Settings icon" (one atomic action)

        The executor will ground your semantic intent to the actual UI element.
        """.trimIndent()

    override val parameterSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
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
            put("agent_thought", JSONObject().apply {
                put("type", "string")
                put("description", "Brief reason for this delegation")
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
        } else if (registry.get(agentName) == null) {
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
        val definition = registry.get(agentName)
            ?: throw IllegalArgumentException("Unknown agent: $agentName")
        val description = buildDescription(agentName, query, agentThought)

        return DelegateTaskInvocation(
            sessionId = sessionId,
            params = params,
            definition = definition,
            request = SubAgentRequest(
                query = query,
                currentSubgoal = currentSubgoal,
                importantNotes = importantNotes
            ),
            description = description,
            runnerFactory = runnerFactory,
            eventEmitter = eventEmitter
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
    private val sessionId: SessionId,
    override val params: JSONObject,
    private val definition: AgentDefinition,
    private val request: SubAgentRequest,
    private val description: String,
    private val runnerFactory: (AgentDefinition) -> SubAgentRunner,
    private val eventEmitter: suspend (AgentEvent) -> Unit
) : ToolInvocation {

    override val toolName: String = "delegate_task"

    override fun getDescription(): String = description

    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        if (context.isCancelled()) {
            return ToolExecutionResult.Cancelled("Cancelled before execution")
        }

        val requestWithCallId = request.copy(delegationCallId = context.callId)

        eventEmitter(
            SubAgentStarted(
                sessionId = sessionId,
                timestamp = System.currentTimeMillis(),
                agentName = definition.name,
                query = requestWithCallId.query
            )
        )

        val result = runnerFactory(definition).run(requestWithCallId)

        eventEmitter(
            SubAgentCompleted(
                sessionId = sessionId,
                timestamp = System.currentTimeMillis(),
                agentName = definition.name,
                success = result.success,
                message = result.message
            )
        )

        val output = if (result.success) {
            result.message
        } else {
            "Sub-agent failed: ${result.message}"
        }

        return textToolSuccess(
                output = output,
                data = mapOf(
                        "agent" to definition.name,
                        "success" to result.success,
                        "message" to result.message
                )
        )
    }
}
