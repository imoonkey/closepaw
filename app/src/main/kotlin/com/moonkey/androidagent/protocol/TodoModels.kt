package com.moonkey.androidagent.protocol

/**
 * Todo - A simple task item for agent planning.
 */
data class Todo(
    val description: String,
    val status: TodoStatus
)

/**
 * TodoStatus - Status of a todo item.
 */
enum class TodoStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}
