package com.moonkey.androidagent.history

import android.util.Log
import org.json.JSONObject

/**
 * HistoryManager - Manages conversation history for agent sessions.
 * 
 * Key features (from Codex's context_manager/history.rs):
 * - Tracks conversation history as a list of ResponseItems
 * - Supports truncation policies for tool outputs (to manage context window)
 * - History normalization (ensures call/output pairs match)
 * - Turn rollback for error recovery
 * - Token budget estimation
 * 
 * Note: This manages CONVERSATION history, not memory files.
 * For instructional memory files (like GEMINI.md), see MemoryDiscovery (future).
 */
class HistoryManager(
    private val config: HistoryConfig = HistoryConfig()
) {
    
    companion object {
        private const val TAG = "HistoryManager"
        
        // Approximate tokens per character (conservative estimate for English text)
        private const val TOKENS_PER_CHAR = 0.25f
    }
    
    // The conversation history — guarded by @Synchronized for thread safety.
    // Supplement injection (main thread) can race with agent turn recording (IO dispatcher).
    private val items = mutableListOf<ResponseItem>()

    // Token usage tracking (null = needs recalculation, fixes H2 bug)
    private var lastTokenEstimate: Long? = null

    /**
     * Add a single item to history.
     */
    @Synchronized
    fun addItem(item: ResponseItem) {
        val processed = processItem(item, config.defaultTruncationPolicy)
        items.add(processed)
        lastTokenEstimate = null
        Log.d(TAG, "Added item: ${item.javaClass.simpleName}, total items: ${items.size}")
        autoCompressIfNeeded()
    }

    /**
     * Record multiple items from a turn.
     *
     * @param newItems Items to add
     * @param policy Truncation policy for tool outputs
     */
    @Synchronized
    fun recordItems(newItems: List<ResponseItem>, policy: TruncationPolicy = config.defaultTruncationPolicy) {
        newItems.forEach { item ->
            val processed = processItem(item, policy)
            items.add(processed)
        }
        lastTokenEstimate = null
        Log.d(TAG, "Recorded ${newItems.size} items, total: ${items.size}")
        autoCompressIfNeeded()
    }

    /**
     * Get all history items (defensive copy).
     */
    @Synchronized
    fun getAll(): List<ResponseItem> = items.toList()

    /**
     * Get history prepared for sending to the LLM.
     *
     * Performs normalization to ensure call/output pairs match.
     */
    @Synchronized
    fun forPrompt(): List<ResponseItem> {
        return normalizeHistory(items.toList())
    }
    
    /**
     * Get the number of items in history.
     */
    @Synchronized
    fun size(): Int = items.size

    @Synchronized
    fun isEmpty(): Boolean = items.isEmpty()

    /**
     * Clear all history.
     */
    @Synchronized
    fun clear() {
        items.clear()
        lastTokenEstimate = null
        Log.d(TAG, "History cleared")
    }
    
    /**
     * Estimate total token count for context window management.
     * 
     * This is a rough estimate - actual tokenization depends on the model.
     * Uses nullable type to avoid returning 0 on first call.
     */
    @Synchronized
    fun estimateTokenCount(): Long {
        lastTokenEstimate?.let { return it }

        val estimate = items.sumOf { it.estimateTokens() }
        lastTokenEstimate = estimate
        return estimate
    }
    
    /**
     * Check if we're approaching the context window limit.
     * 
     * @param maxTokens Maximum tokens allowed
     * @param warningThreshold Percentage threshold for warning (default 80%)
     */
    fun isApproachingLimit(maxTokens: Long, warningThreshold: Float = 0.8f): Boolean {
        return estimateTokenCount() >= (maxTokens * warningThreshold).toLong()
    }
    
    /**
     * Drop the last N user turns for rollback/retry.
     * 
     * A "user turn" is defined as a Message with role "user" and all subsequent
     * items until the next user message.
     * 
     * M7: Algorithm documentation:
     * 1. Find indices of all user messages in the history
     * 2. If n >= total user messages, cut from the first user message (drop everything)
     * 3. Otherwise, find the (total - n)th user message index as the cut point
     *    Example: [U0, A0, U1, A1, U2, A2] with n=1 → cut at U2 (index = size - 1 = 2)
     *    userTurnPositions = [0, 2, 4], cutIndex = userTurnPositions[3 - 1] = userTurnPositions[2] = 4
     * 4. Remove all items from cutIndex to end
     */
    @Synchronized
    fun dropLastNUserTurns(n: Int) {
        if (n <= 0) return
        
        // Step 1: Find positions of all user messages
        val userTurnPositions = items.mapIndexedNotNull { index, item ->
            if (item is ResponseItem.Message && item.role == "user") index else null
        }
        
        if (userTurnPositions.isEmpty()) return
        
        // Step 2-3: Calculate cut index
        // If n >= total user turns, cut from the first user message
        // Otherwise, cut from the (total - n)th user message
        val cutIndex = if (n >= userTurnPositions.size) {
            userTurnPositions.first()  // Drop all user turns
        } else {
            userTurnPositions[userTurnPositions.size - n]  // Drop last n user turns
        }
        
        // Step 4: Remove from cutIndex to end (removes the last n user turns and their responses)
        val toRemove = items.size - cutIndex
        repeat(toRemove) {
            items.removeAt(items.lastIndex)
        }
        
        lastTokenEstimate = null // Invalidate cache
        Log.d(TAG, "Dropped last $n user turns, remaining items: ${items.size}")
    }
    
    /**
     * Remove the first (oldest) item for context window management.
     * 
     * Also removes corresponding output/call if needed to maintain consistency.
     */
    @Synchronized
    fun removeFirstItem() {
        if (items.isEmpty()) return
        
        val removed = items.removeAt(0)
        
        // If we removed a function call, also remove its output
        if (removed is ResponseItem.FunctionCall) {
            val outputIndex = items.indexOfFirst { 
                it is ResponseItem.FunctionCallOutput && it.callId == removed.id 
            }
            if (outputIndex >= 0) {
                items.removeAt(outputIndex)
            }
        }
        
        lastTokenEstimate = null // Invalidate cache
        Log.d(TAG, "Removed first item: ${removed.javaClass.simpleName}")
    }
    
    /**
     * Compress history to fit within token budget.
     * 
     * Strategies:
     * 1. Truncate old tool outputs more aggressively
     * 2. Remove oldest items
     * 3. Summarize old conversations (future)
     */
    @Synchronized
    fun compress(targetTokens: Long) {
        Log.d(TAG, "Compressing history from ${estimateTokenCount()} to $targetTokens tokens")
        
        // Strategy 1: Apply aggressive truncation to older tool outputs
        val threshold = items.size / 2 // First half of history
        for (i in 0 until threshold) {
            val item = items[i]
            if (item is ResponseItem.FunctionCallOutput) {
                items[i] = truncateOutput(item, TruncationPolicy.AGGRESSIVE)
            }
        }
        lastTokenEstimate = null
        
        // Strategy 2: Remove oldest items until within budget
        while (estimateTokenCount() > targetTokens && items.size > 2) {
            removeFirstItem()
        }
        
        Log.d(TAG, "Compression complete, now ${estimateTokenCount()} tokens, ${items.size} items")
    }
    
    /**
     * Get a summary of history for debugging.
     */
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
    
    /**
     * Process an item according to truncation policy.
     */
    private fun processItem(item: ResponseItem, policy: TruncationPolicy): ResponseItem {
        return when (item) {
            is ResponseItem.FunctionCallOutput -> truncateOutput(item, policy)
            else -> item
        }
    }
    
    /**
     * Truncate a function call output based on policy.
     * NONE policy returns output unchanged to avoid Int.MAX_VALUE overflow.
     */
    private fun truncateOutput(output: ResponseItem.FunctionCallOutput, policy: TruncationPolicy): ResponseItem.FunctionCallOutput {
        // NONE policy: no truncation at all
        if (policy == TruncationPolicy.NONE) {
            return output
        }
        
        val maxChars = (policy.maxTokens / TOKENS_PER_CHAR).toInt()
        
        if (output.content.length <= maxChars) {
            return output
        }
        
        val truncated = output.content.take(maxChars) + "\n...[truncated, ${output.content.length - maxChars} chars omitted]"
        return output.copy(content = truncated, truncated = true)
    }
    
    /**
     * Normalize history to ensure consistency.
     * 
     * 1. Add placeholder outputs for calls without outputs
     * 2. Remove outputs without corresponding calls
     */
    private fun normalizeHistory(items: List<ResponseItem>): List<ResponseItem> {
        val result = items.toMutableList()
        
        // Collect all function call IDs and output IDs
        val callIds = result.filterIsInstance<ResponseItem.FunctionCall>().map { it.id }.toSet()
        val outputCallIds = result.filterIsInstance<ResponseItem.FunctionCallOutput>().map { it.callId }.toSet()
        
        // 1. Add placeholder outputs for calls without outputs
        callIds.minus(outputCallIds).forEach { callId ->
            val callIndex = result.indexOfFirst { it is ResponseItem.FunctionCall && it.id == callId }
            if (callIndex >= 0) {
                // Insert placeholder right after the call
                result.add(callIndex + 1, ResponseItem.FunctionCallOutput(
                    callId = callId,
                    content = "[Output not recorded]",
                    success = false
                ))
            }
        }
        
        // 2. Remove orphaned outputs (outputs without corresponding calls)
        result.removeAll { 
            it is ResponseItem.FunctionCallOutput && it.callId !in callIds 
        }
        
        return result
    }

    private fun autoCompressIfNeeded() {
        if (!config.autoCompress) return
        if (config.maxTokenBudget <= 0) return
        if (isApproachingLimit(config.maxTokenBudget, config.autoCompressThreshold)) {
            compress(config.maxTokenBudget)
        }
    }
}

