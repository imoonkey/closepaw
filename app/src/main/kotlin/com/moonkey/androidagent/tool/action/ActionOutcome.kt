package com.moonkey.androidagent.tool.action

import com.moonkey.androidagent.tool.ToolObservation

/**
 * Result of executor-level action execution.
 *
 * Richer than ActionResult: includes post-action observation and optional
 * attempt trail metadata for debuggability.
 */
sealed interface ActionOutcome {
    data class Success(
        val message: String,
        val observation: ToolObservation?,
        val attemptTrail: List<String>,
        val verified: Boolean = true
    ) : ActionOutcome

    data class Failed(
        val reason: String,
        val attemptTrail: List<String>
    ) : ActionOutcome

    data class Cancelled(
        val reason: String
    ) : ActionOutcome
}
