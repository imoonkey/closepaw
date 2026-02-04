# Session History Persistence

> Session recording, storage, and resume functionality.
> Last updated: 2026-02-04

## Overview

The session history system enables **automatic persistence** of chat sessions, allowing users to browse past conversations and resume them later.

---

## Architecture

```
┌──────────────────┐       ┌──────────────────────────────┐
│   MainActivity   │──────►│   SessionHistoryManager      │
│  (UI entry)      │       │   (High-level API)           │
└────────┬─────────┘       └──────────────┬───────────────┘
         │                                │ coordinates
         ▼                                ▼
┌──────────────────┐       ┌──────────────────────────────┐
│  ChatViewModel   │◄─────►│  SessionRecordingService     │
│  (State mgmt)    │       │  (Real-time event bridge)    │
└────────┬─────────┘       └──────────────┬───────────────┘
         │ events                         │ debounced writes
         ▼                                ▼
┌──────────────────┐       ┌──────────────────────────────┐
│  AgentSession    │──────►│     SessionStorage           │
│  (Events)        │       │     (File I/O)               │
└──────────────────┘       └──────────────┬───────────────┘
                                          ▼
                           ┌──────────────────────────────┐
                           │  /files/sessions/*.json      │
                           └──────────────────────────────┘
```

---

## Core Components

### SessionHistoryManager

→ See: `history/SessionHistoryManager.kt`

High-level API for session management:

```kotlin
class SessionHistoryManager(storage, recordingService, scope) {
    suspend fun listSessions(): List<SessionInfo>
    suspend fun loadSession(sessionId: String): Result<ResumedSessionData>
    suspend fun deleteSession(sessionId: String): Result<Unit>
    fun startNewSession(model: String?, appVersion: String?): String
    fun resumeSession(data: ResumedSessionData)
    fun getRecordingService(): SessionRecordingService
}
```

### SessionRecordingService

→ See: `history/SessionRecordingService.kt`

Real-time bridge between `AgentEvent` stream and persisted `SessionRecord`:

- Record user messages and agent responses in real-time
- Build agent messages incrementally using `AgentMessageBuffer`
- Debounce writes (500ms delay)
- Handle session resume and completion

```kotlin
class SessionRecordingService(storage, scope) {
    fun initializeNewSession(model: String?, appVersion: String?): String
    fun resumeSession(data: ResumedSessionData)
    fun recordUserMessage(id: String, timestamp: Long, text: String)
    fun startAgentMessage(id: String, timestamp: Long)
    fun appendTextDelta(delta: String)
    fun recordAction(actionId: String, toolName: String, description: String, state: String)
    fun updateActionState(actionId: String, state: String, result: String?)
    fun completeAgentMessage()
    fun completeSession()
}
```

### SessionStorage

→ See: `history/storage/SessionStorage.kt`

Low-level file I/O operations:

```kotlin
class SessionStorage(context) {
    suspend fun writeSession(fileName: String, record: SessionRecord): Result<Unit>
    suspend fun readSession(fileName: String): Result<SessionRecord>
    suspend fun listSessionFiles(): List<File>
    suspend fun deleteSession(fileName: String): Result<Unit>
    fun generateFileName(sessionId: String): String
}
```

**Storage Location:** `/data/data/{package}/files/sessions/`

**File Naming:** `session-{timestamp}-{uuid}.json`

---

## Data Models

### SessionRecord

→ See: `history/model/SessionRecord.kt`

Complete session data stored on disk:

```kotlin
@Serializable
data class SessionRecord(
    val sessionId: String,
    val startTime: Long,
    val lastUpdated: Long,
    val messages: List<MessageRecord>,
    val summary: String? = null,
    val metadata: SessionMetadata = SessionMetadata()
)

@Serializable
data class SessionMetadata(
    val appVersion: String? = null,
    val model: String? = null,
    val turnCount: Int = 0,
    val completedNormally: Boolean = false
)
```

### MessageRecord

→ See: `history/model/MessageRecord.kt`

```kotlin
@Serializable
sealed interface MessageRecord {
    val id: String
    val timestamp: Long
    
    data class User(id, timestamp, text: String) : MessageRecord
    data class Agent(id, timestamp, contentBlocks: List<ContentBlockRecord>, isComplete: Boolean) : MessageRecord
}

@Serializable
sealed interface ContentBlockRecord {
    data class Text(val text: String) : ContentBlockRecord
    data class Action(id, toolName, description, state, resultSummary?) : ContentBlockRecord
}
```

### SessionInfo

→ See: `history/model/SessionInfo.kt`

Lightweight summary for session list UI:

```kotlin
data class SessionInfo(
    val id: String,
    val fileName: String,
    val startTime: Long,
    val lastUpdated: Long,
    val messageCount: Int,
    val displayTitle: String,
    val firstUserMessage: String,
    val isActive: Boolean = false
)
```

---

## Recording Flow

```
AgentEvent                     SessionRecordingService              File
    │                                    │                            │
    │ TaskStarted                        │                            │
    │───────────────────────────────────►│ startAgentMessage()        │
    │                                    │                            │
    │ MessageDelta("I'll...")            │                            │
    │───────────────────────────────────►│ appendTextDelta()          │
    │                                    │ (buffer, no save)          │
    │                                    │                            │
    │ ActionExecuted(click)              │                            │
    │───────────────────────────────────►│ recordAction()             │
    │                                    │───────────────────────────►│
    │                                    │ (debounced save)           │
    │                                    │                            │
    │ TaskCompleted                      │                            │
    │───────────────────────────────────►│ completeAgentMessage()     │
    │                                    │ completeSession()          │
    │                                    │───────────────────────────►│
    │                                    │ (immediate save)           │
```

---

## Session Lifecycle

```
┌─────────────┐
│  No Session │
└──────┬──────┘
       │ startNewSession() or resumeSession()
       ▼
┌─────────────┐
│   Active    │◄──── recordUserMessage()
│   Session   │◄──── appendTextDelta()
│             │◄──── recordAction()
└──────┬──────┘      (debounced auto-save)
       │ completeSession()
       ▼
┌─────────────┐
│  Completed  │ ──► Saved to /files/sessions/*.json
│   Session   │
└──────┬──────┘
       │ listSessions() + user selects
       ▼
┌─────────────┐
│   Resumed   │ ──► loadSession() ──► resumeSession()
└─────────────┘
```

---

## File Structure

```
history/
├── HistoryManager.kt           # Token management, truncation
├── SessionHistoryManager.kt    # High-level session management
├── SessionRecordingService.kt  # Real-time recording
├── AgentMessageBuffer.kt       # Streaming agent message buffer
├── model/
│   ├── SessionRecord.kt        # Complete session data
│   ├── MessageRecord.kt        # Message types
│   ├── SessionInfo.kt          # Lightweight summary
│   └── MessageConverter.kt     # ChatMessage ↔ MessageRecord
└── storage/
    └── SessionStorage.kt       # File I/O operations
```

---

## Related Docs

- [UI User Interaction](../ui/user_interaction.md) - Session history UI
- [Protocol](../protocol/protocol.md) - Events that trigger recording
- [Session](../infra/session.md) - AgentSession lifecycle
