# Phase 0: Foundation Tools Design

> **Goal**: Implement `write_todos` and `scratchpad` tools before multi-agent infra

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

### Storage

```kotlin
class TodoState(
    private val todos: MutableList<Todo> = mutableListOf()
) {
    fun update(newTodos: List<Todo>) {
        // Validate: max 1 IN_PROGRESS
        require(newTodos.count { it.status == IN_PROGRESS } <= 1) {
            "Only one task can be IN_PROGRESS at a time"
        }
        todos.clear()
        todos.addAll(newTodos)
    }
    
    fun get(): List<Todo> = todos.toList()
    
    fun toPromptContext(): String = todos.mapIndexed { i, todo ->
        "${i + 1}. [${todo.status}] ${todo.description}"
    }.joinToString("\n")
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

### Storage

```kotlin
class ScratchpadState(
    private val data: MutableMap<String, String> = mutableMapOf()
) {
    fun write(key: String, value: String) {
        data[key] = value
    }
    
    fun read(key: String): String? = data[key]
    
    fun delete(key: String) {
        data.remove(key)
    }
    
    fun list(): List<String> = data.keys.toList()
    
    fun toPromptContext(): String {
        if (data.isEmpty()) return ""
        return data.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }
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

---

## Implementation Order

1. Add `Todo` and `TodoStatus` data classes
2. Add `TodoState` with validation
3. Implement `WriteTodosTool`
4. Add `ScratchpadState`
5. Implement `ScratchpadTool`
6. Add to `SessionServices`
7. Inject into system prompt
8. Add events and UI updates

**Estimated effort**: 1-2 days
