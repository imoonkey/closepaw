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
                completedTimestamp = message.completedTimestamp
            )
        }
    }
    
    /**
     * Convert a MessageRecord to ChatMessage for UI display.
     */
    fun fromRecord(record: MessageRecord): ChatMessage {
        return when (record) {
            is MessageRecord.User -> ChatMessage.User(
                id = record.id,
                timestamp = record.timestamp,
                text = record.text
            )
            is MessageRecord.Agent -> ChatMessage.Agent(
                id = record.id,
                timestamp = record.timestamp,
                contentBlocks = record.contentBlocks.map { block ->
                    when (block) {
                        is ContentBlockRecord.Text -> ContentBlock.Text(block.text)
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
                },
                state = if (record.isComplete) AgentMessageState.Complete else AgentMessageState.Streaming,
                rowState = if (record.isComplete) RowState.Complete else RowState.Live,
                completedTimestamp = record.completedTimestamp
            )
        }
    }
    
    /**
     * Convert a list of MessageRecords to ChatMessages.
     */
    fun fromRecords(records: List<MessageRecord>): List<ChatMessage> {
        return records.map { fromRecord(it) }
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
}
