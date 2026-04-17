package ai.closepaw.protocol

/** Whether the agent needs text input or a physical action from the user. */
enum class AskUserType {
    /** Agent needs a text answer from the user. */
    QUESTION,

    /** Agent needs the user to perform a physical action on the phone. */
    ACTION
}
