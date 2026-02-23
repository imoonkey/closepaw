# Session Reload: Aligned Design

status: draft

## 1. Design Decisions (Consensus)

1. **LLM context has its own persistence model** — independent from UI `MessageRecord`. No reverse-engineering from UI records.
2. **Single source of truth is the persisted history items list** — UI `SessionRecord.messages` is a display-only projection.
3. **Function call arguments persisted as canonical JSON string** — v1 keeps runtime-equivalent behavior (`JSONObject.toString()`), with optional follow-up to carry original raw string end-to-end.
4. **Session state split: Conversation State (persistable) vs Task Runtime (releasable)** — task completion releases heavy resources; follow-up reloads from disk.
5. **Config frozen on reload** — reloaded session uses the config snapshot from when it was created, preventing prompt drift.
6. **Agent/PromptBuilder transparent** — reload hydrates HistoryManager; downstream code sees no difference.

## 2. Persisted Data Model

```kotlin
@Serializable
data class SessionRuntimeSnapshot(
    val schemaVersion: Int = 1,
    val sessionId: String,
    val config: ConversationConfigSnapshot,
    val historyItems: List<PersistedHistoryItem>,  // ordered, append-only
    val todos: List<TodoSnapshot>,
    val scratchpad: Map<String, String>,
    val checkpointState: CheckpointState,          // IDLE_READY / RUNNING_DIRTY / CLOSED
    val lastCheckpointAt: Long
)

@Serializable
sealed interface PersistedHistoryItem {
    @Serializable data class Message(
        val role: String,
        val content: String,
        val name: String? = null,
        val isScreenObservation: Boolean = false
    ) : PersistedHistoryItem

    @Serializable data class FunctionCall(
        val id: String,
        val name: String,
        val argumentsRawJson: String   // v1 canonical JSON string (from JSONObject.toString())
    ) : PersistedHistoryItem

    @Serializable data class FunctionCallOutput(
        val callId: String,
        val content: String,
        val success: Boolean = true,
        val truncated: Boolean = false
    ) : PersistedHistoryItem
}

@Serializable
data class ConversationConfigSnapshot(
    val mainModel: String,
    val executorModel: String? = null,
    val agentMode: String,
    val maxTurns: Int,
    val perceptionMode: String,
    val platformMode: String
)

@Serializable
data class TodoSnapshot(
    val description: String,
    val status: String
)

@Serializable
enum class CheckpointState {
    IDLE_READY,      // task completed, safe to reload
    RUNNING_DIRTY,   // task in progress, snapshot may be incomplete
    CLOSED           // session explicitly ended (Op.Shutdown)
}
```

## 3. Storage

### RESOLVED: Sibling file (Option A)

```
/data/data/{pkg}/files/sessions/
├── session-{ts}-{uuid}.json   # SessionRecord (UI messages)
└── context-{ts}-{uuid}.json   # SessionRuntimeSnapshot (LLM context)
```
- Why: smallest migration, keeps current `SessionRecord` readers untouched, and decouples UI history persistence from runtime checkpoint cadence.
- Cons accepted: cross-file sync/orphan risk.
- Mitigation:
  - context snapshot is the only source used for reload;
  - delete operation removes both `session-*` and `context-*`;
  - pair files by shared suffix (`{ts}-{uuid}`).

## 4. Converter: PersistedHistoryItem ↔ ResponseItem

```kotlin
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
}
```

### RESOLVED: v1 uses runtime-equivalent canonicalization (Option A)

Current runtime already does:
`LLM arguments string -> JSONObject (Turn) -> arguments.toString() (PromptBuilder)`.

v1 checkpoint/reload keeps the same semantics:
- persist `arguments.toString()`;
- reload with `JSONObject(argumentsRawJson)`;
- build prompt with `arguments.toString()`.

Rationale: no additional behavior drift versus today's hot path; minimal scope.

Follow-up (non-blocking): introduce `argumentsRawJson` on `ResponseItem.FunctionCall` and prefer it in prompt/persistence to fully remove dependency on JSONObject serialization behavior.

## 5. Write Strategy

| Trigger | Flush | Debounced? |
|---------|-------|------------|
| HistoryManager mutation (addItem/recordItems/compress) | Snapshot | Yes (500ms) |
| TodoState / ScratchpadState change | Snapshot | Yes (500ms) |
| TaskCompleted | Force flush, mark `IDLE_READY` | No |
| Op.Shutdown | Force flush, mark `CLOSED` | No |

- Atomic write: temp file + rename (prevents crash producing half-written files).
- Reuse existing `SessionRecordingService` debounce mechanism.

## 6. Reload Path

