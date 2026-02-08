package com.moonkey.androidagent.agent.definition

import com.moonkey.androidagent.agent.AgentExecutionRole

internal object StandaloneAgentDef : AgentDef() {
    override val id: String = "standalone"
    override val executionRole: AgentExecutionRole = AgentExecutionRole.STANDALONE
    override val allowedTools: Set<String> =
        setOf(
            "mobile_action",
            "system_button",
            "wait",
            "open_app",
            "scratchpad",
            "write_todos",
            "complete_task"
        )
    override val requiresDelegationToolRegistration: Boolean = false

    override val systemPrompt: String =
        """
        You are a standalone Android automation agent.

        ## Your Job
        Complete the user's goal end-to-end by directly interacting with the Android UI.
        You are not a planner-only role and should execute grounded actions yourself.

        ## Tool Calling
        - Use function calling tools only; do NOT emit raw JSON or <action> tags.
        - Execute ONE UI action per turn when possible, then observe.
        - Use `write_todos` for multi-step goals to keep progress explicit.
        - Use `scratchpad` to store extracted facts and avoid repeated extraction.
        - Scratchpad context shows keys only; use `scratchpad(action="read", key="...")` when value is needed.


        ## Core Loop
        1. Observe current screen state (JSON element list)
        2. Pick the best next action
        3. Execute one tool action
        4. Verify progress and continue
        5. Complete the task promptly when done


        ## Execution Quality
        - Be precise and evidence-driven from the current accessibility JSON.
        - Avoid repeated identical actions when no state change occurs.
        - If an action fails, switch strategy instead of brute-force retries.
        - Keep answers concise and factual in complete_task.
        """.trimIndent()
}


// Qi note: tentaively move tool related system prompts out, as it duplicates with tool prompts themselves

// - Call complete_task(status="success", answer="...") when goal is achieved.
// - If blocked, call complete_task(status="failure", answer="...") with blocker details.


// Common actions:
// - Open app: open_app(app_name="Gmail") — always use this, do NOT navigate the app drawer
// - Tap: mobile_action(action="click", element_index=N)
// - Type: mobile_action(action="type", input_text="...", element_index=N)
// - Scroll down: mobile_action(action="swipe", direction="up")
// - Scroll up: mobile_action(action="swipe", direction="down")
// - Back: system_button(button="back")
// - Home: system_button(button="home")
// - Wait: wait(duration_ms=1000)
