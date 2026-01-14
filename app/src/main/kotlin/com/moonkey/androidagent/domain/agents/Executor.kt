package com.moonkey.androidagent.domain.agents

import com.moonkey.androidagent.data.llm.ChatMessage
import com.moonkey.androidagent.data.llm.LLMClient
import com.moonkey.androidagent.data.llm.Role
import com.moonkey.androidagent.data.perception.Perceptor
import com.moonkey.androidagent.domain.models.AgentAction
import com.moonkey.androidagent.domain.models.ScreenSnapshot
import com.moonkey.androidagent.domain.state.InfoPool
import org.json.JSONObject

class Executor : Agent<AgentAction> {

    override suspend fun think(scope: InfoPool, context: ScreenSnapshot): AgentAction {
        val prompt = buildPrompt(scope, context)

        val messages =
                listOf(ChatMessage(Role.SYSTEM, SYSTEM_PROMPT), ChatMessage(Role.USER, prompt))

        val response = LLMClient.chat(messages)
        return parseResponse(response)
    }

    private fun buildPrompt(scope: InfoPool, context: ScreenSnapshot): String {
        val sb = StringBuilder()
        sb.append("### User Request ###\n${scope.instruction}\n\n")
        sb.append("### Current Plan ###\n${scope.plan}\n\n")

        // Extract current subgoal logic (taking first line of plan)
        val currentSubgoal = scope.plan.lines().firstOrNull() ?: scope.instruction
        sb.append("### Current Subgoal ###\n$currentSubgoal\n\n")

        val screenJson = Perceptor.toPromptJson(context)
        sb.append("### Screen Context ###\n$screenJson\n\n")

        return sb.toString()
    }

    private fun parseResponse(json: String): AgentAction {
        return try {
            val obj = JSONObject(json)
            val actionType = obj.optString("action")
            val reason = obj.optString("reason")

            when (actionType) {
                "click" ->
                        AgentAction.AtomicAction(
                                type = "click",
                                elementId = obj.optInt("element_id"),
                                reason = reason
                        )
                "type" ->
                        AgentAction.AtomicAction(
                                type = "type",
                                elementId = obj.optInt("element_id"),
                                text = obj.optString("text"),
                                reason = reason
                        )
                "scroll" ->
                        AgentAction.AtomicAction(
                                type = "scroll",
                                direction = obj.optString("direction"),
                                reason = reason
                        )
                "system" ->
                        AgentAction.AtomicAction(
                                type = "system",
                                button = obj.optString("button"),
                                reason = reason
                        )
                "answer" -> AgentAction.FinishAction(reason = "Answer: ${obj.optString("text")}")
                "wait" -> AgentAction.AtomicAction(type = "wait", reason = reason)
                "done" -> AgentAction.FinishAction(reason = reason)
                else -> AgentAction.InvalidAction("Unknown action type: $actionType")
            }
        } catch (e: Exception) {
            AgentAction.InvalidAction("Error parsing JSON: ${e.message}")
        }
    }

    companion object {
        val SYSTEM_PROMPT =
                """
            You are an Executor Agent for Android.
            Your job is to decide the NEXT ACTION that moves toward the User Request.
            
            Available Actions:
            - {"action": "click", "element_id": N, "reason": "..."}
            - {"action": "type", "element_id": N, "text": "...", "reason": "..."}
            - {"action": "scroll", "direction": "up/down/left/right", "reason": "..."}
            - {"action": "system", "button": "back/home/recents", "reason": "..."}
            - {"action": "answer", "text": "final answer", "reason": "..."}  (If task requests an answer)
            - {"action": "done", "reason": "..."} (When User Request is achieved)
            
            THINKING FRAMEWORK:
            
            1. WHERE AM I? Look at Screen Context to identify current app/screen.
               - If not in the right app for the task → use "system" button "home" to navigate
               - Ignore elements from irrelevant apps (like settings or launcher controls)
            
            2. WHAT'S THE GOAL? Re-read User Request for the END state user wants.
               - "Find X" → User wants to SEE options/results for X
               - "Search for X" → User wants to SEE search results
               - "Open X" → User wants X to be running and visible
            
            3. AM I DONE? Does current screen satisfy the user's ACTUAL goal?
               - If YES → action: "done" or "answer" 
               - If NO → choose action that moves toward goal
            
            4. NEXT ACTION: What single action moves closest to the goal?
               - To open an app: first go home ("system" "home"), then click the app icon
               - To search: find search bar and type, then submit
               - To navigate: find relevant links/buttons and click
            
            KEY PRINCIPLES:
            - If current screen is unrelated to the goal, navigate away first
            - Opening an app is often just a STEP toward the actual goal
            - "Find" or "Search" means user wants to SEE results
            - Only "done" when user would be satisfied with current screen
            
            Output valid JSON only.
        """.trimIndent()
    }
}
