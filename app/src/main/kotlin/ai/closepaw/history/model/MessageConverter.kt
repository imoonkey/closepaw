package ai.closepaw.history.model

import ai.closepaw.ui.chat.model.ActionCardData
import ai.closepaw.ui.chat.model.ActionState
import ai.closepaw.ui.chat.model.AgentMessageState
import ai.closepaw.ui.chat.model.ChatMessage
import ai.closepaw.ui.chat.model.ContentBlock
import ai.closepaw.ui.chat.model.RowState
import ai.closepaw.ui.common.formatToolName

/**
 * Utility functions for converting between ChatMessage (UI) and MessageRecord (persistence).
 */
object MessageConverter {
    
    /**
     * Convert a ChatMessage to MessageRecord for persistence.
     */
    fun toRecord(message: ChatMessage): MessageRecord {
        return when (message) {
            is ChatMessage.User -> MessageRecord.User(
                id = message.id,
                timestamp = message.timestamp,
                text = message.text
            )
            is ChatMessage.Agent -> MessageRecord.Agent(
                id = message.id,
                timestamp = message.timestamp,
                contentBlocks = message.contentBlocks.map { block ->
                    when (block) {
                        is ContentBlock.Text -> ContentBlockRecord.Text(block.text)
                        is ContentBlock.FinalText -> ContentBlockRecord.FinalText(block.text)
                        is ContentBlock.Thought -> ContentBlockRecord.Thought(block.text)
                        is ContentBlock.Action -> ContentBlockRecord.Action(
                            id = block.data.id,
                            toolName = block.data.toolName,
                            description = block.data.description,
                            state = block.data.state.name.lowercase(),
                            resultSummary = block.data.resultSummary
                        )
                    }
                },
                isComplete = message.state == AgentMessageState.Complete,
                completedTimestamp = message.completedTimestamp,
                rowState = message.rowState.name.lowercase()
            )
        }
    }

    /**
     * Convert a MessageRecord to ChatMessage for UI display.
     *
     * Note: [userPrompt] hydration requires the prior record, so callers that
     * want headline restoration should use [fromRecords] instead.
     */
    fun fromRecord(record: MessageRecord, userPrompt: String? = null): ChatMessage {
        return when (record) {
            is MessageRecord.User -> ChatMessage.User(
                id = record.id,
                timestamp = record.timestamp,
                text = record.text
            )
            is MessageRecord.Agent -> {
                val rawBlocks = record.contentBlocks.map { block ->
                    when (block) {
                        is ContentBlockRecord.Text -> ContentBlock.Text(block.text)
                        is ContentBlockRecord.FinalText -> ContentBlock.FinalText(block.text)
                        is ContentBlockRecord.Thought -> ContentBlock.Thought(block.text)
                        is ContentBlockRecord.Action -> ContentBlock.Action(
                            ActionCardData(
                                id = block.id,
                                toolName = formatToolName(block.toolName),
                                description = block.description,
                                state = parseActionState(block.state),
                                resultSummary = block.resultSummary
                            )
                        )
                    }
                }
                ChatMessage.Agent(
                    id = record.id,
                    timestamp = record.timestamp,
                    contentBlocks = migrateLegacyFinalText(rawBlocks, record.isComplete),
                    state = if (record.isComplete) AgentMessageState.Complete else AgentMessageState.Streaming,
                    rowState = parseRowState(record.rowState, record.isComplete),
                    userPrompt = userPrompt,
                    completedTimestamp = record.completedTimestamp
                )
            }
        }
    }

    /**
     * Convert a list of MessageRecords to ChatMessages.
     *
     * Walks one-back to hydrate [ChatMessage.Agent.userPrompt] from the
     * preceding User record so the collapsed-headline ladder survives reload.
     */
    fun fromRecords(records: List<MessageRecord>): List<ChatMessage> {
        return records.mapIndexed { index, record ->
            val prev = records.getOrNull(index - 1)
            val userPrompt = (prev as? MessageRecord.User)?.text
            fromRecord(record, userPrompt = userPrompt)
        }
    }
    
    /**
     * Convert a list of ChatMessages to MessageRecords.
     */
    fun toRecords(messages: List<ChatMessage>): List<MessageRecord> {
        return messages.map { toRecord(it) }
    }
    
    // ===== Private Helpers =====
    
    private fun parseActionState(state: String): ActionState {
        return when (state.lowercase()) {
            "proposed" -> ActionState.Proposed
            "executing" -> ActionState.Executing
            "success" -> ActionState.Success
            "failed" -> ActionState.Failed
            "skipped" -> ActionState.Skipped
            else -> ActionState.Proposed
        }
    }

    private fun parseRowState(persisted: String?, isComplete: Boolean): RowState {
        return when (persisted?.lowercase()) {
            "live" -> RowState.Live
            "waiting" -> RowState.Waiting
            "complete" -> RowState.Complete
            "error" -> RowState.Error
            null -> if (isComplete) RowState.Complete else RowState.Live
            else -> if (isComplete) RowState.Complete else RowState.Live
        }
    }

    /**
     * Pre-uxfb-3 history persisted every closing answer as ContentBlockRecord.Text.
     * AgentRow only renders ContentBlock.FinalText outside the collapsible trace,
     * so legacy completed rows would default-collapse with their answer hidden.
     * If a restored Complete row has no FinalText but does have a non-blank trailing
     * Text, promote that Text to FinalText in place — same rule the live reducer
     * applies when sealing a row without complete_task.
     */
    private fun migrateLegacyFinalText(
        blocks: List<ContentBlock>,
        isComplete: Boolean
    ): List<ContentBlock> {
        if (!isComplete) return blocks
        if (blocks.any { it is ContentBlock.FinalText }) return blocks
        val lastTextIndex = blocks.indexOfLast {
            it is ContentBlock.Text && it.text.isNotBlank()
        }
        if (lastTextIndex < 0) return blocks
        val text = (blocks[lastTextIndex] as ContentBlock.Text).text
        return blocks.mapIndexed { i, b ->
            if (i == lastTextIndex) ContentBlock.FinalText(text) else b
        }
    }
}
