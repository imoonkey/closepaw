package com.moonkey.androidagent.tool

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.AppInfo
import com.moonkey.androidagent.platform.DisplayInfo
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.protocol.ApprovalMode
import com.moonkey.androidagent.protocol.ApprovalDecision
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolRouterTest {

    @Test
    fun `unknown tool returns error`() = runTest {
        val router = ToolRouter(ToolRegistry(), PolicyEngine())
        val context = SimpleToolRouterContext(FakeAndroidPlatform())

        val result = router.execute("missing_tool", JSONObject(), context)

        assertThat(result).isInstanceOf(ToolCallResult.Error::class.java)
        assertThat((result as ToolCallResult.Error).error).contains("Unknown tool")
    }

    @Test
    fun `approval timeout returns cancelled`() = runTest {
        val registry = ToolRegistry().apply { register(TestToolSpec()) }
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.ALWAYS_ASK))
        val context = SimpleToolRouterContext(FakeAndroidPlatform())

        val deferred = async {
            router.execute(
                toolName = "test_tool",
                params = JSONObject(),
                context = context,
                onApprovalRequired = { /* intentionally no-op */ }
            )
        }

        advanceTimeBy(60_000)
        advanceUntilIdle()

        val result = deferred.await()
        assertThat(result).isInstanceOf(ToolCallResult.Cancelled::class.java)
        assertThat((result as ToolCallResult.Cancelled).reason).isEqualTo("Approval timed out")
        advanceUntilIdle()
        assertThat(router.hasPendingApprovals()).isFalse()
        assertThat(router.getActiveCallIds()).isEmpty()
    }

    @Test
    fun `approval approved executes tool`() = runTest {
        val registry = ToolRegistry().apply { register(TestToolSpec()) }
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.ALWAYS_ASK))
        val context = SimpleToolRouterContext(FakeAndroidPlatform())

        val result = router.execute(
            toolName = "test_tool",
            params = JSONObject(),
            context = context,
            onApprovalRequired = { details ->
                router.resolveApproval(details.callId, ApprovalDecision.APPROVED)
            }
        )

        assertThat(result).isInstanceOf(ToolCallResult.Success::class.java)
    }

    @Test
    fun `policy deny returns error`() = runTest {
        val registry = ToolRegistry().apply { register(TestToolSpec()) }
        val policy = PolicyEngine(ApprovalMode.AUTO_APPROVE).apply {
            denyTool("test_tool")
        }
        val router = ToolRouter(registry, policy)
        val context = SimpleToolRouterContext(FakeAndroidPlatform())

        val result = router.execute("test_tool", JSONObject(), context)

        assertThat(result).isInstanceOf(ToolCallResult.Error::class.java)
        assertThat((result as ToolCallResult.Error).error).contains("Policy denied")
    }

    @Test
    fun `concurrent executions tracked and cleaned up`() = runTest {
        val registry = ToolRegistry().apply { register(DelayingToolSpec(1_000L)) }
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.AUTO_APPROVE))
        val context = SimpleToolRouterContext(FakeAndroidPlatform())

        val first = async { router.execute("delaying_tool", JSONObject(), context) }
        val second = async { router.execute("delaying_tool", JSONObject(), context) }

        advanceTimeBy(1L)
        assertThat(router.getActiveCallIds()).hasSize(2)

        advanceTimeBy(1_000L)
        advanceUntilIdle()
        first.await()
        second.await()

        assertThat(router.getActiveCallIds()).isEmpty()
    }

    @Test
    fun `cancellation during execution cleans up state`() = runTest {
        val registry = ToolRegistry().apply { register(DelayingToolSpec(10_000L)) }
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.AUTO_APPROVE))
        val context = SimpleToolRouterContext(FakeAndroidPlatform())

        val job = launch { router.execute("delaying_tool", JSONObject(), context) }

        advanceTimeBy(1L)
        assertThat(router.getActiveCallIds()).hasSize(1)

        job.cancel()
        advanceUntilIdle()

        assertThat(router.getActiveCallIds()).isEmpty()
    }
}

private class TestToolSpec : ToolSpec {
    override val name: String = "test_tool"
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

            override fun getDescription(): String = "Test tool invocation"

            override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
                return ToolExecutionResult.Success("ok")
            }
        }
    }
}

private class DelayingToolSpec(
    private val delayMs: Long
) : ToolSpec {
    override val name: String = "delaying_tool"
    override val description: String = "Delaying tool"
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

            override fun getDescription(): String = "delaying"

            override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
                delay(delayMs)
                return ToolExecutionResult.Success("ok")
            }
        }
    }
}

private class FakeAndroidPlatform : AndroidPlatform {
    override suspend fun captureScreen(): ScreenSnapshot {
        return ScreenSnapshot(timestamp = 0L, elements = emptyList())
    }

    override suspend fun performAction(action: UIAction, snapshot: ScreenSnapshot?): ActionResult {
        return ActionResult.Success()
    }

    override fun hasRequiredPermissions(): Boolean = true

    override fun getCurrentPackageName(): String? = "com.example.fake"

    override fun getDisplayInfo(): DisplayInfo = DisplayInfo(
        widthPixels = 1080,
        heightPixels = 1920,
        density = 2f
    )

    override suspend fun getInstalledApps(): List<AppInfo> = emptyList()

    override suspend fun launchApp(packageName: String): ActionResult = ActionResult.Success()
}
