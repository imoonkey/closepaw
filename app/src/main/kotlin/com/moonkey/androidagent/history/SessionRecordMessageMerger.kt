package com.moonkey.androidagent.history

import com.moonkey.androidagent.history.model.MessageRecord
import com.moonkey.androidagent.history.model.SessionRecord

internal object SessionRecordMessageMerger {
    fun mergeAgentSnapshot(
        session: SessionRecord,
        snapshot: AgentMessageSnapshot,
        isComplete: Boolean,
        timestamp: Long = System.currentTimeMillis()
    ): SessionRecord {
        val agentMessage =
            MessageRecord.Agent(
                id = snapshot.id,
                timestamp = timestamp,
                contentBlocks = snapshot.blocks,
                isComplete = isComplete
            )

        val existingIndex =
            session.messages.indexOfFirst { it is MessageRecord.Agent && it.id == snapshot.id }

        return if (existingIndex >= 0) {
            session.copy(
                messages =
                    session.messages.mapIndexed { index, msg ->
                        if (index == existingIndex) agentMessage else msg
                    },
                lastUpdated = timestamp
            )
        } else {
            session.copy(messages = session.messages + agentMessage, lastUpdated = timestamp)
        }
    }
}
