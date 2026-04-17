package ai.closepaw.protocol

/** Why the session as a whole ended. */
enum class SessionEndReason {
    /** User explicitly requested shutdown. */
    USER_STOPPED,

    /** Session auto-shutdown after idle timeout. */
    IDLE_TIMEOUT,

    /** Session was interrupted by external cause. */
    INTERRUPTED,
}
