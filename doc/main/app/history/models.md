# History Data Models

> Serializable models for session persistence and conversion utilities.
> -> See: [overview](overview.md) for architecture.

## SessionRecord

> See: `history/model/SessionRecord.kt`

```kotlin
@Serializable
data class SessionRecord(
    val sessionId: String, val startTime: Long, val lastUpdated: Long,
    val messages: List<MessageRecord>,
    val screenStates: List<ScreenStateRecord> = emptyList(),
    val summary: String? = null,
    val metadata: SessionMetadata = SessionMetadata()
)

@Serializable
data class SessionMetadata(
    val appVersion: String? = null, val model: String? = null,
    val traceRunId: String? = null, val turnCount: Int = 0,
    val completedNormally: Boolean = false
)
```

## MessageRecord

> See: `history/model/MessageRecord.kt`

```kotlin
@Serializable
sealed interface MessageRecord {
    val id: String; val timestamp: Long
    data class User(id, timestamp, text: String) : MessageRecord
    data class Agent(id, timestamp, contentBlocks: List<ContentBlockRecord>, isComplete: Boolean) : MessageRecord
}

@Serializable
sealed interface ContentBlockRecord {
    data class Text(val text: String) : ContentBlockRecord
    data class Action(id, toolName, description, state, resultSummary?) : ContentBlockRecord
}
```

Action `state` values: `"proposed"`, `"executing"`, `"success"`, `"failed"`, `"skipped"`.

## ScreenStateRecord

> See: `history/model/ScreenStateRecord.kt`

Captures screen state metadata per turn: `id`, `timestamp`, `turnId`, `turnNumber`, `phase` (`PRE_TURN`/`POST_ACTION`), `elementCount`, `packageName`, `activityName`, raw/sanitized a11y tree paths, `screenshotPath`, `traceRunId`.

## SessionInfo

> See: `history/model/SessionInfo.kt`

Lightweight summary for session list UI (avoids loading full content): `id`, `fileName`, `startTime`, `lastUpdated`, `messageCount`, `displayTitle` (truncated to 50 chars), `firstUserMessage`, `isActive`.

## Converters

**HistoryItemConverter** (`history/model/HistoryItemConverter.kt`): Bidirectional `ResponseItem` ↔ `PersistedHistoryItem` conversion. `resolveMessageKind()` handles legacy checkpoint migration (including the historical `COMPRESSION_DIGEST` → `COMPACTION_SUMMARY` rename).

**MessageConverter** (`history/model/MessageConverter.kt`): Bidirectional `ChatMessage` ↔ `MessageRecord` conversion for UI layer.
