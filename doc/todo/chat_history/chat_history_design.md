# Session History Management Design

> This document describes the design for persisting and restoring chat session history.

## Table of Contents

1. [Goals](#goals)
2. [Inspiration from Reference Implementations](#inspiration-from-reference-implementations)
3. [Architecture Overview](#architecture-overview)
4. [Data Models](#data-models)
5. [Storage Layer](#storage-layer)
6. [Session Recording Service](#session-recording-service)
7. [Session Selector](#session-selector)
8. [UI Components](#ui-components)
9. [Integration Points](#integration-points)
10. [Implementation Plan](#implementation-plan)

---

## Goals

| Goal | Description |
|------|-------------|
| **Persistence** | Save chat sessions to disk so they survive app restarts |
| **Resume** | Load a previous session and continue the conversation |
| **Browse** | List all past sessions with preview and timestamps |
| **Delete** | Remove old sessions to free storage |
| **Simple** | Keep complexity low - no cloud sync, no multi-device |

### Non-Goals (for now)

- Cloud sync / backup
- Export to other formats
- Session sharing between devices
- Session merging

---

## Inspiration from Reference Implementations

### From gemini-cli

| Concept | What we borrow |
|---------|----------------|
| **ConversationRecord** | Session container with sessionId, timestamps, messages[], summary |
| **ChatRecordingService** | Single service that records messages as they occur |
| **Session file naming** | `session-{timestamp}-{id}.json` pattern |
| **SessionSelector** | Utility for listing and finding sessions |
| **Summary generation** | Display name from first user message or AI-generated summary |

### From codex

| Concept | What we borrow |
|---------|----------------|
| **Resume picker UI** | Interactive session picker with search |
| **JSONL format consideration** | Though we'll use JSON for simplicity |
| **Preview from first message** | Show first user message as session preview |
| **Timestamp display** | Relative time (e.g., "5 minutes ago") |

### What we simplify

- No pagination (sessions are lightweight)
- No git branch tracking (not relevant for mobile)
- No project hash (single app context)
- No tool output truncation policies for persistence (record everything)
- No multi-file rollout format (single JSON file per session)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                            Android Application                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌────────────────────┐                                                 │
│  │    MainActivity    │                                                 │
│  │    + ChatScreen    │                                                 │
│  └─────────┬──────────┘                                                 │
│            │ uses                                                        │
│            ▼                                                             │
│  ┌────────────────────┐    observes    ┌───────────────────────────┐   │
│  │   ChatViewModel    │ ◄────────────── │  SessionHistoryManager    │   │
│  │                    │                 │  (coordinates all)         │   │
│  └─────────┬──────────┘                 └──────────┬────────────────┘   │
│            │                                        │                    │
│            │ collects events                        │ uses               │
│            ▼                                        ▼                    │
│  ┌────────────────────┐                 ┌───────────────────────────┐   │
│  │   AgentSession     │                 │  SessionRecordingService  │   │
│  │   (existing)       │ ◄──────────────►│  (records to disk)        │   │
│  └────────────────────┘    feeds        └──────────┬────────────────┘   │
│                            events                   │                    │
│                                                     │ reads/writes       │
│                                                     ▼                    │
│                                         ┌───────────────────────────┐   │
│                                         │    SessionStorage         │   │
│                                         │    (file I/O)             │   │
│                                         └──────────┬────────────────┘   │
│                                                     │                    │
│                                                     ▼                    │
│                                         ┌───────────────────────────┐   │
│                                         │  /data/data/.../sessions/ │   │
│                                         │  session-{ts}-{id}.json   │   │
│                                         └───────────────────────────┘   │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Responsibility |
|-----------|----------------|
| **SessionStorage** | Low-level file read/write operations |
| **SessionRecordingService** | Records events to a session file in real-time |
| **SessionHistoryManager** | High-level API: list, load, delete, create sessions |
| **ChatViewModel** | Coordinates between UI and session management |

---

## Data Models

### SessionRecord

The main container for a persisted session.

```kotlin
/**
 * A complete chat session record stored on disk.
 */
@Serializable
data class SessionRecord(
    /** Unique session identifier (UUID) */
    val sessionId: String,
    
    /** When the session started */
    val startTime: Long,
    
    /** When the session was last updated */
    val lastUpdated: Long,
    
    /** All messages in the session */
    val messages: List<MessageRecord>,
    
    /** AI-generated or extracted summary (optional) */
    val summary: String? = null,
    
    /** Session metadata */
    val metadata: SessionMetadata = SessionMetadata()
)

@Serializable
data class SessionMetadata(
    /** App version that created this session */
    val appVersion: String? = null,
    
    /** Model used (e.g., "gpt-4o") */
    val model: String? = null,
    
    /** Total number of turns */
    val turnCount: Int = 0,
    
    /** Whether session completed normally */
    val completedNormally: Boolean = false
)
```

### MessageRecord

Persisted message that can be restored to ChatMessage.

```kotlin
/**
 * A message in a session, can be user or agent.
 */
@Serializable
sealed interface MessageRecord {
    val id: String
    val timestamp: Long
    
    @Serializable
    @SerialName("user")
    data class User(
        override val id: String,
        override val timestamp: Long,
        val text: String
    ) : MessageRecord
    
    @Serializable
    @SerialName("agent")
    data class Agent(
        override val id: String,
        override val timestamp: Long,
        val contentBlocks: List<ContentBlockRecord>,
        val isComplete: Boolean
    ) : MessageRecord
}

/**
 * Persisted content block (text or action).
 */
@Serializable
sealed interface ContentBlockRecord {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentBlockRecord
    
    @Serializable
    @SerialName("action")
    data class Action(
        val id: String,
        val toolName: String,
        val description: String,
        val state: String, // "proposed", "executing", "success", "failed", "skipped"
        val resultSummary: String? = null
    ) : ContentBlockRecord
}
```

### SessionInfo

Lightweight session info for listing (avoids loading full messages).

```kotlin
/**
 * Summary information for a session (for listing without loading full content).
 */
data class SessionInfo(
    /** Session ID */
    val id: String,
    
    /** File path (relative to sessions directory) */
    val fileName: String,
    
    /** When session started */
    val startTime: Long,
    
    /** When session was last updated */
    val lastUpdated: Long,
    
    /** Number of messages */
    val messageCount: Int,
    
    /** Display title (summary or first user message) */
    val displayTitle: String,
    
    /** First user message text (for preview) */
    val firstUserMessage: String,
    
    /** Whether this is the currently active session */
    val isActive: Boolean = false
)
```

---

## Storage Layer

### SessionStorage

Low-level file operations using Kotlin Serialization.

```kotlin
/**
 * Low-level storage operations for session files.
 * 
 * Files are stored in: /data/data/{package}/files/sessions/
 * File naming: session-{yyyy-MM-ddTHH-mm-ss}-{uuid_8chars}.json
 */
class SessionStorage(private val context: Context) {
    
    companion object {
        private const val SESSIONS_DIR = "sessions"
        private const val SESSION_PREFIX = "session-"
        private const val SESSION_SUFFIX = ".json"
    }
    
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    /** Get the sessions directory, creating if needed */
    fun getSessionsDir(): File
    
    /** Generate a filename for a new session */
    fun generateFileName(sessionId: String): String
    
    /** Write a session record to disk */
    suspend fun writeSession(fileName: String, record: SessionRecord): Result<Unit>
    
    /** Read a session record from disk */
    suspend fun readSession(fileName: String): Result<SessionRecord>
    
    /** List all session files (sorted by modification time, newest first) */
    suspend fun listSessionFiles(): List<File>
    
    /** Delete a session file */
    suspend fun deleteSession(fileName: String): Result<Unit>
    
    /** Check if a session file exists */
    fun sessionExists(fileName: String): Boolean
}
```

### File Format Example

```json
{
  "sessionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "startTime": 1705881600000,
  "lastUpdated": 1705882500000,
  "messages": [
    {
      "type": "user",
      "id": "msg-001",
      "timestamp": 1705881600000,
      "text": "Open the Settings app"
    },
    {
      "type": "agent",
      "id": "task-001",
      "timestamp": 1705881601000,
      "contentBlocks": [
        {
          "type": "text",
          "text": "I'll open the Settings app for you."
        },
        {
          "type": "action",
          "id": "action-001",
          "toolName": "click",
          "description": "Click on Settings",
          "state": "success",
          "resultSummary": "Clicked Settings icon"
        },
        {
          "type": "text",
          "text": "Settings app is now open."
        }
      ],
      "isComplete": true
    }
  ],
  "summary": "Open Settings app",
  "metadata": {
    "appVersion": "1.0.0",
    "model": "gpt-4o",
    "turnCount": 1,
    "completedNormally": true
  }
}
```

---

## Session Recording Service

Records events in real-time as they occur.

```kotlin
/**
 * Records chat events to a session file in real-time.
 * 
 * Usage:
 * 1. Call initialize() with optional resumeData
 * 2. Call record*() methods as events occur
 * 3. Session is auto-saved after each significant change
 */
class SessionRecordingService(
    private val storage: SessionStorage
) {
    private var currentSession: SessionRecord? = null
    private var currentFileName: String? = null
    
    /** Initialize a new session or resume an existing one */
    fun initialize(resumeData: ResumedSessionData? = null)
    
    /** Record a user message */
    fun recordUserMessage(id: String, timestamp: Long, text: String)
    
    /** Start recording an agent message (creates placeholder) */
    fun startAgentMessage(id: String, timestamp: Long)
    
    /** Append text delta to current agent message */
    fun appendTextDelta(delta: String)
    
    /** Record an action in current agent message */
    fun recordAction(action: ActionRecord)
    
    /** Update an action's state */
    fun updateActionState(actionId: String, state: String, result: String?)
    
    /** Mark current agent message as complete */
    fun completeAgentMessage()
    
    /** Mark session as completed normally */
    fun completeSession()
    
    /** Get the current session record (for summary generation) */
    fun getCurrentSession(): SessionRecord?
    
    /** Get current file name */
    fun getCurrentFileName(): String?
    
    /** Save current state to disk */
    private suspend fun save()
}

data class ResumedSessionData(
    val session: SessionRecord,
    val fileName: String
)
```

### Recording Flow

```
AgentEvent.TaskStarted
    │
    ├─► recordUserMessage(event.input)
    │
    └─► startAgentMessage(event.taskId)
    
AgentEvent.MessageDelta
    │
    └─► appendTextDelta(event.delta)
    
AgentEvent.ActionExecuted
    │
    └─► recordAction(...)
    │
    └─► updateActionState(...)
    
AgentEvent.TaskCompleted
    │
    ├─► completeAgentMessage()
    │
    └─► completeSession()
```

---

## Session Selector

High-level API for managing sessions.

```kotlin
/**
 * High-level session management API.
 */
class SessionHistoryManager(
    private val storage: SessionStorage,
    private val recordingService: SessionRecordingService
) {
    /** List all sessions (lightweight, doesn't load full content) */
    suspend fun listSessions(): List<SessionInfo>
    
    /** Load a session for resuming */
    suspend fun loadSession(sessionId: String): Result<ResumedSessionData>
    
    /** Load a session by file name */
    suspend fun loadSessionByFileName(fileName: String): Result<ResumedSessionData>
    
    /** Delete a session */
    suspend fun deleteSession(sessionId: String): Result<Unit>
    
    /** Get the most recent session (for "resume latest" feature) */
    suspend fun getMostRecentSession(): SessionInfo?
    
    /** Start a new session */
    fun startNewSession(): String // Returns sessionId
    
    /** Resume an existing session */
    fun resumeSession(data: ResumedSessionData)
    
    /** Get current session ID (if any) */
    fun getCurrentSessionId(): String?
    
    /** Check if there's an active session */
    fun hasActiveSession(): Boolean
}
```

### Session Info Extraction

To list sessions without loading full content:

```kotlin
/**
 * Extract SessionInfo from a session file by reading only the header.
 * 
 * Strategy: Read file, parse just enough to extract:
 * - sessionId, startTime, lastUpdated from top-level
 * - First user message text from messages[0] (if user type)
 * - summary field if present
 */
private suspend fun extractSessionInfo(file: File): SessionInfo? {
    // Read and parse JSON
    val record = storage.readSession(file.name).getOrNull() ?: return null
    
    // Extract first user message for preview
    val firstUserMessage = record.messages
        .filterIsInstance<MessageRecord.User>()
        .firstOrNull()?.text ?: "Empty session"
    
    // Use summary if available, otherwise first user message
    val displayTitle = record.summary 
        ?: firstUserMessage.take(50).let { 
            if (firstUserMessage.length > 50) "$it..." else it 
        }
    
    return SessionInfo(
        id = record.sessionId,
        fileName = file.name,
        startTime = record.startTime,
        lastUpdated = record.lastUpdated,
        messageCount = record.messages.size,
        displayTitle = displayTitle,
        firstUserMessage = firstUserMessage,
        isActive = record.sessionId == getCurrentSessionId()
    )
}
```

---

## UI Components

### SessionListSheet

Bottom sheet for browsing and selecting sessions.

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListSheet(
    sessions: List<SessionInfo>,
    onSessionSelect: (SessionInfo) -> Unit,
    onNewSession: () -> Unit,
    onDeleteSession: (SessionInfo) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
)
```

**Visual Design:**

```
┌────────────────────────────────────────────────┐
│  Session History                          [✕]  │
├────────────────────────────────────────────────┤
│  ┌──────────────────────────────────────────┐  │
│  │ [+] Start New Session                    │  │
│  └──────────────────────────────────────────┘  │
│                                                │
│  Recent Sessions                               │
│  ──────────────────────────────────────────    │
│                                                │
│  ┌──────────────────────────────────────────┐  │
│  │ Open Settings app                        │  │
│  │ 5 minutes ago • 3 messages           [🗑] │  │
│  └──────────────────────────────────────────┘  │
│                                                │
│  ┌──────────────────────────────────────────┐  │
│  │ Check my email                           │  │
│  │ 2 hours ago • 8 messages             [🗑] │  │
│  └──────────────────────────────────────────┘  │
│                                                │
│  ┌──────────────────────────────────────────┐  │
│  │ Send a text message                      │  │
│  │ Yesterday • 5 messages               [🗑] │  │
│  └──────────────────────────────────────────┘  │
│                                                │
└────────────────────────────────────────────────┘
```

### SessionListItem

Individual session row.

```kotlin
@Composable
fun SessionListItem(
    session: SessionInfo,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Card with:
    // - Title (summary or first message)
    // - Subtitle: relative time + message count
    // - Optional: current session indicator
    // - Delete button (swipe or icon)
}
```

### Time Formatting

```kotlin
/**
 * Format timestamp as relative time.
 * 
 * - < 1 minute: "Just now"
 * - < 60 minutes: "X minutes ago"
 * - < 24 hours: "X hours ago"
 * - < 7 days: "X days ago"
 * - >= 7 days: "MMM d" (e.g., "Jan 21")
 */
fun formatRelativeTime(timestamp: Long): String
```

---

## Integration Points

### 1. ChatViewModel Integration

```kotlin
class ChatViewModel(
    private val sessionProvider: () -> AgentSession?,
    private val sessionHistoryManager: SessionHistoryManager,  // NEW
    private val onSessionNeeded: ((String) -> Unit)? = null,
    private val onTaskCompleted: (() -> Unit)? = null
) : ViewModel() {
    
    // Session list state (for UI)
    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions.asStateFlow()
    
    /** Load sessions list */
    fun loadSessions() {
        viewModelScope.launch {
            _sessions.value = sessionHistoryManager.listSessions()
        }
    }
    
    /** Resume a session */
    fun resumeSession(sessionInfo: SessionInfo) {
        viewModelScope.launch {
            sessionHistoryManager.loadSession(sessionInfo.id)
                .onSuccess { data ->
                    // Clear current messages
                    _messages.clear()
                    
                    // Restore messages from record
                    data.session.messages.forEach { record ->
                        _messages.add(record.toChatMessage())
                    }
                    
                    // Update UI state
                    _uiState.update { it.copy(showEmptyState = _messages.isEmpty()) }
                    
                    // Resume the session in recording service
                    sessionHistoryManager.resumeSession(data)
                }
        }
    }
    
    /** Start new session */
    fun startNewSession() {
        clearConversation()
        sessionHistoryManager.startNewSession()
    }
    
    /** Delete a session */
    fun deleteSession(sessionInfo: SessionInfo) {
        viewModelScope.launch {
            sessionHistoryManager.deleteSession(sessionInfo.id)
            loadSessions() // Refresh list
        }
    }
}
```

### 2. AgentService Integration

```kotlin
class AgentService : AccessibilityService() {
    
    private lateinit var recordingService: SessionRecordingService
    
    override fun onCreate() {
        super.onCreate()
        val storage = SessionStorage(applicationContext)
        recordingService = SessionRecordingService(storage)
    }
    
    private fun collectEvents(session: AgentSession) {
        scope.launch {
            session.events.collect { event ->
                // Record to history
                when (event) {
                    is AgentEvent.TaskStarted -> {
                        recordingService.recordUserMessage(
                            id = UUID.randomUUID().toString(),
                            timestamp = event.timestamp,
                            text = event.input
                        )
                        recordingService.startAgentMessage(event.taskId, event.timestamp)
                    }
                    is AgentEvent.MessageDelta -> {
                        recordingService.appendTextDelta(event.delta)
                    }
                    is AgentEvent.ActionExecuted -> {
                        recordingService.recordAction(/* ... */)
                    }
                    is AgentEvent.TaskCompleted -> {
                        recordingService.completeAgentMessage()
                        recordingService.completeSession()
                    }
                    // ... etc
                }
                
                // Forward to UI (existing behavior)
                capsuleManager?.onEvent(event)
            }
        }
    }
}
```

### 3. MainActivity Integration

```kotlin
class MainActivity : ComponentActivity() {
    
    private var showSessionList by mutableStateOf(false)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // ... existing setup ...
        
        setContent {
            ChatTheme {
                ChatScreen(
                    viewModel = viewModel,
                    onOpenSettings = { showSettings = true },
                    onOpenSessionList = { showSessionList = true }  // NEW
                )
                
                if (showSessionList) {
                    SessionListSheet(
                        sessions = viewModel.sessions.collectAsState().value,
                        onSessionSelect = { session ->
                            viewModel.resumeSession(session)
                            showSessionList = false
                        },
                        onNewSession = {
                            viewModel.startNewSession()
                            showSessionList = false
                        },
                        onDeleteSession = { viewModel.deleteSession(it) },
                        onDismiss = { showSessionList = false }
                    )
                }
            }
        }
    }
}
```

---

## Implementation Plan

### Phase 1: Core Infrastructure

1. **Data Models** (`history/model/`)
   - `SessionRecord.kt` - Main session container
   - `MessageRecord.kt` - Persisted message types
   - `SessionInfo.kt` - Lightweight session summary

2. **Storage Layer** (`history/storage/`)
   - `SessionStorage.kt` - File I/O operations

### Phase 2: Recording Service

3. **Recording Service** (`history/`)
   - `SessionRecordingService.kt` - Real-time recording
   - Integration with AgentService event collection

### Phase 3: Session Management

4. **Session Manager** (`history/`)
   - `SessionHistoryManager.kt` - High-level API

### Phase 4: UI

5. **UI Components** (`ui/session/`)
   - `SessionListSheet.kt` - Bottom sheet with session list
   - `SessionListItem.kt` - Individual session row
   - Time formatting utilities

6. **Integration**
   - ChatViewModel extensions
   - MainActivity wiring
   - Entry point from ChatHeader (long-press or icon)

### File Structure

```
app/src/main/kotlin/com/moonkey/androidagent/
├── history/
│   ├── model/
│   │   ├── SessionRecord.kt
│   │   ├── MessageRecord.kt
│   │   └── SessionInfo.kt
│   ├── storage/
│   │   └── SessionStorage.kt
│   ├── SessionRecordingService.kt
│   └── SessionHistoryManager.kt
│
└── ui/
    └── session/
        ├── SessionListSheet.kt
        ├── SessionListItem.kt
        └── TimeUtils.kt
```

---

## Future Considerations

### Potential Enhancements (not in scope)

1. **Search** - Full-text search across sessions
2. **Export** - Export sessions as JSON/Markdown
3. **Summary Generation** - Use LLM to generate session summaries
4. **Session Tags** - User-defined tags for organization
5. **Auto-cleanup** - Delete sessions older than X days
6. **Compression** - Compress old sessions to save space

### Migration Path

If data model changes:
1. Add version field to SessionRecord
2. Create migration functions: `v1_to_v2()`
3. Apply migrations on load

---

## References

- [gemini-cli ChatRecordingService](../.reference/gemini-cli/packages/core/src/services/chatRecordingService.ts)
- [gemini-cli SessionUtils](../.reference/gemini-cli/packages/cli/src/utils/sessionUtils.ts)
- [codex resume_picker](../.reference/codex/codex-rs/tui/src/resume_picker.rs)
- [Current UI Stack](../main/ui_stack.md)
- [Current Agent Infrastructure](../main/agent_infra.md)
