package com.moonkey.androidagent.history

import com.moonkey.androidagent.history.model.ContentBlockRecord

internal data class AgentMessageSnapshot(
    val id: String,
    val blocks: List<ContentBlockRecord>
)

internal class AgentMessageBuffer {
    private var messageId: String? = null
    private val textBuffer = StringBuilder()
    private val contentBlocks = mutableListOf<ContentBlockRecord>()

    fun hasActiveMessage(): Boolean = messageId != null

    fun start(id: String) {
        messageId = id
        textBuffer.clear()
        contentBlocks.clear()
    }

    fun clear() {
        messageId = null
        textBuffer.clear()
        contentBlocks.clear()
    }

    fun appendText(delta: String) {
        textBuffer.append(delta)
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
        return AgentMessageSnapshot(id, blocks)
    }

    fun finalizeSnapshot(): AgentMessageSnapshot? {
        val id = messageId ?: return null
        finalizeTextBlock()
        if (contentBlocks.isEmpty()) {
            clear()
            return null
        }
        val snapshot = AgentMessageSnapshot(id, contentBlocks.toList())
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
