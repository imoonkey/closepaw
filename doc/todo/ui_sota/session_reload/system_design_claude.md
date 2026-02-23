status: draft

# Session Reload: LLM Context Persistence & Lifecycle Redesign

## 1. Problem

The current session lifecycle keeps everything in memory between tasks. When the session object dies — app killed, service shutdown, `debug-run.sh` `stop_agent`, or Android process death — follow-up is impossible. There is no path to reconstruct the LLM conversation context from what's persisted on disk.

### Why the current persistence layer can't reload

`SessionRecord` stores `List<MessageRecord>` for **UI display**, not for LLM context:

| What LLM needs (ResponseItem) | What's persisted (MessageRecord) | Gap |
|---|---|---|
| `FunctionCall(id, name, arguments: JSONObject)` | `Action(toolName, description, state)` | Arguments lost entirely |
| `FunctionCallOutput(callId, content, success)` | `Action.resultSummary` (truncated) | Full output lost |
| `Message(isScreenObservation=true, content=fullJson)` | Not stored (file path refs only) | Screen content lost |
| `Message(role="assistant", content=thought)` | `ContentBlock.Text(text)` | Role/metadata lost |

These are fundamentally different representations. `MessageRecord` was designed for chat UI rendering, not LLM prompt reconstruction. Conversion between them is lossy in both directions.

### Consequences

1. **No follow-up after session death**: If the session object is garbage collected, all LLM context is gone. The user must start a new session from scratch.
2. **Resources kept alive unnecessarily**: Platform (virtual display), LLM connections, and tool router stay alive between tasks just to preserve HistoryManager state.
3. **LLM cache invalidation on imperfect reconstruction**: Even approximate reconstruction (e.g., from MessageRecord) would produce a different token sequence, invalidating the LLM provider's KV cache. The follow-up turn would be a full cache miss, not an append.

---

## 2. Current Architecture (Source of Truth: Code)

### 2.1 LLM Context: HistoryManager

```
HistoryManager
├── items: MutableList<ResponseItem>     // THE canonical LLM context
├── config: HistoryConfig                // token budget, compression policy
└── lastTokenEstimate: Long?             // cached, invalidated on mutation
```

`ResponseItem` sealed class (`ResponseItem.kt`):
```kotlin
sealed class ResponseItem {
    data class Message(
        val role: String,                    // "user" | "assistant"
        val content: String,
        val name: String? = null,
        val isScreenObservation: Boolean = false
    )
    data class FunctionCall(
        val id: String,
        val name: String,
        val arguments: JSONObject            // ← not serializable by default
    )
    data class FunctionCallOutput(
        val callId: String,
        val content: String,
        val success: Boolean = true,
        val truncated: Boolean = false
    )
}
```

**Mutation points** (places that modify the items list):
- `Agent.run()`: adds `Message(role="user", content="Goal: ...")` at task start
- `AgentSession.handleSupplement()`: adds `Message(role="user", content=supplementText)`
- `TurnPlanningPhaseRunner`: records LLM response as `Message(role="assistant", ...)`
- `TurnExecutionPhaseRunner`: records `FunctionCall` + `FunctionCallOutput` per tool
- `HistoryManager.compress()`: removes/truncates old items (lossy, irreversible)
- `HistoryManager.clear()`: called only in `SessionServices.cleanup()`

### 2.2 Cross-Task State: AgentSessionState

```kotlin
class AgentSessionState {
    val todos: TodoState           // MutableList<Todo>, @Synchronized
    val scratchpad: ScratchpadState // MutableMap<String, String>, @Synchronized
}
```

Not persisted. Lost on session death.

### 2.3 Session Persistence: SessionRecordingService

Records `SessionRecord` (UI messages) to disk via `SessionStorage`. Debounced 500ms saves. File format: JSON in `/data/data/{pkg}/files/sessions/`.

Already has:
- `recordUserMessage()` → finalizes agent buffer, appends user message
- `startAgentMessage()` → finalizes previous, starts new buffer
- `recordAction()` / `updateActionState()` → tool cards for UI
- `completeSession()` → force save