/**
 * HistoryConfig - Configuration for history management.
 */
data class HistoryConfig(
    /** Default truncation policy for tool outputs */
    val defaultTruncationPolicy: TruncationPolicy = TruncationPolicy.CONSERVATIVE,
    
    /** Maximum token budget for history */
    val maxTokenBudget: Long = 100_000,
    
    /** Whether to auto-compress when approaching limit */
    val autoCompress: Boolean = true,
    
    /** Threshold for auto-compression (percentage of maxTokenBudget) */
    val autoCompressThreshold: Float = 0.85f
)

/**
 * TruncationPolicy - Controls how much of tool outputs to keep.
 * 
 * NONE uses -1 as sentinel value (handled specially in truncateOutput).
 */
enum class TruncationPolicy(val maxTokens: Int) {
    /** Keep everything (for debugging) - sentinel value, handled specially */
    NONE(-1),
    
    /** Conservative truncation - good balance for most uses */
    CONSERVATIVE(8000),
    
    /** Aggressive truncation - for very long sessions */
    AGGRESSIVE(2000),
    
    /** Minimal - keep only essential info */
    MINIMAL(500)
}

/**
 * ResponseItem - An item in the conversation history.
 * 
 * Represents the different types of content that can be in a conversation:
 * - Messages (system/user/assistant)
 * - Function calls (tool invocations)
 * - Function call outputs (tool results)
 * - Ghost snapshots (placeholders for removed items)
 */
sealed class ResponseItem {
    
    /** Estimate the number of tokens this item uses */
    abstract fun estimateTokens(): Long
    
    /**
     * A chat message.
     */
    data class Message(
        val role: String,  // "system", "user", "assistant"
        val content: String,
        val name: String? = null,  // Optional name for multi-agent
        val isScreenObservation: Boolean = false  // Screen state captured at turn start
    ) : ResponseItem() {
        override fun estimateTokens(): Long = (content.length * 0.25f).toLong() + 4 // +4 for role tokens
    }
    
    /**
     * A function/tool call from the assistant.
     */
    data class FunctionCall(
        val id: String,
        val name: String,
        val arguments: JSONObject
    ) : ResponseItem() {
        // Apply toLong() AFTER multiplication, not to 0.25f
        override fun estimateTokens(): Long = ((name.length + arguments.toString().length) * 0.25f).toLong() + 10
    }
    
    /**
     * The output of a function/tool call.
     */
    data class FunctionCallOutput(
        val callId: String,
        val content: String,
        val success: Boolean = true,
        val truncated: Boolean = false
    ) : ResponseItem() {
        override fun estimateTokens(): Long = (content.length * 0.25f).toLong() + 4
    }
    
}
