# Planning State & Context Hygiene

> TodoState, ScratchpadState, and history compression strategies.
> Last updated: 2026-02-04

## Planning State System

The agent uses **planning state tools** to track progress on complex tasks and share data between planner and executor.

`ContextPackager` also injects system reminders based on planning state:
- Todo reminder only when there are actionable items (not all completed/cancelled)
- Scratchpad reminder with key count + key preview
- Loop/turn-budget reminders from cognition policies

---

## TodoState

→ See: `session/TodoState.kt`

Thread-safe todo list for tracking subgoals:

```kotlin
class TodoState {
    fun update(todos: List<Todo>)  // Replace entire list
    fun get(): List<Todo>          // Get current list
    fun clear()                    // Clear all todos
}
```

**Constraints:**
- Only ONE todo can be `IN_PROGRESS` at a time
- Full list replacement (no incremental updates)

### Todo Model

```kotlin
data class Todo(
    val description: String,
    val status: TodoStatus  // PENDING, IN_PROGRESS, COMPLETED, CANCELLED
)
```

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
    
    companion object {
        const val MAX_ENTRIES = 20
        const val MAX_KEY_LENGTH = 100
        const val MAX_VALUE_LENGTH = 2000
    }
}
```

**Use cases:**
- Store extracted info from one screen to use in another
- Remember values across navigation
- Pass structured data from planner to executor

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

To optimize token usage and improve LLM performance, the agent uses text-only history with latest screen injection.

### Key Design Decisions

| Aspect | Approach |
|--------|----------|
| **History** | Text-only (no screenshots, no a11y trees) |
| **Screen State** | Latest screen injected per turn (not stored in history) |
| **Observations** | `ScreenSummary` text instead of raw JSON |

### ScreenSummary

→ See: `perception/ScreenSummary.kt`

Generates a concise text summary for history:

```kotlin
object ScreenSummary {
    fun generate(snapshot: ScreenSnapshot): String
    // Example: "Screen: com.google.gmail (MainActivity) - 42 elements"
}
```

### Data Flow

```
Turn N                                  Turn N+1
  │                                       │
  ├─ Perceive: capture screen            ├─ Perceive: capture screen
  │                                       │
  ├─ Think: LLM with                     ├─ Think: LLM with
  │   - History (text-only)              │   - History (text-only)
  │   - Latest screen (full JSON)        │   - Latest screen (full JSON)
  │   - Todos, scratchpad                │   - Todos, scratchpad
  │                                       │
  ├─ Act: execute tool                   ├─ Act: execute tool
  │                                       │
  └─ Observe: add text summary           └─ Observe: add text summary
      to history (NOT raw screen)            to history (NOT raw screen)
```

---

## Why Context Hygiene Matters

| Problem | Solution |
|---------|----------|
| Token explosion from a11y trees | Only inject latest; summarize in history |
| Stale screen state confusion | Fresh capture each turn |
| History bloat | Text summaries, not full observations |
| Cross-turn data loss | Scratchpad for explicit persistence |

---

## Planning Events

| Event | Description |
|-------|-------------|
| `TodosUpdated` | Emitted when todos change |
| `ScratchpadUpdated` | Emitted on write/delete |

→ See: [Protocol Events](../protocol/protocol.md#planning-state-events)

---

## Related Docs

- [Loop Execution](loop.md) - How planning state is injected
- [Multi-Agent](multiagent.md) - Scratchpad for cross-agent data
- [Tools](../infra/tools.md) - Tool implementations
