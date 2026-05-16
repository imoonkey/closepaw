package ai.closepaw.history

import android.util.Log

/**
 * HistoryManager - Manages conversation history for agent sessions.
 *
 * Key features:
 * - Tracks conversation history as a list of ResponseItems
 * - Supports truncation policies for tool outputs (to manage context window)
 * - History normalization (ensures call/output pairs match)
 * - Proactive screen downgrade on every new SCREEN_OBSERVATION
 *
 * Lossy eviction lives in the past. Context-window pressure is now handled by
 * [Compactor] via [snapshot] / [replaceAllIfRevision].
 *
 * Note: This manages CONVERSATION history, not memory files.
 */
class HistoryManager(
    private val config: HistoryConfig = HistoryConfig()
) {

    companion object {
        private const val TAG = "HistoryManager"
        private const val TOKENS_PER_CHAR = 0.25f
        internal val ELEMENT_COUNT_REGEX = Regex("""Screen state \((\d+) elements\)""")
    }

    // The conversation history — guarded by @Synchronized for thread safety.
    // Supplement injection (main thread) can race with agent turn recording (IO dispatcher).
    private val items = mutableListOf<ResponseItem>()

    // Token usage tracking (null = needs recalculation, fixes H2 bug)
    private var lastTokenEstimate: Long? = null
    @Volatile
    private var onMutation: (() -> Unit)? = null

    // Monotonically increasing revision — bumped by every mutation. Enables CAS
    // swap from Compactor so a concurrent supplement during a long LLM call
    // cannot be silently overwritten.
    @Volatile
    private var _revision: Long = 0
    val revision: Long get() = _revision

    fun setMutationListener(listener: (() -> Unit)?) {
        onMutation = listener
    }

    /**
     * Add a single item to history.
     *
     * When a [MessageKind.SCREEN_OBSERVATION] is added, proactively downgrades
     * older screen observations beyond [HistoryConfig.recentFullScreens].
     */
    @Synchronized
    fun addItem(item: ResponseItem) {
        val processed = processItem(item, config.defaultTruncationPolicy)
        items.add(processed)
        lastTokenEstimate = null
        _revision++

        if (item is ResponseItem.Message && item.kind == MessageKind.SCREEN_OBSERVATION) {
            downgradeOldScreens()
        }

        Log.d(TAG, "Added item: ${item.javaClass.simpleName}, total items: ${items.size}")
        onMutation?.invoke()
    }

    /**
     * Record multiple items from a turn.
     *
     * @param newItems Items to add
     * @param policy Truncation policy for tool outputs
     */
    @Synchronized
    fun recordItems(newItems: List<ResponseItem>, policy: TruncationPolicy = config.defaultTruncationPolicy) {
        var hasNewScreen = false
        newItems.forEach { item ->
            val processed = processItem(item, policy)
            items.add(processed)
            if (item is ResponseItem.Message && item.kind == MessageKind.SCREEN_OBSERVATION) {
                hasNewScreen = true
            }
        }
        lastTokenEstimate = null
        _revision++

        if (hasNewScreen) downgradeOldScreens()

        Log.d(TAG, "Recorded ${newItems.size} items, total: ${items.size}")
        onMutation?.invoke()
    }

    /**
     * Replace the whole history with an externally prepared list.
     *
     * Used by session reload to restore an exact checkpoint without re-running
     * truncation/compression side effects.
     */
    @Synchronized
    fun replaceAll(newItems: List<ResponseItem>) {
        items.clear()
        items.addAll(newItems)
        lastTokenEstimate = null
        _revision++
        Log.d(TAG, "History replaced, total items: ${items.size}")
    }

    /**
     * Atomic read of (revision, items snapshot). Use with [replaceAllIfRevision]
     * to perform a read-modify-write under contention without holding the
     * monitor for the duration of the work (e.g. an LLM summarization call).
     */
    @Synchronized
    fun snapshot(): Pair<Long, List<ResponseItem>> = _revision to items.toList()

    /**
     * CAS-replace history. Swaps only if [expected] matches the current
     * revision; returns true on swap, false on mismatch (caller should discard
     * derived state and retry). Bumps revision and notifies mutation listener
     * on success.
     */
    @Synchronized
    fun replaceAllIfRevision(expected: Long, newItems: List<ResponseItem>): Boolean {
        if (_revision != expected) return false
        items.clear()
        items.addAll(newItems)
        lastTokenEstimate = null
        _revision++
        onMutation?.invoke()
        return true
    }

    /** Get all history items (defensive copy). */
    @Synchronized
    fun getAll(): List<ResponseItem> = items.toList()

    /**
     * Get history prepared for sending to the LLM.
     * Performs normalization to ensure call/output pairs match.
     */
    @Synchronized
    fun forPrompt(): List<ResponseItem> {
        return normalizeHistory(items.toList())
    }

    @Synchronized
    fun size(): Int = items.size

    @Synchronized
    fun isEmpty(): Boolean = items.isEmpty()

    @Synchronized
    fun clear() {
        items.clear()
        lastTokenEstimate = null
        _revision++
        Log.d(TAG, "History cleared")
    }

    /**
     * Estimate total token count for context window management.
     * Uses nullable type to avoid returning 0 on first call.
     */
    @Synchronized
    fun estimateTokenCount(): Long {
        lastTokenEstimate?.let { return it }
        val estimate = items.sumOf { it.estimateTokens() }
        lastTokenEstimate = estimate
        return estimate
    }

    fun isApproachingLimit(maxTokens: Long, warningThreshold: Float = 0.8f): Boolean {
        return estimateTokenCount() >= (maxTokens * warningThreshold).toLong()
    }

    // ===== Compression Pipeline =====

    /**
     * Lightweight compression: normalize call/output pairs and downgrade old
     * screen observations to one-liners.
     *
     * Lossy eviction was removed when [Compactor] took over context-window
     * pressure. This method now only applies the cheap, lossless passes and
     * returns whether the result is below [targetTokens]. The [targetTokens]
     * argument is retained for the caller's diagnostics; this method does not
     * try to force the result under it.
     */
    @Synchronized
    fun compress(targetTokens: Long): CompressionResult {
        val before = estimateTokenCount()
        if (before <= targetTokens) return CompressionResult.Noop(before, before)

        Log.d(TAG, "Compressing: $before → target $targetTokens tokens")

        val normalized = normalizeHistory(items.toList())
        items.clear()
        items.addAll(normalized)
        lastTokenEstimate = null
        _revision++

        downgradeOldScreens()
        lastTokenEstimate = null

        val after = estimateTokenCount()
        onMutation?.invoke()
        return if (after < before) {
            CompressionResult.Compressed(before, after, stepsApplied = 1)
        } else {
            CompressionResult.Noop(before, after)
        }
    }

    /** Debug summary. */
    @Synchronized
    fun getSummary(): String {
        return buildString {
            appendLine("History Summary:")
            appendLine("  Items: ${items.size}")
            appendLine("  Estimated tokens: ${estimateTokenCount()}")
            appendLine("  Item types:")
            items.groupBy { it.javaClass.simpleName }
                .forEach { (type, list) ->
                    appendLine("    - $type: ${list.size}")
                }
        }
    }

    // ===== Private Helpers =====

    private fun processItem(item: ResponseItem, policy: TruncationPolicy): ResponseItem {
        return when (item) {
            is ResponseItem.FunctionCallOutput -> truncateOutput(item, policy)
            else -> item
        }
    }

    private fun truncateOutput(output: ResponseItem.FunctionCallOutput, policy: TruncationPolicy): ResponseItem.FunctionCallOutput {
        if (policy == TruncationPolicy.NONE) return output
        val maxChars = (policy.maxTokens / TOKENS_PER_CHAR).toInt()
        if (output.content.length <= maxChars) return output
        val truncated = output.content.take(maxChars) +
            "\n...[truncated, ${output.content.length - maxChars} chars omitted]"
        return output.copy(content = truncated, truncated = true)
    }

    /**
     * Downgrade all but the last [HistoryConfig.recentFullScreens] screen observations
     * to compact one-line summaries. Runs proactively on every new screen observation.
     */
    private fun downgradeOldScreens() {
        val screenIndices = items.withIndex()
            .filter { (_, item) ->
                item is ResponseItem.Message && item.kind == MessageKind.SCREEN_OBSERVATION
            }
            .map { it.index }

        if (screenIndices.size <= config.recentFullScreens) return

        val toDowngrade = screenIndices.dropLast(config.recentFullScreens)
        for (idx in toDowngrade) {
            val msg = items[idx] as ResponseItem.Message
            if (msg.content.startsWith("Screen:") && msg.content.contains("(compressed)")) continue
            items[idx] = msg.copy(content = compressScreenContent(msg.content))
        }
        lastTokenEstimate = null
    }

    /**
     * Distill a full screen-state message to a compact summary.
     */
    internal fun compressScreenContent(fullContent: String): String {
        val count = ELEMENT_COUNT_REGEX.find(fullContent)?.groupValues?.get(1)
        return if (count != null) {
            "Screen: $count elements (compressed)"
        } else if (fullContent.contains("No accessibility tree") ||
            fullContent.contains("no accessibility tree") ||
            fullContent.contains("accessibility tree omitted")) {
            "Screen: screenshot only (compressed)"
        } else {
            "Screen: unknown (compressed)"
        }
    }

    private fun normalizeHistory(items: List<ResponseItem>): List<ResponseItem> {
        val result = items.toMutableList()
        val callIds = result.filterIsInstance<ResponseItem.FunctionCall>().map { it.id }.toSet()
        val outputCallIds = result.filterIsInstance<ResponseItem.FunctionCallOutput>().map { it.callId }.toSet()

        callIds.minus(outputCallIds).forEach { callId ->
            val callIndex = result.indexOfFirst { it is ResponseItem.FunctionCall && it.id == callId }
            if (callIndex >= 0) {
                result.add(callIndex + 1, ResponseItem.FunctionCallOutput(
                    callId = callId,
                    content = "[Output not recorded]",
                    success = false
                ))
            }
        }

        result.removeAll {
            it is ResponseItem.FunctionCallOutput && it.callId !in callIds
        }

        return result
    }
}

/** Result of a [HistoryManager.compress] call. */
sealed class CompressionResult {
    data class Noop(val before: Long, val after: Long) : CompressionResult()
    data class Compressed(val before: Long, val after: Long, val stepsApplied: Int) : CompressionResult()
}
