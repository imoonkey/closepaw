package com.moonkey.androidagent.history

/** Configuration for history management. */
data class HistoryConfig(
    val defaultTruncationPolicy: TruncationPolicy = TruncationPolicy.CONSERVATIVE,
    val maxTokenBudget: Long = 100_000,
    val autoCompress: Boolean = true,
    val autoCompressThreshold: Float = 0.85f,
    val compressTargetRatio: Float = 0.5f,
    val recentFullScreens: Int = 3,
    val recentWindowSize: Int = 10
)

/** Controls how much tool output to keep in history. */
enum class TruncationPolicy(val maxTokens: Int) {
    NONE(-1),
    CONSERVATIVE(8000),
    AGGRESSIVE(2000),
    MINIMAL(500)
}
