package com.moonkey.androidagent.domain.agents

import com.moonkey.androidagent.data.llm.ChatMessage
import com.moonkey.androidagent.data.llm.LLMClient
import com.moonkey.androidagent.data.llm.Role
import com.moonkey.androidagent.data.perception.Perceptor
import com.moonkey.androidagent.domain.models.ManagerResult
import com.moonkey.androidagent.domain.models.ScreenSnapshot
import com.moonkey.androidagent.domain.state.InfoPool
import org.json.JSONObject

class Manager : Agent<ManagerResult> {

    override suspend fun think(scope: InfoPool, context: ScreenSnapshot): ManagerResult {
        val prompt = buildPrompt(scope, context)

        val messages =
                listOf(ChatMessage(Role.SYSTEM, SYSTEM_PROMPT), ChatMessage(Role.USER, prompt))

        val response = LLMClient.chat(messages)
        return parseResponse(response)
    }

    private fun buildPrompt(scope: InfoPool, context: ScreenSnapshot): String {
        val sb = StringBuilder()
        sb.append("### User Request ###\n${scope.instruction}\n\n")

        if (scope.plan.isEmpty()) {
            sb.append(
                    "### Task ###\nMake a high-level plan to achieve the request. The screen context is provided below.\n"
            )
        } else {
            sb.append("### Current Plan ###\n${scope.plan}\n\n")
            if (scope.actionHistory.isNotEmpty()) {
                sb.append("### History ###\nLast Action: ${scope.actionHistory.lastOrNull()}\n")
            }
            sb.append(
                    "### Completed Subgoal ###\n${scope.currentSubgoal}\n\n"
            ) // Renamed in InfoPool logic, but kept concept.
        }

        if (scope.errorFlagPlan) {
            sb.append(
                    "### WARNING ###\nThe executor is stuck. Please revise the plan to try a different approach.\n\n"
            )
        }

        // Screen Context
        val screenJson = Perceptor.toPromptJson(context)
        sb.append("### Screen Context ###\n$screenJson\n\n")

        return sb.toString()
    }

    private fun parseResponse(json: String): ManagerResult {
        return try {
            val obj = JSONObject(json)
            ManagerResult(
                    thought = obj.optString("thought", ""),
                    plan = obj.optString("plan", ""),
                    completedSubgoal = obj.optString("completed_subgoal", "No completed subgoal.")
            )
        } catch (e: Exception) {
            ManagerResult("Error parsing JSON: $json", "Finished", "")
        }
    }

    companion object {
        val SYSTEM_PROMPT =
                """
            You are a Manager Agent for an Android phone.
            Your job is to create and maintain a plan to achieve the user's ACTUAL GOAL.
            
            Output strictly in JSON format:
            {
                "thought": "1) What is the user's end goal? 2) What has been achieved? 3) What remains?",
                "plan": "Remaining steps to achieve the goal (or 'Finished' if goal achieved)",
                "completed_subgoal": "What was just accomplished"
            }
            
            THINKING FRAMEWORK:
            
            1. UNDERSTAND THE GOAL: What does the user actually want to accomplish?
               - "Find shoes on Amazon" means SEEING shoe options, not just opening Amazon
               - "Send a message" means the message is SENT, not just the app is open
               - Parse the full intent, not just the first verb
            
            2. ASSESS CURRENT STATE: Look at Screen Context
               - Where are we now?
               - What progress has been made toward the ACTUAL goal?
               
            3. DETERMINE REMAINING WORK:
               - What steps are still needed to fully satisfy the user's request?
               - Only mark "Finished" when the user's ACTUAL INTENT is achieved
               
            4. CREATE ACTIONABLE STEPS:
               - Each step should move toward the goal
               - Be specific about what needs to happen
            
            KEY PRINCIPLE: The task is complete ONLY when the user's actual intent is satisfied.
            Opening an app or website is usually a MEANS, not the END goal.
        """.trimIndent()
    }
}
