package ai.closepaw.session

import com.google.common.truth.Truth.assertThat
import ai.closepaw.protocol.Todo
import ai.closepaw.protocol.TodoStatus
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
    fun `clear removes all todos`() {
        val state = TodoState()
        state.update(listOf(Todo("first", TodoStatus.PENDING)))

        state.clear()

        assertThat(state.get()).isEmpty()
    }

    @Test
    fun `toPromptContext uses uppercase status`() {
        val state = TodoState()
        state.update(listOf(Todo("first", TodoStatus.IN_PROGRESS)))

        val context = state.toPromptContext()

        assertThat(context).isEqualTo("1. [IN_PROGRESS] first")
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
