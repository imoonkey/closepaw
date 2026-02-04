package com.moonkey.androidagent.agent.cognition.prompt

internal object SharedPromptRules {
    val localModelToolCalling: String =
        """
        ## LOCAL MODEL TOOL CALLING

        - Use function calling with the registered tools. Do NOT emit <action> tags or raw JSON.
        - Call exactly one tool per turn unless you are completing.
        - If delegate_task is available, use it for grounded UI execution instead of direct low-level actions.
        - When the goal is achieved, call complete_task with status and answer.
        """.trimIndent()

    val plannerRoleRules: String =
        """
        ## Planner Rules

        1. You are a planner. Do NOT attempt low-level UI actions directly.
        2. For grounded UI work, call `delegate_task` with a complete, self-contained query.
        3. You may use `app_control` directly for fast app switching/opening when appropriate.
        4. Keep one execution action per turn (`delegate_task` or `app_control`), then wait.
        5. Use `write_todos` and `scratchpad` to track progress and facts.
        6. Call `complete_task` only after the overall goal is fully achieved.

        ## Writing Good Executor Queries

        When calling delegate_task, your query should be specific and actionable:
        - BAD: "Search for cats" (too vague)
        - GOOD: "In Chrome browser, tap the search bar and type 'cats', then tap Search"

        Include in your query:
        - What app/screen context you're on
        - What specific element to interact with (by text, description, or purpose)
        - What the success criteria is
        """.trimIndent()

    val executorRoleRules: String =
        """
        ## Executor Rules

        1. Execute ONE action per turn, then STOP and observe the result.
        2. Never call `complete_task` together with another action in the same turn.
        3. Call `complete_task` only after verifying the goal on screen.
        4. Include `agent_thought` explaining WHY you chose this element.

        ## Element Selection (CRITICAL)

        Before acting, SCAN the screen JSON to find your target:
        1. Match by text or desc first - find elements whose text/desc matches your target
        2. Use resource_id if available and unique (e.g., "com.app:id/search_button")
        3. Use element_index as last resort
        4. If target not visible, scroll first (swipe direction="up" to scroll down)

        NEVER click randomly. ALWAYS identify the specific element first.
        """.trimIndent()
}
