package ai.closepaw.tool

import ai.closepaw.protocol.ApprovalDecision
import ai.closepaw.protocol.ApprovalMode
import ai.closepaw.protocol.AppTier
import ai.closepaw.test.FakeAndroidPlatform
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class ToolCallStateTest {

    @Test
    fun `isTerminal returns true for terminal states`() {
        val params = JSONObject()
        val success = ToolCallState.Success(
            callId = "c1",
            toolName = "tool",
            params = params,
            result = ToolExecutionResult.Success("ok")
        )
        val error = ToolCallState.Error(
            callId = "c2",
            toolName = "tool",
            params = params,
            error = "bad"
        )
        val cancelled = ToolCallState.Cancelled(
            callId = "c3",
            toolName = "tool",
            params = params,
            reason = "nope"
        )

        assertThat(success.isTerminal()).isTrue()
        assertThat(error.isTerminal()).isTrue()
        assertThat(cancelled.isTerminal()).isTrue()
    }

    @Test
    fun `isTerminal returns false for non-terminal states`() {
        val params = JSONObject()
        val validating = ToolCallState.Validating("c1", "tool", params)
        val awaiting = ToolCallState.AwaitingApproval(
            callId = "c2",
            toolName = "tool",
            params = params,
            invocation = StubInvocation(),
            description = "desc"
        )
        val scheduled = ToolCallState.Scheduled("c3", "tool", params, StubInvocation())
        val executing = ToolCallState.Executing("c4", "tool", params, StubInvocation())

        assertThat(validating.isTerminal()).isFalse()
        assertThat(awaiting.isTerminal()).isFalse()
        assertThat(scheduled.isTerminal()).isFalse()
        assertThat(executing.isTerminal()).isFalse()
    }

    // === FSM characterization: every transition in doc/main/state_machines/tool_call.md ===

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `entry to Validating - Validating to Error - unknown tool`() = runTest {
        val router = ToolRouter(ToolRegistry(), PolicyEngine(appClassifier = AppClassifier(emptyMap())))
        val states = mutableListOf<ToolCallState>()

        router.execute("missing", JSONObject(), SimpleToolRouterContext(FakeAndroidPlatform()), onStateChange = { states.add(it) })

        assertThat(states.map { it::class }).containsExactly(
            ToolCallState.Validating::class,
            ToolCallState.Error::class
        ).inOrder()
        assertThat((states.last() as ToolCallState.Error).error).contains("Unknown tool")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Validating to Error - invalid params`() = runTest {
        val registry = ToolRegistry().apply { register(InvalidParamsToolSpec()) }
        val router = ToolRouter(registry, PolicyEngine(appClassifier = AppClassifier(emptyMap())))
        val states = mutableListOf<ToolCallState>()

        router.execute("invalid_tool", JSONObject(), SimpleToolRouterContext(FakeAndroidPlatform()), onStateChange = { states.add(it) })

        assertThat(states.map { it::class }).containsExactly(
            ToolCallState.Validating::class,
            ToolCallState.Error::class
        ).inOrder()
        assertThat((states.last() as ToolCallState.Error).error).contains("Validation failed")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Validating to Cancelled - PolicyDecision Deny`() = runTest {
        val registry = ToolRegistry().apply { register(SuccessToolSpec()) }
        val classifier = AppClassifier(mapOf("com.bank" to AppTier.BLOCKED))
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.AUTO_APPROVE, classifier))
        val states = mutableListOf<ToolCallState>()

        router.execute(
            "success_tool",
            JSONObject(),
            SimpleToolRouterContext(FakeAndroidPlatform()),
            packageName = "com.bank",
            onStateChange = { states.add(it) }
        )

        assertThat(states.map { it::class }).containsExactly(
            ToolCallState.Validating::class,
            ToolCallState.Cancelled::class
        ).inOrder()
        val cancelled = states.last() as ToolCallState.Cancelled
        assertThat(cancelled.reason).contains("Policy denied")
        assertThat(cancelled.decision).isNull()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Validating to Scheduled to Executing to Success - PolicyDecision Allow`() = runTest {
        val registry = ToolRegistry().apply { register(SuccessToolSpec()) }
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.AUTO_APPROVE, AppClassifier(emptyMap())))
        val states = mutableListOf<ToolCallState>()

        router.execute("success_tool", JSONObject(), SimpleToolRouterContext(FakeAndroidPlatform()), onStateChange = { states.add(it) })

        assertThat(states.map { it::class }).containsExactly(
            ToolCallState.Validating::class,
            ToolCallState.Scheduled::class,
            ToolCallState.Executing::class,
            ToolCallState.Success::class
        ).inOrder()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Validating to AwaitingApproval to Executing to Success - APPROVED`() = runTest {
        val registry = ToolRegistry().apply { register(SuccessToolSpec()) }
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.ALWAYS_ASK, AppClassifier(emptyMap())))
        val states = mutableListOf<ToolCallState>()

        router.execute(
            toolName = "success_tool",
            params = JSONObject(),
            context = SimpleToolRouterContext(FakeAndroidPlatform()),
            onStateChange = { states.add(it) },
            onApprovalRequired = { router.resolveApproval(it.callId, ApprovalDecision.APPROVED) }
        )

        assertThat(states.map { it::class }).containsExactly(
            ToolCallState.Validating::class,
            ToolCallState.AwaitingApproval::class,
            ToolCallState.Executing::class,
            ToolCallState.Success::class
        ).inOrder()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `AwaitingApproval to Cancelled - DENIED carries decision`() = runTest {
        val registry = ToolRegistry().apply { register(SuccessToolSpec()) }
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.ALWAYS_ASK, AppClassifier(emptyMap())))
        val states = mutableListOf<ToolCallState>()

        router.execute(
            toolName = "success_tool",
            params = JSONObject(),
            context = SimpleToolRouterContext(FakeAndroidPlatform()),
            onStateChange = { states.add(it) },
            onApprovalRequired = { router.resolveApproval(it.callId, ApprovalDecision.DENIED) }
        )

        assertThat(states.map { it::class }).containsExactly(
            ToolCallState.Validating::class,
            ToolCallState.AwaitingApproval::class,
            ToolCallState.Cancelled::class
        ).inOrder()
        val cancelled = states.last() as ToolCallState.Cancelled
        assertThat(cancelled.reason).isEqualTo("User denied")
        assertThat(cancelled.decision).isEqualTo(ApprovalDecision.DENIED)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `AwaitingApproval to Cancelled - ABORT carries decision`() = runTest {
        val registry = ToolRegistry().apply { register(SuccessToolSpec()) }
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.ALWAYS_ASK, AppClassifier(emptyMap())))
        val states = mutableListOf<ToolCallState>()

        router.execute(
            toolName = "success_tool",
            params = JSONObject(),
            context = SimpleToolRouterContext(FakeAndroidPlatform()),
            onStateChange = { states.add(it) },
            onApprovalRequired = { router.resolveApproval(it.callId, ApprovalDecision.ABORT) }
        )

        val cancelled = states.last() as ToolCallState.Cancelled
        assertThat(cancelled.reason).isEqualTo("User aborted")
        assertThat(cancelled.decision).isEqualTo(ApprovalDecision.ABORT)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `AwaitingApproval to Cancelled - approval timeout after 60s with null decision`() = runTest {
        val registry = ToolRegistry().apply { register(SuccessToolSpec()) }
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.ALWAYS_ASK, AppClassifier(emptyMap())))
        val states = mutableListOf<ToolCallState>()

        val deferred = async {
            router.execute(
                toolName = "success_tool",
                params = JSONObject(),
                context = SimpleToolRouterContext(FakeAndroidPlatform()),
                onStateChange = { states.add(it) },
                onApprovalRequired = { /* never resolves */ }
            )
        }

        advanceTimeBy(60_000)
        advanceUntilIdle()
        deferred.await()

        assertThat(states.map { it::class }).containsExactly(
            ToolCallState.Validating::class,
            ToolCallState.AwaitingApproval::class,
            ToolCallState.Cancelled::class
        ).inOrder()
        val cancelled = states.last() as ToolCallState.Cancelled
        assertThat(cancelled.reason).isEqualTo("Approval timed out")
        assertThat(cancelled.decision).isNull()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `AwaitingApproval to Error - onApprovalRequired throws`() = runTest {
        val registry = ToolRegistry().apply { register(SuccessToolSpec()) }
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.ALWAYS_ASK, AppClassifier(emptyMap())))
        val states = mutableListOf<ToolCallState>()

        router.execute(
            toolName = "success_tool",
            params = JSONObject(),
            context = SimpleToolRouterContext(FakeAndroidPlatform()),
            onStateChange = { states.add(it) },
            onApprovalRequired = { throw IllegalStateException("UI broken") }
        )

        assertThat(states.map { it::class }).containsExactly(
            ToolCallState.Validating::class,
            ToolCallState.AwaitingApproval::class,
            ToolCallState.Error::class
        ).inOrder()
        assertThat((states.last() as ToolCallState.Error).error).contains("Approval request failed")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `AwaitingApproval to Cancelled - TOCTOU foreground package changed`() = runTest {
        val registry = ToolRegistry().apply { register(SuccessToolSpec()) }
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.ALWAYS_ASK, AppClassifier(emptyMap())))
        val platform = FakeAndroidPlatform(currentPackageName = "com.other")
        val states = mutableListOf<ToolCallState>()

        router.execute(
            toolName = "success_tool",
            params = JSONObject(),
            context = SimpleToolRouterContext(platform),
            packageName = "com.original",
            onStateChange = { states.add(it) },
            onApprovalRequired = { router.resolveApproval(it.callId, ApprovalDecision.APPROVED) }
        )

        val cancelled = states.last() as ToolCallState.Cancelled
        assertThat(cancelled.reason).isEqualTo("App changed during approval wait")
        assertThat(cancelled.decision).isEqualTo(ApprovalDecision.APPROVED)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `AwaitingApproval to Cancelled - TOCTOU blocked app detected when origin unknown`() = runTest {
        val registry = ToolRegistry().apply { register(SuccessToolSpec()) }
        val classifier = AppClassifier(mapOf("com.bank" to AppTier.BLOCKED))
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.ALWAYS_ASK, classifier))
        val platform = FakeAndroidPlatform(currentPackageName = "com.bank")
        val states = mutableListOf<ToolCallState>()

        router.execute(
            toolName = "success_tool",
            params = JSONObject(),
            context = SimpleToolRouterContext(platform),
            packageName = null,
            onStateChange = { states.add(it) },
            onApprovalRequired = { router.resolveApproval(it.callId, ApprovalDecision.APPROVED) }
        )

        val cancelled = states.last() as ToolCallState.Cancelled
        assertThat(cancelled.reason).isEqualTo("Blocked app detected after approval")
        assertThat(cancelled.decision).isEqualTo(ApprovalDecision.APPROVED)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Scheduled to Cancelled - context cancelled before exec`() = runTest {
        val registry = ToolRegistry().apply { register(SuccessToolSpec()) }
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.AUTO_APPROVE, AppClassifier(emptyMap())))
        val context = SimpleToolRouterContext(FakeAndroidPlatform())
        context.cancel()
        val states = mutableListOf<ToolCallState>()

        router.execute("success_tool", JSONObject(), context, onStateChange = { states.add(it) })

        assertThat(states.map { it::class }).containsExactly(
            ToolCallState.Validating::class,
            ToolCallState.Scheduled::class,
            ToolCallState.Cancelled::class
        ).inOrder()
        assertThat((states.last() as ToolCallState.Cancelled).reason).isEqualTo("Cancelled before execution")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Executing to Error - invocation returns Failure`() = runTest {
        val registry = ToolRegistry().apply { register(FailingToolSpec()) }
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.AUTO_APPROVE, AppClassifier(emptyMap())))
        val states = mutableListOf<ToolCallState>()

        router.execute("failing_tool", JSONObject(), SimpleToolRouterContext(FakeAndroidPlatform()), onStateChange = { states.add(it) })

        assertThat(states.map { it::class }).containsExactly(
            ToolCallState.Validating::class,
            ToolCallState.Scheduled::class,
            ToolCallState.Executing::class,
            ToolCallState.Error::class
        ).inOrder()
        assertThat((states.last() as ToolCallState.Error).error).isEqualTo("boom")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Executing to Error - invocation throws is wrapped`() = runTest {
        val registry = ToolRegistry().apply { register(ThrowingToolSpec()) }
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.AUTO_APPROVE, AppClassifier(emptyMap())))
        val states = mutableListOf<ToolCallState>()

        router.execute("throwing_tool", JSONObject(), SimpleToolRouterContext(FakeAndroidPlatform()), onStateChange = { states.add(it) })

        val terminal = states.last() as ToolCallState.Error
        assertThat(terminal.exception).isInstanceOf(IllegalStateException::class.java)
        assertThat(terminal.error).isEqualTo("kaboom")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Executing to Cancelled - invocation returned Cancelled`() = runTest {
        val registry = ToolRegistry().apply { register(CancelledResultToolSpec()) }
        val router = ToolRouter(registry, PolicyEngine(ApprovalMode.AUTO_APPROVE, AppClassifier(emptyMap())))
        val states = mutableListOf<ToolCallState>()

        router.execute("cancel_result_tool", JSONObject(), SimpleToolRouterContext(FakeAndroidPlatform()), onStateChange = { states.add(it) })

        assertThat(states.map { it::class }).containsExactly(
            ToolCallState.Validating::class,
            ToolCallState.Scheduled::class,
            ToolCallState.Executing::class,
            ToolCallState.Cancelled::class
        ).inOrder()
        assertThat((states.last() as ToolCallState.Cancelled).reason).isEqualTo("self-cancelled")
    }
}

