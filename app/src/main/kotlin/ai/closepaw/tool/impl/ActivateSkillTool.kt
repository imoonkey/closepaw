package ai.closepaw.tool.impl

import ai.closepaw.agent.cognition.skills.ActivationResult
import ai.closepaw.agent.cognition.skills.AgentSkillManager
import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ToolInvocation
import ai.closepaw.tool.ToolSpec
import ai.closepaw.tool.ValidationResult
import ai.closepaw.tool.appendReason
import ai.closepaw.tool.textToolSuccess
import org.json.JSONArray
import org.json.JSONObject

class ActivateSkillTool(
    private val manager: AgentSkillManager
) : ToolSpec {

    override val name: String = "activate_skill"

    override val description: String =
        "Activate a skill by name. See the skill catalog in the system prompt for available skills."

    override val parameterSchema: JSONObject =
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("name", JSONObject().apply {
                    put("type", "string")
                    put("description", "Name of the skill to activate")
                })
                put("agent_thought", JSONObject().apply {
                    put("type", "string")
                    put("description", "Brief reason for activating this skill")
                })
            })
            put("required", JSONArray(listOf("name")))
            put("additionalProperties", false)
        }

    override fun validate(params: JSONObject): ValidationResult {
        val name = params.optString("name", "").trim()
        if (name.isEmpty()) {
            return ValidationResult.Invalid("Missing required parameter: name")
        }
        return ValidationResult.Valid
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val skillName = params.getString("name").trim()
        val agentThought = params.optString("agent_thought", "").trim()
        val description = appendReason("Activate skill '$skillName'", agentThought)
        return ActivateSkillInvocation(
            manager = manager,
            params = params,
            skillName = skillName,
            description = description
        )
    }
}

private class ActivateSkillInvocation(
    private val manager: AgentSkillManager,
    override val params: JSONObject,
    private val skillName: String,
    private val description: String
) : ToolInvocation {

    override val toolName: String = "activate_skill"

    override fun getDescription(): String = description

    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        if (context.isCancelled()) {
            return ToolExecutionResult.Cancelled("Cancelled before execution")
        }

        return when (val result = manager.activate(skillName)) {
            is ActivationResult.Success -> textToolSuccess(result.body)
            is ActivationResult.AlreadyActive ->
                textToolSuccess("Skill '${result.name}' is already active.")
            is ActivationResult.NotFound ->
                ToolExecutionResult.Failure("Unknown skill '${result.name}'. Check the skill catalog in the system prompt.")
        }
    }
}
