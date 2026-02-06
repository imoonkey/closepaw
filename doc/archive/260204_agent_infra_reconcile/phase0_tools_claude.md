# Phase 0: Foundation Tools Design (Implemented)

> **Goal**: Implement `write_todos` and `scratchpad` tools before multi-agent infra
> **Status**: ✅ Implemented + hardened (thread safety, size limits, tests)

---

## Claude vs Codex: API Design Choice

| Aspect | Claude (Gemini-style) | Codex |
|--------|----------------------|-------|
| **TODO API** | Full-list replacement | Operations (add, update_status, list) |
| **Scratchpad** | read/write/delete/list | put/get/list_keys/clear |

**Reasoning**: I prefer Gemini's full-list-replacement for `write_todos` because:
1. **Atomicity**: LLM sends entire state, no partial update bugs
2. **Simpler validation**: Easy to enforce "max 1 IN_PROGRESS"
3. **Less tool calls**: One call updates everything vs N calls for N changes
4. **Proven**: Gemini CLI uses this in production

**Codex's operations approach** makes sense if:
- Lists are very long (>20 items) where full list is costly
- Incremental persistence is needed

For mobile-use tasks, lists are typically 3-10 items, so full-list is fine.

---

## 1. WriteTodosTool

Track subtasks/subgoals as a tool (inspired by Gemini CLI).

### Schema

```kotlin
data class Todo(
    val description: String,
    val status: TodoStatus
)

enum class TodoStatus {
    PENDING,      // Not started
    IN_PROGRESS,  // Currently working (max 1)
    COMPLETED,    // Done
    CANCELLED     // No longer needed
}

data class WriteTodosParams(
    val todos: List<Todo>  // Full replacement, not incremental
)
```

### Tool Definition

```kotlin
object WriteTodosTool : BaseTool(
    name = "write_todos",
    description = """
        Manage a todo list for tracking progress on complex tasks.
        
        Use this when:
        - Task requires multiple steps
        - You need to track progress
        
        Do NOT use for:
        - Simple single-step tasks
        - Q&A queries
        
        Statuses:
        - pending: Not started
        - in_progress: Currently working (only ONE at a time)
        - completed: Successfully done
        - cancelled: No longer needed
        
        Always pass the FULL list. This replaces the previous list.
    """.trimIndent(),
    arguments = listOf(
        Argument("todos", "array", required = true, 
            description = "Full list of todo items")
    )
)
```

### Storage (Implemented)

```kotlin
class TodoState(
    private val todos: MutableList<Todo> = mutableListOf()
) {
    // Thread-safe access
    private val lock = Any()

    fun update(newTodos: List<Todo>) {
        // Validate: max 1 IN_PROGRESS
        require(newTodos.count { it.status == IN_PROGRESS } <= 1) {
            "Only one task can be IN_PROGRESS at a time"
        }
        synchronized(lock) {
            todos.clear()
            todos.addAll(newTodos)
        }
    }
    
    fun clear() {
        synchronized(lock) { todos.clear() }
    }
    
    fun get(): List<Todo> = synchronized(lock) { todos.toList() }
    
    fun toPromptContext(): String {
        val snapshot = get()
        return snapshot.mapIndexed { i, todo ->
            "${i + 1}. [${todo.status}] ${todo.description}"
        }.joinToString("\n")
    }
}
```

### System Prompt Integration

```kotlin
// In system prompt builder, include current todos:
"""
## Current Todo List
${todoState.toPromptContext().ifEmpty { "(none)" }}
"""
```

---

## 2. ScratchpadTool

Key-value store for cross-step data (contacts to add, items extracted, etc.).

### Schema

```kotlin
sealed class ScratchpadAction {
    data class Write(val key: String, val value: String) : ScratchpadAction()
    data class Read(val key: String) : ScratchpadAction()
    data class Delete(val key: String) : ScratchpadAction()
    object List : ScratchpadAction()
}
```