private class StubInvocation : ToolInvocation {
    override val toolName: String = "tool"
    override val params: JSONObject = JSONObject()
    override fun getDescription(): String = "stub"
    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult =
        ToolExecutionResult.Success("ok")
}

private val emptyParamSchema: JSONObject
    get() = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
        put("required", JSONArray())
        put("additionalProperties", false)
    }

private class SuccessToolSpec : ToolSpec {
    override val name: String = "success_tool"
    override val description: String = "ok"
    override val parameterSchema: JSONObject = emptyParamSchema
    override fun validate(params: JSONObject) = ValidationResult.Valid
    override fun createInvocation(params: JSONObject) = object : ToolInvocation {
        override val toolName = name
        override val params = params
        override fun getDescription() = "success"
        override suspend fun execute(context: ToolExecutionContext) = ToolExecutionResult.Success("ok")
    }
}

private class InvalidParamsToolSpec : ToolSpec {
    override val name: String = "invalid_tool"
    override val description: String = "always invalid"
    override val parameterSchema: JSONObject = emptyParamSchema
    override fun validate(params: JSONObject) = ValidationResult.Invalid(listOf("missing required field 'x'"))
    override fun createInvocation(params: JSONObject) = object : ToolInvocation {
        override val toolName = name
        override val params = params
        override fun getDescription() = "invalid"
        override suspend fun execute(context: ToolExecutionContext) = ToolExecutionResult.Success("never")
    }
}

