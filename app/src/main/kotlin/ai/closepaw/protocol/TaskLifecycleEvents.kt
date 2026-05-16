package ai.closepaw.protocol

/** A new task has started within the session. */
data class TaskStarted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val taskId: String,
        val input: String
) : TaskLifecycleEvent

/**
 * A task has completed.
 *
 * [outcome] indicates how the task ended — goal achieved, max turns, error, etc.
 *
 * [handoff] is populated only for Virtual Display completions so chat can render
 * the "Open <App>" CTA. Null for accessibility completions.
 */
data class TaskCompleted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val taskId: String,
        val result: String?,
        val outcome: TaskOutcome,
        val handoff: CompletionHandoff? = null,
) : TaskLifecycleEvent
