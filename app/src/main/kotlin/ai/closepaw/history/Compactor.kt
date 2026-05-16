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
 * `keepRecentTokens` is met, then snap to a safe boundary. A [ResponseItem.FunctionCall]
 * and its paired [ResponseItem.FunctionCallOutput] (matched by callId) form an atomic
 * group — the cut never splits a pair, so an output is never orphaned and a call is
 * never summarized away from its output.
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
     * Choose the cut index `c` such that `items[0..c)` is summarized and
     * `items[c..]` is kept verbatim.
     *
     * Groups a [ResponseItem.FunctionCall] with the [ResponseItem.FunctionCallOutput]
     * carrying the same callId into an atomic group. A cut never falls between
     * a FunctionCall and its paired FunctionCallOutput — that would orphan the
     * output (or summarize away the call its output is referencing). When the
     * kept-tokens threshold lands inside such a group the cut snaps backward to
     * the start of that group, so the call + output stay together in the kept
     * tail.
     *
     * Valid cut points:
     *   - Any [ResponseItem.Message]
     *   - The index of a [ResponseItem.FunctionCall] (the call is included in
     *     the kept tail along with its paired output to the right).
     *
     * Returns `items.size` when no valid cut produces any summarizable prefix
     * (caller treats this as [CompactionOutcome.NothingToCompact]). Returns `0`
     * when total tokens are below `keepTokens`.
     */
    internal fun findSafeCutPoint(items: List<ResponseItem>, keepTokens: Long): Int {
        if (items.isEmpty()) return 0

        val unpairedFcoAt = computeUnpairedFcoAt(items)

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

        // Walk backward from hitIdx looking for the largest safe cut.
        // Safe iff cutting at c does not leave an FCO in items[c..) whose
        // paired FC is in items[0..c).
        for (c in hitIdx downTo 1) {
            if (!unpairedFcoAt[c]) return c
        }
        return items.size
    }

    /**
     * `unpairedFcoAt[i] == true` iff `items[i..n)` contains a
     * [ResponseItem.FunctionCallOutput] whose paired [ResponseItem.FunctionCall]
     * (matched by callId / id) lives in `items[0..i)`. Computed in a single
     * reverse pass.
     */
    private fun computeUnpairedFcoAt(items: List<ResponseItem>): BooleanArray {
        val n = items.size
        val result = BooleanArray(n + 1)
        val pending = HashSet<String>()
        for (i in n - 1 downTo 0) {
            when (val item = items[i]) {
                is ResponseItem.FunctionCallOutput -> pending.add(item.callId)
                is ResponseItem.FunctionCall -> pending.remove(item.id)
                is ResponseItem.Message -> Unit
            }
            result[i] = pending.isNotEmpty()
        }
        return result
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
            model = model.modelId,
            maxOutputTokens = maxSummaryTokens,
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
