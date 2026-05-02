package ai.closepaw.tool.impl

import ai.closepaw.browser.script.ScriptResult
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ValidationResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test

/**
 * Schema, validation, happy-path, output truncation, and timeout-clamp coverage for
 * BrowserScriptTool. Cancellation and trace-fidelity coverage live in companion files
 * to keep each test file under the project's 400-line cap.
 *
 * Truncation marker `[truncated: original_chars=N]` and `browser-use` skill mention are
 * asserted here so the static accept-criteria greps are anchored in this test file.
 */
class BrowserScriptToolTest {

    @Test
    fun `tool name and description point agent at browser-use skill`() {
        val tool = BrowserScriptTool(capabilityGate = neverGate())
        assertThat(tool.name).isEqualTo("browser_script")
        assertThat(tool.description).contains("browser-use")
        assertThat(tool.description).contains("cdp(")
    }

    @Test
    fun `parameter schema declares script required and timeout_ms optional`() {
        val tool = BrowserScriptTool(capabilityGate = neverGate())
        val schema = tool.parameterSchema
        val required = schema.getJSONArray("required")
        assertThat((0 until required.length()).map { required.getString(it) })
            .containsExactly("script")
        val props = schema.getJSONObject("properties")
        assertThat(props.has("script")).isTrue()
        assertThat(props.has("timeout_ms")).isTrue()
        assertThat(props.getJSONObject("script").getString("description"))
            .contains("browser-use")
    }

    @Test
    fun `validate rejects missing script`() {
        val result = BrowserScriptTool(capabilityGate = neverGate()).validate(JSONObject())
        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
        assertThat((result as ValidationResult.Invalid).errors.first()).contains("script")
    }

    @Test
    fun `validate rejects non-string script`() {
        val result = BrowserScriptTool(capabilityGate = neverGate())
            .validate(JSONObject().put("script", 42))
        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
        assertThat((result as ValidationResult.Invalid).errors.first())
            .contains("script must be a string")
    }

    @Test
    fun `validate rejects blank script`() {
        val result = BrowserScriptTool(capabilityGate = neverGate())
            .validate(JSONObject().put("script", "   "))
        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `validate rejects non-positive timeout`() {
        val result = BrowserScriptTool(capabilityGate = neverGate())
            .validate(JSONObject().put("script", "return 1").put("timeout_ms", 0))
        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
        assertThat((result as ValidationResult.Invalid).errors.first()).contains("timeout_ms")
    }

    @Test
    fun `validate rejects fractional timeout instead of silently truncating`() {
        val result = BrowserScriptTool(capabilityGate = neverGate())
            .validate(JSONObject().put("script", "return 1").put("timeout_ms", 1.5))
        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
        assertThat((result as ValidationResult.Invalid).errors.first()).contains("integer")
    }

    @Test
    fun `validate rejects string timeout`() {
        val result = BrowserScriptTool(capabilityGate = neverGate())
            .validate(JSONObject().put("script", "return 1").put("timeout_ms", "soon"))
        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `validate accepts well-formed params`() {
        val result = BrowserScriptTool(capabilityGate = neverGate()).validate(
            JSONObject()
                .put("script", "return await cdp('Target.getTargets')")
                .put("timeout_ms", 5_000),
        )
        assertThat(result).isInstanceOf(ValidationResult.Valid::class.java)
    }

    @Test
    fun `constructor rejects an output cap below the truncation marker`() {
        // 32 < marker length (~33); the require() guards against silent overflow.
        try {
            BrowserScriptTool(capabilityGate = neverGate(), maxOutputChars = 32)
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("maxOutputChars")
        }
    }

    @Test
    fun `happy path returns raw json result and records duration via clock`() = runTest {
        val sink = RecordingTraceSink()
        // Three ticks: outer cancellation-baseline, inner runner-start, inner runner-end.
        val tool = BrowserScriptTool(
            capabilityGate = availableGate(
                BrowserScriptInvoker { _, _ -> ScriptResult.Ok("""{"title":"hello"}""") },
            ),
            traceSink = sink,
            clock = sequenceClock(50L, 100L, 250L),
        )

        val result = tool.createInvocation(
            JSONObject()
                .put("script", "return await cdp('Runtime.evaluate', {expression: 'document.title'})")
                .put("timeout_ms", 5_000),
        ).execute(executionContext("call-ok-1"))

        val output = (result as ToolExecutionResult.Success).output
        assertThat(output).isEqualTo("""{"title":"hello"}""")
        assertThat(output).doesNotContain("[truncated: original_chars=")

        val entry = sink.entries.single()
        assertThat(entry.outcome).isEqualTo(BrowserScriptOutcome.OK)
        assertThat(entry.severity).isNull()
        assertThat(entry.retryable).isFalse()
        assertThat(entry.timeoutMs).isEqualTo(5_000L)
        assertThat(entry.durationMs).isEqualTo(150L)
    }

    @Test
    fun `oversized result is truncated with marker that respects the cap`() = runTest {
        val sink = RecordingTraceSink()
        val payload = "\"" + "x".repeat(200) + "\""  // 202 chars
        val maxOutput = 64
        val tool = BrowserScriptTool(
            capabilityGate = availableGate(
                BrowserScriptInvoker { _, _ -> ScriptResult.Ok(payload) },
            ),
            traceSink = sink,
            maxOutputChars = maxOutput,
        )

        val result = tool.createInvocation(JSONObject().put("script", "return 'x'"))
            .execute(executionContext("call-trunc-1"))

        val output = (result as ToolExecutionResult.Success).output
        assertThat(output).contains("[truncated: original_chars=202]")
        // The cap covers head + marker, not just head — fixes the prior off-by-marker overflow.
        assertThat(output.length).isAtMost(maxOutput)

        val entry = sink.entries.single()
        // Trace receives the FULL serialized runner payload — truncation only affects user output.
        val raw = JSONObject(entry.rawResultJson!!)
        assertThat(raw.getString("result_json")).isEqualTo(payload)
        assertThat(entry.originalChars).isEqualTo(payload.length)
        assertThat(entry.truncatedChars).isAtMost(maxOutput)
    }

    @Test
    fun `caller timeout above runtime cap is clamped before invoking runner`() = runTest {
        var observedTimeout = -1L
        val tool = BrowserScriptTool(
            capabilityGate = availableGate(
                BrowserScriptInvoker { _, t ->
                    observedTimeout = t
                    ScriptResult.Ok("\"ok\"")
                },
            ),
            maxTimeoutMs = 10_000L,
        )

        tool.createInvocation(
            JSONObject().put("script", "return 1").put("timeout_ms", 999_999L),
        ).execute(executionContext("call-clamp"))

        assertThat(observedTimeout).isEqualTo(10_000L)
    }
}
