# Session History Persistence

> Session recording, storage, runtime prompt history, and resume functionality.
> Last updated: 2026-02-23 (commit: 1dd2020)

## Overview

The session history system has three layers:

1. **Persistence layer** — automatic recording of chat sessions to disk for browsing and resuming past conversations
2. **Runtime layer** — in-memory conversation history management for LLM context with token budgeting and truncation
3. **Checkpoint layer** — session state snapshots for process-death recovery (history + todos + scratchpad)

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

## Persistence Layer

### SessionHistoryManager

> See: `history/SessionHistoryManager.kt`

High-level API coordinating between `SessionStorage` and `SessionRecordingService`.

```kotlin
class SessionHistoryManager(storage, recordingService, scope) {
    suspend fun listSessions(): List<SessionInfo>        // Cached, sorted newest-first
    suspend fun loadSession(sessionId: String): Result<ResumedSessionData>
    suspend fun loadSessionByFileName(fileName: String): Result<ResumedSessionData>
    suspend fun deleteSession(sessionId: String): Result<Unit>
    suspend fun deleteSessionByFileName(fileName: String): Result<Unit>
    fun getMostRecentSession(): SessionInfo?
    fun startNewSession(model: String?, appVersion: String?): String
    fun resumeSession(data: ResumedSessionData)
    fun getCurrentSessionId(): String?
    fun setActiveSessionId(sessionId: String?)    // Bridge for per-session RS
    fun hasActiveSession(): Boolean
    fun endSession(reason: CompletionReason)
    fun getRecordingService(): SessionRecordingService
}
```

Session info caching uses `ConcurrentHashMap<String, CachedSessionInfo>` with file modification time checks — entries are re-read only when the file has been updated. Protected by `Mutex` for concurrent access.

**Session lookup:** `loadSession(sessionId)` extracts session IDs from file names using `extractSessionIdFromFileName()` for exact matching (avoids substring collisions with UUID-based IDs).

**Active session tracking:** Two `SessionRecordingService` instances exist — one in `SessionHistoryManager` (manages sidebar listing) and a per-session one in `SessionServices` (records actual events). `externalActiveSessionId` bridges this gap so the sidebar can correctly identify the active session. Set by `MainActivity` at session lifecycle boundaries (create, rebind, clear).

### SessionRecordingService

> See: `history/SessionRecordingService.kt`

Real-time bridge between `AgentEvent` stream and persisted `SessionRecord`:

```kotlin
class SessionRecordingService(storage, scope) {
    fun initializeNewSession(sessionId?, model?, appVersion?): String
    fun resumeSession(data: ResumedSessionData)
    fun recordUserMessage(id, timestamp, text)
    fun startAgentMessage(id, timestamp)
    fun appendTextDelta(delta)
    fun recordAction(actionId, toolName, description, state)
    fun updateActionState(actionId, state, result?)
    fun recordScreenState(state: ScreenStateRecord)
    fun completeAgentMessage()
    fun completeSession(reason: CompletionReason)
    fun clearSession()
    suspend fun clearSessionAndAwait()   // Cancels + awaits pending saves before returning
}
```

Key behaviors:
- **Debounced saves**: 500ms delay (`SAVE_DEBOUNCE_MS`) to avoid excessive I/O
- **Agent message buffering**: Uses `AgentMessageBuffer` to accumulate streaming text + interleaved actions
- **Immediate save** on session completion
- **Screen state recording**: Normalizes paths, captures `traceRunId` for replay/debug artifact correlation

### AgentMessageBuffer

> See: `history/AgentMessageBuffer.kt`

Buffers a streaming agent message with interleaved text and action blocks:

- `appendText(delta)` — accumulates text in `StringBuilder`
- `recordAction(action)` — finalizes current text block, adds action block
- `updateActionState(actionId, state, result?)` — updates existing action in-place
- `buildPartialSnapshot()` — returns current state without finalizing (for incremental saves)
- `finalizeSnapshot()` — returns final snapshot and clears buffer

### SessionRecordMessageMerger

> See: `history/SessionRecordMessageMerger.kt`

Internal utility: `mergeAgentSnapshot()` updates or inserts an agent message snapshot into a `SessionRecord`, returning a new record with updated `lastUpdated`.

### SessionStorage