private class FailingToolSpec : ToolSpec {
    override val name: String = "failing_tool"
    override val description: String = "fails"
    override val parameterSchema: JSONObject = emptyParamSchema
    override fun validate(params: JSONObject) = ValidationResult.Valid
    override fun createInvocation(params: JSONObject) = object : ToolInvocation {
        override val toolName = name
        override val params = params
        override fun getDescription() = "fail"
        override suspend fun execute(context: ToolExecutionContext) = ToolExecutionResult.Failure("boom")
    }
}

private class ThrowingToolSpec : ToolSpec {
    override val name: String = "throwing_tool"
    override val description: String = "throws"
    override val parameterSchema: JSONObject = emptyParamSchema
    override fun validate(params: JSONObject) = ValidationResult.Valid
    override fun createInvocation(params: JSONObject) = object : ToolInvocation {
        override val toolName = name
        override val params = params
        override fun getDescription() = "throw"
        override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
            throw IllegalStateException("kaboom")
        }
    }
}

private class CancelledResultToolSpec : ToolSpec {
    override val name: String = "cancel_result_tool"
    override val description: String = "self-cancels"
    override val parameterSchema: JSONObject = emptyParamSchema
    override fun validate(params: JSONObject) = ValidationResult.Valid
    override fun createInvocation(params: JSONObject) = object : ToolInvocation {
        override val toolName = name
        override val params = params
        override fun getDescription() = "self-cancel"
        override suspend fun execute(context: ToolExecutionContext) = ToolExecutionResult.Cancelled("self-cancelled")
    }
}
