package com.moonkey.androidagent.protocol

/** Todo list has been updated. */
data class TodosUpdated(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val todos: List<Todo>
) : PlanningStateEvent

/** Scratchpad has been updated (write/delete). */
data class ScratchpadUpdated(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val key: String,
        val action: String
) : PlanningStateEvent