### 2.4 Trace System: HistoryTraceSerializer

The trace system already serializes `List<ResponseItem>` to JSON:

```json
[
  {"type": "message", "role": "user", "content": "Goal: open youtube"},
  {"type": "message", "role": "assistant", "content": "I'll open YouTube..."},
  {"type": "function_call", "id": "call_1", "name": "open_app", "arguments_json": "{\"app_name\":\"YouTube\"}"},
  {"type": "function_call_output", "call_id": "call_1", "success": true, "truncated": false, "content": "Launched YouTube"}
]
```

This format works but has a gap: **`isScreenObservation` is not serialized** (line 17-22 of `HistoryTraceSerializer.kt` omits it). Needs to be added.

### 2.5 Session Lifecycle State Machine

```
Created ──(UserInput)──► Running ──(TaskCompleted)──► Idle ──(UserInput)──► Running
                              │                                               │
                              └──(Takeover)──► Paused ──(Resume)──────────────┘

Any ──(Op.Shutdown)──► Shutdown (terminal, services.cleanup())
```

In `Idle`, ALL resources remain alive (platform, LLM client, history) waiting for follow-up. This is the root design issue.

---

## 3. Design Goals

1. **Persist LLM context to disk** — `List<ResponseItem>` survives process death
2. **100% cache-compatible reload** — reloaded context produces identical LLM input tokens as if session never died
3. **Clean lifecycle** — release resources after task completion, don't keep them alive for hypothetical follow-up
4. **KISS** — reuse existing serialization format (HistoryTraceSerializer), minimize new abstractions

---

## 4. Design

### 4.1 New Data Model: SessionContextRecord

```kotlin
@Serializable
data class SessionContextRecord(
    val sessionId: String,
    val historyItems: List<ResponseItemRecord>,
    val todos: List<TodoRecord>,
    val scratchpad: Map<String, String>,
    val configSnapshot: SessionConfigSnapshot,
    val lastUpdated: Long
)

@Serializable
sealed class ResponseItemRecord {
    @Serializable @SerialName("message")
    data class Message(
        val role: String,
        val content: String,
        val name: String? = null,
        val isScreenObservation: Boolean = false
    ) : ResponseItemRecord()

    @Serializable @SerialName("function_call")
    data class FunctionCall(
        val id: String,
        val name: String,
        val argumentsJson: String          // JSONObject.toString()
    ) : ResponseItemRecord()

    @Serializable @SerialName("function_call_output")
    data class FunctionCallOutput(
        val callId: String,
        val content: String,
        val success: Boolean = true,
        val truncated: Boolean = false
    ) : ResponseItemRecord()
}

@Serializable
data class TodoRecord(
    val description: String,
    val status: String                     // "pending" | "in_progress" | "completed" | "cancelled"
)

@Serializable
data class SessionConfigSnapshot(
    val mainModel: String,
    val executorModel: String? = null,
    val agentMode: String,                 // "basic" | "pro"
    val maxTurns: Int,
    val perceptionMode: String,
    val platformMode: String
)
```

### 4.2 Bidirectional Converter: ResponseItemRecord ↔ ResponseItem

```kotlin
object ResponseItemConverter {
    fun toRecord(item: ResponseItem): ResponseItemRecord = when (item) {
        is ResponseItem.Message -> ResponseItemRecord.Message(
            role = item.role,
            content = item.content,
            name = item.name,
            isScreenObservation = item.isScreenObservation
        )
        is ResponseItem.FunctionCall -> ResponseItemRecord.FunctionCall(
            id = item.id,
            name = item.name,
            argumentsJson = item.arguments.toString()
        )
        is ResponseItem.FunctionCallOutput -> ResponseItemRecord.FunctionCallOutput(
            callId = item.callId,
            content = item.content,
            success = item.success,
            truncated = item.truncated
        )
    }

    fun fromRecord(record: ResponseItemRecord): ResponseItem = when (record) {
        is ResponseItemRecord.Message -> ResponseItem.Message(
            role = record.role,
            content = record.content,
            name = record.name,
            isScreenObservation = record.isScreenObservation
        )
        is ResponseItemRecord.FunctionCall -> ResponseItem.FunctionCall(
            id = record.id,
            name = record.name,
            arguments = JSONObject(record.argumentsJson)
        )
        is ResponseItemRecord.FunctionCallOutput -> ResponseItem.FunctionCallOutput(
            callId = record.callId,
            content = record.content,
            success = record.success,
            truncated = record.truncated
        )
    }
}
```

