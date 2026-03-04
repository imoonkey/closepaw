package com.moonkey.androidagent.tool.impl

import com.moonkey.androidagent.agent.AgentEventDispatcher
import com.moonkey.androidagent.protocol.AskUserType
import com.moonkey.androidagent.session.UserResponseChannel
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ToolSpec
import com.moonkey.androidagent.tool.ValidationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * AskUserTool — agent asks the user for help.
 *
 * Two types:
 * - **question**: Agent needs a text answer. Capsule shows text input.
 * - **action**: Agent needs user to operate the phone. Capsule shows instruction.
 *
 * The tool suspends via [UserResponseChannel] until the user responds or
 * the 5-minute timeout expires.
 */
class AskUserTool(
    private val responseChannel: UserResponseChannel,
    private val eventDispatcher: AgentEventDispatcher
) : ToolSpec {

    companion object {
        internal const val TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
    }

    override val name = "ask_user"

    override val description = """
Ask the user for help. Two types:
- question: Ask a question and wait for a text answer.
- action: Ask the user to perform a physical action (login, permission, captcha) and wait for confirmation.

Use when:
- Login or authentication is required
- Ambiguous choice needs user preference
- Permission prompt appears that you cannot handle
- Captcha or human verification is needed

Do NOT use for:
- Progress updates (use agent_thought parameter instead)
- Things you can determine from the screen
""".trimIndent()

    override val parameterSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("agent_thought", JSONObject().apply {
                put("type", "string")
                put("description", "Brief reason for asking the user")
            })
            put("type", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("question", "action")))
                put("description", "Type of request: question (need text answer) or action (need user to operate phone)")
            })
            put("message", JSONObject().apply {
                put("type", "string")
                put("description", "The question to ask or instruction for the user. Be clear and specific.")
            })
        })
        put("required", JSONArray(listOf("type", "message")))
        put("additionalProperties", false)
    }

    override fun validate(params: JSONObject): ValidationResult {
        val type = params.optString("type", "")
        if (type !in listOf("question", "action"))
            return ValidationResult.Invalid("type must be 'question' or 'action'")
        if (params.optString("message", "").isBlank())
            return ValidationResult.Invalid("message is required")
        if (responseChannel.hasPending)
            return ValidationResult.Invalid("Another ask_user request is already pending")
        return ValidationResult.Valid
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        return AskUserInvocation(
            params = params,
            responseChannel = responseChannel,
            eventDispatcher = eventDispatcher
        )
    }
}

/**
 * Invocation for ask_user — suspends until user responds or timeout.
 */
private class AskUserInvocation(
    override val params: JSONObject,
    private val responseChannel: UserResponseChannel,
    private val eventDispatcher: AgentEventDispatcher
) : ToolInvocation {

    override val toolName = "ask_user"

    override fun getDescription(): String {
        val thought = params.optString("agent_thought", "").trim()
        val message = params.optString("message", "")
        return if (thought.isNotEmpty()) thought else "Asking user: ${message.take(40)}"
    }

    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        val typeStr = params.getString("type")
        val type = when (typeStr) {
            "question" -> AskUserType.QUESTION
            "action" -> AskUserType.ACTION
            else -> return ToolExecutionResult.Failure("Invalid ask_user type: $typeStr")
        }
        val message = params.getString("message")
        val callId = context.callId ?: java.util.UUID.randomUUID().toString()

        // Emit AskUser event → capsule transitions to WaitingFor* state
        eventDispatcher.emitAskUser(type, message, callId)

        return try {
            val response = withTimeoutOrNull(AskUserTool.TIMEOUT_MS) {
                responseChannel.awaitResponse(callId)
            }

            if (response != null) {
                val observation = when (type) {
                    AskUserType.QUESTION -> "User answered: $response"
                    AskUserType.ACTION -> "User completed the requested action. Capture fresh screen to see result."
                }
                ToolExecutionResult.Success(
                    output = observation,
                    data = mapOf("response" to response, "type" to type.name.lowercase())
                )
            } else {
                // Timeout
                ToolExecutionResult.Success(
                    output = "User did not respond within the timeout. Consider continuing without their input or trying a different approach.",
                    data = mapOf("timeout" to true, "type" to type.name.lowercase())
                )
            }
        } catch (e: CancellationException) {
            ToolExecutionResult.Cancelled("ask_user cancelled")
        }
    }
}
