# Session History Persistence

> Session recording, storage, runtime prompt history, compression pipeline, and resume functionality.
> Last updated: 2026-02-24

## Overview

The session history system has three layers:

1. **Persistence layer** — automatic recording of chat sessions to disk for browsing and resuming past conversations
2. **Runtime layer** — in-memory conversation history management for LLM context with token budgeting, multi-phase compression, and proactive screen downgrade
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

### ResponseItem

> See: `history/ResponseItem.kt`

Sealed class for conversation items:

```kotlin
sealed class ResponseItem {
    abstract fun estimateTokens(): Long

    data class Message(kind: MessageKind, content: String, name: String? = null) : ResponseItem() {
        val role: String get() = kind.apiRole  // Derived from kind
    }
    data class FunctionCall(id: String, name: String, arguments: JSONObject) : ResponseItem()
    data class FunctionCallOutput(callId: String, content: String, success: Boolean = true, truncated: Boolean = false) : ResponseItem()
}
```

### MessageKind

> See: `history/ResponseItem.kt`

Explicit message classification that replaces the ambiguous `role: String` + `isScreenObservation: Boolean`.

```kotlin
enum class MessageKind {
    USER_INTENT,          // User's task goal or follow-up instruction
    SCREEN_OBSERVATION,   // Screen state captured each turn
    ASSISTANT_TEXT,       // Agent reasoning / action description
    COMPRESSION_DIGEST;   // Breadcrumb inserted when history is evicted

    val apiRole: String get() = when (this) {
        USER_INTENT, SCREEN_OBSERVATION -> "user"
        ASSISTANT_TEXT, COMPRESSION_DIGEST -> "assistant"
    }
}
```

**Why:** The previous design used `role == "user"` for both user intent and screen observations. During compression, all `role == "user"` messages were protected — including screen observations, which are the biggest token consumer. Result: compression entered no-op loops and could never reach budget.

### HistoryManager

> See: `history/HistoryManager.kt`

In-memory conversation history for each active agent session. Stores `ResponseItem` list used as LLM `input`.

```kotlin
class HistoryManager(config: HistoryConfig = HistoryConfig()) {
    fun addItem(item: ResponseItem)                      // Add + proactive screen downgrade + auto-compress
    fun recordItems(newItems: List<ResponseItem>, policy) // Batch add
    fun replaceAll(newItems: List<ResponseItem>)         // Restore exact checkpoint (no side effects)
    fun getAll(): List<ResponseItem>                     // Defensive copy
    fun forPrompt(): List<ResponseItem>                  // Normalized for LLM
    fun size(): Int
    fun isEmpty(): Boolean
    fun clear()
    fun estimateTokenCount(): Long
    fun isApproachingLimit(maxTokens: Long, warningThreshold: Float = 0.8f): Boolean
    fun compress(targetTokens: Long): CompressionResult  // Multi-phase compression pipeline
    fun getSummary(): String                             // Debug summary
}
```

Key behaviors:
- **Token estimation**: `TOKENS_PER_CHAR = 0.25f`, rough estimate for context window management
- **Proactive screen downgrade**: on every new `SCREEN_OBSERVATION`, downgrades all but last `recentFullScreens` to one-line summaries
- **Auto-compression**: triggers at `autoCompressThreshold` (85%) of `maxTokenBudget`, compresses down to `compressTargetRatio` (50%)
- **History normalization** (`forPrompt`): ensures function call/output pairs are matched; adds placeholders for missing outputs, removes orphaned outputs
- **Thread-safe**: all public methods `@Synchronized`
- **Mutation listener**: `setMutationListener()` for checkpoint coordination — fires after any state change

### HistoryConfig

> See: `history/HistoryConfig.kt`

