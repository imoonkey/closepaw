package ai.closepaw.history

import ai.closepaw.llm.LLMClient
import ai.closepaw.llm.ModelEntry
import android.util.Log
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.CancellationException

/**
 * Result of a compaction attempt. See [Compactor] state machine.
 */
sealed class CompactionOutcome {
    /** Token estimate below the trigger threshold — nothing to do. */
    data object Skipped : CompactionOutcome()

    /** No safe cut point exists (e.g. an oversized current turn or unsafe tail). */
    data object NothingToCompact : CompactionOutcome()

    /** CAS swap missed: a supplement arrived during summarization. Retry next turn. */
    data object Stale : CompactionOutcome()

    /** Successful summarize + swap. */
    data class Compacted(val before: Long, val after: Long) : CompactionOutcome()

    /** LLM-side failure during summarization. Counts toward the loop's circuit breaker. */
    data class Failed(val reason: String) : CompactionOutcome()
}

/**
 * Context-window-triggered auto-compaction.
 *
 * One instance per `Agent` (so subagents get their own model/client/window). On each
 * agent turn the runner calls [maybeCompact]; if the running token estimate is above
 * `contextWindow − reserveTokens` the older prefix is summarized via a clean LLM
 * call and the history is CAS-swapped to:
 *
 *     [USER_INTENT("Goal: <currentGoal>"), COMPACTION_SUMMARY(summary), ...kept]
 *
 * The cut point is chosen by [findSafeCutPoint]: walk back accumulating tokens until
 * `keepRecentTokens` is met, then snap forward to the nearest [ResponseItem.Message]
 * or [ResponseItem.FunctionCall]. A [ResponseItem.FunctionCallOutput] is never a
 * valid cut — it would orphan its paired call.
 *
 * Concurrency: snapshot read + CAS replace via [HistoryManager.replaceAllIfRevision].
 * If a supplement is added while the LLM is summarizing, the revision moves and we
 * return [CompactionOutcome.Stale]; the next turn re-evaluates with the supplement
 * included.
 *
 * Cancellation: [CancellationException] from the LLM call propagates — it is *not*
 * a compaction failure and must not be conflated with one.
 *
 * The two prompt strings are passed in (not loaded inside this class) so the unit
 * tests can run without an `AssetManager`. Production wiring reads
 * `assets/prompts/compaction_initial.md` and `assets/prompts/compaction_update.md`.
 */
