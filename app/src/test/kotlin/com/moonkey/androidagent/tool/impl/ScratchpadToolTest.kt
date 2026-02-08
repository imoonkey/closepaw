package com.moonkey.androidagent.tool.impl

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.session.ScratchpadState
import com.moonkey.androidagent.test.FakeAndroidPlatform
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ValidationResult
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test

class ScratchpadToolTest {

    @Test
    fun `missing action is invalid`() {
        val tool = ScratchpadTool(ScratchpadState())

        val result = tool.validate(JSONObject())

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `write requires key and value`() {
        val tool = ScratchpadTool(ScratchpadState())
        val params = JSONObject().put("action", "write")

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `write rejects overly long value`() {
        val tool = ScratchpadTool(ScratchpadState())
        val params = JSONObject()
            .put("action", "write")
            .put("key", "key")
            .put("value", "x".repeat(ScratchpadState.MAX_VALUE_LENGTH + 1))

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `write rejects when scratchpad full`() {
        val state = ScratchpadState()
        repeat(ScratchpadState.MAX_ENTRIES) { index ->
            state.write("key$index", "value")
        }
        val tool = ScratchpadTool(state)
        val params = JSONObject()
            .put("action", "write")
            .put("key", "new")
            .put("value", "value")

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `execute write and read`() = runTest {
        val state = ScratchpadState()
        val tool = ScratchpadTool(state)
        val writeParams = JSONObject()
            .put("action", "write")
            .put("key", "k")
            .put("value", "v")

        val writeInvocation = tool.createInvocation(writeParams)
        val writeResult = writeInvocation.execute(buildContext())

        assertThat(writeResult).isInstanceOf(ToolExecutionResult.Success::class.java)
        assertThat(state.read("k")).isEqualTo("v")
        assertThat((writeResult as ToolExecutionResult.Success).output).isEqualTo("Stored 'k' (1 chars).")

        val readParams = JSONObject()
            .put("action", "read")
            .put("key", "k")
        val readInvocation = tool.createInvocation(readParams)
        val readResult = readInvocation.execute(buildContext())

        assertThat(readResult).isInstanceOf(ToolExecutionResult.Success::class.java)
    }

    @Test
    fun `list action is invalid`() {
        val tool = ScratchpadTool(ScratchpadState())
        val params = JSONObject().put("action", "list")

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    private fun buildContext(): ToolExecutionContext {
        return object : ToolExecutionContext {
            override val platform = FakeAndroidPlatform()
            override val currentSnapshot = null
            override fun isCancelled(): Boolean = false
        }
    }
}
