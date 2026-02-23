package com.moonkey.androidagent.history.model

import com.moonkey.androidagent.history.ResponseItem
import org.json.JSONObject

object HistoryItemConverter {

    fun toRecord(item: ResponseItem): PersistedHistoryItem = when (item) {
        is ResponseItem.Message -> PersistedHistoryItem.Message(
            role = item.role,
            content = item.content,
            name = item.name,
            isScreenObservation = item.isScreenObservation
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
            role = record.role,
            content = record.content,
            name = record.name,
            isScreenObservation = record.isScreenObservation
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

    fun toRecords(items: List<ResponseItem>): List<PersistedHistoryItem> =
        items.map { toRecord(it) }

    fun fromRecords(records: List<PersistedHistoryItem>): List<ResponseItem> =
        records.map { fromRecord(it) }
}
