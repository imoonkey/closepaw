package ai.closepaw.tool.impl

import ai.closepaw.browser.script.ScriptResult
import ai.closepaw.tool.ToolExecutionResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test

/**
 * Trace fidelity coverage for BrowserScriptTool: capability_unavailable categorization,
 * probe_error categorization, host_error from runner, host_error from a thrown exception,
 * script_failure categorization, and the rule that rawResultJson always carries the FULL
 * serialized runner payload — never the user-facing text.
 */
class BrowserScriptToolTraceTest {

    @Test
    fun `capability gate unavailable surfaces actionable setup error and skips runner`() = runTest {
        val invokerCalls = mutableListOf<Pair<String, Long>>()
        val invoker = BrowserScriptInvoker { script, timeout ->
            invokerCalls += script to timeout
            ScriptResult.Ok("\"never\"")
        }
        val sink = RecordingTraceSink()
        val tool = BrowserScriptTool(
            capabilityGate = staticGate(
                BrowserScriptCapabilityGate.Outcome.Unavailable(
                    code = "shizuku_permission_missing",
                    reason = "Grant Shizuku permission to ClosePaw.",
                ),
            ),
            traceSink = sink,
        )

        val result = tool.createInvocation(JSONObject().put("script", "return 1"))
            .execute(executionContext("call-cap-1"))

        assertThat(invokerCalls).isEmpty()
        val error = (result as ToolExecutionResult.Failure).error
        assertThat(error).contains("shizuku_permission_missing")
        assertThat(error).contains("Grant Shizuku permission")

        val entry = sink.entries.single()
        assertThat(entry.outcome).isEqualTo(BrowserScriptOutcome.CAPABILITY_UNAVAILABLE)
        assertThat(entry.outcomeCode).isEqualTo("shizuku_permission_missing")
        assertThat(entry.severity).isEqualTo(BrowserScriptOutcomeSeverity.TRANSIENT)
        assertThat(entry.retryable).isTrue()
        assertThat(entry.callId).isEqualTo("call-cap-1")
        assertThat(entry.script).isEqualTo("return 1")
        // invoker is referenced so the closure capture isn't dead code.
        assertThat(invoker).isNotNull()
    }

    @Test
    fun `capability gate exception is treated as capability_unavailable with probe_error code`() = runTest {
        val sink = RecordingTraceSink()
        val tool = BrowserScriptTool(
            capabilityGate = object : BrowserScriptCapabilityGate {
                override suspend fun acquire(): BrowserScriptCapabilityGate.Outcome {
                    error("binder dead")
                }
            },
            traceSink = sink,
        )

        val result = tool.createInvocation(JSONObject().put("script", "return 1"))
            .execute(executionContext("call-cap-2"))

        assertThat(result).isInstanceOf(ToolExecutionResult.Failure::class.java)
        assertThat((result as ToolExecutionResult.Failure).error).contains("binder dead")
        val entry = sink.entries.single()
        assertThat(entry.outcome).isEqualTo(BrowserScriptOutcome.CAPABILITY_UNAVAILABLE)
        assertThat(entry.outcomeCode).isEqualTo("probe_error")
        assertThat(entry.retryable).isTrue()
        assertThat(entry.rawResultJson).contains("host_throwable")
        assertThat(entry.rawResultJson).contains("binder dead")
    }

    @Test
    fun `happy path serializes the full runner payload to rawResultJson`() = runTest {
        val sink = RecordingTraceSink()
        val tool = BrowserScriptTool(
            capabilityGate = availableGate(
                BrowserScriptInvoker { _, _ -> ScriptResult.Ok("""{"title":"hello"}""") },
            ),
            traceSink = sink,
        )

        tool.createInvocation(JSONObject().put("script", "return cdp('Runtime.evaluate')"))
            .execute(executionContext("call-trace-ok"))

        val raw = JSONObject(sink.entries.single().rawResultJson!!)
        assertThat(raw.getString("kind")).isEqualTo("ok")
        assertThat(raw.getString("result_json")).isEqualTo("""{"title":"hello"}""")
    }

    @Test
    fun `script failure preserves full runner JSON in trace, not just the stack`() = runTest {
        val sink = RecordingTraceSink()
        val tool = BrowserScriptTool(
            capabilityGate = availableGate(
                BrowserScriptInvoker { _, _ ->
                    ScriptResult.Failure(
                        message = "ReferenceError: foo is not defined",
                        stack = "at agent-script:3:1",
                    )
                },
            ),
            traceSink = sink,
        )

        val result = tool.createInvocation(JSONObject().put("script", "foo()"))
            .execute(executionContext("call-fail-1"))

        val error = (result as ToolExecutionResult.Failure).error
        assertThat(error).contains("ReferenceError")
        assertThat(error).contains("agent-script:3:1")

        val entry = sink.entries.single()
        assertThat(entry.outcome).isEqualTo(BrowserScriptOutcome.SCRIPT_FAILURE)
        assertThat(entry.severity).isEqualTo(BrowserScriptOutcomeSeverity.PERMANENT)
        assertThat(entry.retryable).isFalse()
        assertThat(entry.errorMessage).contains("ReferenceError")
        // rawResultJson must carry the full runner failure JSON — not just the stack.
        val raw = JSONObject(entry.rawResultJson!!)
        assertThat(raw.getString("kind")).isEqualTo("failure")
        assertThat(raw.getString("message")).contains("ReferenceError")
        assertThat(raw.getString("stack")).isEqualTo("at agent-script:3:1")
    }

    @Test
    fun `host error from runner propagates with cause and host_error outcome`() = runTest {
        val sink = RecordingTraceSink()
        val cause = IllegalStateException("WebView dead")
        val tool = BrowserScriptTool(
            capabilityGate = availableGate(
                BrowserScriptInvoker { _, _ -> ScriptResult.HostError(cause) },
            ),
            traceSink = sink,
        )

        val result = tool.createInvocation(JSONObject().put("script", "return 1"))
            .execute(executionContext("call-host-1"))

        val failure = result as ToolExecutionResult.Failure
        assertThat(failure.error).contains("WebView dead")
        assertThat(failure.exception).isSameInstanceAs(cause)
        val entry = sink.entries.single()
        assertThat(entry.outcome).isEqualTo(BrowserScriptOutcome.HOST_ERROR)
        assertThat(entry.outcomeCode).isEqualTo("IllegalStateException")
        assertThat(entry.severity).isEqualTo(BrowserScriptOutcomeSeverity.TRANSIENT)
        assertThat(entry.retryable).isTrue()
    }

    @Test
    fun `runner thrown exception is caught and reported as host_error with serialized cause`() = runTest {
        val sink = RecordingTraceSink()
        val cause = RuntimeException("bridge died")
        val tool = BrowserScriptTool(
            capabilityGate = availableGate(BrowserScriptInvoker { _, _ -> throw cause }),
            traceSink = sink,
        )

        val result = tool.createInvocation(JSONObject().put("script", "return 1"))
            .execute(executionContext("call-host-2"))

        assertThat(result).isInstanceOf(ToolExecutionResult.Failure::class.java)
        val entry = sink.entries.single()
        assertThat(entry.outcome).isEqualTo(BrowserScriptOutcome.HOST_ERROR)
        assertThat(entry.outcomeCode).isEqualTo("RuntimeException")
        assertThat(entry.rawResultJson).contains("bridge died")
    }
}
