package ai.closepaw.tool.impl

import com.google.common.truth.Truth.assertThat
import ai.closepaw.session.ScratchpadState
import ai.closepaw.test.FakeAndroidPlatform
import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ValidationResult
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
    fun `write requires content`() {
        val tool = ScratchpadTool(ScratchpadState())
        val params = JSONObject().put("action", "write")

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `write rejects invalid JSON content`() {
        val tool = ScratchpadTool(ScratchpadState())
        val params = JSONObject()
            .put("action", "write")
            .put("content", "not json")

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
        assertThat((result as ValidationResult.Invalid).errors.joinToString()).contains("not valid JSON")
    }

    @Test
    fun `write rejects overly long value in content`() {
        val tool = ScratchpadTool(ScratchpadState())
        val content = JSONObject()
            .put("key", "x".repeat(ScratchpadState.MAX_VALUE_LENGTH + 1))
            .toString()
        val params = JSONObject()
            .put("action", "write")
            .put("content", content)

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `write rejects when scratchpad would exceed max entries`() {
        val state = ScratchpadState()
        repeat(ScratchpadState.MAX_ENTRIES) { index ->
            state.write("key$index", "value")
        }
        val tool = ScratchpadTool(state)
        val content = JSONObject().put("new_key", "value").toString()
        val params = JSONObject()
            .put("action", "write")
            .put("content", content)

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `write allows upsert on existing key when full`() {
        val state = ScratchpadState()
        repeat(ScratchpadState.MAX_ENTRIES) { index ->
            state.write("key$index", "value")
        }
        val tool = ScratchpadTool(state)
        val content = JSONObject().put("key0", "updated").toString()
        val params = JSONObject()
            .put("action", "write")
            .put("content", content)

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Valid::class.java)
    }

    @Test
    fun `execute write single key`() = runTest {
        val state = ScratchpadState()
        val tool = ScratchpadTool(state)
        val content = JSONObject().put("k", "v").toString()
        val params = JSONObject()
            .put("action", "write")
            .put("content", content)

        val invocation = tool.createInvocation(params)
        val result = invocation.execute(buildContext())

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        assertThat(state.read("k")).isEqualTo("v")
        assertThat((result as ToolExecutionResult.Success).output).contains("Stored 1 keys")
    }

    @Test
    fun `execute write batch keys`() = runTest {
        val state = ScratchpadState()
        val tool = ScratchpadTool(state)
        val content = JSONObject()
            .put("name", "Apple")
            .put("price", 3.5)
            .put("count", 10)
            .toString()
        val params = JSONObject()
            .put("action", "write")
            .put("content", content)

        val invocation = tool.createInvocation(params)
        val result = invocation.execute(buildContext())

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        assertThat(state.read("name")).isEqualTo("Apple")
        assertThat(state.read("price").toString()).isEqualTo("3.5")
        assertThat(state.read("count").toString()).isEqualTo("10")
        assertThat((result as ToolExecutionResult.Success).output).contains("Stored 3 keys")
    }

    @Test
    fun `execute read returns native value`() = runTest {
        val state = ScratchpadState()
        state.write("num", 42)
        val tool = ScratchpadTool(state)
        val params = JSONObject()
            .put("action", "read")
            .put("key", "num")

        val invocation = tool.createInvocation(params)
        val result = invocation.execute(buildContext())

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        val output = JSONObject((result as ToolExecutionResult.Success).output)
        assertThat(output.getInt("value")).isEqualTo(42)
    }

    @Test
    fun `list action is invalid`() {
        val tool = ScratchpadTool(ScratchpadState())
        val params = JSONObject().put("action", "list")

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `description shows key count for batch write`() {
        val tool = ScratchpadTool(ScratchpadState())
        val content = JSONObject()
            .put("a", "1")
            .put("b", "2")
            .put("c", "3")
            .toString()
        val params = JSONObject()
            .put("action", "write")
            .put("content", content)

        val invocation = tool.createInvocation(params)
        val desc = invocation.getDescription()

        assertThat(desc).contains("3 keys")
    }

    private fun buildContext(): ToolExecutionContext {
        return object : ToolExecutionContext {
            override val platform = FakeAndroidPlatform()
            override val currentSnapshot = null
            override fun isCancelled(): Boolean = false
        }
    }
}