**Cache compatibility guarantee**: Every field of `ResponseItem` is preserved. `JSONObject` round-trips through `toString()` → `JSONObject(string)`. The `isScreenObservation` flag (missing from the current `HistoryTraceSerializer`) is explicitly included. On reload, `HistoryManager.forPrompt()` → `PromptBuilder.buildHistorySection()` produces byte-identical LLM input.

### 4.3 Storage: Sibling File

Stored alongside `SessionRecord` in the same directory:

```
/data/data/{pkg}/files/sessions/
├── session-2025-02-22T15-19-22-{uuid}.json          # SessionRecord (UI messages)
└── context-2025-02-22T15-19-22-{uuid}.json          # SessionContextRecord (LLM context)
```

Naming convention: replace `session-` prefix with `context-` prefix, same timestamp and uuid. `SessionStorage` extended with `writeContext()` / `readContext()` methods. Same JSON format (kotlinx.serialization).

### 4.4 Persistence Trigger Points

| Event | Action | Debounced? |
|-------|--------|------------|
| Turn completed | Persist context | Yes (500ms, same as recording) |
| Supplement received | Persist context | Yes |
| TaskCompleted | Force immediate persist | No |
| Op.Shutdown | Force immediate persist, then cleanup | No |

`SessionRecordingService` extended with a parallel `saveContext()` path that snapshots `HistoryManager.getAll()` and `AgentSessionState`. The existing save debounce mechanism is reused.

### 4.5 Lifecycle Redesign

#### Current lifecycle (problematic):
```
Created ──► Running ──► Idle (resources alive, waiting) ──► Running ──► ... ──► Shutdown
```

#### New lifecycle:
```
                    ┌──────────────────── reload from disk ────────────────────┐
                    ▼                                                          │
Created ──► Running ──► TaskDone ──(persist + cleanup)──► [on disk only] ──────┘
                              │                                (session not in memory)
                              └──(Op.Shutdown)──► [deleted from disk]
```

**State changes:**

1. **TaskCompleted handler** (`AgentSession.handleAgentComplete`):
   - Emit `TaskCompleted` event (existing)
   - Force persist `SessionContextRecord` to disk (new)
   - Call `services.cleanup()` — release platform, LLM, tools (moved from `handleShutdown`)
   - Set state to new terminal state `Completed` (replaces `Idle`)
   - `AgentService.session` set to null (session no longer in memory)

2. **Follow-up input** (`MainActivity.ensureSessionAndSend`):
   - `currentSession` is null → check for persisted context on disk
   - If found: call `AgentSession.reload(sessionId, ...)` → creates fresh services, hydrates history
   - New session enters `Running` directly (skips `Created` initialization)
   - `AgentService` observes the reloaded session

3. **Explicit shutdown** (`Op.Shutdown`):
   - Existing behavior: cleanup + emit `SessionCompleted`
   - New: also delete `SessionContextRecord` from disk (session is truly over)

#### New state enum:
```kotlin
sealed class SessionState {
    object Created : SessionState()
    object Running : SessionState()
    object Paused : SessionState()
    object Completed : SessionState()    // NEW: task done, context persisted, resources released
    object Shutdown : SessionState()     // Terminal: session explicitly ended
}
```

`Completed` replaces `Idle`. Unlike `Idle`, `Completed` does not accept `Op.UserInput`. Follow-up goes through the reload path instead.

