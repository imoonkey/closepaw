package com.moonkey.androidagent.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScratchpadStateTest {

    @Test
    fun `write read delete and list work`() {
        val state = ScratchpadState()

        state.write("key", "value")

        assertThat(state.read("key")).isEqualTo("value")
        assertThat(state.list()).containsExactly("key")

        val removed = state.delete("key")

        assertThat(removed).isTrue()
        assertThat(state.read("key")).isNull()
        assertThat(state.list()).isEmpty()
    }

    @Test
    fun `toPromptContext renders sorted entries`() {
        val state = ScratchpadState()
        state.write("b", "2")
        state.write("a", "1")

        val context = state.toPromptContext()

        assertThat(context).isEqualTo("- a: 1\n- b: 2")
    }

    @Test
    fun `write rejects overly long value`() {
        val state = ScratchpadState()
        val value = "x".repeat(ScratchpadState.MAX_VALUE_LENGTH + 1)

        try {
            state.write("key", value)
            org.junit.Assert.fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("value too long")
        }
    }
}
