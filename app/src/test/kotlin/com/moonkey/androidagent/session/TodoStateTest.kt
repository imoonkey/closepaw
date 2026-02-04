package com.moonkey.androidagent.session

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.protocol.Todo
import com.moonkey.androidagent.protocol.TodoStatus
import org.junit.Assert.fail
import org.junit.Test

class TodoStateTest {

    @Test
    fun `update stores todos`() {
        val state = TodoState()
        val todos = listOf(
            Todo("first", TodoStatus.PENDING),
            Todo("second", TodoStatus.IN_PROGRESS)
        )

        state.update(todos)

        assertThat(state.get()).isEqualTo(todos)
    }

    @Test
    fun `update rejects multiple in progress`() {
        val state = TodoState()
        val todos = listOf(
            Todo("first", TodoStatus.IN_PROGRESS),
            Todo("second", TodoStatus.IN_PROGRESS)
        )

        try {
            state.update(todos)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("IN_PROGRESS")
        }
    }
}
