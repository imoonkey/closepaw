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
}