```kotlin
data class HistoryConfig(
    val defaultTruncationPolicy: TruncationPolicy = TruncationPolicy.CONSERVATIVE,
    val maxTokenBudget: Long = 100_000,
    val autoCompress: Boolean = true,
    val autoCompressThreshold: Float = 0.85f,
    val compressTargetRatio: Float = 0.5f,
    val recentFullScreens: Int = 3,
    val recentWindowSize: Int = 10
)
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `maxTokenBudget` | 100,000 | Upper bound for estimated token count |
| `autoCompressThreshold` | 0.85 | Fraction of budget at which auto-compress triggers |
| `compressTargetRatio` | 0.5 | Fraction of budget to compress DOWN to |
| `recentFullScreens` | 3 | Number of recent screen observations kept as full JSON |
| `recentWindowSize` | 10 | Number of tail items protected from eviction |

### TruncationPolicy

| Policy | Max Tokens per Output |
|--------|----------------------|
| `NONE` | No truncation |
| `CONSERVATIVE` | 8,000 |
| `AGGRESSIVE` | 2,000 |
| `MINIMAL` | 500 |

Applied on ingestion (`processItem`), not during compression — tool outputs in this agent are typically 13-65 tokens, so compression-phase truncation would be dead code.

---

## Compression Pipeline

> See: `history/HistoryManager.kt` — `compress(targetTokens)`

### Design Principles

1. **`USER_INTENT` is never deleted** — hard invariant, not heuristic. Users issue follow-up requests after task completion; all must survive.
2. **Screen observations are the primary compression target** — they contain full accessibility tree JSON and dominate token usage.
3. **Call/output pairing is preserved** — `FunctionCall` and its paired `FunctionCallOutput` are always evicted as an atomic group.
4. **Compression is deterministic** — no LLM calls, no randomness.
5. **Compress rarely, compress deep** — compress to 50% of budget to maximize KV cache stability (see below).

### Pipeline Phases

```
compress(targetTokens)
│
├─ Budget check: already ≤ target? → return Noop
│
├─ Phase 0: Normalize
│  └─ Ensure every FunctionCall has a paired FunctionCallOutput
│     (insert placeholder if missing, remove orphans)
│
├─ Phase 1: Screen Downgrade
│  └─ Keep last recentFullScreens (3) screen observations as full JSON
│     Rewrite older ones to: "Screen: {N} elements (compressed)"
│  └─ If ≤ target → return Compressed
│
├─ Phase 2: Group-Aware Eviction
│  └─ Walk items from oldest to newest (outside recentWindowSize tail)
│  └─ Skip: USER_INTENT, COMPRESSION_DIGEST
│  └─ Evict whole structural groups:
│     - Message (SCREEN_OBSERVATION, ASSISTANT_TEXT) → remove
│     - FunctionCall → remove call + paired output atomically
│     - Orphaned FunctionCallOutput → remove
│  └─ Insert one COMPRESSION_DIGEST breadcrumb at eviction point:
│     "[Compressed] Removed N earlier items: M tool actions, K screen observations. History truncated to save context."
│
├─ Phase 3: Hard Guard
│  └─ Merge adjacent COMPRESSION_DIGEST messages into one
│  └─ If still over budget and only USER_INTENT + digests remain:
│     → return BudgetUnreachable
│
└─ Return: Compressed(before, after, stepsApplied) or Noop
```

### CompressionResult

```kotlin
sealed class CompressionResult {
    data class Noop(val before: Long, val after: Long) : CompressionResult()
    data class Compressed(val before: Long, val after: Long, val stepsApplied: Int) : CompressionResult()
    data class BudgetUnreachable(val after: Long, val minimumPossible: Long) : CompressionResult()
}
```

### Proactive Screen Downgrade

Screen downgrade runs not only during `compress()` (Phase 1) but also proactively on every `addItem()` / `recordItems()` that includes a `SCREEN_OBSERVATION`:

```
addItem(SCREEN_OBSERVATION)
│
├─ Add to items list
├─ downgradeOldScreens()
│  └─ Find all SCREEN_OBSERVATION indices
│  └─ If count > recentFullScreens:
│     └─ For each beyond the last N: rewrite content to one-liner
│        "Screen state (42 elements):\n```json\n[...]\n```"
│        → "Screen: 42 elements (compressed)"
└─ autoCompressIfNeeded()
```

This means most screen bloat is eliminated before `compress()` ever needs to run. Net token growth per turn is ~275 tokens (assistant text + call + output + screen delta), not ~4K (full screen JSON).

### KV Cache Efficiency

LLM providers cache the KV states of the conversation prefix. When history items are removed from the front, every subsequent token's cache entry is invalidated. Frequent small compressions destroy cache hit rate.

```
Bad (compress to ~95% → triggers again next turn):
  Turn 8:  15.3K → compress to ~14.9K → 0.4K headroom
  Turn 9:  +275 → re-triggers → KV cache invalidated every 1-2 turns

Good (compress to 50% → stable for ~22 turns):
  Turn 8:  15.3K → compress to 9K → 6.3K headroom
  Turn 9-30: +~275/turn → KV cache reused for ~22 turns
```

### Auto-Compress Trigger

```kotlin
private fun autoCompressIfNeeded() {
    if (!config.autoCompress) return
    val trigger = (config.maxTokenBudget * config.autoCompressThreshold).toLong()
    if (estimateTokenCount() > trigger) {
        val target = (config.maxTokenBudget * config.compressTargetRatio).toLong()
        compress(target)
    }
}
```

With 100K budget: trigger at 85K, compress to 50K, stable for many turns.

### Protected Recent Window

The last `recentWindowSize` (default 10) items in the history list are never touched by Phase 2 eviction. Phase 1 may still rewrite old screen payloads if they are outside `recentFullScreens`, even within the protected window.

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

### HistoryItemConverter

> See: `history/model/HistoryItemConverter.kt`

Bidirectional conversion between `ResponseItem` (runtime) and `PersistedHistoryItem` (checkpoint JSON):

- `toRecord(ResponseItem) → PersistedHistoryItem`
- `fromRecord(PersistedHistoryItem) → ResponseItem`
- **Backward compatibility**: `resolveMessageKind()` maps legacy checkpoints (`role` + `isScreenObservation` fields) to `MessageKind`

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
├── HistoryManager.kt              # Runtime prompt history (compression pipeline, proactive screen downgrade)
├── HistoryConfig.kt               # Configuration (TruncationPolicy, token budgets, compression params)
├── ResponseItem.kt                # Conversation items (MessageKind, Message, FunctionCall, FunctionCallOutput)
├── SessionHistoryManager.kt       # High-level session management (list, load, delete, resume, active tracking)
├── SessionRecordingService.kt     # Real-time event recording (debounced saves)
├── AgentMessageBuffer.kt          # Streaming agent message buffer (text + actions)
├── SessionRecordMessageMerger.kt  # Merge agent snapshots into SessionRecord
├── model/
│   ├── SessionRecord.kt           # Complete session data + metadata
│   ├── SessionRuntimeSnapshot.kt  # Checkpoint snapshot (history + todos + scratchpad + config)
│   ├── HistoryItemConverter.kt    # ResponseItem ↔ PersistedHistoryItem conversion (with legacy migration)
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
