package ai.closepaw.history

/** Configuration for history management. */
data class HistoryConfig(
    val defaultTruncationPolicy: TruncationPolicy = TruncationPolicy.CONSERVATIVE,
    val recentFullScreens: Int = 2,
    val recentWindowSize: Int = 10
)

/** Controls how much tool output to keep in history. */
enum class TruncationPolicy(val maxTokens: Int) {
    NONE(-1),
    CONSERVATIVE(8000),
    AGGRESSIVE(2000),
    MINIMAL(500)
}
