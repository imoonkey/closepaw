package ai.closepaw.protocol

/** How a task within a session ended. */
enum class TaskOutcome {
    /** Goal was achieved successfully. */
    GOAL_ACHIEVED,

    /** Agent decided the task cannot be completed. */
    TASK_IMPOSSIBLE,

    /** An error occurred during the task. */
    ERROR,

    /** User requested the task be stopped. */
    USER_STOPPED,
}
