package com.moonkey.androidagent.history

import android.util.Log

/**
 * HistoryManager - Manages conversation history for agent sessions.
 *
 * Key features:
 * - Tracks conversation history as a list of ResponseItems
 * - Supports truncation policies for tool outputs (to manage context window)
 * - History normalization (ensures call/output pairs match)
 * - Multi-phase compression pipeline: screen downgrade → group eviction → hard guard
 * - Proactive screen downgrade on every new SCREEN_OBSERVATION
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

        // Proactive screen downgrade (Phase 1 of design)
        if (item is ResponseItem.Message && item.kind == MessageKind.SCREEN_OBSERVATION) {
            downgradeOldScreens()
        }

        Log.d(TAG, "Added item: ${item.javaClass.simpleName}, total items: ${items.size}")
        autoCompressIfNeeded()
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

        if (hasNewScreen) downgradeOldScreens()

        Log.d(TAG, "Recorded ${newItems.size} items, total: ${items.size}")
        autoCompressIfNeeded()
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
        Log.d(TAG, "History replaced, total items: ${items.size}")
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
     * Compress history to fit within [targetTokens].
     *
     * Pipeline:
     * - Phase 0: Normalize call/output pairs.
     * - Phase 1: Downgrade old screen observations to one-liners.
     * - Phase 2: Group-aware eviction with COMPRESSION_DIGEST breadcrumb.
     * - Phase 3: Merge adjacent digests; return BudgetUnreachable if impossible.
     */
    @Synchronized
    fun compress(targetTokens: Long): CompressionResult {
        val before = estimateTokenCount()
        if (before <= targetTokens) return CompressionResult.Noop(before, before)

        Log.d(TAG, "Compressing: $before → target $targetTokens tokens")
        var stepsApplied = 0

        // Phase 0: normalize
        val normalized = normalizeHistory(items.toList())
        items.clear()
        items.addAll(normalized)
        lastTokenEstimate = null

        // Phase 1: screen downgrade
        downgradeOldScreens()
        lastTokenEstimate = null
        if (estimateTokenCount() <= targetTokens) {
            stepsApplied++
            val after = estimateTokenCount()
            Log.d(TAG, "Compression done after Phase 1: $after tokens")
            onMutation?.invoke()
            return CompressionResult.Compressed(before, after, stepsApplied)
        }
        stepsApplied++

        // Phase 2: group-aware eviction
        val protectedTail = config.recentWindowSize.coerceAtMost(items.size)
        var evictedScreens = 0
        var evictedActions = 0
        var firstEvictionIndex = -1

        // Running token total — updated incrementally as we evict to avoid
        // O(n) full-history rescans inside the eviction loop.
        var runningTokens = estimateTokenCount()

        // Dynamic boundary: protectedTail items at the end are never touched.
        // Recalculated every iteration because removals shift items left.
        var i = 0
        while (i < (items.size - protectedTail).coerceAtLeast(0) &&
            runningTokens > targetTokens
        ) {
            val item = items[i]

            // Never evict USER_INTENT
            if (item is ResponseItem.Message && item.kind == MessageKind.USER_INTENT) {
                i++
                continue
            }

            // Skip existing COMPRESSION_DIGEST (will be merged in Phase 3)
            if (item is ResponseItem.Message && item.kind == MessageKind.COMPRESSION_DIGEST) {
                i++
                continue
            }

            if (firstEvictionIndex < 0) firstEvictionIndex = i

            when (item) {
                is ResponseItem.Message -> {
                    if (item.kind == MessageKind.SCREEN_OBSERVATION) {
                        evictedScreens++
                    } else {
                        evictedActions++
                    }
                    runningTokens -= item.estimateTokens()
                    items.removeAt(i)
                }
                is ResponseItem.FunctionCall -> {
                    // Remove call + paired output as atomic group
                    val callId = item.id
                    runningTokens -= item.estimateTokens()
                    items.removeAt(i)
                    val dynamicBoundary = (items.size - protectedTail).coerceAtLeast(0)
                    val outputIdx = items.indexOfFirst {
                        it is ResponseItem.FunctionCallOutput && it.callId == callId
                    }
                    if (outputIdx >= 0 && outputIdx < dynamicBoundary) {
                        runningTokens -= items[outputIdx].estimateTokens()
                        items.removeAt(outputIdx)
                        if (outputIdx < i) i--
                        evictedActions++ // count the paired output as a separate eviction
                    }
                    evictedActions++ // count the call
                }
                is ResponseItem.FunctionCallOutput -> {
                    // Orphaned output (call already removed) — safe to evict
                    evictedActions++
                    runningTokens -= item.estimateTokens()
                    items.removeAt(i)
                }
            }
        }
        lastTokenEstimate = if (evictedScreens + evictedActions > 0) null else lastTokenEstimate

        // Insert digest breadcrumb if we evicted anything
        if (evictedScreens + evictedActions > 0) {
            val totalEvicted = evictedScreens + evictedActions
            val digestContent = buildString {
                append("[Compressed] Removed $totalEvicted earlier items: ")
                val parts = mutableListOf<String>()
                if (evictedActions > 0) parts.add("$evictedActions tool actions")
                if (evictedScreens > 0) parts.add("$evictedScreens screen observations")
                append(parts.joinToString(", "))
                append(". History truncated to save context.")
            }
            val insertAt = firstEvictionIndex.coerceAtMost(items.size)
            items.add(insertAt, ResponseItem.Message(
                kind = MessageKind.COMPRESSION_DIGEST,
                content = digestContent
            ))
            lastTokenEstimate = null
            stepsApplied++
        }

        // Phase 3: merge adjacent digests + hard guard
        mergeAdjacentDigests()
        lastTokenEstimate = null

        val after = estimateTokenCount()
        if (after > targetTokens) {
            // Check if only USER_INTENT + digests remain
            val onlyProtected = items.all { item ->
                item is ResponseItem.Message &&
                    (item.kind == MessageKind.USER_INTENT || item.kind == MessageKind.COMPRESSION_DIGEST)
            }
            if (onlyProtected) {
                Log.w(TAG, "BudgetUnreachable: $after tokens, only USER_INTENT + digests remain")
                onMutation?.invoke()
                return CompressionResult.BudgetUnreachable(after, after)
            }
        }

        Log.d(TAG, "Compression done: $before → $after tokens, $stepsApplied steps")
        onMutation?.invoke()
        return if (after < before) {
            CompressionResult.Compressed(before, after, stepsApplied)
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
            // Skip already-compressed screens
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

    /** Merge adjacent COMPRESSION_DIGEST messages into one. */
    private fun mergeAdjacentDigests() {
        var i = 0
        while (i < items.size - 1) {
            val current = items[i]
            val next = items[i + 1]
            if (current is ResponseItem.Message && current.kind == MessageKind.COMPRESSION_DIGEST &&
                next is ResponseItem.Message && next.kind == MessageKind.COMPRESSION_DIGEST
            ) {
                val merged = ResponseItem.Message(
                    kind = MessageKind.COMPRESSION_DIGEST,
                    content = current.content + "\n" + next.content
                )
                items[i] = merged
                items.removeAt(i + 1)
                // Don't increment — check if the merged result is also adjacent to another digest
            } else {
                i++
            }
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

    private fun autoCompressIfNeeded() {
        if (!config.autoCompress) return
        if (config.maxTokenBudget <= 0) return
        val trigger = (config.maxTokenBudget * config.autoCompressThreshold).toLong()
        if (estimateTokenCount() > trigger) {
            val target = (config.maxTokenBudget * config.compressTargetRatio).toLong()
            compress(target)
        }
    }
}

/** Result of a [HistoryManager.compress] call. */
sealed class CompressionResult {
    data class Noop(val before: Long, val after: Long) : CompressionResult()
    data class Compressed(val before: Long, val after: Long, val stepsApplied: Int) : CompressionResult()
    data class BudgetUnreachable(val after: Long, val minimumPossible: Long) : CompressionResult()
}
