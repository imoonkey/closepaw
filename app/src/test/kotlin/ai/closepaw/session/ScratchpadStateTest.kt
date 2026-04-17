package ai.closepaw.session

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
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
    fun `write supports any JSON value type`() {
        val state = ScratchpadState()

        state.write("str", "hello")
        state.write("num", 42)
        state.write("bool", true)
        state.write("arr", JSONArray(listOf(1, 2, 3)))
        state.write("obj", JSONObject().put("nested", "value"))

        assertThat(state.read("str")).isEqualTo("hello")
        assertThat(state.read("num")).isEqualTo(42)
        assertThat(state.read("bool")).isEqualTo(true)
        assertThat(state.read("arr").toString()).isEqualTo("[1,2,3]")
        assertThat((state.read("obj") as JSONObject).getString("nested")).isEqualTo("value")
    }

    @Test
    fun `toPromptContext renders JSON format with values`() {
        val state = ScratchpadState()
        state.write("b", "2")
        state.write("a", "1")

        val context = state.toPromptContext()

        assertThat(context).contains("{")
        assertThat(context).contains("}")
        assertThat(context).contains("\"a\": \"1\"")
        assertThat(context).contains("\"b\": \"2\"")
        // Verify sorted order: a before b
        val aIdx = context.indexOf("\"a\"")
        val bIdx = context.indexOf("\"b\"")
        assertThat(aIdx).isLessThan(bIdx)
    }

    @Test
    fun `toPromptContext truncates long values`() {
        val state = ScratchpadState()
        val longValue = "x".repeat(ScratchpadState.DISPLAY_TRUNCATE_LENGTH + 50)
        state.write("long_key", longValue)

        val context = state.toPromptContext()

        assertThat(context).contains("truncated")
        assertThat(context).contains("...")
    }

    @Test
    fun `toPromptContext includes empty guidance`() {
        val state = ScratchpadState()

        val context = state.toPromptContext()

        assertThat(context).contains("(empty)")
        assertThat(context).contains("scratchpad(action=\"write\"")
        assertThat(context).contains("content=")
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

    @Test
    fun `toJsonObject returns deep copy`() {
        val state = ScratchpadState()
        state.write("key", "value")

        val copy = state.toJsonObject()
        copy.put("key", "modified")

        assertThat(state.read("key")).isEqualTo("value")
    }

    @Test
    fun `clear removes all entries`() {
        val state = ScratchpadState()
        state.write("a", "1")
        state.write("b", "2")

        state.clear()

        assertThat(state.list()).isEmpty()
        assertThat(state.entryCount()).isEqualTo(0)
    }

    @Test
    fun `write upserts existing key`() {
        val state = ScratchpadState()
        state.write("key", "old")
        state.write("key", "new")

        assertThat(state.read("key")).isEqualTo("new")
        assertThat(state.entryCount()).isEqualTo(1)
    }
}
