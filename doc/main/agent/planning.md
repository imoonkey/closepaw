# Planning State & Context Hygiene

> TodoState, ScratchpadState, and context-compression strategies.
> Last updated: 2026-02-26 (commit: e2ce450)

## Planning State System

The agent uses planning-state tools to track progress and share data between planner and executor.

`PromptBuilder` injects planning context as a dedicated memory message:
- Includes todos when todo list is non-empty
- Includes scratchpad keys when scratchpad is non-empty
- Omits memory section entirely when both are empty

Runtime warning text (loop warning / final-turn warning) is injected in the observation section by `AgentTurnRunner.buildWarnings(...)`.

→ See: `agent/cognition/prompt/PromptBuilder.kt`, `agent/AgentTurnRunner.kt`

---

## TodoState

→ See: `session/TodoState.kt`

Thread-safe todo list for tracking subgoals:

```kotlin
class TodoState {
    fun update(todos: List<Todo>)  // Replace entire list (validated)
    fun get(): List<Todo>          // Thread-safe retrieval
    fun clear()                    // Clear all todos
    fun toPromptContext(): String  // Markdown list for prompt context
}
```

**Constraints:**
- Only one todo can be `IN_PROGRESS` at a time (validated on `update()`)
- Full-list replacement model (no partial patch API)
- Thread-safe via `synchronized(lock)`

### Todo Model

```kotlin
data class Todo(
    val description: String,
    val status: TodoStatus  // PENDING, IN_PROGRESS, COMPLETED, CANCELLED
)
```

→ See: `protocol/TodoModels.kt`

### write_todos Tool

→ See: `tool/impl/WriteTodosTool.kt`

```kotlin
write_todos(
    todos = [
        { description: "Open Gmail app", status: "COMPLETED" },
        { description: "Navigate to inbox", status: "IN_PROGRESS" },
        { description: "Find email from John", status: "PENDING" }
    ]
)
```

---

## ScratchpadState

→ See: `session/ScratchpadState.kt`

Thread-safe key-value store for intermediate data:

```kotlin
class ScratchpadState {
    fun write(key: String, value: String)
    fun read(key: String): String?
    fun delete(key: String): Boolean
    fun list(): List<String>
    fun clear()
    fun toPromptContext(): String

    companion object {
        const val MAX_ENTRIES = 20
        const val MAX_KEY_LENGTH = 100
        const val MAX_VALUE_LENGTH = 2048
    }
}
```

**Use cases:**
- Store extracted info from one screen for later turns
- Persist critical facts across app navigation
- Share structured handoff data between planner and executor

### scratchpad Tool

→ See: `tool/impl/ScratchpadTool.kt`

```kotlin
// Write
scratchpad(action = "write", key = "recipient_email", value = "john@example.com")

// Read
scratchpad(action = "read", key = "recipient_email")

// Delete
scratchpad(action = "delete", key = "recipient_email")

// List all keys
scratchpad(action = "list")
```

---

## Context Hygiene (History Compression)

To control token usage, `HistoryManager` proactively downgrades old screen observations and runs a multi-phase compression pipeline when token budget is approached.

### Key Design Decisions

| Aspect | Approach |
|--------|----------|
| **History** | User/assistant messages + function calls + function outputs, classified by `MessageKind` |
| **Current Screen** | Current turn always includes full screen JSON in observation section |
| **Screen History** | Each turn records screen JSON as `ResponseItem.Message(kind = SCREEN_OBSERVATION)` |
| **Screen Compression** | `HistoryManager` proactively downgrades old screens on every `addItem()` |
| **Token Budget** | `HistoryManager` auto-compresses at 85% of budget, down to 50% (KV cache efficient) |
| **Compression owner** | Single owner: `HistoryManager`. `PromptBuilder` is read-only pass-through. |

### Compression Pipeline

**Phase 0** — Normalize call/output pairs.
**Phase 1** — Downgrade old screen observations to one-liners (keeps last `recentFullScreens`).
**Phase 2** — Group-aware eviction (oldest first, outside `recentWindowSize` tail). Never evicts `USER_INTENT`.
**Phase 3** — Merge adjacent digests; return `BudgetUnreachable` if impossible.

→ Full details: [History Compression Pipeline](../app/history.md#compression-pipeline)

### Data Flow

```
Turn N                                  Turn N+1
  |                                       |
  |- Perceive: capture screen             |- Perceive: capture screen
  |                                       |
  |- Think: LLM with                      |- Think: LLM with
  |  - History (older screens auto-       |  - History (proactively compressed)
  |    downgraded by HistoryManager)      |
  |  - Working Memory (todos/scratchpad)  |  - Working Memory (todos/scratchpad)
  |  - Current observation JSON           |  - Current observation JSON
  |                                       |
  |- Act: execute tool                    |- Act: execute tool
  |                                       |
  '- Observe: record full screen JSON     '- Observe: record full screen JSON
     in history (kind=SCREEN_OBSERVATION)    in history (triggers downgrade of older screens)
```

---

## Why Context Hygiene Matters

| Problem | Solution |
|---------|----------|
| Token explosion from full a11y trees | Proactive screen downgrade on every new observation |
| Stale screen-state confusion | Re-capture every turn and place current observation at input tail |
| History bloat | Single-owner compression in `HistoryManager` with 4-phase pipeline |
| KV cache thrashing | Compress to 50% of budget (not 95%) for ~22 turns of stable prefix |
| Cross-turn data loss | Explicit persistence via scratchpad/todos |

---

## Planning Events

| Event | Description |
|-------|-------------|
| `TodosUpdated` | Emitted when todos change (carries `todos: List<Todo>`) |
| `ScratchpadUpdated` | Emitted on write/delete (carries `key` and `action`) |

→ See: [Protocol Events](../protocol/protocol.md#planning-state-events)

---

## Related Docs

- [Loop Execution](loop.md) - how planning state is injected
- [Turn Prompt Anatomy](turn_prompt_anatomy.md) - exact prompt/input composition
- [Multi-Agent](multiagent.md) - scratchpad for cross-agent data
- [Tools](../infra/tools.md) - tool implementations