### 4.6 Session Reload Factory

```kotlin
// In AgentSession companion object
suspend fun reload(
    sessionId: String,
    context: Context,
    scope: CoroutineScope,
    config: SessionConfig,
    apiKeys: Map<String, String>,
    visualizer: ActionVisualizerManager?,
    overlayTouchGate: OverlayTouchGate?
): AgentSession {
    // 1. Load persisted context
    val storage = SessionStorage(context)
    val contextRecord = storage.readContext(sessionId)
        ?: throw IllegalStateException("No persisted context for session $sessionId")

    // 2. Create fresh services (new platform, LLM client, tools)
    val services = SessionServices.create(config, platform, apiKeys, context, scope, traceRecorder)

    // 3. Hydrate HistoryManager with persisted items
    contextRecord.historyItems.forEach { record ->
        services.historyManager.addItem(ResponseItemConverter.fromRecord(record))
    }

    // 4. Restore TodoState + ScratchpadState
    contextRecord.todos.forEach { todo ->
        services.sessionState.todos.restore(todo)
    }
    contextRecord.scratchpad.forEach { (key, value) ->
        services.sessionState.scratchpad.write(key, value)
    }

    // 5. Resume recording service with existing session data
    val sessionRecord = storage.readSession(sessionId)
    if (sessionRecord != null) {
        services.recordingService.resumeSession(ResumedSessionData(sessionRecord, fileName))
    }

    // 6. Return session in Created state (platform.start() will run on first UserInput)
    return AgentSession(
        sessionId = SessionId(sessionId),
        config = config,
        services = services,
        scope = scope,
        initialState = SessionState.Created
    )
}
```

Note: The reloaded session enters `Created` state. When the first `UserInput` arrives, `handleUserInput` sees `Created` → calls `platform.start()` and emits `SessionStarted`. The HistoryManager already contains the prior conversation, so the new Agent sees full context.

### 4.7 Integration: MainActivity

```kotlin
// In ensureSessionAndSend()
if (currentSession == null) {
    // Check for persisted session to reload
    val recentSessionId = findRecentPersistedSession()  // from SessionHistoryManager
    if (recentSessionId != null) {
        // Reload path
        val reloadedSession = AgentSession.reload(recentSessionId, ...)
        currentSession = reloadedSession
        service.observeExternalSession(reloadedSession)
        viewModel.restoreMessagesFromRecords(...)
        viewModel.startEventCollection(reloadedSession)
        reloadedSession.submit(Op.UserInput(text = goal))
    } else {
        // Fresh session path (existing)
        createNewSession(goal)
    }
}
```

### 4.8 Context Record Lifecycle

```
┌─────────────────────────────────────────────────────────────────┐
│                    Context Record Lifecycle                      │
│                                                                  │
│  Task Start ──► [turn saves, debounced] ──► TaskCompleted       │
│                                               │                  │
│                                    force save + cleanup          │
│                                               │                  │
│                                        [on disk only]            │
│                                               │                  │
│                            ┌──────────────────┼──────────────┐   │
│                            │                  │              │   │
│                      User follow-up     App killed    Op.Shutdown│
│                            │                  │              │   │
│                    reload + resume     stays on disk    delete   │
│                            │                                     │
│                     new task starts                               │
└──────────────────────────────────────────────────────────────────┘
```

---

## 5. Cache Compatibility Analysis

The LLM prompt is constructed by `PromptBuilder.buildInputItems()`:

```
[History section] + [Memory section] + [Observation section]
```

- **History section**: `HistoryManager.forPrompt()` → `ResponseItem.toResponseInputItem()`. On reload, `forPrompt()` returns the same items (from hydrated HistoryManager). `toResponseInputItem()` is deterministic. Byte-identical output.

- **Memory section**: Built from `TodoState` + `ScratchpadState`. On reload, both are restored from `SessionContextRecord`. `toPromptContext()` is deterministic. Identical output.