> See: `history/storage/SessionStorage.kt`

Low-level file I/O operations.

```kotlin
class SessionStorage(context) {
    suspend fun writeSession(fileName, record: SessionRecord): Result<Unit>
    suspend fun readSession(fileName): Result<SessionRecord>
    suspend fun writeSnapshot(fileName, snapshot: SessionRuntimeSnapshot): Result<Unit>
    suspend fun readSnapshot(fileName): Result<SessionRuntimeSnapshot>
    suspend fun listSessionFiles(): List<File>
    suspend fun deleteSession(fileName): Result<Unit>
    suspend fun deleteSessionPair(fileName): Result<Unit>  // session + context files
    fun generateFileName(sessionId): String
    fun contextFileNameFor(sessionFileName): String
    fun sessionExists(fileName): Boolean
}
```

- **Storage location**: `/data/data/{package}/files/sessions/`
- **Session files**: `session-{yyyy-MM-ddTHH-mm-ss}-{uuid}.json`
- **Context files**: `context-{yyyy-MM-ddTHH-mm-ss}-{uuid}.json` (checkpoint snapshots)
- **JSON config**: pretty print, ignore unknown keys, encode defaults
- All I/O on `Dispatchers.IO`

---

## Runtime Prompt History

### HistoryManager

> See: `history/HistoryManager.kt`

In-memory conversation history for each active agent session. Stores `ResponseItem` list used as LLM `input`.

```kotlin
class HistoryManager {
    fun addItem(item: ResponseItem)
    fun recordItems(newItems: List<ResponseItem>, policy: TruncationPolicy)
    fun getAll(): List<ResponseItem>        // Defensive copy
    fun forPrompt(): List<ResponseItem>     // Normalized for LLM
    fun size(): Int
    fun isEmpty(): Boolean
    fun clear()
    fun estimateTokenCount(): Long
    fun isApproachingLimit(maxTokens: Long, warningThreshold: Float = 0.8f): Boolean
    fun dropLastNUserTurns(n: Int)
    fun removeFirstItem()
    fun compress(targetTokens: Long)
    fun getSummary(): String
}
```

Key behaviors:
- **Token estimation**: `TOKENS_PER_CHAR = 0.25f`, rough estimate for context window management
- **Auto-compression**: triggers at `autoCompressThreshold` (85%) of `maxTokenBudget` (100K tokens)
- **Two-strategy compression**: (1) aggressive truncation of old function outputs, (2) remove oldest non-user items. User messages are always preserved to maintain task intent and corrections.
- **History normalization** (`normalizeHistory`): ensures function call/output pairs are matched; adds placeholders for missing outputs, removes orphaned outputs
- **Screen observation tagging**: items tagged with `isScreenObservation=true` are later compressed by `PromptBuilder`
- **Thread-safe**: all methods `@Synchronized`

### HistoryConfig

> See: `history/HistoryConfig.kt`

```kotlin
data class HistoryConfig(
    val defaultTruncationPolicy: TruncationPolicy = TruncationPolicy.CONSERVATIVE,
    val maxTokenBudget: Long = 100_000,
    val autoCompress: Boolean = true,
    val autoCompressThreshold: Float = 0.85f
)
```

### TruncationPolicy

Defined in `HistoryConfig.kt`:

| Policy | Max Tokens per Output |
|--------|----------------------|
| `NONE` | No truncation |
| `CONSERVATIVE` | 8,000 |
| `AGGRESSIVE` | 2,000 |
| `MINIMAL` | 500 |

### ResponseItem

> See: `history/ResponseItem.kt`

Sealed class for conversation items:

```kotlin
sealed class ResponseItem {
    abstract fun estimateTokens(): Long

    data class Message(role, content, name?, isScreenObservation = false) : ResponseItem()
    data class FunctionCall(id, name, arguments: JSONObject) : ResponseItem()
    data class FunctionCallOutput(callId, content, success = true, truncated = false) : ResponseItem()
}
```

---

## Data Models

### SessionRecord

> See: `history/model/SessionRecord.kt`

