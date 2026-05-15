package ai.closepaw.tool

import com.google.common.truth.Truth.assertThat
import ai.closepaw.agent.toActionOutcome
import ai.closepaw.protocol.ActionOutcome
import ai.closepaw.protocol.ApprovalMode
import ai.closepaw.protocol.ApprovalDecision
import ai.closepaw.protocol.AppTier
import ai.closepaw.platform.AppInfo
import ai.closepaw.test.FakeAndroidPlatform
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

    private fun defaultClassifier() = AppClassifier(emptyMap())
    private fun blockedClassifier(pkg: String) = AppClassifier(mapOf(pkg to AppTier.BLOCKED))

    @Test
    fun `unknown tool returns error`() = runTest {
        val router = ToolRouter(ToolRegistry(), PolicyEngine(appClassifier = defaultClassifier()))
        val context = SimpleToolRouterContext(FakeAndroidPlatform())

        val result = router.execute("missing_tool", JSONObject(), context)

        assertThat(result).isInstanceOf(ToolCallResult.Error::class.java)
        assertThat((result as ToolCallResult.Error).error).contains("Unknown tool")
    }

    @Test
    fun `approval timeout returns cancelled`() = runTest {
        val registry = ToolRegistry().apply { register(TestToolSpec()) }
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.ALWAYS_ASK, defaultClassifier()))
        val context = SimpleToolRouterContext(FakeAndroidPlatform())

        val deferred = async {
            router.execute(
                toolName = "test_tool",
                params = JSONObject(),
                context = context,
                packageName = "com.example.fake",
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
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.ALWAYS_ASK, defaultClassifier()))
        val context = SimpleToolRouterContext(FakeAndroidPlatform())

        val result = router.execute(
            toolName = "test_tool",
            params = JSONObject(),
            context = context,
            packageName = "com.example.fake",
            onApprovalRequired = { details ->
                router.resolveApproval(details.callId, ApprovalDecision.APPROVED)
            }
        )

        assertThat(result).isInstanceOf(ToolCallResult.Success::class.java)
    }

    @Test
    fun `ask user without owning package fails closed before approval UI`() = runTest {
        val registry = ToolRegistry().apply { register(TestToolSpec()) }
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.ALWAYS_ASK, defaultClassifier()))
        val context = SimpleToolRouterContext(FakeAndroidPlatform(currentPackageName = null))
        var approvalRequested = false

        val result = router.execute(
            toolName = "test_tool",
            params = JSONObject(),
            context = context,
            packageName = null,
            onApprovalRequired = { details ->
                approvalRequested = true
                router.resolveApproval(details.callId, ApprovalDecision.DENIED)
            }
        )

        assertThat(result).isInstanceOf(ToolCallResult.Cancelled::class.java)
        assertThat((result as ToolCallResult.Cancelled).reason).contains("approval package unknown")
        assertThat(approvalRequested).isFalse()
        assertThat(router.hasPendingApprovals()).isFalse()
        assertThat(router.getActiveCallIds()).isEmpty()
    }

    @Test
    fun `open app approval uses destination package as approval subject`() = runTest {
        val registry = ToolRegistry().apply { register(TestToolSpec(name = "open_app")) }
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.SMART, defaultClassifier()))
        val context = SimpleToolRouterContext(
            FakeAndroidPlatform(
                currentPackageName = "com.example.fake",
                installedApps = listOf(AppInfo("com.android.chrome", "Chrome")),
            )
        )
        var approvalPackageName: String? = null

        val result = router.execute(
            toolName = "open_app",
            params = JSONObject().put("app_name", "Chrome"),
            context = context,
            packageName = "com.example.fake",
            onApprovalRequired = { details ->
                approvalPackageName = details.packageName
                router.resolveApproval(details.callId, ApprovalDecision.APPROVED)
            }
        )

        assertThat(result).isInstanceOf(ToolCallResult.Success::class.java)
        assertThat(approvalPackageName).isEqualTo("com.android.chrome")
    }

    @Test
    fun `approval emitter exception returns error not timeout`() = runTest {
        val registry = ToolRegistry().apply { register(TestToolSpec()) }
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.ALWAYS_ASK, defaultClassifier()))
        val context = SimpleToolRouterContext(FakeAndroidPlatform())

        val result = router.execute(
            toolName = "test_tool",
            params = JSONObject(),
            context = context,
            packageName = "com.example.fake",
            onApprovalRequired = { throw IllegalStateException("UI dispatch broken") }
        )

        assertThat(result).isInstanceOf(ToolCallResult.Error::class.java)
        assertThat((result as ToolCallResult.Error).error).contains("Approval request failed")
        assertThat(router.hasPendingApprovals()).isFalse()
        assertThat(router.getActiveCallIds()).isEmpty()
    }

    @Test
    fun `blocked app policy deny returns cancelled mapped to skipped`() = runTest {
        val registry = ToolRegistry().apply { register(TestToolSpec()) }
        val policy = PolicyEngine(ApprovalMode.AUTO_APPROVE, blockedClassifier("com.bank"))
        val router = ToolRouter(registry, policy)
        val context = SimpleToolRouterContext(FakeAndroidPlatform())

        val result = router.execute("test_tool", JSONObject(), context, packageName = "com.bank")

        assertThat(result).isInstanceOf(ToolCallResult.Cancelled::class.java)
        assertThat((result as ToolCallResult.Cancelled).reason).contains("Policy denied")
        assertThat(result.toActionOutcome()).isEqualTo(ActionOutcome.SKIPPED)
    }

    @Test
    fun `concurrent executions tracked and cleaned up`() = runTest {
        val registry = ToolRegistry().apply { register(DelayingToolSpec(1_000L)) }
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.AUTO_APPROVE, defaultClassifier()))
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
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.AUTO_APPROVE, defaultClassifier()))
        val context = SimpleToolRouterContext(FakeAndroidPlatform())

        val job = launch { router.execute("delaying_tool", JSONObject(), context) }

        advanceTimeBy(1L)
        assertThat(router.getActiveCallIds()).hasSize(1)

        job.cancel()
        advanceUntilIdle()

        assertThat(router.getActiveCallIds()).isEmpty()
    }

    // === Phase 4: Per-call cancellation tests ===

    @Test
    fun `cancel(callId) propagates to executing tool via per-call token`() = runTest {
        val registry = ToolRegistry().apply { register(CancellableToolSpec()) }
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.AUTO_APPROVE, defaultClassifier()))
        val context = SimpleToolRouterContext(FakeAndroidPlatform())

        val deferred = async {
            router.execute(
                toolName = "cancellable_tool",
                params = JSONObject(),
                context = context,
                callId = "cancel-test-1"
            )
        }

        // Tool is now executing and polling isCancelled() with delay(100) intervals
        advanceTimeBy(50)
        assertThat(router.getActiveCallIds()).contains("cancel-test-1")

        // Signal cancellation via the per-call token (not the shared context)
        router.cancel("cancel-test-1")

        advanceTimeBy(200)
        advanceUntilIdle()

        val result = deferred.await()
        assertThat(result).isInstanceOf(ToolCallResult.Cancelled::class.java)
        assertThat((result as ToolCallResult.Cancelled).reason).isEqualTo("Cancelled via token")
        assertThat(router.getActiveCallIds()).isEmpty()
    }

    @Test
    fun `cancelAll while one tool awaits approval and another executes`() = runTest {
        val registry = ToolRegistry().apply {
            register(CancellableToolSpec())
            register(TestToolSpec())
        }
        // ALWAYS_ASK so test_tool needs approval; cancellable_tool also needs approval
        // but we'll approve cancellable_tool and leave test_tool awaiting
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.ALWAYS_ASK, defaultClassifier()))
        val context = SimpleToolRouterContext(FakeAndroidPlatform())

        // Launch cancellable_tool — approve it so it starts executing
        val executingDeferred = async {
            router.execute(
                toolName = "cancellable_tool",
                params = JSONObject(),
                context = context,
                packageName = "com.example.fake",
                callId = "exec-1",
                onApprovalRequired = { details ->
                    router.resolveApproval(details.callId, ApprovalDecision.APPROVED)
                }
            )
        }

        // Launch test_tool — don't approve, leave it awaiting
        val awaitingDeferred = async {
            router.execute(
                toolName = "test_tool",
                params = JSONObject(),
                context = context,
                packageName = "com.example.fake",
                callId = "await-1",
                onApprovalRequired = { /* no-op: leave awaiting */ }
            )
        }

        advanceTimeBy(50)
        // One executing, one awaiting approval
        assertThat(router.getActiveCallIds()).containsAtLeast("exec-1", "await-1")
        assertThat(router.hasPendingApprovals()).isTrue()

        // Cancel everything
        router.cancelAll()

        advanceTimeBy(200)
        advanceUntilIdle()

        val execResult = executingDeferred.await()
        val awaitResult = awaitingDeferred.await()

        assertThat(execResult).isInstanceOf(ToolCallResult.Cancelled::class.java)
        assertThat(awaitResult).isInstanceOf(ToolCallResult.Cancelled::class.java)
        assertThat(router.getActiveCallIds()).isEmpty()
    }

    @Test
    fun `cancelAll does not drop tracking before tools reach terminal state`() = runTest {
        val registry = ToolRegistry().apply { register(CancellableToolSpec()) }
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.AUTO_APPROVE, defaultClassifier()))
        val context = SimpleToolRouterContext(FakeAndroidPlatform())

        val deferred = async {
            router.execute(
                toolName = "cancellable_tool",
                params = JSONObject(),
                context = context,
                callId = "track-1"
            )
        }

        advanceTimeBy(50)
        assertThat(router.getActiveCallIds()).contains("track-1")

        // cancelAll signals the token but doesn't clear tracking
        router.cancelAll()

        // Tool hasn't polled yet — still tracked as active
        assertThat(router.getActiveCallIds()).contains("track-1")

        // Let the tool detect cancellation and finish
        advanceTimeBy(200)
        advanceUntilIdle()
        deferred.await()

        // Now it's cleaned up
        assertThat(router.getActiveCallIds()).isEmpty()
    }
}

private class TestToolSpec(
    override val name: String = "test_tool"
) : ToolSpec {
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

/**
 * Tool that cooperatively polls isCancelled() and returns Cancelled when signalled.
 */
private class CancellableToolSpec : ToolSpec {
    override val name: String = "cancellable_tool"
    override val description: String = "Cancellable tool"
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

            override fun getDescription(): String = "cancellable"

            override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
                while (!context.isCancelled()) {
                    delay(100)
                }
                return ToolExecutionResult.Cancelled("Cancelled via token")
            }
        }
    }
}
