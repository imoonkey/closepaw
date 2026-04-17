package ai.closepaw.tool

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class ToolRegistryTest {

    @Test
    fun `register and get returns tool`() {
        val registry = ToolRegistry()
        val tool = RegistryTestToolSpec("test_tool")

        registry.register(tool)

        assertThat(registry.get("test_tool")).isEqualTo(tool)
        assertThat(registry.contains("test_tool")).isTrue()
    }

    @Test
    fun `unregister removes tool`() {
        val registry = ToolRegistry()
        registry.register(RegistryTestToolSpec("test_tool"))

        val removed = registry.unregister("test_tool")

        assertThat(removed).isTrue()
        assertThat(registry.get("test_tool")).isNull()
        assertThat(registry.contains("test_tool")).isFalse()
    }

    @Test
    fun `generateResponsesApiTools respects filter`() {
        val registry = ToolRegistry()
        registry.register(RegistryTestToolSpec("keep"))
        registry.register(RegistryTestToolSpec("drop"))

        val tools = registry.generateResponsesApiTools { it.name == "keep" }

        assertThat(tools).hasSize(1)
        assertThat(tools.single().name()).isEqualTo("keep")
    }

    @Test
    fun `createFilteredCopy keeps allowed tools and excludes explicit names`() {
        val registry = ToolRegistry()
        registry.register(RegistryTestToolSpec("mobile_action"))
        registry.register(RegistryTestToolSpec("delegate_task"))
        registry.register(RegistryTestToolSpec("complete_task"))

        val filtered = registry.createFilteredCopy(
            allowedNames = setOf("mobile_action", "delegate_task"),
            excludedNames = setOf("delegate_task")
        )

        assertThat(filtered.contains("mobile_action")).isTrue()
        assertThat(filtered.contains("delegate_task")).isFalse()
        assertThat(filtered.contains("complete_task")).isFalse()
    }
}

private class RegistryTestToolSpec(override val name: String) : ToolSpec {
    override val description: String = "Test tool"
    override val parameterSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
        put("required", JSONArray())
        put("additionalProperties", false)
    }

    override fun validate(params: JSONObject): ValidationResult = ValidationResult.Valid

    override fun createInvocation(params: JSONObject): ToolInvocation {
        return object : ToolInvocation {
            override val toolName: String = name
            override val params: JSONObject = params

            override fun getDescription(): String = "Test invocation"

            override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
                return ToolExecutionResult.Success("ok")
            }
        }
    }
}