### Tool Definition

```kotlin
object ScratchpadTool : BaseTool(
    name = "scratchpad",
    description = """
        Store and retrieve key-value data for multi-step tasks.
        
        Use cases:
        - Store extracted info (e.g., contact list from one screen to use in another)
        - Remember values across navigation
        - Track intermediate results
        
        Actions:
        - write: Store key=value
        - read: Get value for key
        - delete: Remove key
        - list: Show all keys
    """.trimIndent(),
    arguments = listOf(
        Argument("action", "string", required = true,
            enum = listOf("write", "read", "delete", "list")),
        Argument("key", "string", required = false),
        Argument("value", "string", required = false)
    )
)
```

### Storage (Implemented + Limits)

```kotlin
class ScratchpadState(
    private val data: MutableMap<String, String> = mutableMapOf()
) {
    companion object {
        const val MAX_ENTRIES = 20
        const val MAX_KEY_LENGTH = 100
        const val MAX_VALUE_LENGTH = 2048
    }

    private val lock = Any()

    fun write(key: String, value: String) {
        require(key.length <= MAX_KEY_LENGTH)
        require(value.length <= MAX_VALUE_LENGTH)
        synchronized(lock) {
            if (!data.containsKey(key) && data.size >= MAX_ENTRIES) {
                error("Scratchpad is full")
            }
            data[key] = value
        }
    }
    
    fun read(key: String): String? = synchronized(lock) { data[key] }
    
    fun delete(key: String): Boolean = synchronized(lock) { data.remove(key) != null }
    
    fun list(): List<String> = synchronized(lock) { data.keys.toList() }
    
    fun toPromptContext(): String {
        val snapshot = synchronized(lock) { data.toMap() }
        if (snapshot.isEmpty()) return ""
        return snapshot.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }
    }
}
```

---

## 3. Integration with Agent

### State Holder

```kotlin
// Add to SessionServices or AgentSession
class AgentSessionState(
    val todos: TodoState = TodoState(),
    val scratchpad: ScratchpadState = ScratchpadState()
)
```

### Tools Registration

```kotlin
// In ToolRegistry setup
registry.register(WriteTodosTool(sessionState.todos))
registry.register(ScratchpadTool(sessionState.scratchpad))
```

### System Prompt Additions

```kotlin
// Append to system prompt when state is non-empty:
fun buildStateContext(): String = buildString {
    val todosContext = sessionState.todos.toPromptContext()
    if (todosContext.isNotEmpty()) {
        appendLine("## Current Todos")
        appendLine(todosContext)
        appendLine()
    }
    
    val scratchpadContext = sessionState.scratchpad.toPromptContext()
    if (scratchpadContext.isNotEmpty()) {
        appendLine("## Scratchpad")
        appendLine(scratchpadContext)
    }
}
```

---

## 4. Event Integration

```kotlin
// New events for UI
data class TodosUpdated(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val todos: List<Todo>
) : AgentEvent

data class ScratchpadUpdated(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val key: String,
    val action: String  // "write" | "delete"
) : AgentEvent
```

**Note**: Events are emitted by `AgentTurnRunner` after tool success (not by tools).

---

## Implementation Order

✅ 1. Add `Todo` and `TodoStatus` data classes  
✅ 2. Add `TodoState` with validation + thread safety + `clear()`  
✅ 3. Implement `WriteTodosTool`  
✅ 4. Add `ScratchpadState` + limits  
✅ 5. Implement `ScratchpadTool`  
✅ 6. Add to `SessionServices`  
✅ 7. Inject into system prompt  
✅ 8. Add events and UI updates

### Implemented Files (for reference)
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/WriteTodosTool.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/ScratchpadTool.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/session/TodoState.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/session/ScratchpadState.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSessionState.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/protocol/TodoModels.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentPromptBuilder.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/protocol/AgentEvent.kt`

**Estimated effort**: 1-2 days
