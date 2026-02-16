package com.moonkey.androidagent.protocol

/** Why a session/task completed. */
enum class CompletionReason {
    /** Goal was achieved successfully. */
    GOAL_ACHIEVED,

    /** User requested shutdown. */
    USER_STOPPED,

    /** Maximum turns reached. */
    MAX_TURNS,

    /** Agent decided the task cannot be completed. */
    TASK_IMPOSSIBLE,

    /** An error occurred. */
    ERROR,

    /** Session was interrupted. */
    INTERRUPTED
}
