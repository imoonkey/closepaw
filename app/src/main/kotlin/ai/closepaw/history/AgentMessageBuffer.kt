package ai.closepaw.history

import ai.closepaw.history.model.ContentBlockRecord

internal data class AgentMessageSnapshot(
    val id: String,
    val startTimestamp: Long,
    val blocks: List<ContentBlockRecord>
)

internal class AgentMessageBuffer {
    private var messageId: String? = null
    private var startTimestamp: Long = 0L
    private val textBuffer = StringBuilder()
    private val contentBlocks = mutableListOf<ContentBlockRecord>()

    fun hasActiveMessage(): Boolean = messageId != null

    fun start(id: String, timestamp: Long) {
        messageId = id
        startTimestamp = timestamp
        textBuffer.clear()
        contentBlocks.clear()
    }

    fun clear() {
        messageId = null
        startTimestamp = 0L
        textBuffer.clear()
        contentBlocks.clear()
    }

    fun appendText(delta: String) {
        textBuffer.append(delta)
    }

    fun recordThought(text: String) {
        finalizeTextBlock()
        contentBlocks.add(ContentBlockRecord.Thought(text))
    }

    /**
     * Append a final-answer block (TaskCompleted with non-blank result that
     * carries the agent's closing answer). Mirrors `ChatViewModel`'s live
     * reducer rule — only call this when there is a real answer to surface.
     *
     * If the streaming text buffer or the last committed Text block already
     * holds this same answer (text-completion path emits the answer through
     * MessageDelta events first, then resurfaces it via TaskCompleted),
     * promote in place instead of duplicating.
     */
    fun recordFinalAnswer(text: String) {
        // Drain any pending streamed text — TaskCompleted is replacing it
        // with the canonical answer.
        textBuffer.clear()
        val last = contentBlocks.lastOrNull()
        if (last is ContentBlockRecord.Text && last.text == text) {
            contentBlocks[contentBlocks.lastIndex] = ContentBlockRecord.FinalText(text)
        } else {
            contentBlocks.add(ContentBlockRecord.FinalText(text))
        }
    }

    /** Append an inline error/warning text block (SessionError, "⚠ …"). */
    fun recordErrorText(text: String) {
        finalizeTextBlock()
        contentBlocks.add(ContentBlockRecord.Text(text))
    }

    fun recordAction(action: ContentBlockRecord.Action) {
        finalizeTextBlock()
        contentBlocks.add(action)
    }

    fun updateActionState(actionId: String, state: String, result: String?) {
        val updated = contentBlocks.map { block ->
            if (block is ContentBlockRecord.Action && block.id == actionId) {
                block.copy(state = state, resultSummary = result)
            } else {
                block
            }
        }
        contentBlocks.clear()
        contentBlocks.addAll(updated)
    }

    fun buildPartialSnapshot(): AgentMessageSnapshot? {
        val id = messageId ?: return null
        val blocks = contentBlocks.toMutableList()
        if (textBuffer.isNotEmpty()) {
            blocks.add(ContentBlockRecord.Text(textBuffer.toString()))
        }
        if (blocks.isEmpty()) return null
        return AgentMessageSnapshot(id, startTimestamp, blocks)
    }

    fun finalizeSnapshot(): AgentMessageSnapshot? {
        val id = messageId ?: return null
        finalizeTextBlock()
        if (contentBlocks.isEmpty()) {
            clear()
            return null
        }
        val snapshot = AgentMessageSnapshot(id, startTimestamp, contentBlocks.toList())
        clear()
        return snapshot
    }

    private fun finalizeTextBlock() {
        if (textBuffer.isNotEmpty()) {
            contentBlocks.add(ContentBlockRecord.Text(textBuffer.toString()))
            textBuffer.clear()
        }
    }
}
