# Planning State & Context Hygiene

> TodoState, ScratchpadState, and history compression strategies.
> Last updated: 2026-02-05 (commit: 4fa87d8484fddd0862e63fcc08a740646af9a77c)

## Planning State System

The agent uses planning-state tools to track progress and share data between planner and executor.

`PromptUtils` injects dynamic reminders based on planning/context state:
- Todo reminder only when actionable items exist
- Scratchpad reminder with key count + key preview
- Loop and turn-budget reminders from cognition policies

→ See: `agent/cognition/prompt/PromptUtils.kt`

---

## TodoState

→ See: `session/TodoState.kt`

Thread-safe todo list for tracking subgoals:

```kotlin
class TodoState {
    fun update(todos: List<Todo>)  // Replace entire list
    fun get(): List<Todo>          // Get current list
    fun clear()                    // Clear all todos
    fun toPromptContext(): String  // Markdown list for prompt context
}
```

**Constraints:**
- Only one todo can be `IN_PROGRESS`
- Full-list replacement model (no partial patch API)

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

To control token usage, the runtime keeps history text-first and injects fresh screen context every turn.

### Key Design Decisions

| Aspect | Approach |
|--------|----------|
| **History** | Messages + tool calls/outputs (no full raw screen JSON history growth) |
| **Screen State** | Fresh snapshot injected each turn via prompt context |
| **Observations** | Compact summary strings (`ScreenSnapshot.toSummary`) |
| **Compression** | `HistoryManager` auto-compresses near budget |

### Screen Summary

→ See: `perception/ScreenSummary.kt`

`ScreenSnapshot.toSummary(packageName)` generates a compact observation string, for example:

`com.google.android.gm | elements=42, clickable=18, editable=1, focused=Search mail, labels=Inbox, Promotions, Social`

### Data Flow

```
Turn N                                  Turn N+1
  │                                       │
  ├─ Perceive: capture screen            ├─ Perceive: capture screen
  │                                       │
  ├─ Think: LLM with                     ├─ Think: LLM with
  │   - History (text/tool outputs)      │   - History (text/tool outputs)
  │   - Latest screen (prompt JSON)      │   - Latest screen (prompt JSON)
  │   - Todos + scratchpad               │   - Todos + scratchpad
  │                                       │
  ├─ Act: execute tool                   ├─ Act: execute tool
  │                                       │
  └─ Observe: append compact summary     └─ Observe: append compact summary
      (not full raw tree)                    (not full raw tree)
```

---

## Why Context Hygiene Matters

| Problem | Solution |
|---------|----------|
| Token explosion from full a11y trees | Fresh-turn injection + compact summaries |
| Stale screen-state confusion | Re-capture screen every turn |
| History bloat | History compression near budget |
| Cross-turn data loss | Explicit persistence via scratchpad/todos |

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