### Trigger
- User sends input, no active `AgentSession` in memory
- Load most recent `SessionRuntimeSnapshot` with `checkpointState == IDLE_READY`

### Steps
1. Read `SessionRuntimeSnapshot` from disk
2. Validate `schemaVersion` and `checkpointState == IDLE_READY`
3. Create fresh `SessionServices` using `config` from snapshot (frozen config)
4. Hydrate `HistoryManager` with `historyItems` (in order)
5. Restore `TodoState` + `ScratchpadState`
6. Restore `SessionRecordingService` (resume UI recording from existing `SessionRecord`)
7. Return session in `Created` state; first `UserInput` triggers `platform.start()`

### Failure handling
- Snapshot missing or corrupt → treat session as view-only (no reload), start fresh session
- `schemaVersion` unsupported → fail-fast, start fresh
- Flush failure → do NOT release runtime (avoid "thinks it's recoverable but isn't")

## 7. Lifecycle State Machine

```
Created ──(UserInput)──► Running ──(TaskCompleted)──► Completed
                              │                           │
                              ├──(Takeover)──► Paused     │ persist + release runtime
                              │                           │
                              └──(Op.Shutdown)──► Shutdown
                                                    │
                                                    ▼
                                              [on disk, IDLE_READY]
                                                          │
                                              ┌───────────┼───────────┐
                                              │           │           │
                                        follow-up    app killed   Op.Shutdown
                                              │           │           │
                                         reload      stays on     mark CLOSED
                                              │       disk
                                              ▼
                                     Created (hydrated) ──► Running
```

State decision:
- Reuse existing `SessionState.Completed` and `SessionState.Shutdown` (already defined in code).
- Do not introduce `Checkpointed` / `Closed` protocol enums in v1.

## 8. 100% Reproduction Invariants

1. **Order preserved**: `historyItems` list order matches original runtime order
2. **Item boundaries preserved**: message/function_call/function_call_output boundaries not merged or split
3. **Arguments canonical text preserved**: function_call arguments stored/replayed with the same v1 canonicalization path
4. **Output content preserved**: function_call_output content verbatim (including truncation markers)
5. **Config frozen**: reloaded session uses snapshot config, not current app settings
6. **Single PromptBuilder path**: reload and hot session use identical prompt construction

Debug assertion (optional): hash of serialized `llm_input_items` per turn, logged for comparison.

## 9. Files to Create/Modify

### New files
| File | Purpose |
|------|---------|
| `history/model/SessionRuntimeSnapshot.kt` | Data classes: `SessionRuntimeSnapshot`, `PersistedHistoryItem`, `TodoSnapshot`, `ConversationConfigSnapshot`, `CheckpointState` |
| `history/model/HistoryItemConverter.kt` | Bidirectional `ResponseItem` ↔ `PersistedHistoryItem` |

### Modified files
| File | Change |
|------|--------|
| `history/SessionRecordingService.kt` | Add checkpoint save/load alongside UI recording |
| `history/storage/SessionStorage.kt` | Add snapshot read/write (+ atomic write) |
| `session/AgentSession.kt` | Add `reload()` factory; `TaskCompleted` path checkpoints and transitions to `Completed` |
| `app/MainActivity.kt` | Reload path when no active session |
| `trace/HistoryTraceSerializer.kt` | Add `isScreenObservation` to serialization |

### Unchanged
- `HistoryManager.kt` — hydrated via existing `addItem()`
- `Agent.kt` — transparent
- `PromptBuilder.kt` — transparent
- `MessageRecord.kt` / `MessageConverter.kt` — UI layer unchanged

## 10. Verification

- **Unit**: `PersistedHistoryItem` ↔ `ResponseItem` round-trip (all types, edge cases)
- **Unit**: `SessionRuntimeSnapshot` JSON round-trip (serialize → deserialize → equal)
- **Unit**: HistoryManager hydration produces identical `forPrompt()` output
- **Integration**: full task → persist → reload → follow-up; compare LLM input prefix
- **E2E**: `debug-run.sh` → kill process → reopen → follow-up works

## 11. Implementation Stages

### Stage A: Data model + persistence
- Define `SessionRuntimeSnapshot` and `PersistedHistoryItem`
- Implement converter and atomic file I/O
- Unit tests for round-trip fidelity

### Stage B: Runtime write + reload coordinator
- Hook checkpoint writes into mutation points (debounced + flush)
- Implement reload path (load → validate → hydrate)
- Failure/degradation paths

### Stage C: Lifecycle cleanup
- Split Conversation State / Task Runtime
- Release resources on `TaskCompleted`, rebuild on follow-up
- Full regression (process death, service restart, config change)