- **Observation section**: Current screen capture. Not persisted or reloaded — captured fresh each turn. This is correct: the agent should see the actual current screen on follow-up, not a stale snapshot.

**Result**: The first LLM call after reload produces the same prefix tokens as if the session never died. The LLM provider's KV cache (if present) can serve the prefix from cache and only compute the new turn's tokens incrementally.

### Edge case: `isScreenObservation` and compression

`PromptBuilder.compressOldScreenObservations()` replaces old screen observations with one-line summaries, keeping the last 3 full. This operates on the output of `forPrompt()`, not on the persisted items. Since we persist the exact `items` list (with `isScreenObservation` flags intact), the compression produces identical results: same items are marked as screen observations, same last-3 window applies.

---

## 6. Files to Create/Modify

### New files:
| File | Purpose |
|------|---------|
| `history/model/SessionContextRecord.kt` | `SessionContextRecord`, `ResponseItemRecord`, `TodoRecord`, `SessionConfigSnapshot` data classes |
| `history/model/ResponseItemConverter.kt` | Bidirectional `ResponseItem` ↔ `ResponseItemRecord` converter |

### Modified files:
| File | Change |
|------|--------|
| `history/SessionRecordingService.kt` | Add `saveContext()` / `loadContext()` methods |
| `history/storage/SessionStorage.kt` | Add `writeContext()` / `readContext()` file operations |
| `session/AgentSession.kt` | Add `reload()` factory, change `Idle` → `Completed` lifecycle |
| `protocol/SessionState.kt` | Add `Completed` state |
| `app/MainActivity.kt` | Reload path in `ensureSessionAndSend()` |
| `trace/HistoryTraceSerializer.kt` | Add `isScreenObservation` to serialization (align with new format) |

### Files NOT modified:
- `HistoryManager.kt` — no changes needed (persisted items fed via existing `addItem()`)
- `Agent.kt` — no changes (sees hydrated HistoryManager transparently)
- `PromptBuilder.kt` — no changes (reads from HistoryManager as before)
- `MessageRecord.kt` / `MessageConverter.kt` — UI persistence layer unchanged

---

## 7. Verification Plan

1. **Unit test: ResponseItemConverter round-trip** — create ResponseItems with all types, convert to/from records, assert equality. Include edge cases: empty content, unicode content, large JSONObject arguments, truncated=true outputs.

2. **Unit test: SessionContextRecord serialization** — serialize to JSON, deserialize, assert round-trip equality. Verify `isScreenObservation` flag survives.

3. **Unit test: HistoryManager hydration** — persist a HistoryManager's items, clear it, reload from persisted records, assert `forPrompt()` returns identical items.

4. **Integration test: reload produces cache-compatible prompt** — run a task with known turns, persist context, reload into fresh session, call `PromptBuilder.buildInputItems()` on a new turn, compare the history prefix with the original session's last turn.

5. **Manual test**: Run `debug-run.sh`, wait for task completion, verify context file exists on disk. Send follow-up from chat UI, verify agent sees prior conversation history and responds coherently (not starting from scratch).

---

## 8. Open Questions for Review

1. **Eager vs. lazy cleanup**: Should `services.cleanup()` happen immediately after `TaskCompleted`, or after a configurable delay (e.g., 30s)? Eager cleanup is simpler but adds latency to follow-up (recreating services takes ~1-2s). Lazy cleanup keeps resources alive briefly for the common "quick follow-up" pattern.

2. **Context record retention policy**: How long should context files remain on disk? Options:
   - Keep indefinitely (until user deletes session from history UI)
   - Keep last N sessions (e.g., 10)
   - TTL (e.g., 7 days)

3. **Which session to reload on follow-up**: When the user types in the chat with no active session, should we always reload the most recent session, or show a picker? The most recent session is almost always correct for the follow-up use case.

4. **Trace alignment**: Should we deprecate the separate `HistoryTraceSerializer` in favor of `ResponseItemConverter`? They serve different purposes (trace uses JsonElement, persistence uses kotlinx.serialization @Serializable), but maintaining both increases surface area.
