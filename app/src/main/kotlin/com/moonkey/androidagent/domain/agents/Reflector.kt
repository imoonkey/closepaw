package com.moonkey.androidagent.domain.agents

import com.moonkey.androidagent.data.llm.ChatMessage
import com.moonkey.androidagent.data.llm.LLMClient
import com.moonkey.androidagent.data.llm.Role
import com.moonkey.androidagent.data.perception.Perceptor
import com.moonkey.androidagent.domain.models.AgentAction
import com.moonkey.androidagent.domain.models.ScreenSnapshot
import com.moonkey.androidagent.domain.models.ValidationOutcome
import org.json.JSONObject

class Reflector {

    suspend fun validate(
            before: ScreenSnapshot,
            after: ScreenSnapshot,
            action: AgentAction
    ): ValidationOutcome {
        val prompt = buildPrompt(before, after, action)

        val messages =
                listOf(ChatMessage(Role.SYSTEM, SYSTEM_PROMPT), ChatMessage(Role.USER, prompt))

        val response = LLMClient.chat(messages)
        return parseResponse(response)
    }

    private fun buildPrompt(
            before: ScreenSnapshot,
            after: ScreenSnapshot,
            action: AgentAction
    ): String {
        val sb = StringBuilder()
        sb.append("### Last Action ###\n$action\n\n")

        sb.append("### Screen Context (Before) ###\n${Perceptor.toPromptJson(before)}\n\n")
        sb.append("### Screen Context (After) ###\n${Perceptor.toPromptJson(after)}\n\n")

        return sb.toString()
    }

    private fun parseResponse(json: String): ValidationOutcome {
        return try {
            val obj = JSONObject(json)
            val outcome = obj.optString("outcome")
            val reason = obj.optString("reason")

            when (outcome) {
                "A" -> ValidationOutcome.Success(reason)
                "B" -> ValidationOutcome.FailedBacktrack(reason)
                "C" -> ValidationOutcome.FailedNoChange(reason)
                else -> ValidationOutcome.Success("Unknown outcome, assuming success")
            }
        } catch (e: Exception) {
            ValidationOutcome.Success("Error parsing outcome")
        }
    }

    companion object {
        val SYSTEM_PROMPT =
                """
            You are a Reflector Agent.
            Your job is to compare the Before and After screen states to verify if the Last Action was successful.
            
            Output strictly in JSON:
            {
                "outcome": "A", // A = Success (State changed as expected)
                "reason": "..."
            }
            or
            {
                "outcome": "B", // B = Failed (Wrong page/state, need backtrack)
                "reason": "..."
            }
            or
            {
                "outcome": "C", // C = Failed (No change detected)
                "reason": "..."
            }
        """.trimIndent()
    }
}