```kotlin
@Serializable
data class SessionRecord(
    val sessionId: String,
    val startTime: Long,
    val lastUpdated: Long,
    val messages: List<MessageRecord>,
    val screenStates: List<ScreenStateRecord> = emptyList(),
    val summary: String? = null,
    val metadata: SessionMetadata = SessionMetadata()
)

@Serializable
data class SessionMetadata(
    val appVersion: String? = null,
    val model: String? = null,
    val traceRunId: String? = null,
    val turnCount: Int = 0,
    val completedNormally: Boolean = false
)
```

### MessageRecord

> See: `history/model/MessageRecord.kt`

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

Action `state` values: `"proposed"`, `"executing"`, `"success"`, `"failed"`, `"skipped"`.

### ScreenStateRecord

> See: `history/model/ScreenStateRecord.kt`

```kotlin
@Serializable
data class ScreenStateRecord(
    val id: String,
    val timestamp: Long,
    val turnId: String,
    val turnNumber: Int,
    val phase: ScreenStatePhase,
    val elementCount: Int,
    val packageName: String?,
    val activityName: String?,
    val rawA11yTreePath: String?,
    val sanitizedA11yTreePath: String?,
    val screenshotPath: String?,
    val traceRunId: String?
)
```

### SessionInfo

> See: `history/model/SessionInfo.kt`

Lightweight summary for session list UI (avoids loading full content):

```kotlin
data class SessionInfo(
    val id: String,
    val fileName: String,
    val startTime: Long,
    val lastUpdated: Long,
    val messageCount: Int,
    val displayTitle: String,      // Truncated to 50 chars
    val firstUserMessage: String,
    val isActive: Boolean = false
)
```

### MessageConverter

> See: `history/model/MessageConverter.kt`

Bidirectional conversion between `ChatMessage` (UI) and `MessageRecord` (persistence):

- `toRecord(ChatMessage) → MessageRecord`
- `fromRecord(MessageRecord) → ChatMessage`
- Batch: `toRecords()`, `fromRecords()`

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
    │                                    │ (debounced save, 500ms)    │
    │                                    │                            │
    │ TaskCompleted                      │                            │
    │───────────────────────────────────►│ completeAgentMessage()     │
    │                                    │───────────────────────────►│
    │                                    │ (immediate save)           │
    │                                    │                            │
    │  (session enters Hot Idle,         │                            │
    │   follow-up starts new task)       │                            │
    │                                    │                            │
    │ SessionCompleted (shutdown only)   │                            │
    │───────────────────────────────────►│ completeSession()          │
    │                                    │───────────────────────────►│
    │                                    │ (immediate save)           │
```

---

## File Structure

```
history/
├── HistoryManager.kt              # Runtime prompt history (token budget, truncation, mutation listener)
├── HistoryConfig.kt               # Configuration (TruncationPolicy, token budgets)
├── ResponseItem.kt                # Conversation items (Message, FunctionCall, FunctionCallOutput)
├── SessionHistoryManager.kt       # High-level session management (list, load, delete, resume, active tracking)
├── SessionRecordingService.kt     # Real-time event recording (debounced saves)
├── AgentMessageBuffer.kt          # Streaming agent message buffer (text + actions)
├── SessionRecordMessageMerger.kt  # Merge agent snapshots into SessionRecord
├── model/
│   ├── SessionRecord.kt           # Complete session data + metadata
│   ├── SessionRuntimeSnapshot.kt  # Checkpoint snapshot (history + todos + scratchpad + config)
│   ├── HistoryItemConverter.kt    # ResponseItem ↔ PersistedHistoryItem conversion (JSONObject ↔ String)
│   ├── MessageRecord.kt           # Message types + content blocks
│   ├── SessionInfo.kt             # Lightweight session summary (isActive flag)
│   ├── ScreenStateRecord.kt       # Screen state reference (paths for replay/debug)
│   └── MessageConverter.kt        # ChatMessage ↔ MessageRecord conversion
└── storage/
    └── SessionStorage.kt          # File I/O operations (session + context files)
```

---

## Related Docs

- [UI User Interaction](../ui/user_interaction.md) - Session history UI
- [Session State Machine](../ui/session/state_machine.md) - Checkpoint coordination details
- [Session User Flows](../ui/session/user_flows.md) - Recording coordination across dual RS instances
- [Protocol](../protocol/protocol.md) - Events that trigger recording
- [Session](../infra/session.md) - AgentSession lifecycle
- [Planning](../agent/planning.md) - HistoryManager token budget in agent context
