package ai.closepaw.tool.impl

import ai.closepaw.browser.script.ScriptResult
import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.test.FakeAndroidPlatform
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cancellation and timeout coverage for BrowserScriptTool: pre-execution short-circuit,
 * mid-execution watchdog cancellation (with real elapsed-time accounting), runner-reported
 * cancellation, and runner-reported timeout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserScriptToolCancellationTest {

    @Test
    fun `pre-cancelled context short-circuits before consulting capability gate`() = runTest {
        var gateCalled = false
        val tool = BrowserScriptTool(
            capabilityGate = object : BrowserScriptCapabilityGate {
                override suspend fun acquire(): BrowserScriptCapabilityGate.Outcome {
                    gateCalled = true
                    return BrowserScriptCapabilityGate.Outcome.Unavailable("x", "x")
                }
            },
        )
        val result = tool.createInvocation(JSONObject().put("script", "return 1"))
            .execute(executionContext("call-cancel-pre", cancelled = true))

        assertThat(gateCalled).isFalse()
        assertThat(result).isInstanceOf(ToolExecutionResult.Cancelled::class.java)
    }

    @Test
    fun `mid-execution cancellation cancels runner, returns Cancelled, and records real duration`() = runTest {
        val sink = RecordingTraceSink()
        val cancelFlag = AtomicBoolean(false)
        val invokerStarted = AtomicBoolean(false)
        // Sequence: 1000 (started), 1000 (just before cancel poll), 1234 (when watchdog fires).
        // Two clock() reads: started (before scope), elapsed (in catch). 1234 - 1000 = 234.
        val ticks = longArrayOf(1_000L, 1_234L)
        var i = 0
        val invoker = BrowserScriptInvoker { _, _ ->
            invokerStarted.set(true)
            delay(60_000)
            ScriptResult.Ok("\"never\"")
        }
        val tool = BrowserScriptTool(
            capabilityGate = availableGate(invoker),
            traceSink = sink,
            cancellationPollMs = 20L,
            clock = { ticks[i.coerceAtMost(ticks.lastIndex)].also { i++ } },
        )
        val context = object : ToolExecutionContext {
            override val callId = "call-mid"
            override val platform = FakeAndroidPlatform()
            override val currentSnapshot = null
            override fun isCancelled(): Boolean = cancelFlag.get()
        }

        val deferred = async {
            tool.createInvocation(JSONObject().put("script", "return 1")).execute(context)
        }
        advanceTimeBy(50)
        runCurrent()
        assertThat(invokerStarted.get()).isTrue()

        cancelFlag.set(true)
        advanceTimeBy(100)
        advanceUntilIdle()

        val result = deferred.await()
        assertThat(result).isInstanceOf(ToolExecutionResult.Cancelled::class.java)
        assertThat((result as ToolExecutionResult.Cancelled).reason).contains("Cancelled")
        val entry = sink.entries.single()
        assertThat(entry.outcome).isEqualTo(BrowserScriptOutcome.CANCELLATION)
        // Real elapsed time, not 0L — the codex-flagged regression.
        assertThat(entry.durationMs).isEqualTo(234L)
    }

    @Test
    fun `cancellation from runner propagates as Cancelled with cancellation outcome`() = runTest {
        val sink = RecordingTraceSink()
        val tool = BrowserScriptTool(
            capabilityGate = availableGate(
                BrowserScriptInvoker { _, _ -> ScriptResult.Cancelled("session paused") },
            ),
            traceSink = sink,
        )

        val result = tool.createInvocation(JSONObject().put("script", "return 1"))
            .execute(executionContext("call-cancel-runner"))

        assertThat(result).isInstanceOf(ToolExecutionResult.Cancelled::class.java)
        assertThat((result as ToolExecutionResult.Cancelled).reason).isEqualTo("session paused")
        val entry = sink.entries.single()
        assertThat(entry.outcome).isEqualTo(BrowserScriptOutcome.CANCELLATION)
        assertThat(entry.severity).isEqualTo(BrowserScriptOutcomeSeverity.TRANSIENT)
        assertThat(entry.retryable).isFalse()
    }

    @Test
    fun `runner timeout uses runner_timeout outcome with transient retryable severity`() = runTest {
        val sink = RecordingTraceSink()
        val tool = BrowserScriptTool(
            capabilityGate = availableGate(
                BrowserScriptInvoker { _, t -> ScriptResult.Timeout(t) },
            ),
            traceSink = sink,
        )

        val result = tool.createInvocation(
            JSONObject().put("script", "while(true){}").put("timeout_ms", 1_500),
        ).execute(executionContext("call-timeout-1"))

        val error = (result as ToolExecutionResult.Failure).error
        assertThat(error).contains("timed out")
        assertThat(error).contains("1500ms")
        val entry = sink.entries.single()
        assertThat(entry.outcome).isEqualTo(BrowserScriptOutcome.RUNNER_TIMEOUT)
        assertThat(entry.severity).isEqualTo(BrowserScriptOutcomeSeverity.TRANSIENT)
        assertThat(entry.retryable).isTrue()
    }
}