class Compactor(
    private val llmClient: LLMClient,
    private val model: ModelEntry,
    private val initialPrompt: String,
    private val updatePrompt: String,
    private val staticOverheadTokens: Long = 12_000,
    private val reserveTokens: Long = 24_000,
    private val keepRecentTokens: Long = 20_000,
    private val maxSummaryTokens: Long = 5_000,
    private val summaryKind: MessageKind = MessageKind.COMPACTION_SUMMARY,
) {

    companion object {
        private const val TAG = "Compactor"
    }

    /**
     * Token threshold above which compaction runs. Exposed for diagnostics / tests.
     */
    val triggerTokens: Long get() = model.contextWindow.toLong() - reserveTokens

    /**
     * Proactive compaction. Runs only if the estimated token count exceeds
     * `contextWindow − reserveTokens`.
     */
    suspend fun maybeCompact(currentGoal: String, history: HistoryManager): CompactionOutcome =
        compact(currentGoal, history, keepRecentTokens, force = false)

    /**
     * Reactive compaction, used after the provider rejects with `prompt_too_long`.
     * Runs unconditionally with a smaller `keepRecentTokens` (half the proactive
     * value) to free more space.
     */
    suspend fun forceCompactNow(currentGoal: String, history: HistoryManager): CompactionOutcome =
        compact(currentGoal, history, (keepRecentTokens / 2).coerceAtLeast(1), force = true)

    private suspend fun compact(
        currentGoal: String,
        history: HistoryManager,
        keepTokens: Long,
        force: Boolean,
    ): CompactionOutcome {
        val (rev, items) = history.snapshot()
        val historyTokens = items.sumOf { it.estimateTokens() }
        val totalTokens = historyTokens + staticOverheadTokens

        if (!force && totalTokens <= triggerTokens) {
            return CompactionOutcome.Skipped
        }

        val cutIdx = findSafeCutPoint(items, keepTokens)
        if (cutIdx <= 0 || cutIdx >= items.size) {
            Log.w(TAG, "No safe cut point (items=${items.size}, cutIdx=$cutIdx, tokens=$totalTokens)")
            return CompactionOutcome.NothingToCompact
        }

        val previousSummary = items.firstNotNullOfOrNull { item ->
            (item as? ResponseItem.Message)?.takeIf { it.kind == summaryKind }?.content
        }
        val toSummarize = items.subList(0, cutIdx)
            .filterNot { it is ResponseItem.Message && it.kind == summaryKind }

        if (toSummarize.isEmpty()) {
            // Only a prior summary exists in the prefix; nothing new to fold in.
            return CompactionOutcome.NothingToCompact
        }

        val summaryText = try {
            summarize(currentGoal, toSummarize, previousSummary)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "Summarization failed: ${t.message}", t)
            return CompactionOutcome.Failed(t.message ?: t.javaClass.simpleName)
        }

        val newItems = buildList {
            add(ResponseItem.Message(MessageKind.USER_INTENT, "Goal: $currentGoal"))
            add(ResponseItem.Message(summaryKind, summaryText))
            addAll(items.subList(cutIdx, items.size))
        }

        return if (history.replaceAllIfRevision(rev, newItems)) {
            val after = history.estimateTokenCount() + staticOverheadTokens
            Log.i(TAG, "Compacted history: $totalTokens → $after tokens (cut@$cutIdx)")
            CompactionOutcome.Compacted(before = totalTokens, after = after)
        } else {
            Log.i(TAG, "Compaction stale (revision moved from $rev) — retrying next turn")
            CompactionOutcome.Stale
        }
    }

    /**
     * Walk backward from the end, accumulating tokens until [keepTokens] is met,
     * then snap forward to the next safe boundary.
     *
     * Returns the cut index `c` such that `items[0..c)` is summarized and
     * `items[c..]` is kept verbatim. Returns `items.size` if no safe boundary
     * exists in the kept tail (caller treats this as NothingToCompact).
     */
    internal fun findSafeCutPoint(items: List<ResponseItem>, keepTokens: Long): Int {
        if (items.isEmpty()) return 0

        var acc = 0L
        var hitIdx = -1
        for (i in items.indices.reversed()) {
            acc += items[i].estimateTokens()
            if (acc >= keepTokens) {
                hitIdx = i
                break
            }
        }
        if (hitIdx < 0) return 0

        for (j in hitIdx..items.lastIndex) {
            when (items[j]) {
                is ResponseItem.Message,
                is ResponseItem.FunctionCall -> return j
                is ResponseItem.FunctionCallOutput -> continue
            }
        }
        return items.size
    }

    private suspend fun summarize(
        currentGoal: String,
        items: List<ResponseItem>,
        previousSummary: String?,
    ): String {
        val systemPrompt = if (previousSummary != null) updatePrompt else initialPrompt
        val conversationText = renderItemsForSummary(items)
        val userText = buildString {
            append("Current goal (for context only — do NOT restate): ")
            appendLine(currentGoal)
            appendLine()
            if (previousSummary != null) {
                appendLine("<previous-summary>")
                appendLine(previousSummary.trim())
                appendLine("</previous-summary>")
                appendLine()
                appendLine("New conversation events since that summary:")
            } else {
                appendLine("Conversation to summarize:")
            }
            append(conversationText)
        }

        val input = listOf(
            ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder()
                    .role(EasyInputMessage.Role.USER)
                    .content(userText)
                    .build()
            )
        )

        val result = llmClient.chatWithTools(
            systemPrompt = systemPrompt,
            inputItems = input,
            tools = emptyList(),
            model = model.name,
        )
        val text = result.textContent?.trim().orEmpty()
        require(text.isNotEmpty()) { "Empty summary returned by LLM" }
        return text
    }

    private fun renderItemsForSummary(items: List<ResponseItem>): String = buildString {
        for (item in items) {
            when (item) {
                is ResponseItem.Message -> {
                    val tag = when (item.kind) {
                        MessageKind.USER_INTENT -> "USER_INTENT"
                        MessageKind.SCREEN_OBSERVATION -> "SCREEN"
                        MessageKind.ASSISTANT_TEXT -> "ASSISTANT"
                        MessageKind.COMPACTION_SUMMARY -> "PRIOR_SUMMARY"
                    }
                    append('[').append(tag).append("] ").append(item.content).append('\n')
                }
                is ResponseItem.FunctionCall ->
                    append("[TOOL_CALL ").append(item.name).append("] ")
                        .append(item.arguments.toString()).append('\n')
                is ResponseItem.FunctionCallOutput ->
                    append("[TOOL_OUTPUT success=").append(item.success).append("] ")
                        .append(item.content).append('\n')
            }
        }
    }
}
