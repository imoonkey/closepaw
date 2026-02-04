package com.moonkey.androidagent.session

import com.moonkey.androidagent.protocol.Todo
import com.moonkey.androidagent.protocol.TodoStatus

class TodoState(
    private val todos: MutableList<Todo> = mutableListOf()
) {
    fun update(newTodos: List<Todo>) {
        require(newTodos.count { it.status == TodoStatus.IN_PROGRESS } <= 1) {
            "Only one task can be IN_PROGRESS at a time"
        }
        todos.clear()
        todos.addAll(newTodos)
    }

    fun get(): List<Todo> = todos.toList()

    fun toPromptContext(): String {
        if (todos.isEmpty()) return ""
        return todos.mapIndexed { index, todo ->
            val status = todo.status.name.lowercase()
            "${index + 1}. [$status] ${todo.description}"
        }.joinToString("\n")
    }
}
