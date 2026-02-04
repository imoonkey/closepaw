package com.moonkey.androidagent.tool.impl

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.protocol.TodoStatus
import com.moonkey.androidagent.session.TodoState
import com.moonkey.androidagent.test.FakeAndroidPlatform
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ValidationResult
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class WriteTodosToolTest {

    @Test
    fun `missing todos is invalid`() {
        val tool = WriteTodosTool(TodoState())

        val result = tool.validate(JSONObject())

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `invalid status is rejected`() {
        val tool = WriteTodosTool(TodoState())
        val params = JSONObject().put("todos", JSONArray().put(
            JSONObject()
                .put("description", "test")
                .put("status", "maybe")
        ))

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `empty description is rejected`() {
        val tool = WriteTodosTool(TodoState())
        val params = JSONObject().put("todos", JSONArray().put(
            JSONObject()
                .put("description", " ")
                .put("status", "pending")
        ))

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `multiple in progress is invalid`() {
        val tool = WriteTodosTool(TodoState())
        val params = JSONObject().put("todos", JSONArray().apply {
            put(JSONObject().put("description", "a").put("status", "in_progress"))
            put(JSONObject().put("description", "b").put("status", "in_progress"))
        })

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `execute updates state`() = runTest {
        val state = TodoState()
        val tool = WriteTodosTool(state)
        val params = JSONObject().put("todos", JSONArray().apply {
            put(JSONObject().put("description", "task").put("status", "pending"))
        })

        val invocation = tool.createInvocation(params)
        val result = invocation.execute(buildContext())

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        assertThat(state.get()).hasSize(1)
        assertThat(state.get().single().status).isEqualTo(TodoStatus.PENDING)
    }

    private fun buildContext(): ToolExecutionContext {
        return object : ToolExecutionContext {
            override val platform = FakeAndroidPlatform()
            override val currentSnapshot = null
            override fun isCancelled(): Boolean = false
        }
    }
}
