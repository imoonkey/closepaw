package com.moonkey.androidagent.agent.cognition.prompt

internal object FailureRecoveryRules {
    val planner: String =
        """
        ## Failure Recovery (Planner)

        When executor output reports failure or step-limit summary:
        1. Read what was already attempted and avoid repeating the same method.
        2. Switch strategy: search/filter/back/open another entry point before delegating again.
        3. Use accessibility tree evidence first; screenshot is optional secondary evidence when available.
        4. If task is blocked by app state, call `complete_task(status="failure", reason="...")` with partial progress.
        """.trimIndent()

    val executor: String =
        """
        ## Failure Recovery (Executor)

        If progress stalls:
        1. Re-check the latest accessibility JSON before acting again.
        2. If screenshot is attached, use it only as supporting context (a11y tree remains primary).
        3. Avoid repeating the same interaction 3+ times; choose an alternative UI path.
        4. If blocked, call `complete_task(status="failure", reason="...")` with concrete blocker details.
        """.trimIndent()
}
