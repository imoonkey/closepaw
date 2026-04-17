package ai.closepaw.history.model

import ai.closepaw.history.MessageKind
import ai.closepaw.history.ResponseItem
import org.json.JSONObject

object HistoryItemConverter {

    fun toRecord(item: ResponseItem): PersistedHistoryItem = when (item) {
        is ResponseItem.Message -> PersistedHistoryItem.Message(
            kind = item.kind.name,
            content = item.content,
            name = item.name
        )
        is ResponseItem.FunctionCall -> PersistedHistoryItem.FunctionCall(
            id = item.id,
            name = item.name,
            argumentsRawJson = item.arguments.toString()
        )
        is ResponseItem.FunctionCallOutput -> PersistedHistoryItem.FunctionCallOutput(
            callId = item.callId,
            content = item.content,
            success = item.success,
            truncated = item.truncated
        )
    }

    fun fromRecord(record: PersistedHistoryItem): ResponseItem = when (record) {
        is PersistedHistoryItem.Message -> ResponseItem.Message(
            kind = resolveMessageKind(record),
            content = record.content,
            name = record.name
        )
        is PersistedHistoryItem.FunctionCall -> ResponseItem.FunctionCall(
            id = record.id,
            name = record.name,
            arguments = JSONObject(record.argumentsRawJson)
        )
        is PersistedHistoryItem.FunctionCallOutput -> ResponseItem.FunctionCallOutput(
            callId = record.callId,
            content = record.content,
            success = record.success,
            truncated = record.truncated
        )
    }

    /**
     * Resolve [MessageKind] from a persisted record, handling both new (`kind` field)
     * and legacy (`role` + `isScreenObservation`) formats.
     */
    private fun resolveMessageKind(record: PersistedHistoryItem.Message): MessageKind {
        // New format: kind field is present
        record.kind?.let { kindStr ->
            return MessageKind.entries.firstOrNull { it.name == kindStr }
                ?: MessageKind.USER_INTENT // fallback for unknown kind strings
        }

        // Legacy migration: infer kind from role + isScreenObservation
        return when {
            record.isScreenObservation -> MessageKind.SCREEN_OBSERVATION
            record.role == "assistant" -> MessageKind.ASSISTANT_TEXT
            else -> MessageKind.USER_INTENT
        }
    }

    fun toRecords(items: List<ResponseItem>): List<PersistedHistoryItem> =
        items.map { toRecord(it) }

    fun fromRecords(records: List<PersistedHistoryItem>): List<ResponseItem> =
        records.map { fromRecord(it) }
}
