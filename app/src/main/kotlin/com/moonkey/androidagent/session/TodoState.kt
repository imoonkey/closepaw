package com.moonkey.androidagent.session

import com.moonkey.androidagent.protocol.Todo
import com.moonkey.androidagent.protocol.TodoStatus

/**
 * Thread-safe holder for per-session todos.
 */
class TodoState(
    private val todos: MutableList<Todo> = mutableListOf()
) {
    private val lock = Any()
    @Volatile
    private var onMutation: (() -> Unit)? = null

    fun setMutationListener(listener: (() -> Unit)?) {
        onMutation = listener
    }

    fun update(newTodos: List<Todo>) {
        require(newTodos.count { it.status == TodoStatus.IN_PROGRESS } <= 1) {
            "Only one task can be IN_PROGRESS at a time"
        }
        synchronized(lock) {
            todos.clear()
            todos.addAll(newTodos)
        }
        onMutation?.invoke()
    }

    fun clear() {
        synchronized(lock) {
            todos.clear()
        }
        onMutation?.invoke()
    }

    fun get(): List<Todo> = synchronized(lock) { todos.toList() }

    fun toPromptContext(): String {
        val snapshot = get()
        if (snapshot.isEmpty()) return ""
        return snapshot.mapIndexed { index, todo ->
            val status = todo.status.name
            "${index + 1}. [$status] ${todo.description}"
        }.joinToString("\n")
    }
}
