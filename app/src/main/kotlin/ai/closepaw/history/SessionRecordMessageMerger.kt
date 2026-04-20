package ai.closepaw.history

import ai.closepaw.history.model.MessageRecord
import ai.closepaw.history.model.SessionRecord

internal object SessionRecordMessageMerger {
    fun mergeAgentSnapshot(
        session: SessionRecord,
        snapshot: AgentMessageSnapshot,
        isComplete: Boolean,
        completedTimestamp: Long? = null,
        lastUpdated: Long = System.currentTimeMillis()
    ): SessionRecord {
        val agentMessage =
            MessageRecord.Agent(
                id = snapshot.id,
                timestamp = snapshot.startTimestamp,
                contentBlocks = snapshot.blocks,
                isComplete = isComplete,
                completedTimestamp = completedTimestamp
            )

        val existingIndex =
            session.messages.indexOfFirst { it is MessageRecord.Agent && it.id == snapshot.id }

        return if (existingIndex >= 0) {
            session.copy(
                messages =
                    session.messages.mapIndexed { index, msg ->
                        if (index == existingIndex) agentMessage else msg
                    },
                lastUpdated = lastUpdated
            )
        } else {
            session.copy(messages = session.messages + agentMessage, lastUpdated = lastUpdated)
        }
    }
}
