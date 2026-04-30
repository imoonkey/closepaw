package ai.closepaw.tool.impl

import ai.closepaw.agent.cognition.skills.ActivationResult
import ai.closepaw.agent.cognition.skills.AgentSkillManager
import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ValidationResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ActivateSkillToolTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun createSkill(name: String, description: String, body: String = "Instructions.") {
        val dir = tempDir.newFolder(name)
        dir.resolve("SKILL.md").writeText(
            """
            |---
            |name: $name
            |description: $description
            |---
            |$body
            """.trimMargin()
        )
    }

    private fun tool(): ActivateSkillTool {
        val manager = AgentSkillManager(tempDir.root)
        return ActivateSkillTool(manager)
    }

    private val stubContext = object : ToolExecutionContext {
        override val platform: ai.closepaw.platform.AndroidPlatform
            get() = throw UnsupportedOperationException()
        override val currentSnapshot: ai.closepaw.model.ScreenSnapshot? = null
        override fun isCancelled(): Boolean = false
    }

    @Test
    fun `missing name is invalid`() {
        createSkill("date-math", "Compute dates")
        val result = tool().validate(JSONObject())
        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `blank name is invalid`() {
        createSkill("date-math", "Compute dates")
        val result = tool().validate(JSONObject().put("name", "  "))
        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `valid name passes validation`() {
        createSkill("date-math", "Compute dates")
        val result = tool().validate(JSONObject().put("name", "date-math"))
        assertThat(result).isEqualTo(ValidationResult.Valid)
    }

    @Test
    fun `execute returns skill body on success`() = runBlocking {
        createSkill("date-math", "Compute dates", "Use ISO-8601.")
        val t = tool()
        val params = JSONObject().put("name", "date-math")
        t.validate(params)
        val invocation = t.createInvocation(params)

        val result = invocation.execute(stubContext)

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        assertThat((result as ToolExecutionResult.Success).output).isEqualTo("Use ISO-8601.")
    }

    @Test
    fun `execute returns failure for unknown skill`() = runBlocking {
        val t = tool()
        val params = JSONObject().put("name", "nonexistent")
        t.validate(params)
        val invocation = t.createInvocation(params)

        val result = invocation.execute(stubContext)

        assertThat(result).isInstanceOf(ToolExecutionResult.Failure::class.java)
        assertThat((result as ToolExecutionResult.Failure).error).contains("Unknown skill")
    }

    @Test
    fun `execute returns already active on duplicate`() = runBlocking {
        createSkill("date-math", "Compute dates", "Body.")
        val manager = AgentSkillManager(tempDir.root)
        val t = ActivateSkillTool(manager)
        manager.activate("date-math")

        val params = JSONObject().put("name", "date-math")
        t.validate(params)
        val invocation = t.createInvocation(params)

        val result = invocation.execute(stubContext)

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        assertThat((result as ToolExecutionResult.Success).output).contains("already active")
    }

    @Test
    fun `description includes skill name`() {
        createSkill("date-math", "Compute dates")
        val t = tool()
        val params = JSONObject().put("name", "date-math")
        val invocation = t.createInvocation(params)

        assertThat(invocation.getDescription()).contains("date-math")
    }

    @Test
    fun `description includes agent thought when provided`() {
        createSkill("date-math", "Compute dates")
        val t = tool()
        val params = JSONObject()
            .put("name", "date-math")
            .put("agent_thought", "need date calculations")
        val invocation = t.createInvocation(params)

        assertThat(invocation.getDescription()).contains("need date calculations")
    }

    @Test
    fun `cancelled context returns cancelled result`() = runBlocking {
        createSkill("date-math", "Compute dates")
        val t = tool()
        val params = JSONObject().put("name", "date-math")
        val invocation = t.createInvocation(params)

        val cancelledContext = object : ToolExecutionContext {
            override val platform: ai.closepaw.platform.AndroidPlatform
                get() = throw UnsupportedOperationException()
            override val currentSnapshot: ai.closepaw.model.ScreenSnapshot? = null
            override fun isCancelled(): Boolean = true
        }

        val result = invocation.execute(cancelledContext)

        assertThat(result).isInstanceOf(ToolExecutionResult.Cancelled::class.java)
    }
}
