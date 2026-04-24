package ai.closepaw.tool.impl

import com.google.common.truth.Truth.assertThat
import ai.closepaw.memory.MemoryStore
import ai.closepaw.protocol.AppTier
import ai.closepaw.test.FakeAndroidPlatform
import ai.closepaw.tool.AppClassifier
import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ValidationResult
import java.io.File
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RememberExperienceToolTest {

    @get:Rule val tempDir = TemporaryFolder()

    private lateinit var store: MemoryStore
    private lateinit var tool: RememberExperienceTool

    @Before
    fun setUp() {
        store = MemoryStore(tempDir.newFolder("memory"))
        tool = RememberExperienceTool(store, AppClassifier(emptyMap()))
    }

    @Test
    fun `missing scope is invalid`() {
        val result = tool.validate(JSONObject().put("section", "preferences").put("content", "Test"))

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `section must be valid for scope`() {
        val params = JSONObject()
            .put("scope", "user")
            .put("section", "operational_notes")
            .put("content", "Test")

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
        assertThat((result as ValidationResult.Invalid).errors.joinToString())
            .contains("scope=user")
    }

    @Test
    fun `app scope requires package name`() {
        val params = JSONObject()
            .put("scope", "app")
            .put("section", "operational_notes")
            .put("content", "Test")

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
        assertThat((result as ValidationResult.Invalid).errors.joinToString())
            .contains("package_name required")
    }

    @Test
    fun `non app scope rejects package name`() {
        val params = JSONObject()
            .put("scope", "user")
            .put("section", "preferences")
            .put("package_name", "com.example")
            .put("content", "Test")

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
        assertThat((result as ValidationResult.Invalid).errors.joinToString())
            .contains("only allowed when scope = app")
    }

    @Test
    fun `execute writes user preference memory`() = runTest {
        val params = JSONObject()
            .put("scope", "user")
            .put("section", "preferences")
            .put("content", "Prefer search over scrolling")

        val result = tool.createInvocation(params).execute(buildContext())

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        assertThat(store.readUserMemory()).contains("Prefer search over scrolling")
        assertThat((result as ToolExecutionResult.Success).output)
            .contains("user/preferences")
    }

    @Test
    fun `execute writes app operational note without legacy prefix`() = runTest {
        val params = JSONObject()
            .put("scope", "app")
            .put("section", "operational_notes")
            .put("package_name", "com.android.settings")
            .put("content", "[pitfall] BACK may dismiss keyboard first")

        val result = tool.createInvocation(params).execute(buildContext())

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        val content = store.readAppMemory("com.android.settings")!!
        assertThat(content).contains("BACK may dismiss keyboard first")
        assertThat(content).doesNotContain("[pitfall]")
    }

    @Test
    fun `app scope write denied when target package is BLOCKED`() = runTest {
        val blockedTool = RememberExperienceTool(
            store,
            AppClassifier(mapOf("com.blocked.bank" to AppTier.BLOCKED))
        )
        val params = JSONObject()
            .put("scope", "app")
            .put("section", "operational_notes")
            .put("package_name", "com.blocked.bank")
            .put("content", "Some note about the banking app")

        val result = blockedTool.createInvocation(params).execute(buildContext())

        assertThat(result).isInstanceOf(ToolExecutionResult.Failure::class.java)
        assertThat((result as ToolExecutionResult.Failure).error)
            .contains("restricted by security policy")
        assertThat(store.readAppMemory("com.blocked.bank")).isNull()
    }

    @Test
    fun `app scope write allowed when target package is NORMAL`() = runTest {
        val classifiedTool = RememberExperienceTool(
            store,
            AppClassifier(mapOf(
                "com.blocked.bank" to AppTier.BLOCKED,
                "com.normal.app" to AppTier.NORMAL
            ))
        )
        val params = JSONObject()
            .put("scope", "app")
            .put("section", "operational_notes")
            .put("package_name", "com.normal.app")
            .put("content", "This app works great")

        val result = classifiedTool.createInvocation(params).execute(buildContext())

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
    }

    private fun buildContext(): ToolExecutionContext {
        return object : ToolExecutionContext {
            override val platform = FakeAndroidPlatform()
            override val currentSnapshot = null
            override fun isCancelled(): Boolean = false
        }
    }
}
